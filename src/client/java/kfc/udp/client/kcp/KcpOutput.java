package kfc.udp.client.kcp;

import io.netty.buffer.ByteBuf;

/**
 * KCP → UDP 송신 콜백.
 * kcp-netty KcpOutput에서 Kcp 파라미터 제거 (conv로 충분).
 */
@FunctionalInterface
public interface KcpOutput {
    /**
     * @param data  전송할 KCP 프레임 (호출 완료 후 release)
     * @param conv  이 세션의 conv ID (멀티세션 구분용)
     */
    void out(ByteBuf data, int conv);
}
