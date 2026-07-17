package kfc.udp.client.webrtc;

/**
 * P2P 인프라 접속 설정 (시그널링/STUN/TURN) — 호스트·조인 공용.
 * <p>
 * 서버 배치가 바뀌면 이 파일만 고치면 된다. 빌드 없이 JVM 프로퍼티로도 재정의 가능:
 * <pre>
 *   -Dkfcudp.signaling=ws://HOST:PORT
 *   -Dkfcudp.stun=stun:HOST:3478
 *   -Dkfcudp.turn=turn:HOST:3478
 *   -Dkfcudp.turn.user=USER  -Dkfcudp.turn.pass=PASS
 * </pre>
 * 현재 배치 (오라클 클라우드 193.122.114.163):
 * villas-signaling → *:8088, coturn → *:3478
 */
public final class P2PConfig {

    /** villas-signaling WebSocket 주소 */
    public static final String SIGNALING_URL =
            System.getProperty("kfcudp.signaling", "ws://193.122.114.163:8088");

    /** coturn STUN (무인증) */
    public static final String STUN_URL =
            System.getProperty("kfcudp.stun", "stun:193.122.114.163:3478");

    /** coturn TURN (정적 계정 인증) */
    public static final String TURN_URL =
            System.getProperty("kfcudp.turn", "turn:193.122.114.163:3478");

    /** Go 구현(bridge.turnServers)에서 실사용하던 coturn 정적 계정 */
    public static final String TURN_USERNAME =
            System.getProperty("kfcudp.turn.user", "minecraft");
    public static final String TURN_CREDENTIAL =
            System.getProperty("kfcudp.turn.pass", "minecraft");

    private P2PConfig() {}
}