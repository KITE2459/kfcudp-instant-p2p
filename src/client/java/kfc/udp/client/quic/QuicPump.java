package kfc.udp.client.quic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * TCP 소켓 ↔ QUIC 스트림 양방향 복사.
 * <p>
 * <b>WebRTC 쪽 {@code BatchPipe}에 해당하는 계층이 여기엔 없다.</b> DataChannel은 메시지 API라
 * 256KB 상한(libwebrtc max-message-size)이 있었고, 그 상한 안에서 처리량을 내려고 배칭·워터마크·
 * writev 묶음이 필요했다. QUIC 스트림은 그냥 바이트 스트림이고 배압은 QUIC 흐름 제어가 처리하므로
 * {@code read} / {@code write} 루프면 끝이다. 계층 하나가 통째로 사라진 것이라 다시 넣지 말 것.
 */
final class QuicPump {

    private static final Logger LOG = LoggerFactory.getLogger("quic-pump");

    /** 64KB — TCP 소켓 버퍼에 있는 만큼을 한 번에 퍼오기에 충분하다. */
    private static final int BUF = 64 * 1024;

    private QuicPump() {}

    /**
     * 두 방향을 각자 스레드에서 돌리고 <b>어느 쪽이든 끝나면 양쪽을 닫는다</b> —
     * 한쪽만 닫으면 반대편 스레드가 영원히 read에 걸려 남는다.
     *
     * @param label 로그용 꼬리표(sid 등). 실제 IP는 넣지 말 것.
     */
    static void wire(Socket tcp, InputStream quicIn, OutputStream quicOut, String label) {
        Runnable closeBoth = () -> {
            try { tcp.close(); } catch (IOException ignored) {}
            try { quicOut.close(); } catch (IOException ignored) {}
            try { quicIn.close(); } catch (IOException ignored) {}
        };
        spawn(label + "-up", () -> copy(tcp.getInputStream(), quicOut), closeBoth);
        spawn(label + "-down", () -> copy(quicIn, tcp.getOutputStream()), closeBoth);
    }

    private interface Body { void run() throws IOException; }

    private static void spawn(String name, Body body, Runnable onEnd) {
        Thread t = new Thread(() -> {
            try {
                body.run();
            } catch (IOException e) {
                // 정상 종료(상대가 먼저 닫음)와 구분이 안 되므로 DEBUG — 진짜 문제는 상위에서 드러난다.
                LOG.debug("[pump] {} 종료: {}", name, e.getMessage());
            } finally {
                onEnd.run();
            }
        }, "quic-" + name);
        t.setDaemon(true);
        t.start();
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[BUF];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            out.flush(); // 마크는 지연에 민감하다 — 모아 보내면 렉으로 보인다
        }
    }
}
