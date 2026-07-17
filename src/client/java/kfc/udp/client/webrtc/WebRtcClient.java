package kfc.udp.client.webrtc;

import dev.onvoid.webrtc.*;
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;
import dev.onvoid.webrtc.media.audio.AudioLayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
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

    private static final int  BUFFER_SIZE  = 65536;
    private static final long DC_BUF_HIGH  = 16 * 1024 * 1024L;

    // 송신용 ThreadLocal direct ByteBuffer — forwardMcToWebRtc 스레드 전용
    private static final ThreadLocal<ByteBuffer> SEND_BUF =
            ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(BatchPipe.BATCH_MAX));

    // ── 인스턴스 필드 ─────────────────────────────────────────────────────────

    private final String roomId;
    private final int    localPort;
    private final String sessionId;

    private AudioDeviceModule       audioModule;
    private PeerConnectionFactory   factory;
    private RTCPeerConnection       peerConnection;
    private volatile RTCDataChannel dataChannel;
    private ServerSocket            serverSocket;
    private volatile Socket         mcSocket;
    private volatile OutputStream   mcOut;
    private volatile BatchPipe.Writer mcWriter; // DC→MC 배칭 writer

    private final AtomicBoolean  running         = new AtomicBoolean(false);
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

        serverSocket = new ServerSocket(localPort);
        serverSocket.setSoTimeout(120000);
        LOG.info("[webrtc] Ready on port {}", localPort);

        Thread t = new Thread(this::acceptAndBridge, "webrtc-accept");
        t.setDaemon(true);
        t.start();
    }

    private void acceptAndBridge() {
        try {
            Socket sock = serverSocket.accept();
            sock.setTcpNoDelay(true);
            sock.setReceiveBufferSize(512 * 1024);
            sock.setSendBufferSize(512 * 1024);
            mcSocket = sock;
            mcOut    = sock.getOutputStream();
            // DC→MC: 전담 writer 스레드가 연속 청크를 단일 write로 배칭
            mcWriter = new BatchPipe.Writer(mcOut, "webrtc-mc-writer", e -> {
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

        } catch (SocketTimeoutException e) {
            LOG.warn("[webrtc] MC client did not connect in time");
            close();
        } catch (Exception e) {
            if (running.get()) LOG.warn("[webrtc] bridge error: {}", e.getMessage());
            close();
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

    private void forwardMcToWebRtc(Socket sock) {
        byte[] readBuf = new byte[BatchPipe.BATCH_MAX];
        try (InputStream in = sock.getInputStream()) {
            int n;
            while ((n = in.read(readBuf, 0, readBuf.length)) != -1) {
                RTCDataChannel ch = dataChannel;
                if (ch == null || ch.getState() != RTCDataChannelState.OPEN) break;

                while (ch.getBufferedAmount() > DC_BUF_HIGH) {
                    if (!running.get() || ch.getState() != RTCDataChannelState.OPEN) break;
                    Thread.sleep(5);
                }
                if (!running.get()) break;

                // OS 버퍼에 쌓인 후속 데이터를 논블로킹으로 흡수 → send 횟수 감소
                n = BatchPipe.coalesce(in, readBuf, n);

                ByteBuffer base = SEND_BUF.get();
                base.clear();
                base.put(readBuf, 0, n);
                base.flip();
                ByteBuffer slice = base.slice().limit(n);
                ch.send(new RTCDataChannelBuffer(slice, true));
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
            @Override public void onBufferedAmountChange(long p) {}

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
        List<String[]> relays = serverRelays;
        if (relays.isEmpty()) {
            RTCIceServer stun = new RTCIceServer();
            stun.urls.add(P2PConfig.STUN_URL);
            config.iceServers.add(stun);
            RTCIceServer turn = new RTCIceServer();
            turn.urls.add(P2PConfig.TURN_URL);
            turn.username = P2PConfig.TURN_USERNAME;
            turn.password = P2PConfig.TURN_CREDENTIAL;
            config.iceServers.add(turn);
        } else {
            for (String[] r : relays) {
                RTCIceServer s = new RTCIceServer();
                s.urls.add(r[0]);
                if (r[1] != null) s.username = r[1];
                if (r[2] != null) s.password = r[2];
                config.iceServers.add(s);
            }
        }

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
        mcOut = null;
        BatchPipe.Writer w = mcWriter;
        mcWriter = null;
        if (w != null) w.close();
        try { if (mcSocket != null)      mcSocket.close();     } catch (Exception ignored) {}
        try { if (serverSocket != null)  serverSocket.close(); } catch (Exception ignored) {}
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