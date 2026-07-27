package kfc.udp.client.kcp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

/**
 * KCP 프로토콜 코어 — 라이브러리 없이 완전 하드코딩.
 * 고정 파라미터 (kcp-go applyKCPOptions와 동일):
 *   MTU      = 1450       MSS = 1426
 *   nodelay  = true       rxMinRto = 40ms (updateAck에서 하한 클램프 적용)
 *   interval = 2ms
 *   resend   = 2          (fastack: ACK 2회 누락 시 재전송 — 재정렬 내성, kcp-go 서버와 동일)
 *   nocwnd   = true       (혼잡제어 OFF)
 *   sndWnd   = 4096       (재전송 버스트 상한 억제 — BDP 대비 충분한 여유)
 *   rcvWnd   = 81920
 *   stream   = true       (바이트 스트림 모드 — 서버사이드 kcp-go와 동일)
 *   deadLink = 200        (핑 20ms 환경 기준 — 서버→클라이언트 일시 단절 장기 버팀)
 *   fastackLimit = 30     (fastack xmit 소진 방지)
 *   RTO_MAX  = 3000ms     (재전송 간격 상한 — 핑 20ms 환경에서 60초는 과도)
 * RTO 하한(RTO_MIN 40ms) 근거: 실측 경로 지터가 p95 ~8ms, 스파이크 15~25ms,
 * 최대 ~37ms. 하한 없이 rto = srtt + max(interval, 4×rttvar)만 쓰면 안정 구간에서
 * rto가 ~12ms까지 조여져, 지터 스파이크만으로 RTO가 발화해 in-flight 세그먼트를
 * 대량 중복 재전송(spurious RTO)했다. 진짜 손실 복구는 fastack이 ~RTT에 처리하므로
 * 하한을 지터 위(40ms)로 올려도 복구 지연 손해는 거의 없다.
 */
public final class KcpCore {


    private static final int OVERHEAD      = 24;
    private static final int MTU           = 1450;
    private static final int MSS           = MTU - OVERHEAD; // 1426
    private static final int RTO_MIN       = 40;     // RTO 하한 — updateAck에서 클램프 (상단 주석 참조)
    private static final int RTO_DEF       = 200;
    private static final int RTO_MAX       = 3_000;  // 핑 20ms 환경 — 재전송 간격 상한 3초
    private static final int WND_SND       = 4_096;
    private static final int WND_RCV       = 81_920;
    private static final int INTERVAL      = 2;
    private static final int RESEND        = 2;    // fastack 임계 — ACK 2회 건너뜀 시 재전송 (재정렬 내성)
    private static final int DEADLINK      = 200;  // 핑 20ms 환경 — 서버→클라이언트 단절 시 장기 생존
    private static final int PROBE_INIT    = 500;   // kcp-go 기본값과 동일 (7000→500)
    private static final int PROBE_LIM     = 120_000;
    private static final int ASK_SEND      = 1;
    private static final int ASK_TELL      = 2;
    private static final int FASTACK_LIMIT = 30;  // 핑 20ms 환경 — fastack xmit 소진 여유

    private static final byte CMD_PUSH = 81;
    private static final byte CMD_ACK  = 82;
    private static final byte CMD_WASK = 83;
    private static final byte CMD_WINS = 84;

    // ── Segment ──────────────────────────────────────────────────────────────
    static final class Seg {
        Seg prev, next;

        int  conv;
        byte cmd;
        int  frg;
        int  wnd;
        int  ts;
        long sn;
        long una;
        int  resendts;
        int  rto;
        int  fastack;
        int  xmit;
        ByteBuf data;

        // ── Seg 노드 풀 ──────────────────────────────────────────
        // KCP는 단일 eventLoop 스레드에서만 실행되므로(KcpChannel 참고) 락이 불필요.
        // 송신/수신 핫패스에서 패킷마다 new Seg()가 일어나 minor GC를 주기적으로
        // 유발 → 짧은 레이턴시 스파이크의 원인. 노드(자바 객체)만 재사용한다.
        //
        // 중요: data(ByteBuf)의 생명주기는 건드리지 않는다. ByteBuf는 Netty가
        // 자체 풀링하며, release()에서 정상적으로 data.release() 후 노드만 반환한다.
        // 센티넬(SegList.head/tail)은 이 풀을 경유하지 않는다(new Seg() 직접 생성).
        private static final Seg[] POOL = new Seg[256];
        private static int poolSize = 0;

        // alloc - 풀에서 노드를 꺼내 모든 필드를 초기화해 반환. 비었으면 new.
        static Seg alloc() {
            Seg s;
            if (poolSize > 0) {
                s = POOL[--poolSize];
                POOL[poolSize] = null;
            } else {
                s = new Seg();
            }
            // 재사용 시 이전 값 잔존 방지 — 전 필드 초기화
            s.prev = null; s.next = null;
            s.conv = 0; s.cmd = 0; s.frg = 0; s.wnd = 0; s.ts = 0;
            s.sn = 0; s.una = 0; s.resendts = 0; s.rto = 0;
            s.fastack = 0; s.xmit = 0; s.data = null;
            return s;
        }

        static Seg control(ByteBufAllocator alloc) {
            Seg s = alloc();
            s.data = alloc.ioBuffer(0, 0);
            return s;
        }

        void release() {
            if (data != null) { data.release(); data = null; }
            // 노드를 풀로 반환 (상한 초과 시 GC에 맡김)
            if (poolSize < POOL.length) {
                prev = null; next = null;
                POOL[poolSize++] = this;
            }
        }
    }

    // ── 최소 연결 리스트 ──────────────────────────────────────────────────────
    private static final class SegList {
        final Seg head = new Seg();
        final Seg tail = new Seg();
        int size;

        SegList() { head.next = tail; tail.prev = head; }

        Seg peek()     { return head.next == tail ? null : head.next; }
        Seg peekLast() { return tail.prev == head ? null : tail.prev; }

        void addLast(Seg s) {
            s.prev = tail.prev; s.next = tail;
            tail.prev.next = s; tail.prev = s;
            size++;
        }

        Seg pollFirst() {
            if (head.next == tail) return null;
            Seg s = head.next;
            remove(s);
            return s;
        }

        void remove(Seg s) {
            s.prev.next = s.next; s.next.prev = s.prev;
            s.prev = null; s.next = null;
            size--;
        }

        void clear() {
            Seg cur = head.next;
            while (cur != tail) {
                Seg nx = cur.next;
                cur.release();
                cur = nx;
            }
            head.next = tail; tail.prev = head;
            size = 0;
        }
    }

    // ── 상태 변수 ─────────────────────────────────────────────────────────────
    private final int conv;
    private final KcpOutput output;
    private final ByteBufAllocator alloc;

    private int rxRttvar;
    private int rxSrtt;
    private int rxRto = RTO_DEF;

    private long sndUna;
    private long sndNxt;
    private long rcvNxt;

    private int rmtWnd = WND_RCV;

    private int current;
    private final int interval = INTERVAL;
    private int tsFlush = INTERVAL;
    private boolean updated;

    private int probe;
    private int tsProbe;
    private int probeWait;

    private int[] acklist = new int[16];
    private int   ackcount;

    private int state; // -1 = dead

    private final SegList sndQueue = new SegList();
    private final SegList rcvQueue = new SegList();
    private final SegList sndBuf   = new SegList();
    private final SegList rcvBuf   = new SegList();

    // ── 생성 / 해제 ───────────────────────────────────────────────────────────

    public KcpCore(int conv, KcpOutput output, ByteBufAllocator alloc) {
        this.conv   = conv;
        this.output = output;
        this.alloc  = alloc;
    }

    public void release() {
        sndQueue.clear();
        rcvQueue.clear();
        sndBuf.clear();
        rcvBuf.clear();
    }

    // ── 공개 API ──────────────────────────────────────────────────────────────

    public int input(ByteBuf data) {
        if (data == null || data.readableBytes() < OVERHEAD) return -1;

        // RTT 측정 전용 현재 시각. this.current(직전 update가 박은 값)는 update
        // 지터로 최대 수십 ms 낡을 수 있어, 이를 RTT 계산에 쓰면 네트워크가 일정해도
        // 측정 RTT가 update 타이밍에 따라 흔들린다(불규칙 스파이크의 원인).
        // ACK가 실제 처리되는 이 순간의 시각으로 측정해 지터 영향을 제거한다.
        // this.current 필드 자체는 변경하지 않아 다른 로직(재전송 등)에 영향 없음.
        int rttNow = KcpChannel.milliSeconds();

        long maxack = 0;
        int  maxackts = 0;
        boolean hasAck = false;

        while (true) {
            if (data.readableBytes() == 0) break;
            if (data.readableBytes() < OVERHEAD) break; // kcp-go와 동일: 남은 바이트 무시하고 정상 종료

            int  pktConv = data.readIntLE();
            byte cmd     = data.readByte();
            int  frg     = data.readUnsignedByte();
            int  wnd     = data.readUnsignedShortLE();
            int  ts      = data.readIntLE();
            long sn      = data.readUnsignedIntLE();
            long una     = data.readUnsignedIntLE();
            int  len     = data.readIntLE();

            if (data.readableBytes() < len || len < 0) return -2;
            if (pktConv != conv) return -4;
            if (cmd != CMD_PUSH && cmd != CMD_ACK && cmd != CMD_WASK && cmd != CMD_WINS) return -3;

            rmtWnd = wnd;
            parseUna(una);
            shrinkBuf();

            boolean consumed = false;
            switch (cmd) {
                case CMD_ACK: {
                    int rtt = itimediff(rttNow, ts);
                    if (rtt >= 0) updateAck(rtt);
                    parseAck(sn);
                    shrinkBuf();
                    if (!hasAck || itimediff(sn, maxack) > 0) { hasAck = true; maxack = sn; maxackts = ts; }
                    break;
                }
                case CMD_PUSH: {
                    if (itimediff(sn, rcvNxt + WND_RCV) < 0) {
                        ackPush(sn, ts);
                        if (itimediff(sn, rcvNxt) >= 0) {
                            Seg seg = Seg.alloc();
                            seg.data = len > 0 ? data.readRetainedSlice(len) : alloc.ioBuffer(0, 0);
                            consumed = true;
                            seg.conv = pktConv; seg.cmd = cmd; seg.frg = frg;
                            seg.wnd  = wnd;     seg.ts  = ts;  seg.sn  = sn; seg.una = una;
                            parseData(seg);
                        }
                    }
                    break;
                }
                case CMD_WASK: probe |= ASK_TELL; break;
                case CMD_WINS: break;
            }
            if (!consumed) data.skipBytes(len);
        }

        boolean flushNow = false;
        if (hasAck) flushNow = parseFastack(maxack, maxackts);
        if (flushNow) flush();
        return 0;
    }

    public void send(ByteBuf buf) {
        int len = buf.readableBytes();
        if (len == 0) return;

        Seg last = sndQueue.peekLast();
        if (last != null) {
            int lastLen = last.data.readableBytes();
            if (lastLen < MSS) {
                int space  = MSS - lastLen;
                int extend = Math.min(len, space);
                if (last.data.maxWritableBytes() < extend) {
                    ByteBuf newBuf = buf.alloc().ioBuffer(lastLen + extend);
                    newBuf.writeBytes(last.data);
                    last.data.release();
                    last.data = newBuf;
                }
                last.data.writeBytes(buf, extend);
                len = buf.readableBytes();
                if (len == 0) return;
            }
        }

        while (len > 0) {
            int size = Math.min(len, MSS);
            Seg seg = Seg.alloc();
            seg.data = buf.readRetainedSlice(size);
            seg.frg  = 0;
            sndQueue.addLast(seg);
            len -= size;
        }
    }

    public int recv(ByteBuf buf) {
        Seg first = rcvQueue.peek();
        if (first == null) return -1;
        int peekSize = first.data.readableBytes();
        if (peekSize > buf.maxCapacity()) return -3;

        boolean recover = rcvQueue.size >= WND_RCV;
        buf.writeBytes(first.data);
        rcvQueue.remove(first);
        first.release();

        moveRcvData();
        if (rcvQueue.size < WND_RCV && recover) probe |= ASK_TELL;
        return peekSize;
    }

    public int peekSize() {
        Seg first = rcvQueue.peek();
        return first == null ? -1 : first.data.readableBytes();
    }

    public void update(int current) {
        this.current = current;
        if (!updated) { updated = true; tsFlush = current; }

        int slap = itimediff(current, tsFlush);
        if (slap >= 10_000 || slap < -10_000) { tsFlush = current; slap = 0; }
        if (slap >= 0) {
            tsFlush += interval;
            if (itimediff(current, tsFlush) >= 0) tsFlush = current + interval;
        } else {
            tsFlush = current + interval;
        }
        flush();
    }

    public int check(int current) {
        if (!updated) return current;

        int tf = tsFlush;
        int slap = itimediff(current, tf);
        if (slap >= 10_000 || slap < -10_000) { tf = current; slap = 0; }
        if (slap >= 0) return current;

        int tmFlush  = itimediff(tf, current);
        int tmPacket = Integer.MAX_VALUE;
        for (Seg s = sndBuf.head.next; s != sndBuf.tail; s = s.next) {
            int diff = itimediff(s.resendts, current);
            if (diff <= 0) return current;
            if (diff < tmPacket) tmPacket = diff;
        }

        return current + Math.min(Math.min(tmPacket, tmFlush), interval);
    }

    public int     getState()     { return state; }
    public int     getConv()      { return conv; }
    public int     waitSnd()      { return sndBuf.size + sndQueue.size; }
    public int     sndQueueSize() { return sndQueue.size; }
    public int     rmtWnd()       { return rmtWnd; }

    /**
     * ACKNoDelay: input() 직후 호출해서 ACK만 즉시 UDP로 전송.
     * kcp-go SetACKNoDelay(true)와 동일한 효과.
     * 서버의 fastack 트리거를 빠르게 해서 재전송 지연을 줄임.
     */
    public void flushAck() {
        if (!updated) return;
        if (ackcount == 0) return;

        Seg ctrl = Seg.control(alloc);
        ctrl.conv = conv;
        ctrl.cmd  = CMD_ACK;
        ctrl.wnd  = wndUnused();
        ctrl.una  = rcvNxt;

        ByteBuf buf = null;
        for (int i = 0; i < ackcount; i++) {
            buf = tryOut(buf, OVERHEAD);
            ctrl.sn = intToUint(acklist[i * 2]);
            ctrl.ts = acklist[i * 2 + 1];
            encodeSeg(buf, ctrl);
        }
        ackcount = 0;

        if (buf != null && buf.readableBytes() > 0) output.out(buf, buf.readableBytes());
        else if (buf != null) buf.release();
        ctrl.release();
    }

    public boolean canSend(boolean curCanSend) {
        int max     = WND_SND * 2;
        int waitSnd = waitSnd();
        if (curCanSend) return waitSnd < max;
        return waitSnd < max / 2;
    }

    // ── flush ─────────────────────────────────────────────────────────────────

    private void flush() {
        if (!updated) return;

        Seg ctrl = Seg.control(alloc);
        ctrl.conv = conv;
        ctrl.cmd  = CMD_ACK;
        ctrl.wnd  = wndUnused();
        ctrl.una  = rcvNxt;

        ByteBuf buf = null;

        // 1. ACK
        for (int i = 0; i < ackcount; i++) {
            buf = tryOut(buf, OVERHEAD);
            ctrl.sn = intToUint(acklist[i * 2]);
            ctrl.ts = acklist[i * 2 + 1];
            encodeSeg(buf, ctrl);
        }
        ackcount = 0;

        // 2. Window probe
        if (rmtWnd == 0) {
            if (probeWait == 0) {
                probeWait = PROBE_INIT;
                tsProbe   = current + probeWait;
            } else if (itimediff(current, tsProbe) >= 0) {
                probeWait = Math.max(probeWait, PROBE_INIT);
                probeWait += probeWait / 2;
                if (probeWait > PROBE_LIM) probeWait = PROBE_LIM;
                tsProbe = current + probeWait;
                probe |= ASK_SEND;
            }
        } else {
            tsProbe = 0; probeWait = 0;
        }


        if ((probe & ASK_SEND) != 0) {
            ctrl.cmd = CMD_WASK;
            buf = tryOut(buf, OVERHEAD);
            encodeSeg(buf, ctrl);
        }
        if ((probe & ASK_TELL) != 0) {
            ctrl.cmd = CMD_WINS;
            buf = tryOut(buf, OVERHEAD);
            encodeSeg(buf, ctrl);
        }
        probe = 0;

        // 3. sndQueue → sndBuf
        while (itimediff(sndNxt, sndUna + Math.min(WND_SND, rmtWnd)) < 0) {
            Seg ns = sndQueue.pollFirst();
            if (ns == null) break;
            ns.conv = conv; ns.cmd = CMD_PUSH; ns.wnd = ctrl.wnd;
            ns.ts = current; ns.sn = sndNxt++; ns.una = rcvNxt;
            ns.resendts = current; ns.rto = rxRto;
            ns.fastack = 0; ns.xmit = 0;
            sndBuf.addLast(ns);
        }

        // 4. 재전송 / 신규 전송
        for (Seg seg = sndBuf.head.next; seg != sndBuf.tail; seg = seg.next) {
            boolean needSend = false;

            if (seg.xmit == 0) {
                needSend = true;
                seg.xmit++;
                seg.rto      = rxRto;
                seg.resendts = current + seg.rto;
            } else if (itimediff(current, seg.resendts) >= 0) {
                needSend = true;
                seg.xmit++;
                seg.fastack = 0;
                seg.rto    += rxRto / 2;
                seg.resendts = current + seg.rto;
            } else if (seg.fastack >= RESEND && seg.xmit <= FASTACK_LIMIT) {
                // fast retransmit — ACK RESEND(2)회 건너뜀 확인 후 재전송.
                // 임계 1은 ACK 데이터그램 1개의 재정렬/유실만으로 재전송을 유발해
                // 과민(spurious fast retransmit)했다. kcp-go 서버(resend=2)와 정합.
                needSend = true;
                seg.xmit++;
                seg.fastack  = 0;
                seg.resendts = current + seg.rto;
            }

            if (needSend) {
                seg.ts  = current;
                seg.wnd = ctrl.wnd;
                seg.una = rcvNxt;

                int segLen = seg.data.readableBytes();
                buf = tryOut(buf, OVERHEAD + segLen);
                encodeSeg(buf, seg);
                if (segLen > 0)
                    buf.writeBytes(seg.data, seg.data.readerIndex(), segLen);

                if (seg.xmit >= DEADLINK) state = -1;
            }
        }

        // 5. flush
        if (buf != null) {
            if (buf.readableBytes() > 0) doOutput(buf);
            else buf.release();
        }
        ctrl.release();
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private ByteBuf tryOut(ByteBuf buf, int need) {
        if (buf == null) return alloc.ioBuffer(MTU);
        if (buf.readableBytes() + need > MTU) {
            doOutput(buf);
            return alloc.ioBuffer(MTU);
        }
        return buf;
    }

    private void doOutput(ByteBuf data) {
        if (data.readableBytes() > 0) output.out(data, conv);
        else data.release();
    }

    private static void encodeSeg(ByteBuf buf, Seg seg) {
        buf.writeIntLE(seg.conv);
        buf.writeByte(seg.cmd);
        buf.writeByte(seg.frg);
        buf.writeShortLE(seg.wnd);
        buf.writeIntLE(seg.ts);
        buf.writeIntLE((int) seg.sn);
        buf.writeIntLE((int) seg.una);
        buf.writeIntLE(seg.data == null ? 0 : seg.data.readableBytes());
    }

    private void updateAck(int rtt) {
        if (rxSrtt == 0) {
            rxSrtt   = rtt;
            rxRttvar = rtt / 2;
        } else {
            int delta = Math.abs(rtt - rxSrtt);
            rxRttvar = (3 * rxRttvar + delta) / 4;
            rxSrtt   = (7 * rxSrtt + rtt) / 8;
            if (rxSrtt < 1) rxSrtt = 1;
        }
        // RTO_MIN 하한 클램프 — kcp-go의 _ibound_(rx_minrto, rto, RTO_MAX)와 동일.
        // 하한 없이는 안정 구간에서 rto가 srtt+수 ms(~12ms)까지 조여져, 경로 지터
        // 스파이크(15~37ms)만으로 RTO가 발화 → in-flight 세그먼트 대량 중복 재전송.
        rxRto = Math.min(Math.max(RTO_MIN, rxSrtt + Math.max(interval, 4 * rxRttvar)), RTO_MAX);
    }

    private void shrinkBuf() {
        Seg first = sndBuf.peek();
        sndUna = (first != null) ? first.sn : sndNxt;
    }

    private void parseAck(long sn) {
        if (itimediff(sn, sndUna) < 0 || itimediff(sn, sndNxt) >= 0) return;
        for (Seg s = sndBuf.head.next; s != sndBuf.tail; s = s.next) {
            if (sn == s.sn) { sndBuf.remove(s); s.release(); break; }
            if (itimediff(sn, s.sn) < 0) break;
        }
    }

    private void parseUna(long una) {
        Seg s = sndBuf.head.next;
        while (s != sndBuf.tail) {
            Seg nx = s.next;
            if (itimediff(una, s.sn) > 0) { sndBuf.remove(s); s.release(); }
            else break;
            s = nx;
        }
    }

    private boolean parseFastack(long sn, int ts) {
        if (itimediff(sn, sndUna) < 0 || itimediff(sn, sndNxt) >= 0) return false;
        boolean shouldFlush = false;
        for (Seg s = sndBuf.head.next; s != sndBuf.tail; s = s.next) {
            if (itimediff(sn, s.sn) < 0) break;
            else if (sn != s.sn && itimediff(s.ts, ts) <= 0) {
                s.fastack++;
                if (s.fastack >= RESEND) shouldFlush = true;
            }
        }
        return shouldFlush;
    }

    private void ackPush(long sn, int ts) {
        int need = 2 * (ackcount + 1);
        if (need > acklist.length) {
            int newCap = acklist.length << 1;
            if (newCap < 0) throw new OutOfMemoryError();
            int[] newArr = new int[newCap];
            System.arraycopy(acklist, 0, newArr, 0, acklist.length);
            acklist = newArr;
        }
        acklist[2 * ackcount]     = (int) sn;
        acklist[2 * ackcount + 1] = ts;
        ackcount++;
    }

    private void parseData(Seg newSeg) {
        long sn = newSeg.sn;
        // 수신 윈도우 초과 또는 이미 소비된 패킷 → DROP
        if (itimediff(sn, rcvNxt + WND_RCV) >= 0 || itimediff(sn, rcvNxt) < 0) {
            newSeg.release(); return;
        }
        // 꼬리에서부터 역방향 탐색으로 삽입 위치 결정.
        // 청크 로딩처럼 sn이 forward로 진행하는 버스트에서 새 세그먼트는 대부분
        // rcvBuf 맨 뒤에 붙으므로 O(1)에 끝난다. 기존 head-first 순방향 탐색은
        // 이 경우 매 삽입마다 전체를 순회해 O(n²)가 되어(손실 1개로 수천 개가
        // 적체되면 삽입마다 수천 번 비교), eventLoop를 수십 ms 멈추게 만들고
        // 그 사이 OS 소켓 버퍼가 넘쳐 추가 손실 → 처리량이 붕괴한다.
        // insertAfter = sn보다 작은 첫 노드(뒤에서부터). 없으면 head(맨 앞 삽입).
        Seg insertAfter = rcvBuf.head;
        for (Seg s = rcvBuf.tail.prev; s != rcvBuf.head; s = s.prev) {
            if (s.sn == sn) { newSeg.release(); return; }   // 중복 → DROP
            if (itimediff(sn, s.sn) > 0) { insertAfter = s; break; }
        }
        newSeg.prev = insertAfter;
        newSeg.next = insertAfter.next;
        insertAfter.next.prev = newSeg;
        insertAfter.next = newSeg;
        rcvBuf.size++;
        moveRcvData();
    }

    private void moveRcvData() {
        Seg s = rcvBuf.head.next;
        while (s != rcvBuf.tail) {
            if (s.sn == rcvNxt && rcvQueue.size < WND_RCV) {
                Seg nx = s.next;
                rcvBuf.remove(s);
                rcvQueue.addLast(s);
                rcvNxt++;
                s = nx;
            } else {
                break;
            }
        }
    }

    private int wndUnused() {
        return Math.max(0, WND_RCV - rcvQueue.size);
    }

    private static int itimediff(int later, int earlier)  { return later - earlier; }
    private static int itimediff(long later, long earlier) { return (int)(later - earlier); }
    private static long intToUint(int i) { return i & 0xFFFFFFFFL; }
}