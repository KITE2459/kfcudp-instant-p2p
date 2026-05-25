package kfc.udp.client.kcp;

/**
 * ConnectScreenMixin → ClientConnectionMixin 간 KCP 주소 전달.
 * Mixin 클래스 밖에 두어 non-private static 문제 회피.
 */
public class KcpAddressRegistry {

    private static volatile String host = null;
    private static volatile int    port = -1;

    public static void register(String h, int p) {
        host = h;
        port = p;
    }

    public static String pollHost() {
        String h = host;
        host = null;
        return h;
    }

    public static int pollPort() {
        int p = port;
        port = -1;
        return p;
    }

    public static boolean hasPending() {
        return host != null && port >= 0;
    }
}