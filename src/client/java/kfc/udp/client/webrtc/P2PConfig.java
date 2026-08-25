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

    /**
     * <b>TURN 전용(relay-only) 스위치.</b>
     * <p>
     * true 면 ICE 후보를 relay 만 수집한다(=직결/홀펀칭 없음, 100% TURN 경유).
     * 기본값은 false(일반 P2P). TURN 전용으로 돌리려면
     * {@code -Dkfcudp.ice.relayonly=true} 로 실행한다.
     */
    public static final boolean RELAY_ONLY =
            Boolean.getBoolean("kfcudp.ice.relayonly");

    // ── 파이프 버퍼 한도 (지연 ↔ 처리량 트레이드오프) ─────────────────────────

    /**
     * <b>DataChannel 송신 버퍼 상한(바이트).</b> bufferedAmount가 이 값을 넘으면
     * TCP→DC 송신 스레드가 대기한다(백프레셔).
     * <p>
     * 예전 기본값은 16MB였는데, 이건 처리량이 아니라 <b>지연</b>을 망가뜨린다.
     * 청크 로딩으로 링크가 포화되면 이동·keepalive 같은 작은 패킷이 앞서 쌓인
     * 수 MB 뒤에 줄을 선다 — 10Mbps 기준 16MB면 12초치 큐다. SCTP 자체 혼잡제어가
     * 이미 in-flight를 관리하므로, 상한은 BDP를 조금 넘기는 선이면 충분하고
     * 1MB로도 처리량 손해는 거의 없다.
     * <p>되돌리려면 {@code -Dkfcudp.pipe.dchigh=16777216}.
     */
    public static final long DC_BUF_HIGH =
            Long.getLong("kfcudp.pipe.dchigh", 1024 * 1024L);

    /** 이 아래로 빠지면 송신 재개 (히스테리시스). */
    public static final long DC_BUF_LOW =
            Long.getLong("kfcudp.pipe.dclow", 256 * 1024L);

    /**
     * DC→TCP writer 큐 길이(64KB 청크 개수).
     * 예전 512(=32MB)는 위 DC 버퍼와 합쳐 최대 48MB의 버퍼블로트를 만들었다.
     * 64(=4MB)면 배칭 효과는 유지하면서 최악 큐 지연이 8배 줄어든다.
     * <p>되돌리려면 {@code -Dkfcudp.pipe.queuechunks=512}.
     */
    public static final int PIPE_QUEUE_CHUNKS =
            Integer.getInteger("kfcudp.pipe.queuechunks", 64);

    private P2PConfig() {}
}