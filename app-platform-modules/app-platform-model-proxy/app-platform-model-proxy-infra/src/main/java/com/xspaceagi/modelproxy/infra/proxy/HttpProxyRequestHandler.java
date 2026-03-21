package com.xspaceagi.modelproxy.infra.proxy;

import com.xspaceagi.modelproxy.sdk.service.IModelApiProxyConfigService;
import com.xspaceagi.modelproxy.sdk.service.dto.BackendModelDto;
import com.xspaceagi.system.spec.cache.SimpleJvmHashCache;
import com.xspaceagi.system.spec.utils.MD5;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLHandshakeException;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;

public class HttpProxyRequestHandler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(HttpProxyRequestHandler.class);

    private final IModelApiProxyConfigService modelApiProxyConfigService;

    private final Queue<Object> receivedLastMsgsWhenConnect = new LinkedList<>();
    private volatile Channel targetChannel;

    // 使用ByteArrayOutputStream收集原始字节，避免UTF-8多字节字符被截断
    private final ByteArrayOutputStream requestBodyBuffer = new ByteArrayOutputStream();
    private String currentAccessKey;
    private String currentBackendModel;
    private String currentBackendUrl;
    private volatile Map<String, String> requestContext;
    private final ModelProxyServer modelProxyServer;

    public HttpProxyRequestHandler(ModelProxyServer modelProxyServer, IModelApiProxyConfigService service) {
        this.modelProxyServer = modelProxyServer;
        this.modelApiProxyConfigService = service;
    }

    /**
     * 检查 targetChannel 是否健康
     */
    private boolean isTargetChannelHealthy() {
        if (targetChannel == null) {
            return false;
        }
        return targetChannel.isActive() && targetChannel.isOpen();
    }

    /**
     * 清理失效的 targetChannel
     */
    private void cleanupTargetChannel() {
        if (targetChannel != null) {
            logger.warn("Cleaning up stale targetChannel: {}, active: {}, open: {}",
                    targetChannel, targetChannel.isActive(), targetChannel.isOpen());
            targetChannel = null;
        }
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
        if (msg instanceof HttpRequest) {
            DefaultHttpRequest request = (DefaultHttpRequest) msg;
            String host = request.headers().get("Host");

            // 使用局部变量存储目标连接信息，避免修改全局共享的 httpReverseProxyInfo
            String targetIp;
            String targetHost;
            int targetPort;
            String targetScheme;
            logger.debug("uri {}, headers {}", request.uri(), request.headers());
            //从header里通过x-api-key或Authorization获取apiKey
            String apiKey = request.headers().get("x-api-key");
            String xApiKey = apiKey;
            String authorization = request.headers().get("Authorization");
            if (apiKey == null) {
                if (authorization != null) {
                    apiKey = authorization.replace("Bearer ", "").trim();
                }
            }

            currentAccessKey = apiKey != null ? maskApiKey(apiKey) : "unknown";

            // 记录请求基本信息
            logRequestBasicInfo(request, currentAccessKey);

            BackendModelDto backendModelDto;
            try {
                // 获取代理配置并校验key是否合法或可用
                if (modelApiProxyConfigService == null) {
                    sendError(ctx, "ModelApiProxyConfigService not initialized", msg);
                    return;
                }

                backendModelDto = modelApiProxyConfigService.getBackendModelConfig(apiKey);
                if (backendModelDto == null) {
                    sendError(ctx, "Invalid API Key", msg);
                    return;
                }

                if (!backendModelDto.isEnabled()) {
                    sendError(ctx, "API Key is disabled", msg);
                    return;
                }
            } catch (Exception e) {
                logger.error("Failed to get proxy config for apiKey: {}", apiKey, e);
                sendError(ctx, "Failed to get proxy configuration", msg);
                return;
            }

            try {
                // 存储后端模型信息用于日志记录
                currentBackendModel = backendModelDto.getModelName();
                currentBackendUrl = backendModelDto.getBaseUrl();

                // 临时修改为info打印日志观察
                URL url = new URL(backendModelDto.getBaseUrl());
                String ip = resolveDns(url.getHost());
                String newScheme = url.getProtocol() + "://";
                int port = url.getPort();
                if (port == -1) {
                    if (newScheme.startsWith("https")) {
                        port = 443;
                    } else {
                        port = 80;
                    }
                }
                // 使用局部变量存储目标连接信息，避免污染全局对象
                targetScheme = newScheme;
                targetIp = ip;
                targetHost = url.getHost();
                targetPort = port;
                request.setUri(url.getPath() + request.uri().replace("/api/proxy/model", ""));
                request.headers().set("Host", url.getHost());
                //认证信息替换
                if (StringUtils.isNotBlank(authorization)) {
                    request.headers().set("Authorization", "Bearer " + backendModelDto.getApiKey());
                }
                if (StringUtils.isNotBlank(xApiKey)) {
                    request.headers().set("x-api-key", backendModelDto.getApiKey());
                }
                getRequestContext().put("backendApiKey", maskApiKey(backendModelDto.getApiKey()));
                getRequestContext().put("backendApiKeyMd5", MD5.MD5Encode(backendModelDto.getApiKey()));
                getRequestContext().put("apiProtocol", backendModelDto.getProtocol());
                getRequestContext().put("apiScope", backendModelDto.getScope());
                getRequestContext().put("backendBaseUrl", backendModelDto.getBaseUrl());
                getRequestContext().put("modelId", backendModelDto.getModelId() == null ? null : backendModelDto.getModelId().toString());
                getRequestContext().put("tenantId", backendModelDto.getTenantId() == null ? "1" : backendModelDto.getTenantId().toString());
                getRequestContext().put("userId", backendModelDto.getUserId() == null ? null : backendModelDto.getUserId().toString());
                getRequestContext().put("userName", backendModelDto.getUserName());
                getRequestContext().put("conversationId", backendModelDto.getConversationId());
                getRequestContext().put("requestId", backendModelDto.getRequestId());
            } catch (Exception e) {
                sendError(ctx, e.getMessage(), msg);
                return;
            }


            String remoteIp = ((InetSocketAddress) ctx.channel().remoteAddress()).getAddress().getHostAddress();
            if (request.headers().get("X-Forwarded-For") == null) {
                request.headers().set("X-Forwarded-For", remoteIp);
            } else {
                request.headers().set("X-Forwarded-For", request.headers().get("X-Forwarded-For") + "," + remoteIp);
            }
            if (request.headers().get("X-Real-IP") == null) {
                request.headers().set("X-Real-IP", remoteIp);
            }
            // 检查 targetChannel 是否健康，如果不健康则清理并重新连接
            if (targetChannel == null || !isTargetChannelHealthy()) {
                if (targetChannel != null && !isTargetChannelHealthy()) {
                    logger.warn("Detected stale targetChannel for host {}, cleaning up and reconnecting", host);
                    cleanupTargetChannel();
                }
                ctx.channel().config().setOption(ChannelOption.AUTO_READ, false);
                Bootstrap clientBootstrap;
                if (targetScheme.equals("https://")) {
                    clientBootstrap = modelProxyServer.createHttpsClientBootstrap(targetHost, targetPort);
                } else {
                    clientBootstrap = modelProxyServer.createClientBootstrap();
                }
                logger.debug("Connecting to backend {}:{} for host {}, channel {}", targetIp, targetPort, host, ctx.channel());
                clientBootstrap.connect(targetIp, targetPort).addListener((ChannelFutureListener) future -> {
                    // 连接后端服务器成功
                    if (future.isSuccess()) {
                        synchronized (receivedLastMsgsWhenConnect) {
                            targetChannel = future.channel();
                            targetChannel.attr(ModelProxyServer.nextChannelAttributeKey).set(ctx.channel());
                            ctx.channel().attr(ModelProxyServer.nextChannelAttributeKey).set(targetChannel);

                            // 存储请求上下文信息，用于响应日志记录
                            targetChannel.attr(ModelProxyServer.accessKeyAttributeKey).set(currentAccessKey);
                            targetChannel.attr(ModelProxyServer.backendModelAttributeKey).set(currentBackendModel);
                            targetChannel.attr(ModelProxyServer.backendUrlAttributeKey).set(currentBackendUrl);

                            // 添加关闭监听器，当 targetChannel 关闭时自动清理
                            targetChannel.closeFuture().addListener((ChannelFutureListener) closeFuture -> {
                                logger.info("Backend connection closed for host {}, cleaning up targetChannel, channel {}", host, ctx.channel());
                                synchronized (HttpProxyRequestHandler.this) {
                                    if (targetChannel == closeFuture.channel()) {
                                        targetChannel = null;
                                    }
                                }
                            });
                        }
                        future.channel().writeAndFlush(msg);
                        synchronized (receivedLastMsgsWhenConnect) {
                            Object msg0;
                            while ((msg0 = receivedLastMsgsWhenConnect.poll()) != null) {
                                future.channel().writeAndFlush(msg0);
                            }
                        }
                        String connection = request.headers().get("Connection");
                        String upgrade = request.headers().get("Upgrade");
                        // 协议升级走原生通道，支持websocket
                        if ((connection != null && connection.equals("Upgrade")) || (upgrade != null && upgrade.equals("websocket"))) {
                            ctx.channel().pipeline().remove("httpServerCodec");
                            future.channel().pipeline().remove("httpClientCodec");
                        }
                        ctx.channel().config().setOption(ChannelOption.AUTO_READ, true);
                    } else {
                        //清理回收receivedLastMsgsWhenConnect中的msg
                        synchronized (receivedLastMsgsWhenConnect) {
                            Object msg0;
                            while ((msg0 = receivedLastMsgsWhenConnect.poll()) != null) {
                                ReferenceCountUtil.release(msg0);
                            }
                        }
                        logger.warn("Backend connection failed for host {}, cleaning up targetChannel, channel {}", host, ctx.channel());
                        ctx.channel().close();
                    }
                });
            } else {
                // targetChannel 存在且健康，直接使用
                if (!isTargetChannelHealthy()) {
                    logger.warn("targetChannel became unhealthy, closing client connection");
                    ctx.channel().close();
                    ReferenceCountUtil.release(msg);
                    return;
                }

                // 存储请求上下文信息，用于响应日志记录
                targetChannel.attr(ModelProxyServer.accessKeyAttributeKey).set(currentAccessKey);
                targetChannel.attr(ModelProxyServer.backendModelAttributeKey).set(currentBackendModel);
                targetChannel.attr(ModelProxyServer.backendUrlAttributeKey).set(currentBackendUrl);

                targetChannel.writeAndFlush(request);
            }
        } else {
            // 处理请求体内容 (HttpContent)
            if (msg instanceof HttpContent httpContent) {
                ByteBuf content = httpContent.content();
                if (content.isReadable()) {
                    // 读取字节并写入ByteArrayOutputStream，避免UTF-8多字节字符被截断
                    byte[] bytes = new byte[content.readableBytes()];
                    content.markReaderIndex();
                    content.readBytes(bytes);
                    content.resetReaderIndex();
                    requestBodyBuffer.writeBytes(bytes);
                }

                // 如果是最后一个内容块，记录完整的请求体
                if (msg instanceof LastHttpContent) {
                    if (requestBodyBuffer.size() > 0) {
                        // 统一将字节转换为字符串，确保UTF-8编码正确
                        logRequestBody(requestBodyBuffer.toString(CharsetUtil.UTF_8));
                        ctx.channel().attr(ModelProxyServer.logRequestQueueAttributeKey).get().add(getRequestContext());
                        requestContext = null;
                        requestBodyBuffer.reset(); // 清空buffer
                    }
                }
            }

            if (targetChannel == null) {
                synchronized (receivedLastMsgsWhenConnect) {
                    if (targetChannel == null || !isTargetChannelHealthy()) {
                        receivedLastMsgsWhenConnect.offer(msg);
                    } else {
                        targetChannel.writeAndFlush(msg);
                    }
                }
            } else {
                if (isTargetChannelHealthy()) {
                    targetChannel.writeAndFlush(msg);
                } else {
                    cleanupTargetChannel();
                    ctx.channel().close();
                    ReferenceCountUtil.release(msg);
                }
            }
        }
    }

    private void sendError(ChannelHandlerContext ctx, String mcp_id_error, Object msg) {
        FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND,
                Unpooled.copiedBuffer("{\"error\":{\"message\":\"" + mcp_id_error + "\"}}", CharsetUtil.UTF_8));
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        ctx.channel().writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
        ReferenceCountUtil.release(msg);
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        if (targetChannel != null) {
            targetChannel.config().setOption(ChannelOption.AUTO_READ, ctx.channel().isWritable());
        }
        super.channelWritabilityChanged(ctx);
    }


    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        ctx.channel().attr(ModelProxyServer.logRequestQueueAttributeKey).set(new LinkedList<>());
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        ctx.channel().attr(ModelProxyServer.logRequestQueueAttributeKey).get().clear();
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

    private static String resolveDns(String domain) {
        Object dns = SimpleJvmHashCache.getHash("dns", domain);
        if (dns != null) {
            return dns.toString();
        }
        String ip = domain;
        try {
            InetAddress address = InetAddress.getByName(domain);
            ip = address.getHostAddress();
        } catch (Exception ignored) {
        }
        SimpleJvmHashCache.putHash("dns", domain, ip, SimpleJvmHashCache.DEFAULT_EXPIRE_AFTER_SECONDS);
        return ip;
    }

    /**
     * 记录请求基本信息
     */
    private void logRequestBasicInfo(HttpRequest request, String accessKey) {
        try {
            getRequestContext().put("requestTime", String.valueOf(System.currentTimeMillis()));
            getRequestContext().put("userAccessKey", accessKey);
            logger.debug("[Model Proxy Request] AccessKey: {}", accessKey);
            logger.debug("Request Method: {}", request.method().name());
            logger.debug("Request URI: {}", request.uri());

            StringBuilder sb = new StringBuilder();
            // 记录请求头
            logger.debug("Request Headers:");
            request.headers().forEach(header -> {
                // 隐藏敏感的 Authorization 头信息
                if ("Authorization".equalsIgnoreCase(header.getKey()) ||
                        "x-api-key".equalsIgnoreCase(header.getKey())) {
                    logger.debug("  {}: {}", header.getKey(), maskApiKey(header.getValue()));
                    sb.append(header.getKey()).append(": ").append(maskApiKey(header.getValue())).append("\n");
                } else {
                    logger.debug("  {}: {}", header.getKey(), header.getValue());
                    sb.append(header.getKey()).append(": ").append(header.getValue()).append("\n");
                }
            });
            getRequestContext().put("requestHeaders", sb.toString());
        } catch (Exception e) {
            logger.error("Error logging request basic info", e);
        }
    }

    /**
     * 记录请求体信息
     */
    private void logRequestBody(String requestBody) {
        try {
            getRequestContext().put("model", currentBackendModel);
            getRequestContext().put("requestBody", requestBody);
            getRequestContext().put("backendUrl", currentBackendUrl);
            logger.debug("Backend URL: {}", currentBackendUrl);
            logger.debug("Request Body: {}", requestBody);
        } catch (Exception e) {
            logger.error("Error logging request body", e);
        }
    }

    /**
     * 隐藏API Key的敏感信息，只显示前4位和后4位
     */
    private static String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() <= 8) {
            return "***";
        }
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }

    private Map<String, String> getRequestContext() {
        if (requestContext == null) {
            requestContext = new HashMap<>();
        }
        return requestContext;
    }
}
