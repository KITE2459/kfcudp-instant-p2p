package kfc.udp.client.webrtc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 시그널링 서버 WebSocket 클라이언트 — 자체 RFC 6455 구현.
 * <p>
 * 기존 java.net.http.WebSocket은 일부 서버/방화벽 조합에서 업그레이드 응답이
 * 오지 않아 핸드셰이크가 타임아웃되는 문제가 있었다 (Go coder/websocket은 정상).
 * Go 클라이언트가 보내는 것과 동일한 최소 핸드셰이크 헤더만 전송하도록
 * raw 소켓 기반으로 재구현. 외부 의존성 없음.
 */
public abstract class WebSocketClient {

    private static final Logger LOG = LoggerFactory.getLogger("webrtc-ws");

    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int CONNECT_TIMEOUT_MS   = 10_000; // TCP 연결 + 핸드셰이크 응답 한도
    private static final int MAX_FRAME_BYTES      = 16 * 1024 * 1024;

    private final String url;

    private volatile Socket socket;
    private volatile OutputStream out;
    private volatile boolean closedByUser = false;
    private volatile boolean disconnectNotified = false;

    private final Object writeLock = new Object();
    private static final SecureRandom RANDOM = new SecureRandom();

    public WebSocketClient(String url) {
        this.url = url;
    }

    /**
     * 동기 접속: TCP 연결 → RFC 6455 핸드셰이크 → 수신 스레드 시작 → onConnected().
     * 실패 시 예외를 던진다 (호출자가 재시도 판단).
     */
    public void connect() throws Exception {
        URI uri = URI.create(url);
        boolean tls = "wss".equalsIgnoreCase(uri.getScheme());
        String host = uri.getHost();
        int port = uri.getPort() != -1 ? uri.getPort() : (tls ? 443 : 80);
        String path = (uri.getRawPath() == null || uri.getRawPath().isEmpty()) ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) path = path + "?" + uri.getRawQuery();

        // JVM 프록시 설정(런처가 주입하는 socksProxyHost 등)을 우회하고
        // Go 바이너리와 동일하게 항상 "직접" 연결한다. 프록시가 설정된 환경에서
        // new Socket()은 프록시를 경유해 Connection refused/timeout이 날 수 있다.
        String socksHost = System.getProperty("socksProxyHost");
        String httpProxyHost = System.getProperty("http.proxyHost");
        if (socksHost != null || httpProxyHost != null) {
            LOG.warn("[ws] JVM proxy settings detected (socksProxyHost={}, http.proxyHost={}) — bypassing, connecting directly",
                    socksHost, httpProxyHost);
        }
        Socket s = new Socket(java.net.Proxy.NO_PROXY);
        s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        s.setTcpNoDelay(true);
        if (tls) {
            s = ((javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault())
                    .createSocket(s, host, port, true);
        }

        boolean ok = false;
        try {
            OutputStream o = new BufferedOutputStream(s.getOutputStream());
            InputStream  in = new BufferedInputStream(s.getInputStream());

            // Go coder/websocket과 동일한 최소 헤더 (User-Agent 등 미포함)
            byte[] keyBytes = new byte[16];
            RANDOM.nextBytes(keyBytes);
            String key = Base64.getEncoder().encodeToString(keyBytes);

            String req = "GET " + path + " HTTP/1.1\r\n" +
                    "Host: " + host + ":" + port + "\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: " + key + "\r\n" +
                    "Sec-WebSocket-Version: 13\r\n" +
                    "\r\n";
            o.write(req.getBytes(StandardCharsets.ISO_8859_1));
            o.flush();

            // 핸드셰이크 응답 대기 (한도 내)
            s.setSoTimeout(CONNECT_TIMEOUT_MS);
            String statusLine = readHeaderLine(in);
            if (statusLine == null || !statusLine.startsWith("HTTP/1.1 101")) {
                throw new IOException("handshake failed: " + statusLine);
            }
            String accept = null;
            String line;
            while ((line = readHeaderLine(in)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase("Sec-WebSocket-Accept")) {
                    accept = line.substring(c + 1).trim();
                }
            }
            String expected = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1")
                            .digest((key + WS_GUID).getBytes(StandardCharsets.ISO_8859_1)));
            if (!expected.equals(accept)) {
                throw new IOException("handshake failed: bad Sec-WebSocket-Accept");
            }

            s.setSoTimeout(0); // 이후는 무한 대기 (Go와 동일)
            socket = s;
            out = o;
            ok = true;
            LOG.info("[ws] connected to {}", url);

            final InputStream fin = in;
            Thread reader = new Thread(() -> readLoop(fin), "webrtc-ws-read");
            reader.setDaemon(true);
            reader.start();

            onConnected();
        } finally {
            if (!ok) {
                try { s.close(); } catch (IOException ignored) {}
            }
        }
    }

    // ── 수신 ──────────────────────────────────────────────────────────────────

    private void readLoop(InputStream in) {
        ByteArrayOutputStream fragment = new ByteArrayOutputStream();
        try {
            while (true) {
                int b0 = in.read();
                if (b0 < 0) break;
                int b1 = in.read();
                if (b1 < 0) break;

                boolean fin    = (b0 & 0x80) != 0;
                int     opcode = b0 & 0x0F;
                boolean masked = (b1 & 0x80) != 0;
                long    len    = b1 & 0x7F;
                if (len == 126) {
                    len = ((long) readByte(in) << 8) | readByte(in);
                } else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) len = (len << 8) | readByte(in);
                }
                if (len < 0 || len > MAX_FRAME_BYTES) throw new IOException("frame too large: " + len);

                byte[] mask = masked ? readN(in, 4) : null;
                byte[] payload = readN(in, (int) len);
                if (mask != null) {
                    for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
                }

                if (opcode == 0x8) {            // CLOSE
                    try { sendFrame(0x8, payload); } catch (IOException ignored) {}
                    break;
                } else if (opcode == 0x9) {     // PING → PONG
                    sendFrame(0xA, payload);
                } else if (opcode == 0xA) {     // PONG
                    // 무시
                } else if (opcode == 0x1 || opcode == 0x2 || opcode == 0x0) {
                    fragment.write(payload);
                    if (fin) {
                        String msg = fragment.toString(StandardCharsets.UTF_8);
                        fragment.reset();
                        LOG.debug("[ws] recv: {}", msg.length() > 120 ? msg.substring(0, 120) + "..." : msg);
                        // type 필드가 없는 프로토콜(VILLAS 등)도 있으므로 항상 전달
                        String type = extractType(msg);
                        try {
                            onMessage(type != null ? type : "", msg);
                        } catch (Exception e) {
                            LOG.warn("[ws] onMessage handler error: {}", e.toString());
                        }
                    }
                }
                // 그 외 opcode는 무시
            }
        } catch (Exception e) {
            if (!closedByUser) LOG.warn("[ws] read error: {}", e.toString());
        } finally {
            closeSocket();
            notifyDisconnected();
        }
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException("stream closed");
        return b;
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) throw new EOFException("stream closed");
            off += r;
        }
        return buf;
    }

    private static String readHeaderLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
        }
        if (c < 0 && b.size() == 0) return null;
        return b.toString(StandardCharsets.ISO_8859_1);
    }

    // ── 송신 ──────────────────────────────────────────────────────────────────

    /** raw JSON 문자열 직접 전송 */
    public void send(String json) {
        LOG.debug("[ws] send: {}", json.length() > 120 ? json.substring(0, 120) + "..." : json);
        try {
            sendFrame(0x1, json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOG.warn("[ws] send failed: {}", e.toString());
        }
    }

    /** 클라이언트 프레임은 반드시 마스킹 (RFC 6455 §5.3) */
    private void sendFrame(int opcode, byte[] payload) throws IOException {
        OutputStream o = out;
        if (o == null) return;
        byte[] mask = new byte[4];
        RANDOM.nextBytes(mask);

        ByteArrayOutputStream frame = new ByteArrayOutputStream(payload.length + 14);
        frame.write(0x80 | opcode); // FIN=1
        int len = payload.length;
        if (len < 126) {
            frame.write(0x80 | len);
        } else if (len < 65536) {
            frame.write(0x80 | 126);
            frame.write((len >>> 8) & 0xFF);
            frame.write(len & 0xFF);
        } else {
            frame.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) frame.write((int) ((long) len >>> (8 * i)) & 0xFF);
        }
        frame.write(mask, 0, 4);
        byte[] maskedPayload = new byte[payload.length];
        for (int i = 0; i < payload.length; i++) maskedPayload[i] = (byte) (payload[i] ^ mask[i & 3]);
        frame.write(maskedPayload, 0, maskedPayload.length);

        synchronized (writeLock) {
            o.write(frame.toByteArray());
            o.flush();
        }
    }

    // ── 종료 ──────────────────────────────────────────────────────────────────

    public void close() {
        closedByUser = true;
        try { sendFrame(0x8, new byte[]{0x03, (byte) 0xE8}); } catch (IOException ignored) {} // 1000 normal
        closeSocket();
    }

    private void closeSocket() {
        Socket s = socket;
        socket = null;
        out = null;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    private void notifyDisconnected() {
        if (closedByUser || disconnectNotified) return;
        disconnectNotified = true;
        onDisconnected();
    }

    // ── 파싱 ──────────────────────────────────────────────────────────────────

    private String extractType(String json) {
        String key = "\"type\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + key.length());
        if (colon < 0) return null;
        int start = json.indexOf('"', colon + 1);
        if (start < 0) return null;
        int end = json.indexOf('"', start + 1);
        if (end < 0) return null;
        return json.substring(start + 1, end);
    }

    public abstract void onConnected();
    public abstract void onMessage(String type, String json);

    /** 연결이 비정상적으로 끊겼을 때 1회 호출 (close() 호출 시에는 미호출). 재접속용 훅. */
    public void onDisconnected() {}
}