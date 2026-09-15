package kfc.udp.client.webrtc;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 시그널링 서버(오라클)까지의 왕복 지연 추정 — 방 목록의 핑 막대용.
 * <p>
 * 방장과 직접 핑을 주고받으면 목록만 열어도 서로의 IP가 패킷에 찍힌다. 그래서 각자 오라클까지의
 * RTT만 재고, 방 목록에는 "내 RTT + 방장 RTT(방 공지에 실려 옴)"를 보여준다. 릴레이 연결은 실제로
 * 오라클 coturn을 지나므로 거의 정확하고, 직결은 이보다 빠를 수 있는 보수적 추정이다.
 * <p>
 * 표본은 웹소켓의 TCP 연결 시간(왕복 1회)과 {@link #PING_INTERVAL_S}초마다 보내는 ping의 pong 시간.
 * 순간 지연 한 번에 막대가 흔들리지 않게 최근 {@link #WINDOW}개 중 최솟값을 쓴다.
 */
public final class SignalingRtt {

    private static final int WINDOW = 5;
    private static final long PING_INTERVAL_S = 5;

    private static final long[] samples = new long[WINDOW];
    private static int count, next;

    /** 오래 붙어 있는 연결(방 목록·방 공지·호스트 로비) — 전부 같은 서버라 그중 하나로만 ping을 보낸다. */
    private static final Set<WebSocketClient> LIVE = ConcurrentHashMap.newKeySet();

    static {
        ScheduledExecutorService pinger = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "signaling-rtt");
            t.setDaemon(true);
            return t;
        });
        pinger.scheduleWithFixedDelay(() -> LIVE.stream().findAny().ifPresent(WebSocketClient::sendPing),
                PING_INTERVAL_S, PING_INTERVAL_S, TimeUnit.SECONDS);
    }

    private SignalingRtt() {}

    static synchronized void record(long ms) {
        samples[next] = Math.max(0, ms);
        next = (next + 1) % WINDOW;
        if (count < WINDOW) count++;
    }

    /** 최근 표본 중 최솟값(ms), 아직 없으면 -1. */
    public static synchronized long currentMs() {
        long min = -1;
        for (int i = 0; i < count; i++) {
            if (min < 0 || samples[i] < min) min = samples[i];
        }
        return min;
    }

    static void track(WebSocketClient c) {
        LIVE.add(c);
    }

    static void untrack(WebSocketClient c) {
        LIVE.remove(c);
    }

    /** 바닐라 서버 목록과 같은 기준의 막대 수(1~5), 모르면 0. */
    public static int bars(long ms) {
        if (ms < 0) return 0;
        if (ms < 150) return 5;
        if (ms < 300) return 4;
        if (ms < 600) return 3;
        if (ms < 1000) return 2;
        return 1;
    }
}
