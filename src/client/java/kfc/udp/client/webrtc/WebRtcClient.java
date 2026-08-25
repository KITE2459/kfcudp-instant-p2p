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

    private static final Logger LOG = LoggerFactory.getLogger("webrtc-native");

    // 버퍼 한도는 P2PConfig에서 관리 — 지연/처리량 트레이드오프 근거와
    // -Dkfcudp.pipe.* 되돌리기 방법은 그쪽 주석 참고.
    private static final long DC_BUF_HIGH  = P2PConfig.DC_BUF_HIGH;
    private static final long DC_BUF_LOW   = P2PConfig.DC_BUF_LOW; // 이하로 빠지면 송신 재개

    /** MC 클라이언트 접속 대기 한도 */
    private static final int ACCEPT_TIMEOUT_MS = 120_000;

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
                close(); return;
            }

            initPeerConnection();
            createOffer();

            if (!readyLatch.await(30, TimeUnit.SECONDS)) {
                LOG.warn("[webrtc] DataChannel open timed out");
                close(); return;
            }

            // 연결 완료 — 조인 알림용 로비 접속은 정리
            WebSocketClient a = announceWs;
            announceWs = null;
            if (a != null) a.close();

            forwardMcToWebRtc(sock);

        } catch (Exception e) {
            if (running.get()) LOG.warn("[webrtc] bridge error: {}", e.getMessage());
            close();
        }
    }

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

    private void connectPairSignaling() throws Exception {
        pairWs = new WebSocketClient(
                P2PConfig.SIGNALING_URL + "/" + roomId + "-" + sessionId + "/p" + sessionId) {
            @Override public void onConnected() {
                send(VillasMsg.hello()); // 서버가 최초 1회 signals 메시지를 요구함
            }
            @Override public void onMessage(String type, String json) {
                handlePairMessage(json);
            }
            @Override public void onDisconnected() {
                // 연결 수립 전에 시그널링이 끊기면 실패 처리 (수립 후에는 P2P 독립)
                if (readyLatch.getCount() > 0) {
                    LOG.warn("[webrtc] pair signaling lost");
                    close();
                }
            }
        };
        pairWs.connect();
    }

    private void announceJoin() throws Exception {
        announceWs = new WebSocketClient(
                P2PConfig.SIGNALING_URL + "/" + roomId + "/j" + sessionId) {
            @Override public void onConnected() {
                send(VillasMsg.hello());
                LOG.info("[webrtc] join announced: room={} sid={}", roomId, sessionId);
            }
            @Override public void onMessage(String type, String json) { /* 로비 메시지 무시 */ }
        };
        announceWs.connect();
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
            // 호스트(peer "h…")가 페어 세션에 연결되면 진행
            for (String[] p : VillasMsg.peers(json)) {
                String name = p[0], remote = p[1];
                if (name != null && remote != null && name.startsWith("h")) {
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

    private void setupDataChannel(RTCDataChannel channel) {
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
                if (state == RTCDataChannelState.OPEN)        readyLatch.countDown();
                else if (state == RTCDataChannelState.CLOSED) close();
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

    private void initPeerConnection() {
        // DataChannel 전용 — dummy audio로 오디오 장치 초기화 생략
        audioModule = new AudioDeviceModule(AudioLayer.kDummyAudio);
        factory = new PeerConnectionFactory(audioModule);

        RTCConfiguration config = new RTCConfiguration();
        // ICE 서버 구성 (relay-only 여부는 P2PConfig.RELAY_ONLY)
        IceConfig.apply(config, serverRelays, "client");

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
                if (state == RTCIceConnectionState.FAILED ||
                        state == RTCIceConnectionState.DISCONNECTED) close();
            }
        });

        RTCDataChannelInit dcInit = new RTCDataChannelInit();
        dcInit.ordered = true;
        dataChannel = peerConnection.createDataChannel("minecraft", dcInit);
        setupDataChannel(dataChannel);

        synchronized (pendingIce) {
            for (RTCIceCandidate ic : pendingIce) peerConnection.addIceCandidate(ic);
            pendingIce.clear();
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
        try { if (factory != null)        factory.dispose();       } catch (Exception ignored) {}
        try { if (audioModule != null)    audioModule.dispose();   } catch (Exception ignored) {}
        if (pairWs != null)     pairWs.close();
        if (announceWs != null) announceWs.close();
    }
}