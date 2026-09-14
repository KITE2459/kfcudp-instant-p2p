package kfc.udp.client.webrtc;

import dev.onvoid.webrtc.*;
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;
import dev.onvoid.webrtc.media.audio.AudioLayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
//? if >=26.1 {
/*import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.network.chat.Component;
*///?} else {
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.text.Text;
//?}

/**
 * Java 네이티브 WebRTC 클라이언트(조인) — VILLASframework signaling 프로토콜.
 * <p>
 * 조인 절차 (WebRtcHost의 로비/페어 구조와 짝):
 * <ol>
 *   <li>페어 세션 {@code /{roomId}-{sid}}에 peer "p{sid}"로 먼저 접속해 대기</li>
 *   <li>로비 {@code /{roomId}}에 peer "j{sid}"로 접속(조인 알림) — 호스트가 감지</li>
 *   <li>페어 세션 control에 호스트(peer "h…")가 나타나면 → OFFER 생성/전송</li>
 *   <li>ANSWER/ICE 교환 → DataChannel open → MC 트래픽 파이프</li>
 * </ol>
 * 지연 최적화(기존 유지):
 *   - onMessage → 직접 MC 소켓 write (스레드 핸드오프/큐 지연 제거)
 *   - 수신 버퍼는 ThreadLocal로 재사용, 송신은 ThreadLocal direct buffer
 */
public class WebRtcClient {

    //? if >=26.2 {
    /*private static net.minecraft.client.gui.screens.Screen kfcudp$currentScreen(net.minecraft.client.Minecraft client) {
        return client.gui.screen();
    }
    *///?}
    //? if >=26.1 <26.2 {
    /*private static net.minecraft.client.gui.screens.Screen kfcudp$currentScreen(net.minecraft.client.Minecraft client) {
        return client.screen;
    }
    *///?}

    private static final Logger LOG = LoggerFactory.getLogger("webrtc-native");

    // 버퍼 한도는 P2PConfig에서 관리 — 지연/처리량 트레이드오프 근거와
    // -Dkfcudp.pipe.* 되돌리기 방법은 그쪽 주석 참고.
    private static final long DC_BUF_HIGH  = P2PConfig.DC_BUF_HIGH;
    private static final long DC_BUF_LOW   = P2PConfig.DC_BUF_LOW; // 이하로 빠지면 송신 재개

    /** MC 클라이언트 접속 대기 한도 */
    private static final int ACCEPT_TIMEOUT_MS = 120_000;

    /**
     * 1차(직결 전용) 시도 한도. 시그널링/coturn이 전부 가까이(수십 ms 이내) 있는
     * 배포 환경 기준 — 홀펀칭이 되는 조합이면 이 안에 거의 항상 판명난다.
     * 안 되면(양쪽 다 대칭형 NAT 등) 더 기다려도 대개 소용없으므로 바로
     * 2차(릴레이 포함) 시도로 넘어간다. {@link IceConfig} 클래스 주석 참고.
     */
    private static final int DIRECT_ATTEMPT_TIMEOUT_MS = 2_000;
    /** 2차(릴레이 포함) 시도까지 포함한 최종 한도 — 기존 동작과 동일하게 유지. */
    private static final int RELAY_ATTEMPT_TIMEOUT_MS  = 30_000;

    // ── 인스턴스 필드 ─────────────────────────────────────────────────────────

    private final String roomId;
    private final int    localPort;
    private final String sessionId;

    private AudioDeviceModule       audioModule;
    private PeerConnectionFactory   factory;
    private RTCPeerConnection       peerConnection;
    private volatile RTCDataChannel dataChannel;
    private ServerSocketChannel     serverChannel;
    private volatile SocketChannel  mcChannel;
    private volatile BatchPipe.Writer mcWriter; // DC→MC 배칭 writer

    private final AtomicBoolean  running         = new AtomicBoolean(false);
    /** 백프레셔 대기/웨이크업 (onBufferedAmountChange 이벤트 기반) */
    private final Object bpLock = new Object();
    private final CountDownLatch hostArrivedLatch = new CountDownLatch(1);
    private final CountDownLatch readyLatch       = new CountDownLatch(1);

    private volatile String             pendingAnswer = null;
    private final List<RTCIceCandidate> pendingIce    = new ArrayList<>();

    private WebSocketClient pairWs;      // SDP/ICE 교환용 (roomId-sid)
    private WebSocketClient announceWs;  // 조인 알림용 (roomId 로비)

    /** 서버가 내려준 TURN/STUN relays (없으면 P2PConfig 기본값 사용) */
    private volatile List<String[]> serverRelays = List.of();

    /** 호스트가 중계 통신을 강제 중인지 — 호스트의 페어 세션 peer 이름("h" 다음 글자)에
     * 실려 온다(WebRtcHost.PairSignal.open() 참고). 내가 중계 강제가 아니어도
     * 호스트가 강제면 1차(직결 전용) 시도는 무의미하므로 건너뛴다. */
    private volatile boolean hostRelayOnly = false;

    public WebRtcClient(String roomId, int localPort) {
        this.roomId    = roomId;
        this.localPort = localPort;
        this.sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    public void start() throws Exception {
        running.set(true);
        connectPairSignaling();

        serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress(localPort));
        LOG.info("[webrtc] Ready on port {}", localPort);

        Thread t = new Thread(this::acceptAndBridge, "webrtc-accept");
        t.setDaemon(true);
        t.start();
    }

    private void acceptAndBridge() {
        try {
            SocketChannel sock = acceptWithTimeout();
            if (sock == null) {
                LOG.warn("[webrtc] MC client did not connect in time");
                close(); return;
            }
            sock.setOption(StandardSocketOptions.TCP_NODELAY, true);
            sock.setOption(StandardSocketOptions.SO_RCVBUF, 512 * 1024);
            sock.setOption(StandardSocketOptions.SO_SNDBUF, 512 * 1024);
            mcChannel = sock;
            // DC→MC: 전담 writer 스레드가 연속 청크를 writev 1회로 배칭
            mcWriter = new BatchPipe.Writer(sock, "webrtc-mc-writer", e -> {
                if (running.get()) LOG.warn("[webrtc] MC write failed: {}", e.getMessage());
                close();
            });

            // 로비에 조인 알림 → 호스트가 페어 세션으로 들어옴
            announceJoin();

            if (!hostArrivedLatch.await(15, TimeUnit.SECONDS)) {
                LOG.warn("[webrtc] host did not arrive in pair session (room={})", roomId);
                //? if >=26.1 {
                /*notifyFailure(Component.translatable("instant-p2p.msg.host_not_found"));
                *///?} else {
                notifyFailure(Text.translatable("instant-p2p.msg.host_not_found"));
                //?}
                close(); return;
            }

            // 1차: TURN 후보 자체를 안 만들어서 릴레이 pair가 생길 수 없게 한 뒤
            // 직결(host/srflx)만 시도한다. 짧은 시간 안에 안 되면 2차로 TURN을
            // 포함해서 재시도한다 — TURN allocate가 홀펀칭보다 먼저 성사돼서
            // 직결이 가능한데도 릴레이로 확정돼버리는 경쟁을 피하기 위함.
            // IceConfig 클래스 주석 참고.
            //
            // 나 또는 호스트 둘 중 하나라도 중계 강제면 1차(직결 전용)를 건너뛴다.
            // 예전엔 "호스트가 강제일 때"만 건너뛰었는데("나만" 강제인 경우 호스트가
            // 최초 OFFER를 여전히 1차/직결 전용으로 응답해버려서 나는 relay 후보만,
            // 호스트는 host/srflx 후보만 갖게 돼 서로 못 붙는 문제가 있었다) — 이제는
            // 조인 알림 peer 이름("j" 다음 글자, WebRtcHost.handleLobby 참고)에 내
            // 강제 여부도 같이 실어 보내서, 호스트가 최초 OFFER부터 이미 릴레이
            // 허용으로 응답하도록(PairSignal.handlePair) 만들어 뒀다 — 그래서 어느
            // 쪽이 강제든 대칭으로 맞아떨어져 1차를 건너뛰어도 안전하다.
            boolean skipToRelayOnly = hostRelayOnly || P2PConfig.isRelayOnly();
            boolean connected = attemptConnection(skipToRelayOnly, skipToRelayOnly ? RELAY_ATTEMPT_TIMEOUT_MS : DIRECT_ATTEMPT_TIMEOUT_MS);
            if (!connected && running.get() && !skipToRelayOnly) {
                LOG.info("[webrtc] direct-only attempt did not complete within {}ms, retrying with relay allowed",
                        DIRECT_ATTEMPT_TIMEOUT_MS);
                connected = attemptConnection(true, RELAY_ATTEMPT_TIMEOUT_MS);
            }

            if (!connected) {
                if (running.get()) {
                    LOG.warn("[webrtc] DataChannel open timed out");
                    //? if >=26.1 {
                    /*notifyFailure(Component.translatable("instant-p2p.msg.ice_failed"));
                    *///?} else {
                    notifyFailure(Text.translatable("instant-p2p.msg.ice_failed"));
                    //?}
                    close();
                }
                return;
            }

            // 연결 완료 — 조인 알림용 로비 접속은 정리
            WebSocketClient a = announceWs;
            announceWs = null;
            if (a != null) a.close();

            forwardMcToWebRtc(sock);

        } catch (Exception e) {
            if (running.get()) {
                LOG.warn("[webrtc] bridge error: {}", e.getMessage());
                //? if >=26.1 {
                /*notifyFailure(Component.translatable("instant-p2p.msg.connect_failed", String.valueOf(e.getMessage())));
                *///?} else {
                notifyFailure(Text.translatable("instant-p2p.msg.connect_failed", String.valueOf(e.getMessage())));
                //?}
            }
            close();
        }
    }

    private volatile Boolean usesRelay = null;

    /** null = 아직 모름(통계 조회 전이거나 candidate pair 미확정). KfcudpClient가 월드 진입 시점에 읽어간다. */
    public Boolean usesRelay() {
        return usesRelay;
    }

    /**
     * DataChannel이 열리는 시점은 ICE가 "connected" 상태만 되면 도달하고, 그 뒤로도
     * 더 나은 candidate pair로 nominated가 바뀔 수 있다(특히 relay-only일 때 TURN
     * allocate가 조금 느리면 그 사이 순간적으로 succeeded 상태였던 pair를 잘못 잡을
     * 여지가 있음 — WebRtcStats 클래스 주석 참고). 그래서 여기서 한 번 읽고, 조금
     * 있다가 한 번 더 읽어서 값이 바뀌면 그걸로 덮어쓴다. 실제로 이 값을 쓰는
     * 참여 메시지(instant-p2p.msg.my_connection_*)는 MC 로그인 절차가 끝난 뒤에야
     * 뜨므로, 이 지연 정도는 그 전에 여유 있게 끝난다.
     */
    private void resolveConnectionType() {
        RTCPeerConnection pc = peerConnection;
        if (pc == null) return;
        pc.getStats(report -> {
            usesRelay = WebRtcStats.usesRelay(report);
            LOG.info("[webrtc] connection type (initial): usesRelay={} (relayOnly={})", usesRelay, P2PConfig.isRelayOnly());
        });
        // 2초 자고 끝나는 스레드를 새로 만들지 않고 기존 시그널링 타이머에 예약한다.
        try {
            signalScheduler.schedule(() -> {
                RTCPeerConnection pc2 = peerConnection;
                if (pc2 == null) return;
                pc2.getStats(report -> {
                    Boolean recheck = WebRtcStats.usesRelay(report);
                    if (recheck != null && !recheck.equals(usesRelay)) {
                        LOG.warn("[webrtc] connection type changed on recheck: {} -> {} (relayOnly={})",
                                usesRelay, recheck, P2PConfig.isRelayOnly());
                        usesRelay = recheck;
                    }
                });
            }, 2, TimeUnit.SECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {}
    }

    /** 연결 실패를 실제 화면으로 보여준다 — 안 그러면 조인자는 원인도 모르고 로컬 소켓만 뚝 끊긴다. */
    //? if >=26.1 {
    /*private void notifyFailure(Component reason) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (kfcudp$currentScreen(client) instanceof DisconnectedScreen) return;
            client.setScreenAndShow(new DisconnectedScreen(
                    kfcudp$currentScreen(client), Component.translatable("connect.failed"), reason));
        });
    }
    *///?} else {
    private void notifyFailure(Text reason) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            if (client.currentScreen instanceof DisconnectedScreen) return;
            client.setScreen(new DisconnectedScreen(
                    client.currentScreen, Text.translatable("connect.failed"), reason));
        });
    }
    //?}

    /**
     * MC 클라이언트 접속을 최대 {@link #ACCEPT_TIMEOUT_MS}까지 기다린다.
     * <p>
     * {@code ServerSocketChannel}은 블로킹 accept에 타임아웃을 걸 수 없어
     * (예전 {@code ServerSocket.setSoTimeout}에 해당하는 게 없다) Selector로
     * 대기한다. 채널 방식으로 accept해야 {@code SocketChannel}을 얻어
     * direct 버퍼 read/writev를 쓸 수 있다.
     *
     * @return 접속된 채널, 시간 초과/종료 시 null
     */
    private SocketChannel acceptWithTimeout() throws IOException {
        ServerSocketChannel ssc = serverChannel;
        ssc.configureBlocking(false);
        try (Selector sel = Selector.open()) {
            ssc.register(sel, SelectionKey.OP_ACCEPT);
            long deadline = System.nanoTime() + ACCEPT_TIMEOUT_MS * 1_000_000L;
            while (running.get()) {
                long remainMs = (deadline - System.nanoTime()) / 1_000_000L;
                if (remainMs <= 0) return null;
                sel.select(remainMs);
                sel.selectedKeys().clear();
                if (!running.get()) return null; // close() 중 깨어난 경우
                SocketChannel sc = ssc.accept();
                if (sc != null) {
                    sc.configureBlocking(true); // 새 채널이라 셀렉터에 등록된 적 없음
                    return sc;
                }
            }
            return null;
        }
    }

    // ── 시그널링 (VILLAS) ─────────────────────────────────────────────────────

    /**
     * 시그널링 순단 재접속 지연 — WebRtcHost.scheduleReconnect와 같은 지수 백오프.
     * 예전엔 이 두 웹소켓(페어/조인 알림) 중 하나라도 연결 수립 전에 한 번만
     * 끊기면(순간적인 네트워크 순단, 시그널링 서버 재시작 등) 곧장 접속 시도
     * 전체를 실패로 치고 close()했다 — 호스트 쪽 로비 접속은 이미 재시도가
     * 있는데 접속자 쪽만 없어서, 접속자만 "갑자기 이유 없이 연결이 안 되는"
     * 일이 훨씬 잦았다.
     */
    private static final long INITIAL_SIGNAL_BACKOFF_MS = 1_000;
    private static final long MAX_SIGNAL_BACKOFF_MS     = 15_000;

    private final ScheduledExecutorService signalScheduler =
            new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, "webrtc-client-signal-retry");
                t.setDaemon(true);
                return t;
            });

    private volatile long pairBackoffMs     = INITIAL_SIGNAL_BACKOFF_MS;
    private volatile long announceBackoffMs = INITIAL_SIGNAL_BACKOFF_MS;

    private void connectPairSignaling() {
        if (!running.get() || readyLatch.getCount() == 0) return;
        WebSocketClient client = new WebSocketClient(
                P2PConfig.SIGNALING_URL + "/" + roomId + "-" + sessionId + "/p" + sessionId) {
            @Override public void onConnected() {
                pairBackoffMs = INITIAL_SIGNAL_BACKOFF_MS;
                send(VillasMsg.hello()); // 서버가 최초 1회 signals 메시지를 요구함
            }
            @Override public void onMessage(String type, String json) {
                handlePairMessage(json);
            }
            @Override public void onDisconnected() {
                // pairWs가 이미 다른(더 최신) 연결로 넘어갔으면 중복 재시도 필요 없음.
                if (pairWs == this && readyLatch.getCount() > 0) {
                    LOG.warn("[webrtc] pair signaling lost, retrying");
                    schedulePairReconnect();
                }
            }
        };
        pairWs = client;
        try {
            client.connect();
        } catch (Exception e) {
            LOG.warn("[webrtc] pair signaling connect failed: {}", e.toString());
            if (pairWs == client) schedulePairReconnect();
        }
    }

    private void schedulePairReconnect() {
        if (!running.get() || readyLatch.getCount() == 0) return;
        long delay = pairBackoffMs;
        pairBackoffMs = Math.min(pairBackoffMs * 2, MAX_SIGNAL_BACKOFF_MS);
        try {
            signalScheduler.schedule(this::connectPairSignaling, delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {}
    }

    private void announceJoin() {
        if (!running.get() || hostArrivedLatch.getCount() == 0) return;
        // 조인 알림 peer 이름의 "j" 다음 글자에 내 중계 강제 여부를 실어 보낸다 — 호스트가
        // 이 join 감지 메시지에서 그대로 읽어가므로 새 왕복 없이 공짜로 전달된다. 내가
        // 이미 중계 강제 중이면 호스트도 최초 OFFER부터 릴레이 허용으로 응답할 수 있어
        // 1차(직결 전용) 실패 → 2차 재협상을 거칠 필요가 없다(WebRtcHost.handleLobby,
        // PairSignal.handlePair 참고).
        String flag = P2PConfig.isRelayOnly() ? "r" : "d";
        WebSocketClient client = new WebSocketClient(
                P2PConfig.SIGNALING_URL + "/" + roomId + "/j" + flag + sessionId) {
            @Override public void onConnected() {
                announceBackoffMs = INITIAL_SIGNAL_BACKOFF_MS;
                send(VillasMsg.hello());
                LOG.info("[webrtc] join announced: room={} sid={}", roomId, sessionId);
            }
            @Override public void onMessage(String type, String json) { /* 로비 메시지 무시 */ }
            @Override public void onDisconnected() {
                if (announceWs == this && hostArrivedLatch.getCount() > 0) {
                    LOG.warn("[webrtc] join announce lost, retrying");
                    scheduleAnnounceReconnect();
                }
            }
        };
        announceWs = client;
        try {
            client.connect();
        } catch (Exception e) {
            LOG.warn("[webrtc] join announce connect failed: {}", e.toString());
            if (announceWs == client) scheduleAnnounceReconnect();
        }
    }

    private void scheduleAnnounceReconnect() {
        if (!running.get() || hostArrivedLatch.getCount() == 0) return;
        long delay = announceBackoffMs;
        announceBackoffMs = Math.min(announceBackoffMs * 2, MAX_SIGNAL_BACKOFF_MS);
        try {
            signalScheduler.schedule(this::announceJoin, delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {}
    }

    private void handlePairMessage(String json) {
        if (VillasMsg.has(json, "servers")) {
            List<String[]> servers = VillasMsg.servers(json);
            if (!servers.isEmpty()) {
                serverRelays = servers;
                LOG.info("[webrtc] using {} relay(s) from signaling server", servers.size());
            }
        }
        if (VillasMsg.has(json, "control")) {
            // 호스트(peer "h…")가 페어 세션에 연결되면 진행 — "h" 다음 글자('r'/'d')로
            // 호스트의 중계 강제 여부도 같이 읽는다(WebRtcHost.PairSignal.open() 참고).
            for (String[] p : VillasMsg.peers(json)) {
                String name = p[0], remote = p[1];
                if (name != null && remote != null && name.startsWith("h")) {
                    if (name.length() > 1 && name.charAt(1) == 'r') hostRelayOnly = true;
                    hostArrivedLatch.countDown();
                }
            }
        } else if (VillasMsg.has(json, "description")) {
            String desc = VillasMsg.object(json, "description");
            if (desc == null) return;
            String sdpType = VillasMsg.field(desc, "type");
            String sdp     = VillasMsg.field(desc, "spd");
            if (!"answer".equalsIgnoreCase(sdpType) || sdp == null) return;
            if (peerConnection != null) applyAnswer(sdp);
            else pendingAnswer = sdp;
        } else if (VillasMsg.has(json, "candidate")) {
            String cand = VillasMsg.object(json, "candidate");
            if (cand == null) return;
            String spd = VillasMsg.field(cand, "spd");
            String mid = VillasMsg.field(cand, "mid");
            if (spd == null) return;
            RTCIceCandidate ic = new RTCIceCandidate(mid != null ? mid : "0", 0, spd);
            if (peerConnection != null) peerConnection.addIceCandidate(ic);
            else synchronized (pendingIce) { pendingIce.add(ic); }
        }
    }

    private void sendPair(String json) {
        if (json.length() > 4000) {
            LOG.warn("[webrtc] outgoing signaling message near server limit ({} bytes)", json.length());
        }
        WebSocketClient w = pairWs;
        if (w != null) w.send(json);
    }

    // ── MC → DataChannel ──────────────────────────────────────────────────────

    private void forwardMcToWebRtc(SocketChannel sock) {
        // direct 버퍼로 직접 read — 예전의 힙 byte[] → direct 복사 단계가 사라진다.
        // 블로킹 read는 OS 버퍼에 있는 만큼을 용량까지 한 번에 채우므로
        // 별도의 coalesce(available 기반 추가 흡수)도 필요 없다.
        ByteBuffer buf = ByteBuffer.allocateDirect(BatchPipe.BATCH_MAX);
        try {
            while (true) {
                buf.clear();
                int n = sock.read(buf);
                if (n < 0) break;   // EOF
                if (n == 0) continue;

                RTCDataChannel ch = dataChannel;
                if (ch == null || ch.getState() != RTCDataChannelState.OPEN) break;

                // 이벤트 기반 백프레셔: onBufferedAmountChange가 깨움 (50ms 안전 타임아웃)
                while (ch.getBufferedAmount() > DC_BUF_HIGH) {
                    if (!running.get() || ch.getState() != RTCDataChannelState.OPEN) break;
                    synchronized (bpLock) {
                        if (ch.getBufferedAmount() > DC_BUF_HIGH) bpLock.wait(50);
                    }
                }
                if (!running.get()) break;

                // slice()는 필수다. RTCDataChannelBuffer는 JNI로 넘어가고
                // GetDirectBufferAddress/GetDirectBufferCapacity는 position/limit을
                // 무시하고 capacity 전체를 읽는다. slice()만이 "시작 주소 = 현재
                // position, capacity = 유효 길이"인 뷰를 만들어 준다.
                // 이걸 빼면 유효 데이터 뒤에 버퍼 잔여분까지 전송돼 스트림이 깨진다.
                buf.flip();
                ch.send(new RTCDataChannelBuffer(buf.slice(), true));
            }
        } catch (Exception e) {
            if (running.get()) LOG.warn("[webrtc] MC read error: {}", e.getMessage());
        } finally {
            close();
        }
    }

    // ── DataChannel → MC ──────────────────────────────────────────────────────

    private void setupDataChannel(RTCDataChannel channel, CountDownLatch settled, AtomicBoolean succeeded) {
        channel.registerObserver(new RTCDataChannelObserver() {
            @Override
            public void onBufferedAmountChange(long previousAmount) {
                if (previousAmount > DC_BUF_LOW) {
                    RTCDataChannel ch = dataChannel;
                    if (ch != null && ch.getBufferedAmount() <= DC_BUF_LOW) {
                        synchronized (bpLock) { bpLock.notifyAll(); }
                    }
                }
            }

            @Override
            public void onStateChange() {
                RTCDataChannelState state = channel.getState();
                if (state == RTCDataChannelState.OPEN) {
                    succeeded.set(true);
                    settled.countDown();
                    readyLatch.countDown();
                    resolveConnectionType();
                } else if (state == RTCDataChannelState.CLOSED) {
                    close();
                }
            }

            @Override
            public void onMessage(RTCDataChannelBuffer buffer) {
                BatchPipe.Writer w = mcWriter;
                if (w == null) return;
                try {
                    w.feed(buffer.data); // 큐 가득 시 블로킹 → SCTP 수신 윈도우로 배압
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    close();
                }
            }
        });
    }

    // ── PeerConnection ────────────────────────────────────────────────────────

    /**
     * 한 단계(직결 전용 또는 릴레이 포함) 연결 시도를 수행하고 성공 여부를 반환한다.
     * 실패/타임아웃이면 이번 시도의 PeerConnection/DataChannel을 정리해서
     * (시그널링 웹소켓은 유지한 채로) 다음 시도가 깨끗한 상태에서 시작하게 한다.
     */
    private boolean attemptConnection(boolean allowRelay, long timeoutMs) throws InterruptedException {
        // 이전 시도에서 남은 버퍼링된 answer/candidate는 이번 시도의 SDP와 안 맞으므로 버린다.
        pendingAnswer = null;
        synchronized (pendingIce) { pendingIce.clear(); }

        CountDownLatch settled = new CountDownLatch(1);
        AtomicBoolean succeeded = new AtomicBoolean(false);
        initPeerConnection(allowRelay, settled, succeeded);
        createOffer();

        settled.await(timeoutMs, TimeUnit.MILLISECONDS);
        if (succeeded.get()) return true;
        teardownPeerConnection();
        return false;
    }

    private void initPeerConnection(boolean allowRelay, CountDownLatch settled, AtomicBoolean succeeded) {
        // DataChannel 전용 — dummy audio로 오디오 장치 초기화 생략. factory는 시도 간 재사용.
        if (factory == null) {
            audioModule = new AudioDeviceModule(AudioLayer.kDummyAudio);
            factory = new PeerConnectionFactory(audioModule);
        }

        RTCConfiguration config = new RTCConfiguration();
        // ICE 서버 구성 (relay-only 여부는 P2PConfig.isRelayOnly())
        IceConfig.apply(config, serverRelays, "client", allowRelay);

        // 디버그/특수 네트워크 환경용: any-address 포트 강제 (-Dkfcudp.ice.anyaddress=true)
        if (Boolean.getBoolean("kfcudp.ice.anyaddress")) {
            config.portAllocatorConfig.setDisableAdapterEnumeration(true);
            config.portAllocatorConfig.setEnableAnyAddressPorts(true);
        }

        peerConnection = factory.createPeerConnection(config, new PeerConnectionObserver() {
            @Override
            public void onIceCandidate(RTCIceCandidate candidate) {
                sendPair(VillasMsg.candidate(candidate.sdp,
                        candidate.sdpMid != null ? candidate.sdpMid : "0"));
            }

            @Override
            public void onIceConnectionChange(RTCIceConnectionState state) {
                if (state == RTCIceConnectionState.FAILED) {
                    LOG.warn("[webrtc] ICE failed (allowRelay={})", allowRelay);
                    // 이번 시도가 아직 확정 전이면(settled 대기 중) 실패로 확정만 시키고
                    // attemptConnection이 알아서 다음 단계로 넘어가게 한다. 이미 확정된
                    // 뒤(= 최종 성공 이후)의 FAILED만 진짜 종료 사유다.
                    if (settled.getCount() > 0) settled.countDown();
                    else close();
                } else if (state == RTCIceConnectionState.DISCONNECTED) {
                    LOG.warn("[webrtc] ICE disconnected, waiting for reconnect...");
                }
            }
        });

        RTCDataChannelInit dcInit = new RTCDataChannelInit();
        dcInit.ordered = true;
        dataChannel = peerConnection.createDataChannel("minecraft", dcInit);
        setupDataChannel(dataChannel, settled, succeeded);

        synchronized (pendingIce) {
            for (RTCIceCandidate ic : pendingIce) peerConnection.addIceCandidate(ic);
            pendingIce.clear();
        }
    }

    /** 실패/타임아웃한 시도의 PeerConnection/DataChannel만 정리한다 — 시그널링은 그대로 둔다. */
    private void teardownPeerConnection() {
        RTCDataChannel dc = dataChannel;
        dataChannel = null;
        if (dc != null) {
            try {
                dc.unregisterObserver();
                dc.close();
                dc.dispose();
            } catch (Exception ignored) {}
        }
        RTCPeerConnection pc = peerConnection;
        peerConnection = null;
        if (pc != null) {
            try { pc.close(); } catch (Exception ignored) {}
        }
    }

    private void createOffer() {
        peerConnection.createOffer(new RTCOfferOptions(), new CreateSessionDescriptionObserver() {
            @Override
            public void onSuccess(RTCSessionDescription desc) {
                peerConnection.setLocalDescription(desc, new SetSessionDescriptionObserver() {
                    @Override
                    public void onSuccess() {
                        sendPair(VillasMsg.description("offer", desc.sdp));
                        String ans = pendingAnswer;
                        if (ans != null) { pendingAnswer = null; applyAnswer(ans); }
                    }
                    @Override public void onFailure(String e) {
                        LOG.warn("[webrtc] setLocalDescription failed: {}", e);
                    }
                });
            }
            @Override public void onFailure(String e) {
                LOG.warn("[webrtc] createOffer failed: {}", e);
            }
        });
    }

    private void applyAnswer(String sdp) {
        peerConnection.setRemoteDescription(
                new RTCSessionDescription(RTCSdpType.ANSWER, sdp),
                new SetSessionDescriptionObserver() {
                    @Override public void onSuccess() {}
                    @Override public void onFailure(String e) {
                        LOG.warn("[webrtc] setRemoteDescription failed: {}", e);
                    }
                });
    }

    // ── 정리 ──────────────────────────────────────────────────────────────────

    public void close() {
        if (!running.compareAndSet(true, false)) return;
        LOG.info("[webrtc] Closing");
        // 이 세션이 여전히 WebRtcBridge가 들고 있는 "현재" 클라이언트라면 참조를 지운다
        // (WebRtcBridge.clearClientIfCurrent 클래스 주석 참고) — 안 그러면 이 방을 나간
        // 뒤에 여는 관계없는 싱글플레이 월드에서도 이 (이미 닫힌) 세션의 직결/중계 값이
        // 남아 있다가 잘못 표시된다.
        WebRtcBridge.clearClientIfCurrent(this);
        signalScheduler.shutdownNow();
        hostArrivedLatch.countDown();
        readyLatch.countDown();
        synchronized (bpLock) { bpLock.notifyAll(); } // 백프레셔 대기 해제
        BatchPipe.Writer w = mcWriter;
        mcWriter = null;
        if (w != null) w.close();
        try { if (mcChannel != null)     mcChannel.close();     } catch (Exception ignored) {}
        try { if (serverChannel != null) serverChannel.close(); } catch (Exception ignored) {}
        try { if (dataChannel != null) {
            dataChannel.unregisterObserver();
            dataChannel.close();
            dataChannel.dispose();
        }} catch (Exception ignored) {}
        try { if (peerConnection != null) peerConnection.close(); } catch (Exception ignored) {}

        // factory.dispose()는 자신이 소유한 워커/시그널링 스레드를 내부적으로 join한다.
        // 그런데 close()는 onStateChange/onIceConnectionChange 콜백(=바로 그 스레드)에서도
        // 호출될 수 있어서, 콜백 스택 안에서 곧장 dispose()를 부르면 스레드가 자기
        // 자신을 join하며 영원히 멈춘다 — "Client shutdown from post-main" watchdog
        // 크래시의 원인. 콜백 스레드가 먼저 리턴하도록 네이티브 해제는 별도 스레드로 미룬다.
        PeerConnectionFactory f = factory;
        factory = null;
        AudioDeviceModule am = audioModule;
        audioModule = null;
        if (f != null || am != null) {
            Thread cleanup = new Thread(() -> {
                try { if (f != null) f.dispose(); } catch (Exception ignored) {}
                try { if (am != null) am.dispose(); } catch (Exception ignored) {}
            }, "webrtc-client-close");
            cleanup.setDaemon(true);
            cleanup.start();
        }

        if (pairWs != null)     pairWs.close();
        if (announceWs != null) announceWs.close();
    }
}