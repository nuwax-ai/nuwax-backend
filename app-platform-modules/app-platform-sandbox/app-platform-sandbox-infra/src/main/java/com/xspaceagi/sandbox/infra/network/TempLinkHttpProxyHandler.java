package com.xspaceagi.sandbox.infra.network;

import com.xspaceagi.sandbox.infra.dao.service.SandboxProxyService;
import com.xspaceagi.sandbox.infra.dao.vo.SandboxProxyBackend;
import com.xspaceagi.sandbox.infra.network.protocol.Constants;
import com.xspaceagi.sandbox.infra.network.protocol.ProxyMessage;
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

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static com.xspaceagi.sandbox.infra.network.UserChannelHandler.newUserId;

/**
 * 客户端临时代理
 */
@Slf4j
public class TempLinkHttpProxyHandler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(TempLinkHttpProxyHandler.class);

    private final SandboxProxyService sandboxProxyService;

    /**
     * 隧道建立前缓存的消息。CONNECT 之后由 ServerChannelHandler 跨 event loop drain，必须用并发队列。
     */
    private final Queue<Object> receivedLastMessagesWhenConnect = new ConcurrentLinkedQueue<>();

    /**
     * 建立隧道时绑定的 cmd 通道与用户 id（handler 每连接 new 一个，实例字段即连接级状态），channelInactive 清理用。
     */
    private Channel cmdChannel;
    private String userId;

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
                sendError(ctx, HttpResponseStatus.NOT_FOUND, "Invalid domain", msg);
                return;
            }
            cmdChannel = ProxyChannelManager.getCmdChannel(sandboxProxyBackend.getSandboxConfigKey());
            if (cmdChannel == null) {
                log.warn("no cmd channel {}", sandboxProxyBackend);
                // 目标机器客户端不在线；此时 codec 仍在 pipeline 中，错误响应可以正常编码发出
                sendError(ctx, HttpResponseStatus.BAD_GATEWAY, "Claw is offline", msg);
                return;
            }
            // 确认走隧道后才移除 codec：此后消息以裸 ByteBuf 进来
            ctx.channel().pipeline().remove("httpServerCodec");
            userId = newUserId();
            Channel userChannel = ctx.channel();
            // 先缓存首帧并发布 MESSAGE_QUEUE，再发布 userChannel 映射，最后发 CONNECT：
            // 保证 handleConnectMessage 无论何时到达都能读到队列，首帧请求不会永久滞留
            ByteBuf firstRequest = convertToByteBuf(request);
            receivedLastMessagesWhenConnect.offer(firstRequest);
            userChannel.attr(Constants.MESSAGE_QUEUE).set(receivedLastMessagesWhenConnect);
            // 用户连接到代理服务器时，设置用户连接不可读，等待代理后端服务器连接成功后再改变为可读状态
            userChannel.config().setOption(ChannelOption.AUTO_READ, false);
            ProxyChannelManager.addUserChannelToCmdChannel(cmdChannel, userId, userChannel);
            ProxyMessage proxyMessage = new ProxyMessage();
            proxyMessage.setType(ProxyMessage.TYPE_CONNECT);
            proxyMessage.setUri(userId);
            proxyMessage.setData((sandboxProxyBackend.getBackendHost() + ":" + sandboxProxyBackend.getBackendPort()).getBytes(CharsetUtil.US_ASCII));
            cmdChannel.writeAndFlush(proxyMessage);
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

    private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status, String message, Object msg) {
        ByteBuf content = Unpooled.copiedBuffer("{\"error\":{\"message\":\"" + message + "\"}}", CharsetUtil.UTF_8);
        FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content);
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        resp.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
        resp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
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
        Channel userChannel = ctx.channel();
        if (userId != null && cmdChannel != null) {
            // 用户连接断开：从 cmd 通道的 USER_CHANNELS 映射中移除，防止映射无界增长
            ProxyChannelManager.removeUserChannelFromCmdChannel(cmdChannel, userId);
            userChannel.attr(Constants.USER_ID).remove();
            userChannel.attr(Constants.MESSAGE_QUEUE).remove();
            Channel targetChannel = userChannel.attr(Constants.NEXT_CHANNEL).get();
            if (targetChannel != null && targetChannel.isActive()) {
                targetChannel.attr(Constants.NEXT_CHANNEL).remove();
                targetChannel.attr(Constants.CLIENT_KEY).remove();
                targetChannel.attr(Constants.USER_ID).remove();
                targetChannel.config().setOption(ChannelOption.AUTO_READ, true);
                // 通知客户端用户连接已断开，代理通道由客户端自行关闭（与 UserChannelHandler 的清理路径一致）
                ProxyMessage proxyMessage = new ProxyMessage();
                proxyMessage.setType(ProxyMessage.TYPE_DISCONNECT);
                proxyMessage.setUri(userId);
                targetChannel.writeAndFlush(proxyMessage);
            }
        }

        Object pending;
        while ((pending = receivedLastMessagesWhenConnect.poll()) != null) {
            ReferenceCountUtil.release(pending);
        }
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // 该端口没有 SSL；异常一律记日志并关连接，避免半死连接滞留占用 FD
        logger.warn("temp link channel error, close: {}", ctx.channel(), cause);
        ctx.close();
    }

    private static String extractDomainPrefix(String domain) {
        if (domain == null || domain.isEmpty()) {
            return null;
        }
        // 剥掉 ":端口" 后缀；Host 大小写不敏感，proxyKey 是小写 hex，统一转小写后再匹配
        int colonIndex = domain.indexOf(':');
        if (colonIndex != -1) {
            domain = domain.substring(0, colonIndex);
        }
        domain = domain.toLowerCase();
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
            // 写入请求行（方法名/版本号恒为 ASCII；uri 用 ISO-8859-1 与 Netty 头解码字节透传往返，不用平台默认字符集）
            buffer.writeBytes(request.method().name().getBytes(CharsetUtil.US_ASCII));
            buffer.writeByte(' ');
            buffer.writeBytes(request.uri().getBytes(CharsetUtil.ISO_8859_1));
            buffer.writeByte(' ');
            buffer.writeBytes(request.protocolVersion().text().getBytes(CharsetUtil.US_ASCII));
            buffer.writeBytes("\r\n".getBytes(CharsetUtil.US_ASCII));

            // 写入请求头（头名恒为 ASCII；头值 ISO-8859-1 字节透传往返）
            request.headers().forEach(entry -> {
                buffer.writeBytes(entry.getKey().getBytes(CharsetUtil.US_ASCII));
                buffer.writeBytes(": ".getBytes(CharsetUtil.US_ASCII));
                buffer.writeBytes(entry.getValue().getBytes(CharsetUtil.ISO_8859_1));
                buffer.writeBytes("\r\n".getBytes(CharsetUtil.US_ASCII));
            });

            // 写入空行
            buffer.writeBytes("\r\n".getBytes(CharsetUtil.US_ASCII));

            return buffer;
        } catch (Exception e) {
            buffer.release();
            throw e;
        }
    }
}
