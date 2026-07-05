package com.xspaceagi.agent.core.infra.proxy;

import com.xspaceagi.agent.core.adapter.dto.ConversationDto;
import com.xspaceagi.agent.core.infra.rpc.SandboxServerConfigService;
import com.xspaceagi.agent.core.infra.rpc.UserShareRpcService;
import com.xspaceagi.agent.core.infra.rpc.dto.SandboxServerConfig;
import com.xspaceagi.custompage.sdk.ICustomPageRpcService;
import com.xspaceagi.custompage.sdk.dto.CustomPageDto;
import com.xspaceagi.sandbox.spec.enums.SandboxScopeEnum;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.AuthService;
import com.xspaceagi.system.application.service.TenantConfigApplicationService;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.sdk.service.dto.UserShareDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Computer resource transparent proxy, supports websocket protocol upgrade
 * Supports proxy for paths like desktop, audio, ime, etc.
 */
public class ComputerProxyRequestHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(ComputerProxyRequestHandler.class);

    private static final Pattern DESKTOP_PATTERN = Pattern.compile("/computer/desktop/(\\d+)/(.*)");
    private static final Pattern AUDIO_PATTERN = Pattern.compile("/computer/audio/(\\d+)/(.*)");
    private static final Pattern IME_PATTERN = Pattern.compile("/computer/ime/(\\d+)/(.*)");
    private static final Pattern TERMINAL_PATTERN = Pattern.compile("/computer/terminal/(\\d+)/(.*)");

    private final SandboxServerConfigService sandboxServerConfigService;
    private final UserShareRpcService userShareRpcService;
    private final AuthService authService;
    private final TenantConfigApplicationService tenantConfigApplicationService;
    private final SpacePermissionService spacePermissionService;
    private final ICustomPageRpcService iCustomPageRpcService;
    private final Bootstrap httpClientBootstrap;
    private final Bootstrap httpsClientBootstrap;
    private final int pendingMax;

    // Pending forward queue (cache requests/frames before connection established to prevent packet loss)
    private final Queue<Object> pendingMessages = new LinkedList<>();

    private Channel targetChannel;
    private String targetHost;
    private int targetPort;
    private String targetOrigin;

    public ComputerProxyRequestHandler(SandboxServerConfigService sandboxServerConfigService,
                                       UserShareRpcService userShareRpcService,
                                       AuthService authService,
                                       TenantConfigApplicationService tenantConfigApplicationService,
                                       ICustomPageRpcService iCustomPageRpcService,
                                       SpacePermissionService spacePermissionService,
                                       Bootstrap httpClientBootstrap,
                                       Bootstrap httpsClientBootstrap,
                                       int pendingMax) {
        this.sandboxServerConfigService = sandboxServerConfigService;
        this.userShareRpcService = userShareRpcService;
        this.authService = authService;
        this.tenantConfigApplicationService = tenantConfigApplicationService;
        this.iCustomPageRpcService = iCustomPageRpcService;
        this.spacePermissionService = spacePermissionService;
        this.httpClientBootstrap = httpClientBootstrap;
        this.httpsClientBootstrap = httpsClientBootstrap;
        this.pendingMax = pendingMax;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof HttpRequest request) { // First HTTP request for authentication and handshake
            String uri = request.uri();

            Long cId = extractCId(uri);
            if (cId == null) {
                ReferenceCountUtil.release(msg);
                writeError(ctx, HttpResponseStatus.BAD_REQUEST, "cId is missing or format error");
                return;
            }
            Long tenantId;
            Long computerUserId = null;
            Long projectUserId = null;
            Long projectSpaceId = null;
            Long projectSandboxId = null;
            String targetUrl = null;
            // Get target server information based on cId
            SandboxServerConfig.SandboxServer sandboxServer = null;
            try {
                sandboxServer = sandboxServerConfigService.selectServer(cId);
                targetUrl = sandboxServer.getServerVncUrl();
                ConversationDto currentConversation = sandboxServer.getCurrentConversation();
                if (currentConversation == null) {
                    ReferenceCountUtil.release(msg);
                    writeError(ctx, HttpResponseStatus.BAD_GATEWAY, "Current session not bound to sandbox server");
                    return;
                }
                // Get creator ID of current session
                computerUserId = currentConversation.getUserId();

                // Set RequestContext, used for multi-tenant user_share table query
                tenantId = currentConversation.getTenantId();
            } catch (Exception e) {
                //兜底检查是不是网页应用
                CustomPageDto customPageDto = TenantFunctions.callWithIgnoreCheck(() -> iCustomPageRpcService.queryDetail(cId));
                if (customPageDto == null) {
                    log.warn("Failed to get sandbox server, cId={}", cId, e);
                    ReferenceCountUtil.release(msg);
                    writeError(ctx, HttpResponseStatus.BAD_REQUEST, "Failed to get sandbox server: " + e.getMessage());
                    return;
                }
                tenantId = customPageDto.getTenantId();
                projectSpaceId = customPageDto.getSpaceId();
                projectUserId = customPageDto.getCreatorId();
                projectSandboxId = customPageDto.getSandboxId();
            }

            RequestContext<?> requestContext = null;
            try {
                if (tenantId != null) {
                    TenantConfigDto tenantConfig = tenantConfigApplicationService.getTenantConfig(tenantId);
                    requestContext = new RequestContext<>();
                    requestContext.setTenantId(tenantId);
                    requestContext.setTenantConfig(tenantConfig);
                    RequestContext.set(requestContext);
                }

                if (computerUserId == null) {
                    if (projectSandboxId == null) {
                        sandboxServer = new SandboxServerConfig.SandboxServer();
                        sandboxServer.setScope(SandboxScopeEnum.GLOBAL);
                        targetUrl = sandboxServerConfigService.getDefaultPageAppVncUrl();
                    } else {
                        sandboxServer = sandboxServerConfigService.selectServer((TenantConfigDto) RequestContext.get().getTenantConfig(), projectUserId, projectSandboxId.toString());
                        targetUrl = sandboxServer.getServerVncUrl();
                    }
                    computerUserId = projectUserId;
                }

                if (StringUtils.isBlank(targetUrl)) {
                    ReferenceCountUtil.release(msg);
                    writeError(ctx, HttpResponseStatus.BAD_REQUEST, "Sandbox server VNC address not configured");
                    return;
                }

                // Get shareKey from URL parameter or Cookie
                String shareKey = getShareKeyFromUri(uri);
                String skCookieKey = "vnc_sk_" + computerUserId + "=";
                if (shareKey == null) {
                    shareKey = getShareKeyFromCookie(request, skCookieKey);
                }

                UserShareDto userShare = null;
                if (shareKey != null) {
                    userShare = userShareRpcService.getUserShare(shareKey, true);
                }

                // If it is a shared link, store shareKey and expire in channel attribute, used to set Cookie in response
                if (userShare != null
                        && userShare.getType() == UserShareDto.UserShareType.DESKTOP
                        && userShare.getUserId().equals(computerUserId)
                        && userShare.getExpire().after(new Date())) {
                    ctx.channel().attr(ComputerProxyServerContainer.SHARE_KEY).set(shareKey);
                    ctx.channel().attr(ComputerProxyServerContainer.SHARE_EXPIRE).set(userShare.getExpire());
                    ctx.channel().attr(ComputerProxyServerContainer.SK_COOKIE_KEY).set(skCookieKey);
                } else {// Non-shared link
                    if (!allow(computerUserId, projectSpaceId, request, ctx)) {
                        ReferenceCountUtil.release(msg);
                        writeError(ctx, HttpResponseStatus.FORBIDDEN, "No permission to access current user resource");
                        return;
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to get sandbox server, cId={}", cId, e);
                ReferenceCountUtil.release(msg);
                writeError(ctx, HttpResponseStatus.BAD_REQUEST, "Failed to get sandbox server: " + e.getMessage());
                return;
            } finally {
                // Clean up RequestContext
                if (requestContext != null) {
                    RequestContext.remove();
                }
            }

            // Parse target URL
            URI targetUri = URI.create(targetUrl);
            String targetScheme = StringUtils.defaultIfBlank(targetUri.getScheme(), "http");
            this.targetHost = targetUri.getHost();
            this.targetPort = targetUri.getPort();
            if (this.targetPort <= 0) {
                this.targetPort = "https".equalsIgnoreCase(targetScheme) ? 443 : 80;
            }
            this.targetOrigin = ComputerProxyServerContainer.buildOrigin(targetScheme, this.targetHost, this.targetPort);

            uri = rewriteUri(uri, computerUserId, sandboxServer, projectSpaceId != null);
            request.setUri(uri);

            request.headers().set(HttpHeaderNames.HOST, this.targetHost + ":" + this.targetPort);
            addForwardHeaders(ctx, request);
            // Add x-api-key header to all requests forwarded to sandbox server
            request.headers().set("x-api-key", sandboxServer.getServerApiKey() == null ? "" : sandboxServer.getServerApiKey());
            log.debug("Computer proxy request uri={} host={} origin={}", uri,
                    request.headers().get(HttpHeaderNames.HOST),
                    request.headers().get(HttpHeaderNames.ORIGIN));

            if (targetChannel == null) { // First time establishing connection to target
                ctx.channel().config().setOption(ChannelOption.AUTO_READ, false);
                boolean enableSsl = "https".equalsIgnoreCase(targetUri.getScheme());
                Bootstrap clientBootstrap = enableSsl ? httpsClientBootstrap : httpClientBootstrap;
                clientBootstrap.connect(this.targetHost, this.targetPort).addListener((ChannelFutureListener) future -> {
                    if (future.isSuccess()) {
                        future.channel().writeAndFlush(msg);
                        synchronized (pendingMessages) {
                            Object pending;
                            while ((pending = pendingMessages.poll()) != null) {
                                future.channel().writeAndFlush(pending);
                            }
                            targetChannel = future.channel();
                            targetChannel.attr(ComputerProxyServerContainer.NEXT_CHANNEL).set(ctx.channel());
                            ctx.channel().attr(ComputerProxyServerContainer.NEXT_CHANNEL).set(targetChannel);
                        }

                        String connection = request.headers().get("Connection");
                        String upgrade = request.headers().get("Upgrade");
                        // Protocol upgrade goes through native channel, supports websocket
                        if ((connection != null && connection.equals("Upgrade")) || (upgrade != null && upgrade.equals("websocket"))) {
                            ctx.channel().pipeline().remove("httpServerCodec");
                            future.channel().pipeline().remove("httpClientCodec");
                        }

                        ctx.channel().config().setOption(ChannelOption.AUTO_READ, true);
                        log.info("Computer proxy connected to target {}:{}", targetHost, targetPort);
                    } else {
                        log.warn("connect target failed {}:{}", targetHost, targetPort, future.cause());
                        ReferenceCountUtil.release(msg);
                        clearPending();
                        ctx.channel().config().setOption(ChannelOption.AUTO_READ, true);
                        writeError(ctx, HttpResponseStatus.BAD_GATEWAY, "502 Bad Gateway (" + targetHost + ":" + targetPort + ")");
                    }
                });
            } else {
                targetChannel.writeAndFlush(msg);
            }
        } else {
            forwardOther(ctx, msg);
        }
    }

    // Non-first HttpRequest data forwarding (including content after handshake or WebSocket frames)
    private void forwardOther(ChannelHandlerContext ctx, Object msg) {
        // For shared links, check expiration time (intercept WebSocket internal transmission)
        String shareKey = ctx.channel().attr(ComputerProxyServerContainer.SHARE_KEY).get();
        if (shareKey != null) {
            Date expire = ctx.channel().attr(ComputerProxyServerContainer.SHARE_EXPIRE).get();
            if (expire != null && expire.before(new Date())) {
                // Shared link has expired, send message and close connection
                log.debug("Computer share expired, closing link. shareKey={}, expire={}", shareKey, expire);
                ReferenceCountUtil.release(msg);
                sendShareExpiredMessage(ctx);
                if (targetChannel != null) {
                    targetChannel.close();
                }
                clearPending();
                return;
            }
        }

        if (targetChannel == null) { // Cache when target not connected yet to prevent packet loss
            synchronized (pendingMessages) {
                if (targetChannel == null) {
                    pendingMessages.offer(msg);
                    if (pendingMessages.size() >= pendingMax) {
                        // Do not release(msg) here: msg has been enqueued, ctx.close() will trigger channelInactive() -> clearPending(),
                        // clearPending() will release all messages in queue (including this msg) once; releasing here again will cause duplicate release.
                        log.warn("pending queue overflow, closing channel. pendingMax={}", pendingMax);
                        writeError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "proxy pending overflow");
                        ctx.close();
                        return;
                    }
                } else {
                    targetChannel.writeAndFlush(msg);
                }
            }
        } else {
            targetChannel.writeAndFlush(msg);
        }
    }

    // Release pending messages (avoid leaks during exception/close)
    private void clearPending() {
        synchronized (pendingMessages) {
            Object pending;
            while ((pending = pendingMessages.poll()) != null) {
                ReferenceCountUtil.release(pending);
            }
        }
    }

    private void addForwardHeaders(ChannelHandlerContext ctx, HttpRequest request) {
        String remoteIp = ((InetSocketAddress) ctx.channel().remoteAddress()).getAddress().getHostAddress();
        String xff = request.headers().get("X-Forwarded-For");
        if (xff == null) {
            request.headers().set("X-Forwarded-For", remoteIp);
        } else {
            request.headers().set("X-Forwarded-For", xff + "," + remoteIp);
        }
        // Rewrite Origin initiated by browser to target site, avoid handshake failure due to target-side CORS validation
        if (request.headers().contains(HttpHeaderNames.ORIGIN)) {
            request.headers().set(HttpHeaderNames.ORIGIN, targetOrigin);
        }
        if (request.headers().get("X-Real-IP") == null) {
            request.headers().set("X-Real-IP", remoteIp);
        }
        if (request.headers().get("X-Forwarded-Proto") == null) {
            String proto = ctx.pipeline().get(SslHandler.class) != null ? "https" : "http";
            request.headers().set("X-Forwarded-Proto", proto);
        }
    }

    private Long extractCId(String uri) {
        // Try to extract from desktop path
        Matcher matcher = DESKTOP_PATTERN.matcher(uri);
        if (matcher.matches()) {
            String cId = matcher.group(1);
            try {
                return Long.valueOf(cId);
            } catch (Exception e) {
                return null;
            }
        }
        // Try to extract from audio path
        matcher = AUDIO_PATTERN.matcher(uri);
        if (matcher.matches()) {
            String cId = matcher.group(1);
            try {
                return Long.valueOf(cId);
            } catch (Exception e) {
                return null;
            }
        }
        // Try to extract from ime path
        matcher = IME_PATTERN.matcher(uri);
        if (matcher.matches()) {
            String cId = matcher.group(1);
            try {
                return Long.valueOf(cId);
            } catch (Exception e) {
                return null;
            }
        }
        matcher = TERMINAL_PATTERN.matcher(uri);
        if (matcher.matches()) {
            String cId = matcher.group(1);
            try {
                return Long.valueOf(cId);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Rewrite path, add userId
     * /computer/desktop/{cId}/xxx -> /computer/vnc/{userId}/{cId}/xxx
     * /computer/audio/{cId}/xxx -> /computer/audio/{userId}/{cId}/xxx
     * /computer/ime/{cId}/xxx -> /computer/ime/{userId}/{cId}/xxx
     */
    private String rewriteUri(String uri, Long userId, SandboxServerConfig.SandboxServer sandboxServer, boolean isPageApp) {
        // Handle desktop path
        Matcher matcher = DESKTOP_PATTERN.matcher(uri);
        if (matcher.matches()) {
            String cId = matcher.group(1);
            String rest = matcher.group(2);
            String newUri;
            if (sandboxServer.getScope() == SandboxScopeEnum.USER) {
                newUri = "/" + rest;
            } else {
                newUri = "/computer/vnc/" + userId + "/" + cId + (rest.isEmpty() ? "" : "/" + rest);
            }

            log.info("Computer proxy rewrite uri: {} -> {}", uri, newUri);
            return newUri;
        }
        // Handle audio path
        matcher = AUDIO_PATTERN.matcher(uri);
        if (matcher.matches()) {
            String cId = matcher.group(1);
            String rest = matcher.group(2);
            String newUri;
            if (sandboxServer.getScope() == SandboxScopeEnum.USER) {
                newUri = "/" + rest;
            } else {
                newUri = "/computer/audio/" + userId + "/" + cId + (rest.isEmpty() ? "" : "/" + rest);
            }
            log.info("Computer proxy rewrite uri: {} -> {}", uri, newUri);
            return newUri;
        }
        // Handle ime path
        matcher = IME_PATTERN.matcher(uri);
        if (matcher.matches()) {
            String cId = matcher.group(1);
            String rest = matcher.group(2);
            String newUri;
            if (sandboxServer.getScope() == SandboxScopeEnum.USER) {
                newUri = "/" + rest;
            } else {
                newUri = "/computer/ime/" + userId + "/" + cId + (rest.isEmpty() ? "" : "/" + rest);
            }
            log.info("Computer proxy rewrite uri: {} -> {}", uri, newUri);
            return newUri;
        }

        matcher = TERMINAL_PATTERN.matcher(uri);
        if (matcher.matches()) {
            String cId = matcher.group(1);
            String newUri;
            if (isPageApp) {
                newUri = "/web/ttyd/" + userId + "/" + cId + "/ws";
            } else {
                newUri = "/computer/ttyd/" + userId + "/" + cId + "/ws";
            }
            log.info("Computer proxy rewrite uri: {} -> {}", uri, newUri);
            return newUri;
        }
        return uri;
    }

    /**
     * User permission validation
     */
    private boolean allow(Long vncUserId, Long projectSpaceId, HttpRequest request, ChannelHandlerContext ctx) {
        if (vncUserId == null) {
            log.warn("Failed to get VNC userId");
            return false;
        }
        Long loginUserId = getLoginUserId(request, ctx);
        if (loginUserId == null) {
            log.info("Failed to get login userId. vncUserId={}", vncUserId);
            return false;
        }
        boolean allow;
        if (projectSpaceId != null) {
            try {
                spacePermissionService.checkSpaceUserPermission(projectSpaceId, loginUserId);
                allow = true;
            } catch (Exception e) {
                allow = false;
            }
        } else {
            allow = vncUserId.equals(loginUserId);
        }
        log.info("Computer proxy allow={}: {} -> {}", allow, vncUserId, loginUserId);
        return allow;
    }

    /**
     * Get login user ID from request
     * Supports getting token from _ticket parameter, Cookie, Authorization header, then parse user information
     */
    private Long getLoginUserId(HttpRequest request, ChannelHandlerContext ctx) {
        String token = null;
        String uri = request.uri();

        // Handle _ticket exchange token
        if (uri != null && uri.contains("_ticket=")) {
            Map<String, String> parseQueryString = parseQueryString(uri);
            if (parseQueryString.containsKey("_ticket")) {
                token = authService.getTokenByTicket(parseQueryString.get("_ticket"));
                if (token != null && !token.isBlank()) {
                    request.headers().set("Authorization", "Bearer " + token);
                    ctx.channel().attr(ComputerProxyServerContainer.tokenKey).set(token);
                    log.info("获取用户token成功(from Uri ticket)");
                }
            }
        }

        String cookie = request.headers().get("Cookie");
        String authorization = request.headers().get("Authorization");

        // 从cookie中获取ticket认证信息
        if (cookie != null && token == null) {
            //去除多余的空内容
            cookie = cookie.replace("ticket=;", "");
            int start = cookie.indexOf("ticket=");
            if (start >= 0) {
                int end = cookie.indexOf(";", start);
                if (end > 0) {
                    token = cookie.substring(start + "ticket=".length(), end);
                } else {
                    token = cookie.substring(start + "ticket=".length());
                }
                if (!token.isBlank()) {
                    log.info("Successfully obtained user token (from Cookie ticket)");
                }
            }
            cookie = cookie.replace("ticket=" + token, "");
            try {
                request.headers().set("Cookie", cookie.trim());
            } catch (Exception e) {
                request.headers().remove("Cookie");
                // ignore
            }
        }
        if (token == null) {
            if (authorization != null) {
                token = authorization.replaceFirst("Basic", "").replaceFirst("Bearer", "").trim();
                if (!token.isBlank()) {
                    log.info("获取用户token成功(from Authorization)");
                }
            }
        }

        if (StringUtils.isNotBlank(token) && authService != null) {
            try {
                UserDto userDto = authService.getLoginUserInfo(token);
                if (userDto != null) {
                    log.info("Successfully obtained user login information, loginUserId={}", userDto.getId());
                    return userDto.getId();
                }
            } catch (Exception e) {
                log.warn("Failed to get user login information, token={}", token, e);
            }
        }
        return null;
    }

    private static String getShareKeyFromUri(String uri) {
        Map<String, String> stringStringMap = parseQueryString(uri);
        return stringStringMap.get("sk");
    }

    /**
     * 从 Cookie 中获取 shareKey
     */
    private static String getShareKeyFromCookie(HttpRequest request, String skCookieKey) {
        String cookie = request.headers().get("Cookie");
        if (cookie == null) {
            return null;
        }
        int start = cookie.indexOf(skCookieKey);
        if (start >= 0) {
            int end = cookie.indexOf(";", start);
            if (end > 0) {
                return cookie.substring(start + skCookieKey.length(), end);
            } else {
                return cookie.substring(start + skCookieKey.length());
            }
        }
        return null;
    }

    private static Map<String, String> parseQueryString(String url) {
        Map<String, String> params = new HashMap<>();
        try {
            URI uri = new URI(url);
            String queryString = uri.getQuery();
            if (queryString != null && !queryString.isEmpty()) {
                String[] pairs = queryString.split("&");
                for (String pair : pairs) {
                    int idx = pair.indexOf("=");
                    if (idx > 0) {
                        String key = pair.substring(0, idx);
                        String value = pair.substring(idx + 1);
                        params.put(key, value);
                    }
                }
            }
        } catch (URISyntaxException e) {
            // 处理异常
            log.warn("Invalid URL: " + url, e);
        }
        return params;
    }

    private void writeError(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
                Unpooled.copiedBuffer("<html> <head><title>" + message + "</title></head> <body>" +
                        "<center><h1>" + message + "</h1></center> <hr><center>NUWAX/0.1</center>" +
                        "</body></html>", CharsetUtil.UTF_8));
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
        ctx.channel().writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * 分享已过期，发送消息（WebSocket 文本帧）并关闭连接
     * 由于是透明代理，需要手动构造 WebSocket 帧字节
     */
    private void sendShareExpiredMessage(ChannelHandlerContext ctx) {
        try {
            byte[] messageBytes = ComputerProxyServerContainer.shareExpiredMessage.getBytes(CharsetUtil.UTF_8);

            // 构造 WebSocket 文本帧
            // FIN=1, RSV=0, Opcode=0x1 (文本帧)
            // MASK=0 (服务器发送不需要mask)
            ByteBuf textFrame = ctx.alloc().buffer();
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
            ctx.writeAndFlush(textFrame).addListener(future -> {
                // 构造并发送关闭帧
                ByteBuf closeFrame = ctx.alloc().buffer();
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
                ctx.writeAndFlush(closeFrame).addListener(ChannelFutureListener.CLOSE);
            });
        } catch (Exception e) {
            // 如果发送失败，直接关闭
            log.debug("Failed to send WebSocket message, closing directly", e);
            ctx.close();
        }
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        if (targetChannel != null) {
            targetChannel.config().setOption(ChannelOption.AUTO_READ, ctx.channel().isWritable());
        }
        super.channelWritabilityChanged(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.debug("Computer proxy front channel inactive, remote={}", ctx.channel().remoteAddress());
        if (targetChannel != null) {
            try {
                targetChannel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            } catch (Exception e) {
                log.warn("close target channel failed", e);
            }
        }
        clearPending();
        super.channelInactive(ctx);
    }

    private String dumpHex(ByteBuf buf, int limit) {
        int len = Math.min(buf.readableBytes(), limit);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02x", buf.getUnsignedByte(buf.readerIndex() + i)));
            if (i != len - 1) {
                sb.append(' ');
            }
        }
        return sb.toString();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.warn("Computer proxy exception", cause);
        clearPending();
        super.exceptionCaught(ctx, cause);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            log.info("Computer proxy idle timeout, closing channel"); // 空闲超时，主动回收前后端
            ctx.close();
            Channel peer = ctx.channel().attr(ComputerProxyServerContainer.NEXT_CHANNEL).get();
            if (peer != null) {
                peer.close();
            }
            clearPending();
            return;
        }
        super.userEventTriggered(ctx, evt);
    }
}

