package com.xspaceagi.userapp.infra.proxy;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpStatusClass;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 下游响应回传客户端（含 _ticket 兑换成功后的 ticket 下发）
 */
public class UserAppProxyResponseHandler extends ChannelInboundHandlerAdapter {

    private static Logger logger = LoggerFactory.getLogger(UserAppProxyResponseHandler.class);

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        Channel nextChannel = ctx.channel().attr(UserAppProxyServerContainer.nextChannelAttributeKey).get();
        if (nextChannel != null) {
            String token = nextChannel.attr(UserAppProxyServerContainer.tokenKey).get();
            if (msg instanceof DefaultHttpResponse response) {
                logRelay(ctx, response);
                UserAppProxyRelayContext relay = ctx.channel().attr(UserAppProxyServerContainer.relayContextKey).get();
                if (relay != null && relay.corsOrigin() != null) {
                    // 下游应用 CORS 配置不可控（默认不放行平台域名）：代理按租户 siteUrl 白名单统一注入，
                    // 覆盖下游可能已带的头，避免双 ACAO 值冲突
                    UserAppProxyCors.apply(response.headers(), relay.corsOrigin());
                }
                if (token != null) {
                    // add 而非 set：下游应用自身下发的 Set-Cookie 需共存，不能被 ticket 覆盖
                    response.headers().add("Set-Cookie", "ticket=" + token + "; Path=/; HttpOnly; SameSite=None; Secure");
                    nextChannel.attr(UserAppProxyServerContainer.tokenKey).set(null);
                }
            } else if (msg instanceof LastHttpContent) {
                // 响应完整落地（HEAD/204/304 等无 body 响应解码器也会合成终止帧）：之后连接关闭不算异常中断
                ctx.channel().attr(UserAppProxyServerContainer.relayContextKey).set(null);
            }
            // 转发时由目标 channel 的 pipeline（HttpObjectEncoder）在编码完成后释放，此处不再 release 避免重复释放
            nextChannel.writeAndFlush(msg);
        } else {
            ReferenceCountUtil.release(msg);
        }
    }

    /**
     * 回传日志：每个响应头到达时打印前端请求 → 后端 → 状态码/耗时。
     * 出现本行即证明响应由下游产生（代理自身错误页不走此处），用于 404/502 归属定位。
     */
    private void logRelay(ChannelHandlerContext ctx, DefaultHttpResponse response) {
        // 1xx 是中间响应（101 升级路径的上下文已提前摘除），等最终状态行再记，避免一次请求打两行
        if (response.status().codeClass() == HttpStatusClass.INFORMATIONAL) {
            return;
        }
        UserAppProxyRelayContext relay = ctx.channel().attr(UserAppProxyServerContainer.relayContextKey).get();
        if (relay == null) {
            return;
        }
        logger.info("[UserAppProxy] relay, method={}, uri={}, backend={}, status={}, cost={}ms",
                relay.method(), relay.uri(), relay.backendKey(), response.status(),
                (System.nanoTime() - relay.startNanos()) / 1_000_000);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        UserAppProxyRelayContext relay = ctx.channel().attr(UserAppProxyServerContainer.relayContextKey).get();
        if (relay != null) {
            // 请求已转发、响应未完整回传（无响应头或 body 中途截断），后端先断开：页面挂起/资源截断的典型根因
            logger.warn("[UserAppProxy] backend closed before response complete, uri={}, backend={}",
                    relay.uri(), relay.backendKey());
        }
        if (ctx.channel().attr(UserAppProxyServerContainer.nextChannelAttributeKey).get() != null) {
            try {
                ctx.channel().attr(UserAppProxyServerContainer.nextChannelAttributeKey).get()
                        .writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            } catch (Exception e) {
                logger.warn("close next channel failed", e);
            }
        }
        super.channelInactive(ctx);
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        Channel channel = ctx.channel().attr(UserAppProxyServerContainer.nextChannelAttributeKey).get();
        if (channel != null) {
            channel.config().setOption(ChannelOption.AUTO_READ, ctx.channel().isWritable());
        }
        super.channelWritabilityChanged(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.error("exception caught", cause);
        super.exceptionCaught(ctx, cause);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            // 目标侧空闲超时：双端关闭
            logger.info("userapp proxy target idle timeout, closing channel");
            Channel peer = ctx.channel().attr(UserAppProxyServerContainer.nextChannelAttributeKey).get();
            ctx.close();
            if (peer != null) {
                peer.close();
            }
            return;
        }
        super.userEventTriggered(ctx, evt);
    }
}
