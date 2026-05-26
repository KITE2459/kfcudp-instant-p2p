package kfc.udp.client.kcp;

/**
 * KCP 세션 이상 (state=-1 등) 예외.
 * KcpExceptionHandler가 잡아 채널만 닫음.
 */
public final class KcpException extends RuntimeException {
    public KcpException(String msg) { super(msg, null, true, false); }
}
