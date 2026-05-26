package kfc.udp.client.kcp;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * KCP 예외 처리.
 * KcpException(State=-1 등) → 채널 닫기만. MC 파이프라인에 전파 안 함.
 */
@ChannelHandler.Sharable
public final class KcpExceptionHandler extends ChannelInboundHandlerAdapter {

    public static final KcpExceptionHandler INSTANCE = new KcpExceptionHandler();
    private KcpExceptionHandler() {}

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (cause instanceof KcpException) {
            ctx.close();
        } else {
            ctx.fireExceptionCaught(cause);
        }
    }
}
