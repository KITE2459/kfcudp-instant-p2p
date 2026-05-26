package kfc.udp.client.webrtc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.*;

/**
 * 시그널링 서버 WebSocket 클라이언트.
 * java.net.http.WebSocket 사용 (외부 의존성 없음).
 */
public abstract class WebSocketClient {

    private static final Logger LOG = LoggerFactory.getLogger("webrtc-ws");

    private final String url;
    private volatile WebSocket ws;
    private final StringBuilder msgBuf = new StringBuilder();

    public WebSocketClient(String url) {
        this.url    = url;
    }

    public void connect() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        CompletableFuture<WebSocket> future = client.newWebSocketBuilder()
                .buildAsync(URI.create(url), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        ws = webSocket;
                        webSocket.request(1);
                        LOG.info("[ws] connected to {}", url);
                        onConnected();
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        msgBuf.append(data);
                        if (last) {
                            String msg = msgBuf.toString();
                            msgBuf.setLength(0);
                            LOG.debug("[ws] recv: {}", msg.length() > 120 ? msg.substring(0, 120) + "..." : msg);
                            String type = extractType(msg);
                            if (type != null) onMessage(type, msg);
                        }
                        webSocket.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        LOG.info("[ws] closed: {} {}", statusCode, reason);
                        return null;
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        LOG.warn("[ws] error: {}", error.getMessage());
                    }
                });

        future.get(10, TimeUnit.SECONDS);
    }

    /** raw JSON 문자열 직접 전송 */
    public void send(String json) {
        WebSocket w = ws;
        if (w != null) {
            LOG.debug("[ws] send: {}", json.length() > 120 ? json.substring(0, 120) + "..." : json);
            w.sendText(json, true);
        }
    }

    public void close() {
        WebSocket w = ws;
        if (w != null) w.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
    }

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
}