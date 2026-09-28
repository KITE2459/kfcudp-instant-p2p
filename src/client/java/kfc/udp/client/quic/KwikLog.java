package kfc.udp.client.quic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.kwik.core.log.BaseLogger;

import java.nio.ByteBuffer;

/**
 * kwik 의 로그를 slf4j 로 넘기는 어댑터.
 * <p>
 * <b>왜 필요한가</b> — 예전엔 {@code NullLogger} 를 넘겨서 kwik 이 내는 말을 전부 버렸다. 그런데
 * kwik 은 설정이 잘못됐을 때 그걸 <b>경고로만</b> 알린다. 예를 들어 서버 쪽에서
 * {@code ApplicationProtocolConnectionFactory} 가 동시 스트림 한도를 정해 주지 않으면
 * {@code ServerConnectionImpl.configure} 가 경고를 남기고 기본값으로 넘어가는데, 그걸 못 보면
 * 흐름 제어가 조용히 좁아져도 알 수가 없다.
 * <p>
 * <b>경고·오류만 켠다</b>(debug/packet/raw 는 끈 채로) — 켜면 패킷마다 줄이 나와 마크 로그가
 * 못 쓰게 된다. 더 봐야 할 때만 {@link #verbose()} 로 만들어 쓴다.
 */
final class KwikLog extends BaseLogger {

    private static final Logger LOG = LoggerFactory.getLogger("quic-kwik");

    /**
     * 우리가 소켓을 닫아서 나는 것들 — 방을 나가거나 재접속할 때마다 kwik 이 스택트레이스 세 개를
     * 남긴다(receiver/sender/abort). 정상 종료라 경고로 올릴 값이 없다.
     */
    /**
     * kwik 이 ERROR 로 올리지만 실제로는 문제가 아닌 것.
     * <p>
     * {@code "sender is looping in busy wait"} — {@code SenderImpl.determineMaximumWaitTime}
     * 은 다음 ACK 예정 시각이 이미 지났으면 대기 0 으로 다시 돈다. 그 시각 계산이
     * {@code Duration.toMillis()} 라 <b>1밀리초 미만이면 0 으로 내려앉는다</b> — 즉 지연이
     * 낮은 링크에서는 정상 동작이 이 경고에 걸린다. 게다가 이 줄은 {@code count % 20 == 3}
     * 에서만 나오므로, 로그에 계속 "got 3 iterations" 만 보인다면 매번 3에서 회복한다는 뜻이다
     * (정말 갇혔으면 23, 43, … 으로 올라가고 kwik 이 10003 에서 8초 대기로 빠진다).
     * <p>
     * 그래서 버리는 게 아니라 <b>DEBUG 로 내린다</b>. 이건 전송 스레드에서 나오는 로그라,
     * 부하가 몰릴 때 줄마다 포맷·IO 를 하는 것 자체가 비용이다.
     */
    private static boolean isBenignNoise(String message) {
        return message.contains("sender is looping in busy wait")
                // 마크가 서버를 나가 우리가 스트림을 닫은 직후, 이미 날아오던 상대 데이터가 도착한 것 — kwik 이
                // 버린다(StreamManager.process). 닫은 연결의 뒷정리라 잃는 것이 없다. 방장 쪽은 같은 상황을
                // "Receiving data for already closed peer-initiated stream" 으로 남긴다.
                || message.contains("Receiving frame for non-existent stream")
                || message.contains("for already closed peer-initiated stream");
    }

    private static boolean isOwnShutdown(String message) {
        return message.contains("Socket closed") || message.contains("ClosedChannelException")
                || message.contains("AsynchronousCloseException");
    }

    private KwikLog() {
        logWarning(true);
        logInfo(false);
        logDebug(false);
        logPackets(false);
        logRaw(false);
        logDecrypted(false);
        logSecrets(false);
    }

    /**
     * 평소에 쓰는 것 — 경고·오류만 올라온다.
     * {@code -Dkfcudp.quic.verbose=true} 면 흐름·혼잡 제어까지 켠다(끊김 원인을 좁힐 때).
     */
    static KwikLog quiet() {
        return Boolean.getBoolean("kfcudp.quic.verbose") ? verbose() : new KwikLog();
    }

    /**
     * 전송 문제를 파고들 때만. 흐름 제어·혼잡 제어·복구 로그를 켠다 —
     * 핑이 튀는 원인을 좁힐 때 이게 필요하다(패킷 덤프는 여전히 끈다).
     */
    static KwikLog verbose() {
        KwikLog l = new KwikLog();
        l.logInfo(true);
        l.logFlowControl(true);
        l.logCongestionControl(true);
        l.logRecovery(true);
        l.logStats(true);
        return l;
    }

    @Override protected void log(String message) {
        if (isOwnShutdown(message) || isBenignNoise(message)) {
            LOG.debug("[kwik] {}", message.stripTrailing());
            return;
        }
        LOG.warn("[kwik] {}", message.stripTrailing());
    }

    @Override protected void log(String message, Throwable ex) {
        if (isOwnShutdown(message) || isBenignNoise(message)
                || (ex != null && isOwnShutdown(String.valueOf(ex)))) {
            LOG.debug("[kwik] {}", message.stripTrailing());
            return;
        }
        LOG.warn("[kwik] {}", message.stripTrailing(), ex);
    }

    // 바이트 덤프는 마크 로그에 쓸 자리가 없다 — 길이만 남긴다.
    @Override protected void logWithHexDump(String message, byte[] data, int length) {
        LOG.warn("[kwik] {} ({}B)", message.stripTrailing(), length);
    }

    @Override protected void logWithHexDump(String message, ByteBuffer data, int offset, int length) {
        LOG.warn("[kwik] {} ({}B)", message.stripTrailing(), length);
    }
}
