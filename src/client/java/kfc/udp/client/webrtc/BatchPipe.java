package kfc.udp.client.webrtc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.GatheringByteChannel;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Consumer;

/**
 * DataChannel↔TCP 파이프의 배칭 유틸.
 * <p>
 * <b>TCP→DataChannel</b>: 별도 헬퍼 없이 각 forwarder가 direct ByteBuffer 하나로
 * {@code SocketChannel.read()}를 돌린다. 블로킹 read는 OS 소켓 버퍼에 있는 만큼을
 * 버퍼 용량까지 한 번에 채워 주므로, 예전의 {@code coalesce(InputStream, byte[])}
 * (available() 기반 논블로킹 추가 흡수)가 하던 일을 read 한 번이 그대로 해낸다.
 * 그러면서 힙 {@code byte[]} → direct 복사 한 단계가 통째로 사라진다.
 * <p>
 * <b>DataChannel→TCP</b>: {@link Writer} — 수신 콜백은 청크 큐에 넣기만 하고
 * 전담 writer 스레드가 연속 청크를 <b>gathering write(writev) 1회</b>로 묶어 낸다.
 * 예전에는 청크들을 {@code byte[] batch}로 옮겨 담아 write했는데, 이제는 청크의
 * direct 버퍼를 그대로 벡터에 실어 보내므로 그 복사도 없다.
 * 큐가 가득 차면 put()이 블로킹 → SCTP 수신 윈도우로 자연 배압.
 * 순서 보장(단일 writer), 드롭 없음(블로킹 큐).
 * <p>
 * 배치 상한 256KB: libwebrtc 기본 max-message-size(262144)와 SCTP 단편화 한도 이하.
 */
final class BatchPipe {

    static final int CHUNK     = 65536;
    static final int BATCH_MAX = 256 * 1024;

    /** 한 번의 writev에 실을 최대 청크 수 (16 × 64KB = 1MB). IOV_MAX 한참 아래. */
    private static final int MAX_VEC = 16;

    // ── 청크 풀 (GC 압력 감소, Go globalChunkPool 대응) ───────────────────────

    /**
     * 청크 버퍼는 direct다. writev로 커널에 그대로 넘기려면 direct여야 하고,
     * 힙 버퍼를 넘기면 JDK가 내부 direct 버퍼로 한 번 더 복사한다.
     */
    static final class Chunk {
        final ByteBuffer data = ByteBuffer.allocateDirect(CHUNK);
    }

    private static final ArrayBlockingQueue<Chunk> POOL = new ArrayBlockingQueue<>(256);

    static Chunk getChunk() {
        Chunk c = POOL.poll();
        return c != null ? c : new Chunk();
    }

    static void putChunk(Chunk c) {
        POOL.offer(c);
    }

    // ── DC→TCP: 큐 + writev writer ───────────────────────────────────────────

    static final class Writer {
        /** 큐 용량 = PIPE_QUEUE_CHUNKS × 64KB. 가득 차면 feed()가 블로킹 → 배압. */
        private final ArrayBlockingQueue<Chunk> q =
                new ArrayBlockingQueue<>(P2PConfig.PIPE_QUEUE_CHUNKS);
        private static final Chunk POISON = new Chunk();

        private final GatheringByteChannel out;
        private final Consumer<Exception> onError;
        private final Thread thread;
        private volatile boolean closed;

        Writer(GatheringByteChannel out, String name, Consumer<Exception> onError) {
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

                c.data.clear();
                int srcLimit = buffer.limit();
                buffer.limit(buffer.position() + n);
                c.data.put(buffer);          // direct→direct 복사
                buffer.limit(srcLimit);
                c.data.flip();

                q.put(c); // 가득 차면 블로킹 = 배압
            }
        }

        private void run() {
            final ByteBuffer[] vec  = new ByteBuffer[MAX_VEC];
            final Chunk[]      held = new Chunk[MAX_VEC];
            try {
                while (true) {
                    Chunk first = q.take();
                    if (first == POISON) return;

                    int count = 0;
                    held[count] = first;
                    vec[count]  = first.data;
                    count++;

                    // 논블로킹으로 후속 청크 흡수 → writev 1회로 묶기
                    boolean poisoned = false;
                    Chunk next;
                    while (count < MAX_VEC && (next = q.poll()) != null) {
                        if (next == POISON) { poisoned = true; break; }
                        held[count] = next;
                        vec[count]  = next.data;
                        count++;
                    }

                    try {
                        writeFully(vec, count);
                    } finally {
                        for (int i = 0; i < count; i++) {
                            putChunk(held[i]);
                            held[i] = null;
                            vec[i]  = null;
                        }
                    }
                    if (poisoned) return;
                }
            } catch (Exception e) {
                if (!closed) onError.accept(e);
            }
        }

        /**
         * 블로킹 채널이라 원칙적으로 한 번에 다 쓰이지만, 부분 write가 나더라도
         * 각 버퍼의 position이 진행된 상태이므로 남은 만큼 이어서 쓰면 된다.
         */
        private void writeFully(ByteBuffer[] vec, int count) throws IOException {
            long remaining = 0;
            for (int i = 0; i < count; i++) remaining += vec[i].remaining();
            while (remaining > 0) {
                long w = (count == 1) ? out.write(vec[0]) : out.write(vec, 0, count);
                if (w <= 0) throw new IOException("socket write returned " + w);
                remaining -= w;
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
