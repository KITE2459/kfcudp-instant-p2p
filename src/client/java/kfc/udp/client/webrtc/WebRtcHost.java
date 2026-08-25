package kfc.udp.client.webrtc;

import dev.onvoid.webrtc.*;
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;
import dev.onvoid.webrtc.media.audio.AudioLayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Java 네이티브 WebRTC 호스트 — VILLASframework signaling 프로토콜.
 * <p>
 * 서버(villas-signaling)는 경로 기반 세션 릴레이만 제공하므로
 * 방(roomId) 운영은 다음 2단계 구조로 구현한다:
 * <ol>
 *   <li><b>로비 세션</b> {@code /{roomId}}: 호스트가 peer "h####"로 상주.
 *       조인자는 peer "j{sid}"로 잠깐 접속해 자신을 알린다.
 *       서버가 브로드캐스트하는 control 메시지(peer 목록)로 호스트가 조인을 감지.</li>
 *   <li><b>페어 세션</b> {@code /{roomId}-{sid}}: 조인자별 전용 1:1 세션.
 *       조인자(offer/DataChannel 생성) ↔ 호스트(answer)가 SDP/ICE를 교환.
 *       릴레이 메시지에 발신자 정보가 없으므로 반드시 2인 세션으로 격리.</li>
 * </ol>
 * WebRTC 세션 수립 후 흐름은 OpenFriend Go 구현과 동일:
 * 첫 데이터 수신 시 target TCP dial(5s) → 양방향 파이프, 16MB 백프레셔,
 * DataChannel open 10초 타임아웃, target 프로브(1s) 후 진행.
 * ICE 서버는 시그널링 서버가 relays(servers) 메시지로 내려주면 그것을,
 * 없으면 P2PConfig 기본값(coturn)을 사용한다.
 */
public class WebRtcHost {

    private static final Logger LOG = LoggerFactory.getLogger("webrtc-host");

    private static final long   INITIAL_BACKOFF_MS   = 1_000;
    private static final long   MAX_BACKOFF_MS       = 30_000;
    private static final int    PROBE_TIMEOUT_MS     = 1_000;
    private static final int    HANDSHAKE_TIMEOUT_MS = 10_000; // DataChannel open 한도
    private static final int    OFFER_TIMEOUT_MS     = 20_000; // 페어 세션에서 OFFER 대기 한도
    private static final int    DIAL_TIMEOUT_MS      = 5_000;
    private static final int    BUFFER_SIZE          = 65536;
    // 버퍼 한도는 P2PConfig에서 관리 — 지연/처리량 트레이드오프 근거와
    // -Dkfcudp.pipe.* 되돌리기 방법은 그쪽 주석 참고.
    private static final long   DC_BUF_HIGH          = P2PConfig.DC_BUF_HIGH;
    private static final long   DC_BUF_LOW           = P2PConfig.DC_BUF_LOW; // 이하로 빠지면 송신 재개

    // ── 인스턴스 필드 ─────────────────────────────────────────────────────────
    private final String roomId;
    private final String targetHost;
    private final int    targetPort;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile AudioDeviceModule audioModule;
    private volatile PeerConnectionFactory factory;
    private volatile WebSocketClient lobbyWs;

    /** sid → 진행 중인 페어 시그널링 */
    private final Map<String, PairSignal> pairs = new ConcurrentHashMap<>();
    /** 처리한 조인 알림 (로비 peer name → 시각) */
    private final Map<String, Long> handledJoins = new ConcurrentHashMap<>();
    /** 서버가 내려준 TURN/STUN relays (없으면 P2PConfig 기본값 사용) */
    private volatile List<String[]> serverRelays = List.of();

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "webrtc-host-timer");
                t.setDaemon(true);
                return t;
            });
    private final ExecutorService worker =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "webrtc-host-worker");
                t.setDaemon(true);
                return t;
            });

    private volatile long backoffMs = INITIAL_BACKOFF_MS;

    public WebRtcHost(String roomId, String target) {
        this.roomId = roomId;
        int colon = target.lastIndexOf(':');
        if (colon < 0) throw new IllegalArgumentException("invalid target: " + target);
        this.targetHost = target.substring(0, colon);
        this.targetPort = Integer.parseInt(target.substring(colon + 1));
    }

    // ── 라이프사이클 ──────────────────────────────────────────────────────────

    /** 로비 접속 시작. 접속/재접속은 백그라운드에서 진행되며 즉시 반환. */
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        // DataChannel 전용 — dummy audio로 오디오 장치 초기화 생략 (장치 없는 환경 크래시 방지)
        audioModule = new AudioDeviceModule(AudioLayer.kDummyAudio);
        factory = new PeerConnectionFactory(audioModule);
        LOG.info("[host] WebRTC Host: room={} target={}:{}", roomId, targetHost, targetPort);
        worker.execute(this::connectLobby);
    }

    public void close() {
        if (!running.compareAndSet(true, false)) return;
        LOG.info("[host] Closing");
        for (PairSignal p : pairs.values()) p.close();
        pairs.clear();
        handledJoins.clear();
        WebSocketClient ws = lobbyWs;
        if (ws != null) ws.close();
        scheduler.shutdownNow();
        worker.shutdownNow();
        PeerConnectionFactory f = factory;
        factory = null;
        if (f != null) {
            try { f.dispose(); } catch (Exception ignored) {}
        }
        AudioDeviceModule adm = audioModule;
        audioModule = null;
        if (adm != null) {
            try { adm.dispose(); } catch (Exception ignored) {}
        }
    }

    // ── 로비 세션 (조인 감지) ─────────────────────────────────────────────────

    private void connectLobby() {
        if (!running.get()) return;
        // peer 이름은 접속마다 유니크하게 — 서버는 같은 이름의 재접속을
        // "peer is already connected"로 거부하므로 (연결 유실 직후 재접속 대비)
        String peerName = "h" + Integer.toHexString(
                java.util.concurrent.ThreadLocalRandom.current().nextInt(0x10000, 0x100000));
        WebSocketClient ws = new WebSocketClient(
                P2PConfig.SIGNALING_URL + "/" + roomId + "/" + peerName) {
            @Override public void onConnected() {
                backoffMs = INITIAL_BACKOFF_MS;
                send(VillasMsg.hello()); // 서버가 최초 1회 signals 메시지를 요구함
                LOG.info("[host] lobby joined: room={}", roomId);
            }
            @Override public void onMessage(String type, String json) {
                handleLobby(json);
            }
            @Override public void onDisconnected() {
                scheduleReconnect();
            }
        };
        lobbyWs = ws;
        try {
            ws.connect();
        } catch (Exception e) {
            LOG.warn("[host] Signaling connect failed: {}", e.toString());
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!running.get()) return;
        long delay = backoffMs;
        backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
        LOG.info("[host] Signaling reconnect in {}ms", delay);
        try {
            scheduler.schedule(this::connectLobby, delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {}
    }

    private void handleLobby(String json) {
        if (VillasMsg.has(json, "servers")) {
            updateRelays(json);
        }
        if (!VillasMsg.has(json, "control")) return;

        long now = System.currentTimeMillis();
        handledJoins.values().removeIf(t -> now - t > 600_000);

        for (String[] p : VillasMsg.peers(json)) {
            String name = p[0], remote = p[1];
            // 조인 알림: peer 이름 "j" + 16 hex, 현재 연결 중(remote 존재)
            if (name == null || remote == null) continue;
            if (name.length() != 17 || !name.startsWith("j")) continue;
            if (handledJoins.putIfAbsent(name, now) != null) continue;

            String sid = name.substring(1);
            String clientIp = remote.contains(":") ? remote.substring(0, remote.lastIndexOf(':')) : remote;
            LOG.info("[host] join detected: sid={} ip={}", sid, clientIp);

            worker.execute(() -> {
                // Go와 동일: target 프로브 후 진행 (실패 시 조인자는 타임아웃)
                if (!probeTarget()) {
                    LOG.warn("[host] target unreachable; ignoring join sid={}", sid);
                    return;
                }
                PairSignal pair = new PairSignal(sid, clientIp);
                PairSignal prev = pairs.put(sid, pair);
                if (prev != null) prev.close();
                pair.open();
            });
        }
    }

    private void updateRelays(String json) {
        List<String[]> servers = VillasMsg.servers(json);
        if (!servers.isEmpty()) {
            serverRelays = servers;
            LOG.info("[host] using {} relay(s) from signaling server", servers.size());
        }
    }

    private boolean probeTarget() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(targetHost, targetPort), PROBE_TIMEOUT_MS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * target으로 {@link SocketChannel}을 연결한다 (타임아웃 {@link #DIAL_TIMEOUT_MS}).
     * <p>
     * {@code SocketChannel}은 블로킹 connect에 타임아웃을 걸 수 없어
     * (예전 {@code Socket.connect(addr, timeout)}에 해당하는 게 없다) 논블로킹으로
     * 연결한 뒤 Selector로 기다린다. 채널이어야 direct 버퍼 read/writev를 쓸 수 있다.
     */
    private SocketChannel dialTarget() throws IOException {
        SocketChannel sc = SocketChannel.open();
        try {
            sc.configureBlocking(false);
            // target이 127.0.0.1이라 대개 여기서 즉시 true가 나와 셀렉터를 안 탄다.
            if (!sc.connect(new InetSocketAddress(targetHost, targetPort))) {
                try (Selector sel = Selector.open()) {
                    SelectionKey key = sc.register(sel, SelectionKey.OP_CONNECT);
                    try {
                        long deadline = System.nanoTime() + DIAL_TIMEOUT_MS * 1_000_000L;
                        while (!sc.finishConnect()) {
                            long remainMs = (deadline - System.nanoTime()) / 1_000_000L;
                            if (remainMs <= 0)
                                throw new SocketTimeoutException(
                                        "dial timeout " + DIAL_TIMEOUT_MS + "ms");
                            sel.select(remainMs);
                            sel.selectedKeys().clear();
                        }
                    } finally {
                        // configureBlocking(true)는 채널에 유효한 키가 남아 있으면
                        // IllegalBlockingModeException을 던진다. 셀렉터를 닫아도
                        // 무효화되지만, 명시적으로 취소해 순서 의존을 없앤다.
                        key.cancel();
                    }
                }
            }
            sc.configureBlocking(true);
            return sc;
        } catch (IOException e) {
            try { sc.close(); } catch (IOException ignored) {}
            throw e;
        }
    }

    /** ICE 서버 구성: 시그널링 서버 relays 우선, 없으면 P2PConfig 기본값 */
    private RTCConfiguration buildConfig() {
        RTCConfiguration config = new RTCConfiguration();
        // ICE 서버 구성 (relay-only 여부는 P2PConfig.RELAY_ONLY)
        IceConfig.apply(config, serverRelays, "host");
        // 디버그/특수 네트워크 환경용: any-address 포트 강제 (-Dkfcudp.ice.anyaddress=true)
        if (Boolean.getBoolean("kfcudp.ice.anyaddress")) {
            config.portAllocatorConfig.setDisableAdapterEnumeration(true);
            config.portAllocatorConfig.setEnableAnyAddressPorts(true);
        }
        return config;
    }

    // ── 페어 세션 (조인자별 1:1 시그널링) ────────────────────────────────────

    private class PairSignal {
        final String sid;
        final String clientIp;
        volatile WebSocketClient ws;
        volatile HostSession session;
        volatile boolean closed;

        PairSignal(String sid, String clientIp) {
            this.sid = sid;
            this.clientIp = clientIp;
        }

        void open() {
            if (!running.get()) return;
            WebSocketClient w = new WebSocketClient(
                    P2PConfig.SIGNALING_URL + "/" + roomId + "-" + sid + "/h" + sid) {
                @Override public void onConnected() {
                    send(VillasMsg.hello());
                    LOG.info("[host] pair session joined: sid={}", sid);
                }
                @Override public void onMessage(String type, String json) {
                    handlePair(json);
                }
                @Override public void onDisconnected() {
                    HostSession s = session;
                    if (s == null || !s.dcOpened) {
                        LOG.warn("[host] pair signaling lost before establishment sid={}", sid);
                        PairSignal.this.close();
                    }
                }
            };
            ws = w;
            try {
                w.connect();
            } catch (Exception e) {
                LOG.warn("[host] pair connect failed sid={}: {}", sid, e.toString());
                close();
                return;
            }
            // OFFER 대기 타임아웃
            try {
                scheduler.schedule(() -> {
                    if (!closed && session == null) {
                        LOG.warn("[host] no OFFER within {}ms; closing pair sid={}", OFFER_TIMEOUT_MS, sid);
                        close();
                    }
                }, OFFER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException ignored) {}
        }

        void handlePair(String json) {
            if (VillasMsg.has(json, "servers")) {
                updateRelays(json);
            }
            if (VillasMsg.has(json, "description")) {
                String desc = VillasMsg.object(json, "description");
                if (desc == null) return;
                String sdpType = VillasMsg.field(desc, "type");
                String sdp     = VillasMsg.field(desc, "spd");
                if (!"offer".equalsIgnoreCase(sdpType) || sdp == null || sdp.isEmpty()) return;
                if (session != null) return; // 중복 OFFER 무시
                LOG.info("[host] OFFER received sid={}", sid);
                final PairSignal self = this;
                worker.execute(() -> startSession(self, sdp));
            } else if (VillasMsg.has(json, "candidate")) {
                String cand = VillasMsg.object(json, "candidate");
                if (cand == null) return;
                String spd = VillasMsg.field(cand, "spd");
                String mid = VillasMsg.field(cand, "mid");
                if (spd == null) return;
                HostSession s = session;
                if (s != null) {
                    s.addRemoteIce(new RTCIceCandidate(mid != null ? mid : "0", 0, spd));
                }
            }
        }

        void send(String json) {
            if (json.length() > 4000) {
                LOG.warn("[host] outgoing signaling message near server limit ({} bytes)", json.length());
            }
            WebSocketClient w = ws;
            if (w != null) w.send(json);
        }

        void close() {
            if (closed) return;
            closed = true;
            HostSession s = session;
            session = null;
            if (s != null) s.close();
            WebSocketClient w = ws;
            ws = null;
            if (w != null) w.close();
            pairs.remove(sid, this);
        }
    }

    // ── WebRTC 세션 (기존 OpenFriend Go bridge.hostSession과 동일 흐름) ───────

    private void startSession(PairSignal pair, String offerSdp) {
        if (!running.get() || pair.closed) return;
        HostSession session = new HostSession(pair);
        pair.session = session;
        try {
            session.begin(offerSdp);
        } catch (Exception e) {
            LOG.warn("[host] New WebRTC session failed: {}", e.toString());
            pair.close();
        }
    }

    private class HostSession {
        private final PairSignal pair;
        private final String sid;
        private final String clientIp;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private volatile RTCPeerConnection peerConnection;
        private volatile RTCDataChannel    dataChannel;
        private volatile SocketChannel     tcpChannel;
        private volatile BatchPipe.Writer  tcpWriter;
        private volatile int               tunnelLocalPort = -1;
        volatile boolean dcOpened = false;

        private final CountDownLatch dcOpenLatch = new CountDownLatch(1);
        private final Object dialLock = new Object();
        /** 백프레셔 대기/웨이크업 (onBufferedAmountChange 이벤트 기반) */
        private final Object bpLock = new Object();

        private final List<RTCIceCandidate> queuedIce = new ArrayList<>();
        private volatile boolean remoteSet = false;

        HostSession(PairSignal pair) {
            this.pair = pair;
            this.sid = pair.sid;
            this.clientIp = pair.clientIp;
        }

        void begin(String offerSdp) {
            peerConnection = factory.createPeerConnection(buildConfig(), new PeerConnectionObserver() {
                @Override
                public void onIceCandidate(RTCIceCandidate candidate) {
                    pair.send(VillasMsg.candidate(candidate.sdp,
                            candidate.sdpMid != null ? candidate.sdpMid : "0"));
                }

                @Override
                public void onIceConnectionChange(RTCIceConnectionState state) {
                    // Go와 동일: FAILED에서만 종료, DISCONNECTED는 자동 복구 대기
                    if (state == RTCIceConnectionState.FAILED) {
                        LOG.warn("[host] ICE failed sid={}", sid);
                        pair.close();
                    } else if (state == RTCIceConnectionState.DISCONNECTED) {
                        LOG.warn("[host] ICE disconnected sid={}, waiting for reconnect...", sid);
                    }
                }

                @Override
                public void onDataChannel(RTCDataChannel channel) {
                    LOG.info("[host] DataChannel attached sid={} label={}", sid, channel.getLabel());
                    dataChannel = channel;
                    setupDataChannel(channel);
                }
            });

            peerConnection.setRemoteDescription(
                    new RTCSessionDescription(RTCSdpType.OFFER, offerSdp),
                    new SetSessionDescriptionObserver() {
                        @Override public void onSuccess() {
                            flushQueuedIce();
                            createAnswer();
                        }
                        @Override public void onFailure(String error) {
                            LOG.warn("[host] setRemoteDescription failed sid={}: {}", sid, error);
                            pair.close();
                        }
                    });

            try {
                scheduler.schedule(() -> {
                    if (!closed.get() && dcOpenLatch.getCount() > 0) {
                        LOG.warn("[host] handshake timeout sid={}", sid);
                        pair.close();
                    }
                }, HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException ignored) {}
        }

        private void createAnswer() {
            RTCPeerConnection pc = peerConnection;
            if (pc == null) return;
            pc.createAnswer(new RTCAnswerOptions(), new CreateSessionDescriptionObserver() {
                @Override
                public void onSuccess(RTCSessionDescription desc) {
                    pc.setLocalDescription(desc, new SetSessionDescriptionObserver() {
                        @Override public void onSuccess() {
                            pair.send(VillasMsg.description("answer", desc.sdp));
                            LOG.info("[host] ANSWER sent sid={}", sid);
                        }
                        @Override public void onFailure(String error) {
                            LOG.warn("[host] setLocalDescription failed sid={}: {}", sid, error);
                            pair.close();
                        }
                    });
                }
                @Override public void onFailure(String error) {
                    LOG.warn("[host] createAnswer failed sid={}: {}", sid, error);
                    pair.close();
                }
            });
        }

        void addRemoteIce(RTCIceCandidate candidate) {
            synchronized (queuedIce) {
                if (!remoteSet) {
                    queuedIce.add(candidate);
                    return;
                }
            }
            RTCPeerConnection pc = peerConnection;
            if (pc != null) pc.addIceCandidate(candidate);
        }

        private void flushQueuedIce() {
            List<RTCIceCandidate> toApply;
            synchronized (queuedIce) {
                remoteSet = true;
                toApply = new ArrayList<>(queuedIce);
                queuedIce.clear();
            }
            RTCPeerConnection pc = peerConnection;
            if (pc == null) return;
            for (RTCIceCandidate c : toApply) pc.addIceCandidate(c);
        }

        // ── 데이터 파이프 (Go hostSession.onPeerData / TCPBridge와 동일) ─────

        private void setupDataChannel(RTCDataChannel channel) {
            channel.registerObserver(new RTCDataChannelObserver() {
                @Override
                public void onBufferedAmountChange(long previousAmount) {
                    // 하강 에지에서만 웨이크업 (콜백은 모든 변화마다 오므로 조건 최소화)
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
                        dcOpened = true;
                        dcOpenLatch.countDown();
                        LOG.info("[host] DataChannel open; waiting for first data sid={} clientIp={}",
                                sid, clientIp);
                    } else if (state == RTCDataChannelState.CLOSED) {
                        LOG.info("[host] DataChannel closed sid={}", sid);
                        pair.close();
                    }
                }

                @Override
                public void onMessage(RTCDataChannelBuffer buffer) {
                    onPeerData(buffer.data);
                }
            });
            if (channel.getState() == RTCDataChannelState.OPEN) {
                dcOpened = true;
                dcOpenLatch.countDown();
            }
        }

        private void onPeerData(java.nio.ByteBuffer data) {
            if (closed.get()) return;
            BatchPipe.Writer w = tcpWriter;
            if (w == null) {
                synchronized (dialLock) {
                    if (closed.get()) return;
                    w = tcpWriter;
                    if (w == null) {
                        LOG.info("[host] first data received; dialing target {}:{} clientIp={}",
                                targetHost, targetPort, clientIp);
                        try {
                            SocketChannel sock = dialTarget();
                            sock.setOption(StandardSocketOptions.TCP_NODELAY, true);
                            sock.setOption(StandardSocketOptions.SO_RCVBUF, 512 * 1024);
                            sock.setOption(StandardSocketOptions.SO_SNDBUF, 512 * 1024);
                            tcpChannel = sock;
                            // MC 서버는 이 소켓의 로컬 포트를 조인자의 "IP:포트"로 본다.
                            // (전부 127.0.0.1 이므로) 실제 원격 IP를 포트에 매핑해 둔다.
                            tunnelLocalPort =
                                    ((InetSocketAddress) sock.getLocalAddress()).getPort();
                            P2PBanManager.registerTunnelPort(tunnelLocalPort, clientIp);
                            // DC→TCP: 전담 writer 스레드가 연속 청크를 writev 1회로 배칭
                            w = new BatchPipe.Writer(sock,
                                    "webrtc-host-tcpw-" + sid,
                                    e -> {
                                        if (!closed.get())
                                            LOG.warn("[host] TCP write failed sid={}: {}", sid, e.getMessage());
                                        pair.close();
                                    });
                            tcpWriter = w;
                            Thread t = new Thread(() -> forwardTcpToWebRtc(sock),
                                    "webrtc-host-tcp-" + sid);
                            t.setDaemon(true);
                            t.setPriority(Thread.NORM_PRIORITY + 2); // 파이프 지연 최소화
                            t.start();
                        } catch (Exception e) {
                            LOG.warn("[host] Failed to dial target {}:{}: {}",
                                    targetHost, targetPort, e.getMessage());
                            pair.close();
                            return;
                        }
                    }
                }
            }
            try {
                w.feed(data); // 큐 가득 시 블로킹 → SCTP 수신 윈도우로 배압
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pair.close();
            }
        }

        /** MC 서버 TCP → DataChannel. direct 버퍼 직접 read + 백프레셔. */
        private void forwardTcpToWebRtc(SocketChannel sock) {
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
                        if (closed.get() || ch.getState() != RTCDataChannelState.OPEN) return;
                        synchronized (bpLock) {
                            if (ch.getBufferedAmount() > DC_BUF_HIGH) bpLock.wait(50);
                        }
                    }
                    if (closed.get()) break;

                    // slice()는 필수다. RTCDataChannelBuffer는 JNI로 넘어가고
                    // GetDirectBufferAddress/GetDirectBufferCapacity는 position/limit을
                    // 무시하고 capacity 전체를 읽는다. slice()만이 "시작 주소 = 현재
                    // position, capacity = 유효 길이"인 뷰를 만들어 준다.
                    // 이걸 빼면 유효 데이터 뒤에 버퍼 잔여분까지 전송돼 스트림이 깨진다.
                    buf.flip();
                    ch.send(new RTCDataChannelBuffer(buf.slice(), true));
                }
            } catch (Exception e) {
                if (!closed.get()) LOG.warn("[host] TCP read ended sid={}: {}", sid, e.getMessage());
            } finally {
                pair.close();
            }
        }

        void close() {
            if (!closed.compareAndSet(false, true)) return;
            dcOpenLatch.countDown();
            synchronized (bpLock) { bpLock.notifyAll(); } // 백프레셔 대기 해제
            BatchPipe.Writer w = tcpWriter;
            tcpWriter = null;
            if (w != null) w.close();
            if (tunnelLocalPort > 0) {
                P2PBanManager.unregisterTunnelPort(tunnelLocalPort);
                tunnelLocalPort = -1;
            }
            try { if (tcpChannel != null) tcpChannel.close(); } catch (Exception ignored) {}
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
    }
}