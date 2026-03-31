package com.xspaceagi.sandbox.infra.network;

import com.xspaceagi.sandbox.infra.dao.service.SandboxProxyService;
import com.xspaceagi.sandbox.infra.dao.vo.SandboxProxyBackend;
import com.xspaceagi.sandbox.infra.network.protocol.Constants;
import com.xspaceagi.sandbox.infra.network.protocol.ProxyMessage;
import com.xspaceagi.system.spec.cache.SimpleJvmHashCache;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLHandshakeException;
import java.net.InetAddress;
import java.util.LinkedList;
import java.util.Queue;

import static com.xspaceagi.sandbox.infra.network.UserChannelHandler.newUserId;

/**
 * 客户端临时代理
 */
@Slf4j
public class TempLinkHttpProxyHandler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(TempLinkHttpProxyHandler.class);

    private final SandboxProxyService sandboxProxyService;

    private final Queue<Object> receivedLastMessagesWhenConnect = new LinkedList<>();

    public TempLinkHttpProxyHandler(SandboxProxyService sandboxProxyService) {
        this.sandboxProxyService = sandboxProxyService;
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
        if (msg instanceof HttpRequest) {
            DefaultHttpRequest request = (DefaultHttpRequest) msg;
            String host = request.headers().get("Host");
            //获取域名开头字符串
            String proxyKey = extractDomainPrefix(host);
            SandboxProxyBackend sandboxProxyBackend = proxyKey == null ? null : sandboxProxyService.getBackendByProxyKey(proxyKey);
            if (sandboxProxyBackend == null) {
                sendError(ctx, "Invalid domain", msg);
                return;
            }
            ctx.channel().pipeline().remove("httpServerCodec");
            Channel cmdChannel = ProxyChannelManager.getCmdChannel(sandboxProxyBackend.getSandboxConfigKey());
            if (cmdChannel == null) {
                log.warn("no cmd channel {}", sandboxProxyBackend);
                // 该端口还没有代理客户端
                sendError(ctx, "Claw is offline", msg);
                return;
            } else {
                String userId = newUserId();
                Channel userChannel = ctx.channel();
                // 用户连接到代理服务器时，设置用户连接不可读，等待代理后端服务器连接成功后再改变为可读状态
                userChannel.config().setOption(ChannelOption.AUTO_READ, false);
                ProxyChannelManager.addUserChannelToCmdChannel(cmdChannel, userId, userChannel);
                ProxyMessage proxyMessage = new ProxyMessage();
                proxyMessage.setType(ProxyMessage.TYPE_CONNECT);
                proxyMessage.setUri(userId);
                proxyMessage.setData((sandboxProxyBackend.getBackendHost() + ":" + sandboxProxyBackend.getBackendPort()).getBytes());
                cmdChannel.writeAndFlush(proxyMessage);
            }
            ByteBuf firstRequest = convertToByteBuf(request);
            receivedLastMessagesWhenConnect.offer(firstRequest);
            ctx.channel().attr(Constants.MESSAGE_QUEUE).set(receivedLastMessagesWhenConnect);
            ReferenceCountUtil.release(request);
        } else {
            Channel targetChannel = ctx.channel().attr(Constants.NEXT_CHANNEL).get();
            if (targetChannel != null) {
                if (msg instanceof ByteBuf buf) {
                    byte[] bytes = new byte[buf.readableBytes()];
                    buf.readBytes(bytes);
                    String userId = ProxyChannelManager.getUserChannelUserId(ctx.channel());
                    ProxyMessage proxyMessage0 = new ProxyMessage();
                    proxyMessage0.setType(ProxyMessage.P_TYPE_TRANSFER);
                    proxyMessage0.setUri(userId);
                    proxyMessage0.setData(bytes);
                    targetChannel.writeAndFlush(proxyMessage0);
                    ReferenceCountUtil.release(buf);
                }
            } else {
                if (msg instanceof HttpContent) {
                    ByteBuf content = ((HttpContent) msg).content();
                    receivedLastMessagesWhenConnect.offer(content);
                } else if (msg instanceof ByteBuf) {
                    receivedLastMessagesWhenConnect.offer(msg);
                } else {
                    log.warn("unexpected message type {}", msg.getClass());
                    ReferenceCountUtil.release(msg);
                }
            }
        }
    }

    private void sendError(ChannelHandlerContext ctx, String message, Object msg) {
        FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND,
                Unpooled.copiedBuffer("{\"error\":{\"message\":\"" + message + "\"}}", CharsetUtil.UTF_8));
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        ctx.channel().writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
        ReferenceCountUtil.release(msg);
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        Channel targetChannel = ctx.channel().attr(Constants.NEXT_CHANNEL).get();
        if (targetChannel != null) {
            targetChannel.config().setOption(ChannelOption.AUTO_READ, ctx.channel().isWritable());
        }
        super.channelWritabilityChanged(ctx);
    }


    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        Channel targetChannel = ctx.channel().attr(Constants.NEXT_CHANNEL).get();
        if (targetChannel != null) {
            try {
                targetChannel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            } catch (Exception ignored) {
            }
        }
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (cause.getCause() != null && (cause.getCause() instanceof SSLHandshakeException)) {
            logger.warn("SSLHandshakeException: {}", cause.getCause().getMessage());
            return;
        }
        super.exceptionCaught(ctx, cause);
    }

    private static String extractDomainPrefix(String domain) {
        if (domain == null || domain.isEmpty()) {
            return null;
        }
        int dotIndex = domain.indexOf('.');
        if (dotIndex == -1) {
            return domain;
        }
        return domain.substring(0, dotIndex);
    }

    // 方法1: 将HttpRequest序列化为ByteBuf
    public static ByteBuf convertToByteBuf(HttpRequest request) {
        ByteBuf buffer = ByteBufAllocator.DEFAULT.buffer();
        try {
            // 写入请求行
            buffer.writeBytes(request.method().name().getBytes());
            buffer.writeByte(' ');
            buffer.writeBytes(request.uri().getBytes());
            buffer.writeByte(' ');
            buffer.writeBytes(request.protocolVersion().text().getBytes());
            buffer.writeBytes("\r\n".getBytes());

            // 写入请求头
            request.headers().forEach(entry -> {
                buffer.writeBytes(entry.getKey().getBytes());
                buffer.writeBytes(": ".getBytes());
                buffer.writeBytes(entry.getValue().getBytes());
                buffer.writeBytes("\r\n".getBytes());
            });

            // 写入空行
            buffer.writeBytes("\r\n".getBytes());

            return buffer;
        } catch (Exception e) {
            buffer.release();
            throw e;
        }
    }
}
