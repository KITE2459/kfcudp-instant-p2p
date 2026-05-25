package kfc.udp.client.kcp;

import java.util.ArrayDeque;
import java.util.ArrayList;

/**
 * KCP 프로토콜 순수 Java 구현.
 * 외부 라이브러리 없음. xtaci/kcp-go와 동일한 파라미터로 동작.
 * 패킷 헤더 (24바이트):
 *   conv     4B LE  - conversation ID (서버 KCP와 일치해야 함)
 *   cmd      1B     - PUSH/ACK/WASK/WINS
 *   frg      1B     - fragment index (역순)
 *   wnd      2B LE  - receive window size
 *   ts       4B LE  - timestamp
 *   sn       4B LE  - sequence number
 *   una      4B LE  - unacknowledged sequence
 *   len      4B LE  - data length
 */
public class KcpSession {

    // KCP cmd
    static final int CMD_PUSH = 81;
    static final int CMD_ACK  = 82;
    static final int CMD_WASK = 83;
    static final int CMD_WINS = 84;

    static final int OVERHEAD   = 24;
    static final int ASK_SEND   = 1;
    static final int ASK_TELL   = 2;

    // 설정값 (applyKCPOptions와 동일)
    private final long conv;
    private int mtu        = 1450;
    private int mss;               // mtu - OVERHEAD
    private int snd_wnd    = 2048;
    private int rcv_wnd    = 2048;
    private int rmt_wnd    = 2048;
    private int interval   = 2;    // ms
    private int rx_rto     = 200;
    private int rx_minrto  = 100;
    private int rx_srtt    = 0;
    private int rx_rttval  = 0;
    private int fast_resend = 1;
    private boolean nocongestion = false; // nc=0 기본 (혼잡제어 ON)
    private boolean stream = true;

    private long snd_una   = 0;
    private long snd_nxt   = 0;
    private long rcv_nxt   = 0;

    private long incr;
    private long cwnd;
    private long ssthresh;

    private int  probe     = 0;
    private long current   = 0;
    private long updated   = 0;
    private long ts_flush  = 0;

    private final ArrayDeque<Segment> snd_queue = new ArrayDeque<>();
    private final ArrayList<Segment>  snd_buf   = new ArrayList<>();
    private final ArrayList<Segment>  rcv_buf   = new ArrayList<>();
    private final ArrayList<Segment>  rcv_queue = new ArrayList<>();

    private final ArrayList<long[]>   acklist   = new ArrayList<>(); // [ts, sn]

    // flush() 정적 버퍼 — 매 호출마다 힙 할당 방지
    // mtu 상한
    // ACK 재사용 슬롯 — acklist용 long[2] 풀
    private static final int ACK_POOL_SIZE = 4096;
    private static final long[][] ackPool;
    static {
        ackPool = new long[ACK_POOL_SIZE][2];
    }
    private int ackPoolIdx = 0;

    // 송신 콜백 (UDP로 내보내는 함수)
    public interface Output {
        void send(byte[] data, int len);
    }

    private final Output output;

    public KcpSession(long conv, Output output) {
        this.conv   = conv;
        this.output = output;
        this.mss    = mtu - OVERHEAD;
        this.cwnd   = 1;
        this.ssthresh = 2;
        this.incr   = mss;
    }

    // ── 설정 ──────────────────────────────────────────────────────────────────

    public void setNoDelay(int nodelay, int interval, int resend, boolean nc) {
        if (nodelay >= 0) {
            this.rx_minrto = nodelay != 0 ? 30 : 100;
        }
        if (interval >= 0) {
            this.interval = Math.max(10, Math.min(5000, interval));
        }
        if (resend >= 0) {
            this.fast_resend = resend;
        }
        this.nocongestion = nc;
    }

    public void setWindowSize(int snd, int rcv) {
        if (snd > 0) this.snd_wnd = snd;
        if (rcv > 0) this.rcv_wnd = rcv;
    }

    public void setMtu(int mtu) {
        this.mtu = mtu;
        this.mss = mtu - OVERHEAD;
    }

    public void setStreamMode(boolean stream) {
        this.stream = stream;
    }

    public void setAckNoDelay() {
    }

    // ── 수신 (UDP 패킷 → KCP) ────────────────────────────────────────────────

    /**
     * UDP에서 받은 raw 데이터를 KCP에 입력.
     */
    public void input(byte[] data, int offset, int size) {
        long prev_una = snd_una;
        boolean flag = false;

        while (size >= OVERHEAD) {
            long c = rle(data, offset);       // conv
            if (c != conv) return;

            int cmd = data[offset + 4] & 0xFF;
            int frg = data[offset + 5] & 0xFF;
            int wnd = rlew(data, offset + 6);
            long ts  = rle(data, offset + 8);
            long sn  = rle(data, offset + 12);
            long una = rle(data, offset + 16);
            long len = rle(data, offset + 20);

            offset += OVERHEAD;
            size   -= OVERHEAD;

            if (size < len) return;
            if (cmd != CMD_PUSH && cmd != CMD_ACK && cmd != CMD_WASK && cmd != CMD_WINS)
                return;

            rmt_wnd = wnd;
            parseUna(una);
            shrinkBuf();

            if (cmd == CMD_ACK) {
                updateAck(rttval(ts));
                parseAck(sn);
                shrinkBuf();
                flag = true;
            } else if (cmd == CMD_PUSH) {
                if (sn < rcv_nxt + rcv_wnd) {
                    ackPush(ts, sn);
                    if (sn >= rcv_nxt) {
                        Segment seg = acquireSeg((int) len);
                        System.arraycopy(data, offset, seg.data, 0, (int) len);
                        seg.conv = conv; seg.cmd = cmd; seg.frg = frg;
                        seg.wnd = wnd; seg.ts = ts; seg.sn = sn; seg.una = una;
                        parseData(seg);
                    }
                }
            } else if (cmd == CMD_WASK) {
                probe |= ASK_TELL;
            }

            offset += (int) len;
            size   -= (int) len;
        }

        if (flag) {
            if (nocongestion) {
                cwnd = snd_wnd;
            } else {
                if (snd_una > prev_una) {
                    if (cwnd < rmt_wnd) {
                        long mss_ = mss;
                        if (cwnd < ssthresh) {
                            cwnd++;
                            incr += mss;
                        } else {
                            if (incr < mss_) incr = mss_;
                            incr += (mss_ * mss_) / incr + mss_ / 16;
                            if ((cwnd + 1) * mss_ <= incr) cwnd++;
                        }
                        if (cwnd > rmt_wnd) {
                            cwnd = rmt_wnd;
                            incr = rmt_wnd * mss_;
                        }
                    }
                }
            }
        }
    }

    // ── 수신 큐 → 앱 ─────────────────────────────────────────────────────────

    /** 수신 가능한 바이트 수 (스트림 모드) */
    public int peekSize() {
        if (rcv_queue.isEmpty()) return -1;
        Segment front = rcv_queue.getFirst();
        if (front.frg == 0) return front.data.length;
        if (rcv_queue.size() < front.frg + 1) return -1;
        int size = 0;
        for (int i = 0; i <= front.frg; i++) size += rcv_queue.get(i).data.length;
        return size;
    }

    /**
     * 수신 데이터를 buf에 복사.
     * @return 읽은 바이트 수, -1 = 데이터 없음
     */
    public int recv(byte[] buf, int offset, int maxlen) {
        int sz = peekSize();
        if (sz < 0) return -1;
        if (sz > maxlen) return -2;

        boolean recover = rcv_queue.size() >= rcv_wnd;

        int n = 0;
        while (!rcv_queue.isEmpty()) {
            Segment seg = rcv_queue.getFirst();
            System.arraycopy(seg.data, 0, buf, offset + n, seg.data.length);
            n += seg.data.length;
            int frg = seg.frg;
            rcv_queue.removeFirst();
            releaseSeg(seg);
            if (frg == 0) break;
        }

        // rcv_buf → rcv_queue 이동
        moveRcvBuf();

        // 윈도우 열림 알림
        if (recover && rcv_queue.size() < rcv_wnd) {
            probe |= ASK_TELL;
        }
        return n;
    }

    // ── 송신 ──────────────────────────────────────────────────────────────────

    /**
     * 앱 데이터를 KCP 송신 큐에 넣기.
     */
    public void send(byte[] buf, int offset, int len) {
        if (len <= 0) return;

        // 스트림 모드: 마지막 segment에 이어붙이기
        if (stream && !snd_queue.isEmpty()) {
            Segment last = null;
            for (Segment s : snd_queue) last = s;
            if (last != null && last.data.length < mss) {
                int capacity = mss - last.data.length;
                int extend   = Math.min(len, capacity);
                byte[] nd = new byte[last.data.length + extend];
                System.arraycopy(last.data, 0, nd, 0, last.data.length);
                System.arraycopy(buf, offset, nd, last.data.length, extend);
                last.data = nd;
                last.frg  = 0;
                offset += extend;
                len    -= extend;
            }
        }

        int count = len <= mss ? 1 : (len + mss - 1) / mss;
        if (count > 255) return;
        if (count == 0) count = 1;

        for (int i = 0; i < count; i++) {
            int sz = Math.min(len, mss);
            Segment seg = acquireSeg(sz);
            System.arraycopy(buf, offset, seg.data, 0, sz);
            seg.frg = stream ? 0 : (count - i - 1);
            snd_queue.add(seg);
            offset += sz;
            len    -= sz;
        }
    }

    // ── flush / update ────────────────────────────────────────────────────────

    /**
     * 매 interval ms마다 호출해야 함.
     */
    public void update(long current_ms) {
        current = current_ms;
        if (updated == 0) {
            updated = 1;
            ts_flush = current;
        }
        long slap = timeDiff(current, ts_flush);
        if (slap >= 10000 || slap < -10000) {
            ts_flush = current;
            slap = 0;
        }
        if (slap >= 0) {
            ts_flush += interval;
            if (timeDiff(current, ts_flush) >= 0) ts_flush = current + interval;
            flush();
        }
    }

    private void flush() {
        if (updated == 0) return;

        byte[] buf = new byte[mtu];
        int    n   = 0;

        // ACK 전송
        for (long[] ack : acklist) {
            if (n + OVERHEAD > mtu) { output.send(buf, n); n = 0; }
            Segment seg = acquireSeg(0);
            seg.conv = conv; seg.cmd = CMD_ACK; seg.wnd = rcvWnd();
            seg.ts = ack[0]; seg.sn = ack[1]; seg.una = rcv_nxt;
            n = encodeSegHeader(buf, n, seg);
            releaseSeg(seg);
        }
        acklist.clear();

        // 윈도우 탐색
        if ((probe & ASK_SEND) != 0) {
            if (n + OVERHEAD > mtu) { output.send(buf, n); n = 0; }
            Segment seg = acquireSeg(0);
            seg.conv = conv; seg.cmd = CMD_WASK; seg.wnd = rcvWnd(); seg.una = rcv_nxt;
            n = encodeSegHeader(buf, n, seg);
            releaseSeg(seg);
        }
        if ((probe & ASK_TELL) != 0) {
            if (n + OVERHEAD > mtu) { output.send(buf, n); n = 0; }
            Segment seg = acquireSeg(0);
            seg.conv = conv; seg.cmd = CMD_WINS; seg.wnd = rcvWnd(); seg.una = rcv_nxt;
            n = encodeSegHeader(buf, n, seg);
            releaseSeg(seg);
        }
        probe = 0;

        // cwnd 계산
        long cwnd_ = Math.min(snd_wnd, rmt_wnd);
        if (!nocongestion) cwnd_ = Math.min(cwnd, cwnd_);

        // snd_queue → snd_buf
        while (timeDiff(snd_nxt, snd_una + cwnd_) < 0) {
            if (snd_queue.isEmpty()) break;
            Segment seg = snd_queue.poll();
            seg.conv  = conv;
            seg.cmd   = CMD_PUSH;
            seg.wnd   = rcvWnd();
            seg.ts    = current;
            seg.sn    = snd_nxt++;
            seg.una   = rcv_nxt;
            seg.resendts = 0;
            seg.rto   = rx_rto;
            seg.fastack = 0;
            seg.xmit  = 0;
            snd_buf.add(seg);
        }

        // 재전송 판단
        int change = 0;
        int lost   = 0;
        for (Segment seg : snd_buf) {
            boolean needsend = false;
            if (seg.xmit == 0) {
                needsend = true;
                seg.rto = rx_rto;
                seg.resendts = current + seg.rto;
            } else if (timeDiff(current, seg.resendts) >= 0) {
                needsend = true;
                if (!nocongestion) {
                    ssthresh = Math.max(cwnd_ / 2, 1);
                    cwnd  = 1;
                    incr  = mss;
                }
                seg.rto = Math.min(seg.rto + Math.max(seg.rto, rx_rto), 60000);
                seg.resendts = current + seg.rto;
                lost = 1;
            } else if (seg.fastack >= fast_resend && fast_resend > 0) {
                needsend = true;
                seg.fastack = 0;
                if (!nocongestion) {
                    ssthresh = Math.max(cwnd_ / 2, 1);
                    cwnd  = ssthresh + fast_resend;
                    incr  = cwnd * mss;
                }
                seg.resendts = current + seg.rto;
                change++;
            }

            if (needsend) {
                seg.xmit++;
                seg.ts  = current;
                seg.wnd = rcvWnd();
                seg.una = rcv_nxt;

                int need = OVERHEAD + seg.data.length;
                if (n + need > mtu) { output.send(buf, n); n = 0; }
                n = encodeSegHeader(buf, n, seg);
                if (seg.data.length > 0) {
                    System.arraycopy(seg.data, 0, buf, n, seg.data.length);
                    n += seg.data.length;
                }
                if (n > 0) { output.send(buf, n); n = 0; }
            }
        }

        if (n > 0) { output.send(buf, n);
        }

        // 혼잡 제어
        if (change > 0 && !nocongestion) {
            long inflight = snd_nxt - snd_una;
            ssthresh = Math.max(inflight / 2, 1);
            cwnd   = ssthresh + fast_resend;
            incr   = cwnd * mss;
        }
        if (lost != 0 && !nocongestion) {
            ssthresh = Math.max(cwnd_ / 2, 1);
            cwnd = 1;
            incr = mss;
        }
        if (cwnd < 1) { cwnd = 1; incr = mss; }
    }

    // ── 내부 헬퍼 ────────────────────────────────────────────────────────────

    private void parseUna(long una) {
        snd_buf.removeIf(s -> {
            if (timeDiff(s.sn, una) < 0) { releaseSeg(s); return true; }
            return false;
        });
    }

    private void shrinkBuf() {
        if (!snd_buf.isEmpty()) {
            snd_una = snd_buf.getFirst().sn;
        } else {
            snd_una = snd_nxt;
        }
    }

    private void updateAck(int rtt) {
        if (rx_srtt == 0) {
            rx_srtt  = rtt;
            rx_rttval = rtt / 2;
        } else {
            int delta = rtt - rx_srtt;
            if (delta < 0) delta = -delta;
            rx_rttval = (3 * rx_rttval + delta) / 4;
            rx_srtt   = Math.max(1, (7 * rx_srtt + rtt) / 8);
        }
        rx_rto = Math.min(Math.max(rx_srtt + Math.max(interval, 4 * rx_rttval), rx_minrto), 60000);
    }

    private int rttval(long ts) {
        long rtt = timeDiff(current, ts);
        if (rtt < 0) rtt = 0;
        return (int) rtt;
    }

    private void parseAck(long sn) {
        if (timeDiff(sn, snd_una) < 0 || timeDiff(sn, snd_nxt) >= 0) return;
        for (Segment seg : snd_buf) {
            if (sn == seg.sn) {
                seg.fastack++;
                if (timeDiff(sn, seg.sn) == 0) {
                    snd_buf.remove(seg);
                    releaseSeg(seg);
                    break;
                }
            } else if (timeDiff(sn, seg.sn) < 0) {
                seg.fastack++;
            }
        }
    }

    private void ackPush(long ts, long sn) {
        // ACK 풀 슬롯 재사용 — new long[] 힙 할당 제거
        long[] ack = ackPool[ackPoolIdx % ACK_POOL_SIZE];
        ackPoolIdx++;
        ack[0] = ts; ack[1] = sn;
        acklist.add(ack);
    }

    private void parseData(Segment newSeg) {
        long sn = newSeg.sn;
        if (timeDiff(sn, rcv_nxt + rcv_wnd) >= 0 || timeDiff(sn, rcv_nxt) < 0) return;

        // rcv_buf 중복 확인 및 삽입 위치
        int insertIdx = rcv_buf.size();
        boolean repeat = false;
        for (int i = rcv_buf.size() - 1; i >= 0; i--) {
            Segment s = rcv_buf.get(i);
            if (s.sn == sn) { repeat = true; break; }
            if (timeDiff(sn, s.sn) > 0) { insertIdx = i + 1; break; }
        }
        if (!repeat) rcv_buf.add(insertIdx, newSeg);

        moveRcvBuf();
    }

    private void moveRcvBuf() {
        while (!rcv_buf.isEmpty()) {
            Segment seg = rcv_buf.getFirst();
            if (seg.sn != rcv_nxt) break;
            rcv_buf.removeFirst();
            rcv_queue.add(seg);
            rcv_nxt++;
        }
    }

    private int rcvWnd() {
        int n = rcv_wnd - rcv_queue.size();
        return Math.max(n, 0);
    }

    // ── 인코딩 ────────────────────────────────────────────────────────────────

    private int encodeSegHeader(byte[] buf, int offset, Segment seg) {
        wle(buf, offset,      (int) seg.conv);  offset += 4;
        buf[offset++] = (byte) seg.cmd;
        buf[offset++] = (byte) seg.frg;
        wlew(buf, offset, seg.wnd);              offset += 2;
        wle(buf, offset, (int) seg.ts);          offset += 4;
        wle(buf, offset, (int) seg.sn);          offset += 4;
        wle(buf, offset, (int) seg.una);         offset += 4;
        wle(buf, offset, seg.data.length);       offset += 4;
        return offset;
    }

    // ── LE 인코딩/디코딩 ──────────────────────────────────────────────────────

    static long rle(byte[] b, int o) {
        return ((b[o]&0xFFL)) | ((b[o+1]&0xFFL)<<8) | ((b[o+2]&0xFFL)<<16) | ((b[o+3]&0xFFL)<<24);
    }
    static int rlew(byte[] b, int o) {
        return (b[o]&0xFF) | ((b[o+1]&0xFF)<<8);
    }
    static void wle(byte[] b, int o, int v) {
        wlew(b, o, v); b[o+2]=(byte)(v>>16); b[o+3]=(byte)(v>>24);
    }
    static void wlew(byte[] b, int o, int v) {
        b[o]=(byte)v; b[o+1]=(byte)(v>>8);
    }
    static long timeDiff(long a, long b) { return a - b; }

    // ── Segment 풀 ───────────────────────────────────────────────────────────

    private static final int            SEG_POOL_SIZE = 4096;
    private static final ArrayDeque<Segment> segPool  = new ArrayDeque<>(SEG_POOL_SIZE);
    static {
        for (int i = 0; i < SEG_POOL_SIZE; i++) segPool.push(new Segment(new byte[1500]));
    }

    private static Segment acquireSeg(int dataLen) {
        Segment s;
        synchronized (segPool) {
            s = segPool.isEmpty() ? null : segPool.pop();
        }
        if (s == null || s.data.length < dataLen) {
            s = new Segment(new byte[Math.max(dataLen, 1500)]);
        }
        s.conv = 0; s.ts = 0; s.sn = 0; s.una = 0;
        s.cmd  = 0; s.frg = 0; s.wnd = 0;
        s.resendts = 0; s.rto = 0; s.fastack = 0; s.xmit = 0;
        return s;
    }

    private static void releaseSeg(Segment s) {
        if (s.data.length > 1500) return; // 대형 버퍼는 풀에 안 넣음
        synchronized (segPool) {
            if (segPool.size() < SEG_POOL_SIZE) segPool.push(s);
        }
    }

    // ── Segment 내부 클래스 ───────────────────────────────────────────────────

    static class Segment {
        long   conv, ts, sn, una;
        int    cmd, frg, wnd;
        long   resendts;
        int    rto, fastack, xmit;
        byte[] data;

        Segment(byte[] data) { this.data = data; }
    }
}