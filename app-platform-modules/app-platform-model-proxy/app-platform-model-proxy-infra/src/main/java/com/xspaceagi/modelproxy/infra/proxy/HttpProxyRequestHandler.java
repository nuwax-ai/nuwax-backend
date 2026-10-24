package com.xspaceagi.modelproxy.infra.proxy;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.modelproxy.sdk.service.IModelApiProxyConfigService;
import com.xspaceagi.modelproxy.sdk.service.dto.BackendModelDto;
import com.xspaceagi.system.sdk.common.TraceContext;
import com.xspaceagi.system.spec.cache.SimpleJvmHashCache;
import com.xspaceagi.system.spec.exception.BizException;
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
import java.security.InvalidParameterException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private TraceContext traceContext;
    private BackendModelDto backendModelDto;
    // 暂存当前请求，等请求数据接收完毕（LastHttpContent）后再连接后端并转发
    private DefaultHttpRequest storedRequest;
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

            logger.debug("uri {}, headers {}", request.uri(), request.headers());
            String traceId = request.headers().get("trace-id");
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

            try {
                // 获取代理配置并校验key是否合法或可用
                if (modelApiProxyConfigService == null) {
                    sendError(ctx, "ModelApiProxyConfigService not initialized", msg, HttpResponseStatus.INTERNAL_SERVER_ERROR);
                    return;
                }

                backendModelDto = modelApiProxyConfigService.getBackendModelConfig(apiKey, extractModelId(request.uri()), traceId);
                if (backendModelDto == null) {
                    sendError(ctx, "Invalid API Key", msg, HttpResponseStatus.FORBIDDEN);
                    return;
                }

                if (!backendModelDto.isEnabled()) {
                    sendError(ctx, "API Key is disabled", msg, HttpResponseStatus.FORBIDDEN);
                    return;
                }
            } catch (Exception e) {
                logger.error("Failed to get proxy config for apiKey: {}", apiKey, e);
                if (e instanceof BizException) {
                    sendError(ctx, e.getMessage(), msg, HttpResponseStatus.FORBIDDEN);
                } else {
                    sendError(ctx, "Failed to get proxy configuration", msg, HttpResponseStatus.INTERNAL_SERVER_ERROR);
                }
                return;
            }

            try {
                // 存储后端模型信息用于日志记录
                currentBackendModel = backendModelDto.getModelName();
                currentBackendUrl = backendModelDto.getBaseUrl();
                traceContext = backendModelDto.getTraceContext();

                // 临时修改为info打印日志观察
                URL url = new URL(backendModelDto.getBaseUrl());
                request.setUri(url.getPath() + replaceModelPrefix(request.uri(), url.getPath()));
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
                sendError(ctx, e.getMessage(), msg, HttpResponseStatus.INTERNAL_SERVER_ERROR);
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
            // 【调整】不立即建连/转发：暂存请求并入队，等请求数据接收完毕（LastHttpContent）后再统一连接后端并转发
            storedRequest = request;
            synchronized (receivedLastMsgsWhenConnect) {
                receivedLastMsgsWhenConnect.offer(msg);
            }
        } else {
            // 处理请求体内容 (HttpContent)：全部暂存，等接收完毕后再统一连接后端并转发
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

                if (msg instanceof LastHttpContent) {
                    // 请求数据接收完毕：解压→校验→按需改写请求体，再重构分块转发
                    // 默认原样转发原始字节（保证二进制/非 JSON body 无损）
                    byte[] bodyBytes = requestBodyBuffer.toByteArray();
                    // 【解压】按 Content-Encoding 解压请求体，使压缩(gzip/br/deflate)的 body 也能被正确解析与改写
                    // 无 Content-Encoding 时 DecompressUtils 原样返回，不影响明文 body
                    String contentEncoding = storedRequest != null ? storedRequest.headers().get(HttpHeaderNames.CONTENT_ENCODING) : null;
                    try {
                        bodyBytes = DecompressUtils.decompress(bodyBytes, contentEncoding);
                    } catch (Exception e) {
                        logger.warn("Failed to decompress request body (Content-Encoding: {}), keep original bytes", contentEncoding);
                    }
                    if (bodyBytes.length > 0) {
                        // 统一将字节转换为字符串，确保UTF-8编码正确
                        String requestBody = new String(bodyBytes, CharsetUtil.UTF_8);
                        try {
                            if (JSON.isValidObject(requestBody)) {
                                JSONObject jsonObject = JSON.parseObject(requestBody);
                                // 敏感词过滤只作用于内容字段（content/text/instructions 等），
                                // 不能对整个请求体盲替换：会改写 role/model 等协议字段导致上游反序列化失败
                                if (modelApiProxyConfigService.enabledSensitiveFilter()){
                                    // traceContext 为 null 的存量配置用 key 配置的租户兜底，避免 NPE 被误报成敏感词
                                    Long filterTenantId = traceContext != null ? traceContext.getTenantId() : backendModelDto.getTenantId();
                                    try {
                                        SensitiveContentFilter.filter(jsonObject, text -> modelApiProxyConfigService.checkAndReturnNewText(filterTenantId, text));
                                    } catch (InvalidParameterException e) {
                                        // 命中断开连接策略
                                        requestBodyBuffer.reset();
                                        sendError(ctx, "Your content contains sensitive words", msg, HttpResponseStatus.BAD_REQUEST);
                                        return;
                                    } catch (Exception e) {
                                        // 过滤基础设施故障不能伪装成内容违规，也不能静默放行
                                        logger.error("Sensitive filter failed, tenantId={}", filterTenantId, e);
                                        requestBodyBuffer.reset();
                                        sendError(ctx, "Sensitive filter error", msg, HttpResponseStatus.INTERNAL_SERVER_ERROR);
                                        return;
                                    }
                                }
                                if (jsonObject.getString("model") != null && !jsonObject.getString("model").equalsIgnoreCase(currentBackendModel)) {
                                    requestBodyBuffer.reset();
                                    sendError(ctx, "Invalid model", msg, HttpResponseStatus.BAD_REQUEST);
                                    return;
                                }

                                // ===== 请求体改写位置：在此按需修改 jsonObject =====
                                if ("Anthropic".equals(backendModelDto.getProtocol())) {
                                    requestBody = setLangAndClearClaudeKeyWords(jsonObject);
                                } else {
                                    // 重新序列化改写后的请求体（仅 JSON body 走此路径）
                                    requestBody = jsonObject.toJSONString();
                                }
                                bodyBytes = requestBody.getBytes(CharsetUtil.UTF_8);
                            }
                        } catch (Exception e) {
                            //ignore（解析失败则保持原始请求体不变）
                            logger.warn("Request body rewrite skipped: {}", e.getMessage(), e);
                        }
                        logRequestBody(requestBody);
                        ctx.channel().attr(ModelProxyServer.logRequestQueueAttributeKey).get().add(getRequestContext());
                        requestContext = null;
                    }

                    // 将（可能已改写的）完整 body 切块入队，再统一连接后端并转发到目标服务器
                    enqueueBodyChunks(bodyBytes);
                    requestBodyBuffer.reset(); // 清空buffer

                    ReferenceCountUtil.release(msg);
                    connectAndForward(ctx);
                    storedRequest = null;
                } else {
                    // 中间内容块，内容已拷贝进缓冲区，释放原始对象
                    ReferenceCountUtil.release(msg);
                }
            }
        }
    }

    private String setLangAndClearClaudeKeyWords(JSONObject jsonObject) {
        String requestBody = jsonObject.toJSONString();
        String lang = backendModelDto.getLang();
        if (lang == null) {
            lang = "zh-CN";
        }
        requestBody = requestBody.replace("IMPORTANT: this context may or may not be relevant to your tasks. ",
                "IMPORTANT: this context may or may not be relevant to your tasks. You should use  " + lang + " unless the user explicitly specifies another language for both the thinking (reasoning) process and the response.");
        requestBody = requestBody.replace("You are Claude Code, Anthropic's official CLI for Claude, running within the Claude Agent SDK.", "You are a highly versatile intelligent agent. You should use  " + lang + " unless the user explicitly specifies another language for both the thinking process and the response.");
        requestBody = requestBody.replace("Under no circumstances can you say you are Claude or Claude Code.", "");
        requestBody = requestBody.replace("Claude <noreply@anthropic.com>", "Nuwax <noreply@nuwax.com>");
        requestBody = requestBody.replace("Generated with [Claude Code](https://claude.com/claude-code)", "Generated with [Nuwax](https://nuwax.com)");
        requestBody = requestBody.replace("Claude Code", "Nuwax");
        requestBody = requestBody.replace("claude.ai/code", "nuwax.com");
        requestBody = requestBody.replace("IMPORTANT: Assist with authorized security testing, defensive security, CTF challenges, and educational contexts. Refuse requests for destructive techniques, DoS attacks, mass targeting, supply chain compromise, or detection evasion for malicious purposes. Dual-use security tools (C2 frameworks, credential testing, exploit development) require clear authorization context: pentesting engagements, CTF competitions, security research, or defensive use cases.", "");
        return requestBody;
    }

    private void sendError(ChannelHandlerContext ctx, String error, Object msg, HttpResponseStatus status) {
        FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
                Unpooled.copiedBuffer("{\"error\":{\"message\":\"" + error + "\"}}", CharsetUtil.UTF_8));
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        ctx.channel().writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
        if (targetChannel != null && targetChannel.isActive()) {
            targetChannel.close();
        }
        ReferenceCountUtil.release(msg);

        // 清理已暂存待发的消息，避免引用泄漏
        Object pending;
        while ((pending = receivedLastMsgsWhenConnect.poll()) != null) {
            ReferenceCountUtil.release(pending);
        }
    }

    /**
     * 将接收完毕的请求体切块入队（最后一块为 LastHttpContent）。
     * body 已完整缓冲，统一设置 Content-Length 并移除 chunked 编码。
     */
    private void enqueueBodyChunks(byte[] bytes) {
        int chunkSize = 65536; // 64KB per chunk
        synchronized (receivedLastMsgsWhenConnect) {
            if (bytes.length == 0) {
                receivedLastMsgsWhenConnect.offer(new DefaultLastHttpContent(Unpooled.EMPTY_BUFFER));
            } else {
                for (int i = 0; i < bytes.length; i += chunkSize) {
                    int len = Math.min(chunkSize, bytes.length - i);
                    ByteBuf chunk = Unpooled.copiedBuffer(bytes, i, len);
                    if (i + len >= bytes.length) {
                        receivedLastMsgsWhenConnect.offer(new DefaultLastHttpContent(chunk));
                    } else {
                        receivedLastMsgsWhenConnect.offer(new DefaultHttpContent(chunk));
                    }
                }
            }
        }
        if (storedRequest != null) {
            storedRequest.headers().remove(HttpHeaderNames.TRANSFER_ENCODING);
            // body 已解压为明文转发，移除原压缩编码声明，避免后端再次解压导致乱码
            storedRequest.headers().remove(HttpHeaderNames.CONTENT_ENCODING);
            storedRequest.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        }
    }

    /**
     * 连接后端并转发所有暂存的消息（HttpRequest + body 分块 + LastHttpContent）。
     */
    private void connectAndForward(ChannelHandlerContext ctx) {
        if (storedRequest == null) {
            logger.warn("No stored request to forward");
            return;
        }

        String backendUrlStr = currentBackendUrl;
        if (backendUrlStr == null) {
            sendError(ctx, "Backend URL not configured", storedRequest, HttpResponseStatus.INTERNAL_SERVER_ERROR);
            return;
        }

        String targetIp;
        String targetHost;
        int targetPort;
        String targetScheme;
        try {
            URL url = new URL(backendUrlStr);
            targetIp = resolveDns(url.getHost());
            targetScheme = url.getProtocol() + "://";
            targetPort = url.getPort();
            if (targetPort == -1) {
                targetPort = targetScheme.startsWith("https") ? 443 : 80;
            }
            targetHost = url.getHost();
        } catch (Exception e) {
            sendError(ctx, "Invalid backend URL: " + e.getMessage(), storedRequest, HttpResponseStatus.INTERNAL_SERVER_ERROR);
            return;
        }

        // 本次请求的目标 authority，用于与已建连接的绑定信息比对
        String boundAuthority = targetScheme + targetHost + ":" + targetPort;

        // 已有连接健康则复用：直接转发暂存队列。
        // ★复用前必须校验连接绑定的 authority 与本次目标一致：
        //   HTTPS 的 SNI 在建连时已按首个目标 host 写死（ModelProxyServer#createHttpsClientBootstrap），
        //   跨 host 复用会把请求物理上发到另一家厂商，且改写 Host 头也救不回来（表现为间歇性 404）。
        if (targetChannel != null && isTargetChannelHealthy()) {
            String bound = targetChannel.attr(ModelProxyServer.boundAuthorityAttributeKey).get();
            if (boundAuthority.equals(bound)) {
                // 存储请求上下文信息，用于响应日志记录
                targetChannel.attr(ModelProxyServer.accessKeyAttributeKey).set(currentAccessKey);
                targetChannel.attr(ModelProxyServer.backendModelAttributeKey).set(currentBackendModel);
                targetChannel.attr(ModelProxyServer.backendUrlAttributeKey).set(currentBackendUrl);
                targetChannel.attr(ModelProxyServer.traceContextAttributeKey).set(traceContext);
                synchronized (receivedLastMsgsWhenConnect) {
                    Object msg0;
                    while ((msg0 = receivedLastMsgsWhenConnect.poll()) != null) {
                        targetChannel.writeAndFlush(msg0);
                    }
                }
                return;
            }
            // 绑定的 host 与本次目标不一致：这条连接在协议层只能服务原 host，必须关闭重建
            logger.warn("targetChannel bound to [{}] but current request targets [{}], closing and reconnecting. channel {}",
                    bound, boundAuthority, ctx.channel());
            Channel mismatched = targetChannel;
            // ★先解绑、再关闭：这条被弃用的连接在建连成功时把自己的 nextChannelAttributeKey 指向了当前客户端连接
            //   （见下方建连回调），BackendProxyHandler#channelInactive 会顺着该引用向客户端补一个 CLOSE——
            //   不解绑就会把客户端连接一并关掉，而客户端对此没有任何过错。
            //   后端连接本身仍必须关闭：不关会泄漏连接（引用已丢弃、对端要等闲置超时），且它的迟到响应会写回本客户端。
            mismatched.attr(ModelProxyServer.nextChannelAttributeKey).set(null);
            cleanupTargetChannel();
            mismatched.close();
        } else if (targetChannel != null) {
            // 清理失效连接，建立新连接
            logger.warn("Detected stale targetChannel, cleaning up and reconnecting");
            cleanupTargetChannel();
        }

        // 在异步连接前捕获所需值，避免回调中访问 storedRequest（调用方在 connectAndForward 返回后会置 null）
        String capturedHost = storedRequest.headers().get("Host");
        String capturedConnection = storedRequest.headers().get("Connection");
        String capturedUpgrade = storedRequest.headers().get("Upgrade");

        ctx.channel().config().setOption(ChannelOption.AUTO_READ, false);
        Bootstrap clientBootstrap;
        if (targetScheme.equals("https://")) {
            clientBootstrap = modelProxyServer.createHttpsClientBootstrap(targetHost, targetPort);
        } else {
            clientBootstrap = modelProxyServer.createClientBootstrap();
        }
        logger.debug("Connecting to backend {}:{} for host {}, channel {}", targetIp, targetPort, capturedHost, ctx.channel());
        clientBootstrap.connect(targetIp, targetPort).addListener((ChannelFutureListener) future -> {
            // 连接后端服务器成功
            if (future.isSuccess()) {
                synchronized (receivedLastMsgsWhenConnect) {
                    targetChannel = future.channel();
                    // 记录本连接绑定的目标 authority，后续复用前必须比对（见上方复用分支）
                    targetChannel.attr(ModelProxyServer.boundAuthorityAttributeKey).set(boundAuthority);
                    targetChannel.attr(ModelProxyServer.nextChannelAttributeKey).set(ctx.channel());
                    ctx.channel().attr(ModelProxyServer.nextChannelAttributeKey).set(targetChannel);

                    // 存储请求上下文信息，用于响应日志记录
                    targetChannel.attr(ModelProxyServer.accessKeyAttributeKey).set(currentAccessKey);
                    targetChannel.attr(ModelProxyServer.backendModelAttributeKey).set(currentBackendModel);
                    targetChannel.attr(ModelProxyServer.backendUrlAttributeKey).set(currentBackendUrl);
                    targetChannel.attr(ModelProxyServer.traceContextAttributeKey).set(traceContext);

                    // 添加关闭监听器，当 targetChannel 关闭时自动清理
                    targetChannel.closeFuture().addListener((ChannelFutureListener) closeFuture -> {
                        logger.info("Backend connection closed for host {}, cleaning up targetChannel, channel {}", capturedHost, ctx.channel());
                        synchronized (HttpProxyRequestHandler.this) {
                            if (targetChannel == closeFuture.channel()) {
                                targetChannel = null;
                            }
                        }
                    });
                }
                // 转发所有暂存的消息（HttpRequest + body 分块 + LastHttpContent）
                synchronized (receivedLastMsgsWhenConnect) {
                    Object msg0;
                    while ((msg0 = receivedLastMsgsWhenConnect.poll()) != null) {
                        future.channel().writeAndFlush(msg0);
                    }
                }
                // 协议升级走原生通道，支持websocket
                if ((capturedConnection != null && capturedConnection.equals("Upgrade")) || (capturedUpgrade != null && capturedUpgrade.equals("websocket"))) {
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
                logger.warn("Backend connection failed for host {}, cleaning up targetChannel, channel {}", capturedHost, ctx.channel());
                ctx.channel().close();
            }
        });
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
        Object pending;
        while ((pending = receivedLastMsgsWhenConnect.poll()) != null) {
            ReferenceCountUtil.release(pending);
        }
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (cause.getCause() != null && (cause.getCause() instanceof SSLHandshakeException)) {
            logger.warn("SSLHandshakeException: {}", cause.getCause().getMessage());
            ctx.close();
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

    /**
     * 从URI中提取model后面的数字
     * 例如: /api/proxy/model/1312321/v1/xxxx → 1312321
     */
    public static Long extractModelId(String uri) {
        if (uri == null) {
            return null;
        }
        Pattern pattern = Pattern.compile("/api/proxy/model/(\\d+)");
        Matcher matcher = pattern.matcher(uri);
        if (matcher.find()) {
            return Long.parseLong(matcher.group(1));
        }
        return null;
    }

    /**
     * 替换URI中的 /api/proxy/model/{数字} 部分
     * 例如: /api/proxy/model/1312321/v1/xxxx → /v1/xxxx
     * /api/proxy/model/v1/xxxx       → /v1/xxxx
     */
    public static String replaceModelPrefix(String uri, String targetBaseUrl) {
        if (hasVersion(targetBaseUrl)) {
            return uri.replaceAll("/api/proxy/model(/\\d+)?/v1", "").replaceAll("/api/proxy/model", "");
        }
        return uri.replaceAll("/api/proxy/model(/\\d+)?", "");
    }

    public static boolean hasVersion(String url) {
        String ver = extractVersionFromUrl(url);
        if (ver != null) {
            return true;
        }
        String regex = "(/([^\\s\\.]*)((-v|_v|version|_ver|-ver)\\d+))";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(url);

        if (matcher.find()) {
            return true;
        } else {
            return false;
        }
    }

    public static String extractVersionFromUrl(String url) {
        // Define regex pattern to match version information in URL (e.g., v1, v2, v3)
        String regex = "/(v\\d+)";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(url);

        if (matcher.find()) {
            return matcher.group(1); // Return the matched version information
        } else {
            return null; // Return null if no match is found
        }
    }
}
