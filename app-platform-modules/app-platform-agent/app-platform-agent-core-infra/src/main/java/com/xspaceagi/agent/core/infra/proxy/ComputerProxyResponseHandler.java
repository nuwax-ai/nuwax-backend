package com.xspaceagi.agent.core.infra.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;

public class ComputerProxyResponseHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(ComputerProxyResponseHandler.class);

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        Channel nextChannel = ctx.channel().attr(ComputerProxyServerContainer.NEXT_CHANNEL).get();
        if (nextChannel != null) {
            // 对于共享链接，检查过期时间（拦截 WebSocket 内部传输）
            String shareKey = nextChannel.attr(ComputerProxyServerContainer.SHARE_KEY).get();
            if (shareKey != null) {
                Date expire = nextChannel.attr(ComputerProxyServerContainer.SHARE_EXPIRE).get();
                if (expire != null && expire.before(new Date())) {
                    // 共享链接已过期，发送消息并关闭连接
                    log.info("Computer share expired, closing link. shareKey={}, expire={}", shareKey, expire);
                    ReferenceCountUtil.release(msg);
                    sendShareExpiredMessage(nextChannel);
                    ctx.close();
                    return;
                }
            }

            if (msg instanceof DefaultHttpResponse) {
                DefaultHttpResponse response = (DefaultHttpResponse) msg;
                
                // 设置 token Cookie
                String token = nextChannel.attr(ComputerProxyServerContainer.tokenKey).get();
                if (token != null) {
                    response.headers().add("Set-Cookie", "ticket=" + token + "; Path=/; HttpOnly; SameSite=None; Secure");
                    nextChannel.attr(ComputerProxyServerContainer.tokenKey).set(null);
                }
                
                // 设置 shareKey Cookie（用于后续资源请求的权限验证）
                if (shareKey != null) {
                    Date expire = nextChannel.attr(ComputerProxyServerContainer.SHARE_EXPIRE).get();
                    String skCookieKey = nextChannel.attr(ComputerProxyServerContainer.SK_COOKIE_KEY).get();
                    String cookieValue = skCookieKey + shareKey + "; Path=/; SameSite=None; Secure";
                    if (expire != null) {
                        // 计算从当前时间到过期时间的秒数
                        long maxAge = (expire.getTime() - System.currentTimeMillis()) / 1000;
                        if (maxAge > 0) {
                            cookieValue += "; Max-Age=" + maxAge;
                        } else {
                            // 如果已经过期，设置 Max-Age=0 立即删除 cookie
                            cookieValue += "; Max-Age=0";
                        }
                    }
                    response.headers().add("Set-Cookie", cookieValue);
                }
            }

            // 转发时由目标 channel 的 pipeline（HttpObjectEncoder）在编码完成后释放，此处不再 release 避免重复释放
            nextChannel.writeAndFlush(msg);
        } else {
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        Channel nextChannel = ctx.channel().attr(ComputerProxyServerContainer.NEXT_CHANNEL).get();
        log.debug("Computer proxy target channel inactive, remote={}", ctx.channel().remoteAddress());
        if (nextChannel != null) {
            try {
                nextChannel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            } catch (Exception e) {
                log.debug("close peer channel failed", e);
            }
        }
        super.channelInactive(ctx);
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        Channel nextChannel = ctx.channel().attr(ComputerProxyServerContainer.NEXT_CHANNEL).get();
        if (nextChannel != null) {
            nextChannel.config().setOption(ChannelOption.AUTO_READ, ctx.channel().isWritable());
        }
        super.channelWritabilityChanged(ctx);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            log.debug("Computer proxy target idle timeout, closing channel"); // 目标侧空闲超时，双端关闭
            Channel peer = ctx.channel().attr(ComputerProxyServerContainer.NEXT_CHANNEL).get();
            ctx.close();
            if (peer != null) {
                peer.close();
            }
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.warn("Computer proxy response exception", cause);
        super.exceptionCaught(ctx, cause);
    }

    /**
     * 分享已过期，发送消息（WebSocket 文本帧）并关闭连接
     * 由于是透明代理，需要手动构造 WebSocket 帧字节
     */
    private void sendShareExpiredMessage(Channel channel) {
        try {
            byte[] messageBytes = ComputerProxyServerContainer.shareExpiredMessage.getBytes(CharsetUtil.UTF_8);
            
            // 构造 WebSocket 文本帧
            // FIN=1, RSV=0, Opcode=0x1 (文本帧)
            // MASK=0 (服务器发送不需要mask)
            ByteBuf textFrame = channel.alloc().buffer();
            textFrame.writeByte(0x81); // FIN=1, Opcode=0x1
            
            // Payload length (小于126，直接使用7位)
            if (messageBytes.length < 126) {
                textFrame.writeByte(messageBytes.length);
            } else if (messageBytes.length < 65536) {
                textFrame.writeByte(126);
                textFrame.writeShort(messageBytes.length);
            } else {
                textFrame.writeByte(127);
                textFrame.writeLong(messageBytes.length);
            }
            
            // Payload
            textFrame.writeBytes(messageBytes);
            
            // 发送文本帧
            channel.writeAndFlush(textFrame).addListener(future -> {
                // 构造并发送关闭帧
                ByteBuf closeFrame = channel.alloc().buffer();
                closeFrame.writeByte(0x88); // FIN=1, Opcode=0x8 (关闭帧)
                
                // 关闭帧payload: 状态码(2字节) + 原因(UTF-8字节)
                byte[] reasonBytes = ComputerProxyServerContainer.shareExpiredMessage.getBytes(CharsetUtil.UTF_8);
                int payloadLength = 2 + reasonBytes.length; // 状态码2字节 + 原因
                
                if (payloadLength < 126) {
                    closeFrame.writeByte(payloadLength);
                } else if (payloadLength < 65536) {
                    closeFrame.writeByte(126);
                    closeFrame.writeShort(payloadLength);
                } else {
                    closeFrame.writeByte(127);
                    closeFrame.writeLong(payloadLength);
                }
                
                // 状态码 1000 (正常关闭)
                closeFrame.writeShort(1000);
                // 原因
                closeFrame.writeBytes(reasonBytes);
                
                // 发送关闭帧并关闭连接
                channel.writeAndFlush(closeFrame).addListener(ChannelFutureListener.CLOSE);
            });
        } catch (Exception e) {
            // 如果发送失败，直接关闭
            log.debug("Failed to send WebSocket message, closing directly", e);
            channel.close();
        }
    }

}

