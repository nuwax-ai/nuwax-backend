package com.xspaceagi.modelproxy.infra.proxy;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.xspaceagi.modelproxy.infra.service.TokenLogService;
import com.xspaceagi.system.sdk.common.TraceContext;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 后端代理处理器，用于处理后端服务器的响应并转发给客户端
 */
public class BackendProxyHandler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(BackendProxyHandler.class);

    // 使用ByteArrayOutputStream收集原始字节，避免UTF-8多字节字符被截断
    private final ByteArrayOutputStream responseBuffer = new ByteArrayOutputStream();
    private volatile Map<String, Object> responseContext;
    private final TokenLogService tokenLogService;
    private volatile boolean logPushed = false;

    public BackendProxyHandler(TokenLogService tokenLogService) {
        this.tokenLogService = tokenLogService;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        try {
            // 记录响应头
            if (msg instanceof HttpResponse responseHeader) {
                logPushed = false;
                logResponseHeader(responseHeader, ctx);
            }

            // 记录响应体
            if (msg instanceof HttpContent httpContent) {
                ByteBuf content = httpContent.content();
                if (content.isReadable()) {
                    // 读取字节并写入ByteArrayOutputStream，避免UTF-8多字节字符被截断
                    byte[] bytes = new byte[content.readableBytes()];
                    content.markReaderIndex();
                    content.readBytes(bytes);
                    content.resetReaderIndex();
                    responseBuffer.writeBytes(bytes);
                }

                // 如果是最后一个内容块，立即记录完整的响应体
                // 支持 HTTP/1.1 keep-alive 和 HTTP/2.0 等持久连接场景
                if (msg instanceof LastHttpContent) {
                    if (!logPushed) {
                        logResponseBody(ctx);
                        responseBuffer.reset(); // 清空buffer，为下一个请求做准备
                        logPushed = true;
                    }
                }
            }

            // 获取关联的客户端 channel
            Channel clientChannel = ctx.channel().attr(ModelProxyServer.nextChannelAttributeKey).get();
            if (clientChannel != null && clientChannel.isActive()) {
                clientChannel.writeAndFlush(msg);
            } else {
                logger.warn("Client channel is not active, discarding response");
                ReferenceCountUtil.release(msg);
            }
        } catch (Exception e) {
            logger.error("Error processing response", e);
            ReferenceCountUtil.release(msg);
        }
    }

    /**
     * 记录响应头信息
     */
    private void logResponseHeader(HttpResponse response, ChannelHandlerContext ctx) {
        try {
            HttpResponseStatus status = response.status();
            getResponseContext().put("status", String.valueOf(status.code()));
            logger.debug("Response Status: {}", status);
            TraceContext traceContext = ctx.channel().attr(ModelProxyServer.traceContextAttributeKey).get();
            if (traceContext != null && status.code() >= 300) {
                traceContext.setErrorMessage(status.reasonPhrase());
                traceContext.setErrorCode(String.valueOf(status.code()));
                traceContext.setError(true);
            }

            // 记录响应头
            logger.debug("Response Headers:");
            StringBuilder sb = new StringBuilder();
            response.headers().forEach(header -> {
                sb.append(header.getKey()).append(": ").append(header.getValue()).append("\n");
                logger.debug("  {}: {}", header.getKey(), header.getValue());
            });
            getResponseContext().put("responseHeaders", sb.toString());
        } catch (Exception e) {
            logger.error("Error logging response header", e);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        try {
            // 兜底：如果连接异常关闭且还有未记录的响应数据，记录响应体
            // 正常情况下应该在 LastHttpContent 时已经记录过了
            if (!logPushed) {
                logger.warn("Connection closed unexpectedly, logging incomplete response");
                logResponseBody(ctx);
                responseBuffer.reset();
            }
        } finally {
            // 后端连接关闭时，也关闭客户端连接
            Channel clientChannel = ctx.channel().attr(ModelProxyServer.nextChannelAttributeKey).get();
            if (clientChannel != null && clientChannel.isActive()) {
                clientChannel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            }
            ctx.fireChannelInactive();
        }
    }

    /**
     * 记录响应体信息
     */
    private void logResponseBody(ChannelHandlerContext ctx) {
        try {
            String accessKey = ctx.channel().attr(ModelProxyServer.accessKeyAttributeKey).get();
            String backendModel = ctx.channel().attr(ModelProxyServer.backendModelAttributeKey).get();
            TraceContext traceContext = ctx.channel().attr(ModelProxyServer.traceContextAttributeKey).get();
            getResponseContext().put("traceContext", traceContext);
            long responseTime = System.currentTimeMillis();
            getResponseContext().put("responseTime", String.valueOf(responseTime));
            logger.debug("[Model Proxy Response Body] AccessKey: {}, ResponseTime: {}ms", accessKey, responseTime);
            logger.debug("Backend Model: {}", backendModel);
            // 统一将字节转换为字符串，确保UTF-8编码正确
            String resBody = responseBuffer.toString(CharsetUtil.UTF_8);
            logger.debug("Response Body: {}", resBody);
            getResponseContext().put("responseBody", resBody);

            // 合并请求与响应
            Channel channel = ctx.channel().attr(ModelProxyServer.nextChannelAttributeKey).get();
            if (channel != null) {
                Map<String, String> requestContext = channel.attr(ModelProxyServer.logRequestQueueAttributeKey).get().poll();
                if (requestContext != null) {
                    getResponseContext().putAll(requestContext);
                }
            }
            String text = JSON.toJSONString(getResponseContext(), JSONWriter.Feature.LargeObject);
            logger.debug("responseContext: {}", text);
            tokenLogService.log(text);
            responseContext = null;
        } catch (Exception e) {
            responseContext = null;
            logger.error("Error logging response body", e);
        }
    }

    public Map<String, Object> getResponseContext() {
        if (responseContext == null) {
            responseContext = new HashMap<>();
        }
        return responseContext;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("Backend proxy handler exception", cause);
        ctx.close();
    }
}
