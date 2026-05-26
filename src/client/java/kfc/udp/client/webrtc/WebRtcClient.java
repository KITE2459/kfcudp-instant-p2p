package kfc.udp.client.webrtc;

import dev.onvoid.webrtc.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Java 네이티브 WebRTC 클라이언트.
 * 지연 최적화:
 *   - onMessage → 직접 MC 소켓 write (스레드 핸드오프/큐 지연 제거)
 *   - 수신 버퍼는 BufSlot 풀로 GC 없이 재사용
 *   - send 경로: readBuf 재사용, 정확한 크기 배열만 새로 할당
 */
public class WebRtcClient {

    private static final Logger LOG = LoggerFactory.getLogger("webrtc-native");

    private static final int  BUFFER_SIZE  = 65536;
    private static final int  POOL_SIZE    = 256;
    private static final long DC_BUF_HIGH  = 16 * 1024 * 1024L;

    // 송신용 ThreadLocal direct ByteBuffer — forwardMcToWebRtc 스레드 전용
    // sendDirectBuffer는 CopyOnWriteBuffer로 즉시 복사(동기) → 재사용 안전
    // GetDirectBufferCapacity를 쓰므로 slice()로 정확한 크기 뷰를 넘겨야 함
    private static final ThreadLocal<ByteBuffer> SEND_BUF =
            ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(BUFFER_SIZE));

    // ── 수신 버퍼 풀 (onMessage → MC 소켓 write 경로) ────────────────────────
    static final class BufSlot {
        final byte[] data = new byte[BUFFER_SIZE];
    }

    private static final ArrayBlockingQueue<BufSlot> POOL;
    static {
        POOL = new ArrayBlockingQueue<>(POOL_SIZE);
        for (int i = 0; i < POOL_SIZE; i++) POOL.offer(new BufSlot());
    }

    private static BufSlot acquireSlot() {
        BufSlot s = POOL.poll();
        return s != null ? s : new BufSlot();
    }

    private static void releaseSlot(BufSlot s) {
        POOL.offer(s);
    }

    // ── 인스턴스 필드 ─────────────────────────────────────────────────────────

    private final String roomId;
    private final int    localPort;
    private final String sessionId;

    private PeerConnectionFactory   factory;
    private RTCPeerConnection       peerConnection;
    private volatile RTCDataChannel dataChannel;
    private ServerSocket            serverSocket;
    private volatile Socket         mcSocket;
    private volatile OutputStream   mcOut;   // onMessage에서 직접 사용

    private final AtomicBoolean  running       = new AtomicBoolean(false);
    private final CountDownLatch acceptedLatch  = new CountDownLatch(1);
    private final CountDownLatch readyLatch     = new CountDownLatch(1);

    private volatile String             pendingAnswer = null;
    private final List<RTCIceCandidate> pendingIce    = new ArrayList<>();

    private WebSocketClient signalingWs;

    public WebRtcClient(String roomId, int localPort) {
        this.roomId    = roomId;
        this.localPort = localPort;
        this.sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    public void start() throws Exception {
        running.set(true);
        connectSignaling();

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
            sock.setReceiveBufferSize(BUFFER_SIZE * 4);
            sock.setSendBufferSize(BUFFER_SIZE * 4);
            mcSocket = sock;
            mcOut    = sock.getOutputStream();

            signalingWs.send("{\"type\":\"JOIN\",\"roomId\":\"" + roomId +
                    "\",\"sessionId\":\"" + sessionId + "\"}");

            if (!acceptedLatch.await(15, TimeUnit.SECONDS)) {
                LOG.warn("[webrtc] JOIN_ACCEPTED timeout");
                close(); return;
            }

            initPeerConnection();
            createOffer();

            if (!readyLatch.await(30, TimeUnit.SECONDS)) {
                LOG.warn("[webrtc] DataChannel open timed out");
                close(); return;
            }

            forwardMcToWebRtc(sock);

        } catch (SocketTimeoutException e) {
            LOG.warn("[webrtc] MC client did not connect in time");
            close();
        } catch (Exception e) {
            if (running.get()) LOG.warn("[webrtc] bridge error: {}", e.getMessage());
            close();
        }
    }

    // ── MC → DataChannel ──────────────────────────────────────────────────────

    private void forwardMcToWebRtc(Socket sock) {
        // readBuf 재사용 — 루프마다 힙 할당 없음
        byte[] readBuf = new byte[BUFFER_SIZE];
        try (InputStream in = sock.getInputStream()) {
            int n;
            while ((n = in.read(readBuf)) != -1) {
                RTCDataChannel ch = dataChannel;
                if (ch == null || ch.getState() != RTCDataChannelState.OPEN) break;

                // 백프레셔
                while (ch.getBufferedAmount() > DC_BUF_HIGH) {
                    if (!running.get() || ch.getState() != RTCDataChannelState.OPEN) break;
                    Thread.sleep(5);
                }
                if (!running.get()) break;

                // ThreadLocal direct 버퍼 재사용 (동기 확인됨)
                // GetDirectBufferCapacity가 capacity를 쓰므로
                // slice()로 정확한 크기 뷰를 넘겨야 함
                ByteBuffer base = SEND_BUF.get();
                base.clear();
                base.put(readBuf, 0, n);
                base.flip();
                // slice(): position=0, limit=capacity=n 인 독립 뷰
                ByteBuffer slice = base.slice().limit(n);
                ch.send(new RTCDataChannelBuffer(slice, true));
            }
        } catch (Exception e) {
            if (running.get()) LOG.warn("[webrtc] MC read error: {}", e.getMessage());
        } finally {
            close();
        }
    }

    // ── DataChannel → MC (onMessage에서 직접 write — 큐/스레드 핸드오프 없음) ──

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
                int n = buffer.data.remaining();
                BufSlot slot = acquireSlot();
                buffer.data.get(slot.data, 0, n);
                OutputStream out = mcOut;
                if (out != null) {
                    try {
                        // TCP_NODELAY 설정으로 flush 없이도 즉시 전송
                        out.write(slot.data, 0, n);
                    } catch (Exception e) {
                        if (running.get()) LOG.warn("[webrtc] MC write failed: {}", e.getMessage());
                        close();
                    }
                }
                releaseSlot(slot);
            }
        });
    }

    // ── PeerConnection / Signaling ────────────────────────────────────────────

    private void initPeerConnection() {
        factory = new PeerConnectionFactory();

        RTCConfiguration config = new RTCConfiguration();
        RTCIceServer stun = new RTCIceServer();
        stun.urls.add("stun:193.122.114.163:3478");
        config.iceServers.add(stun);

        peerConnection = factory.createPeerConnection(config, new PeerConnectionObserver() {
            @Override
            public void onIceCandidate(RTCIceCandidate candidate) {
                signalingWs.send("{\"type\":\"ICE_CANDIDATE\",\"sessionId\":\"" + sessionId + "\"," +
                        "\"iceCandidate\":{" +
                        "\"candidate\":\"" + escapeJson(candidate.sdp) + "\"," +
                        "\"sdpMid\":\"" + (candidate.sdpMid != null ? candidate.sdpMid : "0") + "\"," +
                        "\"sdpMLineIndex\":" + candidate.sdpMLineIndex + "}}");
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
                        signalingWs.send("{\"type\":\"OFFER\",\"sessionId\":\"" + sessionId + "\"," +
                                "\"sdp\":\"" + escapeJson(desc.sdp) + "\"}");
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

    private void connectSignaling() throws Exception {
        signalingWs = new WebSocketClient("ws://193.122.114.163:8765") {
            @Override public void onConnected() {}
            @Override public void onMessage(String type, String json) {
                switch (type) {
                    case "JOIN_ACCEPTED" -> acceptedLatch.countDown();
                    case "JOIN_REJECTED" -> { LOG.warn("[webrtc] JOIN_REJECTED"); close(); }
                    case "ANSWER" -> {
                        String sdp = extractField(json, "sdp");
                        if (sdp == null) return;
                        if (peerConnection != null) applyAnswer(sdp);
                        else pendingAnswer = sdp;
                    }
                    case "ICE_CANDIDATE" -> {
                        String icJson = extractObject(json);
                        if (icJson == null) return;
                        String cand   = extractField(icJson, "candidate");
                        String mid    = extractField(icJson, "sdpMid");
                        String idxStr = extractField(icJson, "sdpMLineIndex");
                        if (cand == null) return;
                        int idx = 0;
                        try { idx = Integer.parseInt(idxStr != null ? idxStr : "0"); }
                        catch (Exception ignored) {}
                        RTCIceCandidate ic = new RTCIceCandidate(mid != null ? mid : "0", idx, cand);
                        if (peerConnection != null) peerConnection.addIceCandidate(ic);
                        else synchronized (pendingIce) { pendingIce.add(ic); }
                    }
                    case "HOST_DISCONNECTED" -> close();
                }
            }
        };
        signalingWs.connect();
    }

    // ── JSON 유틸 ─────────────────────────────────────────────────────────────

    private String extractField(String json, String field) {
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + key.length());
        if (colon < 0) return null;
        int vs = colon + 1;
        while (vs < json.length() && json.charAt(vs) == ' ') vs++;
        if (vs < json.length() && json.charAt(vs) != '"') {
            int e = vs;
            while (e < json.length() && ",}".indexOf(json.charAt(e)) < 0) e++;
            return json.substring(vs, e).trim();
        }
        int start = json.indexOf('"', colon + 1);
        if (start < 0) return null;
        int end = start + 1;
        while (end < json.length()) {
            if (json.charAt(end) == '"' && json.charAt(end - 1) != '\\') break;
            end++;
        }
        return json.substring(start + 1, end)
                .replace("\\n", "\n").replace("\\r", "\r")
                .replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private String extractObject(String json) {
        String key = "\"" + "iceCandidate" + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int brace = json.indexOf('{', idx + key.length());
        if (brace < 0) return null;
        int depth = 0, end = brace;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == '{') depth++;
            else if (c == '}') { if (--depth == 0) { end++; break; } }
            end++;
        }
        return json.substring(brace, end);
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r\n", "\\r\\n").replace("\n", "\\n").replace("\r", "\\r");
    }

    // ── 정리 ──────────────────────────────────────────────────────────────────

    public void close() {
        if (!running.compareAndSet(true, false)) return;
        LOG.info("[webrtc] Closing");
        acceptedLatch.countDown();
        readyLatch.countDown();
        mcOut = null;
        try { if (mcSocket != null)      mcSocket.close();     } catch (Exception ignored) {}
        try { if (serverSocket != null)  serverSocket.close(); } catch (Exception ignored) {}
        try { if (dataChannel != null) {
            dataChannel.unregisterObserver();
            dataChannel.close();
            dataChannel.dispose();
        }} catch (Exception ignored) {}
        try { if (peerConnection != null) peerConnection.close(); } catch (Exception ignored) {}
        try { if (factory != null)        factory.dispose();       } catch (Exception ignored) {}
        if (signalingWs != null) signalingWs.close();
    }

}