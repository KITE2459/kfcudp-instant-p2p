package kfc.udp.client.webrtc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Consumer;

/**
 * 스루풋 배칭 유틸 (OpenFriend Go의 coalescing 전략 이식).
 * <p>
 * <b>TCP→DataChannel</b>: {@link #coalesce}로 첫 블로킹 read 이후 OS 소켓 버퍼에
 * 쌓인 데이터를 논블로킹으로 최대 {@link #BATCH_MAX}까지 흡수해
 * DataChannel.send() (JNI + SCTP 메시지) 횟수를 줄인다.
 * <p>
 * <b>DataChannel→TCP</b>: {@link Writer} — 수신 콜백은 청크 큐에 넣기만 하고
 * 전담 writer 스레드가 연속 청크를 하나의 write() 시스콜로 묶어 기록한다.
 * 큐가 가득 차면 put()이 블로킹 → SCTP 수신 윈도우로 자연 배압.
 * 순서 보장(단일 writer), 드롭 없음(블로킹 큐).
 * <p>
 * 배치 상한 256KB: libwebrtc 기본 max-message-size(262144)와 SCTP 단편화 한도 이하.
 */
final class BatchPipe {

    static final int CHUNK     = 65536;
    static final int BATCH_MAX = 256 * 1024;

    // ── 청크 풀 (GC 압력 감소, Go globalChunkPool 대응) ───────────────────────

    static final class Chunk {
        final byte[] data = new byte[CHUNK];
        int len;
    }

    private static final ArrayBlockingQueue<Chunk> POOL = new ArrayBlockingQueue<>(256);

    static Chunk getChunk() {
        Chunk c = POOL.poll();
        return c != null ? c : new Chunk();
    }

    static void putChunk(Chunk c) {
        POOL.offer(c);
    }

    // ── TCP→DC: 논블로킹 추가 흡수 ────────────────────────────────────────────

    /**
     * 첫 read로 buf[0..firstLen)을 채운 뒤, OS 버퍼에 남은 데이터를
     * 논블로킹(available 기반)으로 buf 끝까지 이어 붙인다.
     *
     * @return 총 바이트 수
     */
    static int coalesce(InputStream in, byte[] buf, int firstLen) throws IOException {
        int total = firstLen;
        while (total < buf.length) {
            int avail = in.available();
            if (avail <= 0) break;
            int n = in.read(buf, total, Math.min(avail, buf.length - total));
            if (n <= 0) break;
            total += n;
        }
        return total;
    }

    // ── DC→TCP: 큐 + 배칭 writer ─────────────────────────────────────────────

    static final class Writer {
        /** 큐 용량 512 × 64KB = 최대 32MB 대기 (DC_BUF_HIGH 16MB의 2배) */
        private final ArrayBlockingQueue<Chunk> q = new ArrayBlockingQueue<>(512);
        private static final Chunk POISON = new Chunk();

        private final OutputStream out;
        private final Consumer<Exception> onError;
        private final Thread thread;
        private volatile boolean closed;

        Writer(OutputStream out, String name, Consumer<Exception> onError) {
            this.out = out;
            this.onError = onError;
            this.thread = new Thread(this::run, name);
            this.thread.setDaemon(true);
            this.thread.setPriority(Thread.NORM_PRIORITY + 2); // 파이프 지연 최소화
            this.thread.start();
        }

        /** DataChannel 수신 버퍼를 청크로 분할해 큐잉 (콜백 스레드에서 호출) */
        void feed(ByteBuffer buffer) throws InterruptedException {
            while (buffer.hasRemaining()) {
                Chunk c = getChunk();
                int n = Math.min(buffer.remaining(), CHUNK);
                buffer.get(c.data, 0, n);
                c.len = n;
                q.put(c); // 가득 차면 블로킹 = 배압
            }
        }

        private void run() {
            byte[] batch = new byte[BATCH_MAX];
            try {
                while (true) {
                    Chunk first = q.take();
                    if (first == POISON) return;
                    int total = first.len;
                    System.arraycopy(first.data, 0, batch, 0, first.len);
                    putChunk(first);

                    // 논블로킹으로 후속 청크 흡수 → write() 1회로 묶기
                    Chunk next;
                    while (total + CHUNK <= BATCH_MAX && (next = q.poll()) != null) {
                        if (next == POISON) {
                            out.write(batch, 0, total);
                            return;
                        }
                        System.arraycopy(next.data, 0, batch, total, next.len);
                        total += next.len;
                        putChunk(next);
                    }
                    out.write(batch, 0, total);
                }
            } catch (Exception e) {
                if (!closed) onError.accept(e);
            }
        }

        void close() {
            if (closed) return;
            closed = true;
            q.clear();
            q.offer(POISON);
        }
    }

    private BatchPipe() {}
}