package kfc.udp.client.kcp;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * KCP 연결 플래그.
 * ConnectScreenMixin → ClientConnectionMixin 간 신호 전달.
 * DNS/SRV resolve로 주소가 바뀌어도 무관하게 동작.
 */
public class KcpAddressRegistry {

    private static final AtomicBoolean pending = new AtomicBoolean(false);

    public static void register() {
        pending.set(true);
    }

    /** Server Pinger 스레드 차단, 그 외 스레드에서 플래그 소비 */
    public static boolean consumeIfKcp() {
        if (Thread.currentThread().getName().startsWith("Server Pinger")) return false;
        return pending.compareAndSet(true, false);
    }
}