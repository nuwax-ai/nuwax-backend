package com.xspaceagi.agent.core.infra.proxy;

import com.xspaceagi.agent.core.adapter.dto.ConversationDto;
import com.xspaceagi.agent.core.infra.rpc.SandboxServerConfigService;
import com.xspaceagi.agent.core.infra.rpc.UserShareRpcService;
import com.xspaceagi.agent.core.infra.rpc.WorkspaceServiceType;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
    /** 终端 service_type 契约参数键；值取 WorkspaceServiceType.getContainerType()（与下游沙箱 service_type 参数同源，勿另行硬编码） */
    private static final String TERMINAL_SERVICE_TYPE_KEY = "service_type";
    /** 终端 cwd 契约参数键；值取会话的 agentWorkspacePath（chat 侧 agent_work_dir 覆盖同源，勿另行推导） */
    private static final String TERMINAL_CWD_KEY = "cwd";

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
            // 常规项目（normalProject）会话终端定位键改用 devTargetId（常规项目 id），
            // 与 chat 侧 agent_work_dir 同源（SandboxAgentClient 按 service_type +
            // devTargetId 定位容器内 /home/user/normalProject/{pid}；本机 electron
            // 网关为同构镜像目录）——原 cId（conversationId）在容器/本机 normalProject
            // 层都找不到目录。
            TerminalTarget target = resolveTerminalTarget(cId, sandboxServer);
            String newUri;
            if (isPageApp) {
                newUri = "/web/ttyd/" + userId + "/" + target.targetId() + "/ws";
            } else {
                newUri = "/computer/ttyd/" + userId + "/" + target.targetId() + "/ws";
            }
            // 保留原 query（剔除本代理消费的鉴权参数）——终端契约参数 service_type /
            // cwd 的唯一载体（浏览器原生 WebSocket 无法设自定义 header）；此前重写
            // 丢弃 query，云端 Pingora 与本机 electron 网关都收不到。
            // service_type 兜底补参：前端携带与否依赖会话详情加载时序（切会话/秒开
            // 终端存在漏带窗口期，漏带时终端初始目录回落工作区根）；本代理在
            // cId→devTargetId 替换时已权威知道这是常规项目会话，前端未显式携带时
            // 补 computer-normal-project。仅 computer 轨道补参（pageApp 轨道不掺入
            // normalProject 语义）。
            String query = supplementTerminalServiceType(
                    extractForwardableQuery(uri), !isPageApp && target.normalProject());
            // cwd 兜底补参：个人电脑自选目录（agentWorkspacePath）会话在客户端走绝对路径
            // 轨道、不落 normalProject 镜像目录，缺 cwd 时终端只能回落工作区根；上游未
            // 显式携带时由本代理注入会话目录（与 service_type 兜底同款时机与保护）。
            query = supplementTerminalCwd(query, resolveTerminalCwd(sandboxServer, isPageApp));
            if (StringUtils.isNotBlank(query)) {
                newUri = newUri + "?" + query;
            }
            log.info("Computer proxy rewrite uri: {} -> {}", uri, newUri);
            return newUri;
        }
        return uri;
    }

    /**
     * 终端路由定位键解析：常规项目（normalProject）会话用 devTargetId（与 chat 侧
     * 工作区 key 一致），其余会话维持 conversationId 原语义；normalProject 标志供
     * TERMINAL 分支 service_type 兜底补参判定。
     * fromDevTargetType 对 null/未知类型安全回落 TASK_AGENT。
     */
    private record TerminalTarget(String targetId, boolean normalProject) {}

    private static TerminalTarget resolveTerminalTarget(
            String cId, SandboxServerConfig.SandboxServer sandboxServer) {
        Optional<String> devTargetId = Optional.ofNullable(sandboxServer)
                .map(SandboxServerConfig.SandboxServer::getCurrentConversation)
                .filter(conversation -> WorkspaceServiceType.fromDevTargetType(
                        conversation.getDevTargetType()) == WorkspaceServiceType.NORMAL_PROJECT)
                .map(ConversationDto::getDevTargetId)
                .filter(StringUtils::isNotBlank);
        return devTargetId
                .map(id -> new TerminalTarget(id, true))
                .orElseGet(() -> new TerminalTarget(cId, false));
    }

    /**
     * 终端 service_type 兜底补参（纯函数，包级可见供单测）：常规项目会话且原 query
     * 未显式携带 service_type 时补 WorkspaceServiceType.NORMAL_PROJECT 的容器类型值
     * （前端判定依赖会话详情加载时序，切会话/秒开终端存在漏带窗口期，漏带时终端
     * 初始目录回落工作区根）。已携带则原样返回——上游（rcoder form_decode / 本机
     * electron URLSearchParams）重复键按 last-wins 解析，追加会覆盖前端显式值。
     */
    static String supplementTerminalServiceType(String rawQuery, boolean normalProject) {
        if (!normalProject) {
            return rawQuery;
        }
        if (StringUtils.isBlank(rawQuery)) {
            return normalProjectServiceTypeQuery();
        }
        if (rawQueryHasKey(rawQuery, TERMINAL_SERVICE_TYPE_KEY)) {
            return rawQuery;
        }
        return rawQuery + "&" + normalProjectServiceTypeQuery();
    }

    private static String normalProjectServiceTypeQuery() {
        return TERMINAL_SERVICE_TYPE_KEY + "=" + WorkspaceServiceType.NORMAL_PROJECT.getContainerType();
    }

    /**
     * 终端 cwd 兜底补参（纯函数，包级可见供单测）：个人电脑自选目录会话
     * （resolveTerminalCwd 给值）且原 query 未显式携带 cwd 时，补会话的
     * agentWorkspacePath——与 chat 侧 agent_work_dir 覆盖（SandboxAgentClient）同源；
     * 本机 electron 网关对显式 cwd「存在即用」优先消费。已携带则原样返回——
     * last-wins 语义下追加会覆盖调用方显式值。
     * 编码用 form 语义（URLEncoder：空格→+、字面+→%2B、中文→UTF-8 percent），与前端
     * URLSearchParams / 本机 electron 网关 URLSearchParams / rcoder form_decode 的解码
     * 语义对齐；「原始串透传、不重编码」原则仅约束存量参数，本层注入的新值必须编码后拼接。
     */
    static String supplementTerminalCwd(String rawQuery, String cwdValue) {
        if (StringUtils.isBlank(cwdValue)) {
            return rawQuery;
        }
        if (StringUtils.isNotBlank(rawQuery) && rawQueryHasKey(rawQuery, TERMINAL_CWD_KEY)) {
            return rawQuery;
        }
        String pair = TERMINAL_CWD_KEY + "=" + URLEncoder.encode(cwdValue, StandardCharsets.UTF_8);
        return StringUtils.isBlank(rawQuery) ? pair : rawQuery + "&" + pair;
    }

    /**
     * 终端 cwd 注入条件（包级可见供单测）：仅 computer 轨道（对齐 service_type 兜底的
     * 轨道纪律，pageApp 容器不掺入）+ 个人电脑（USER scope）——自选目录是用户本机
     * 绝对路径，云端容器（GLOBAL 等）会话的目录语义由云端自决，不得泄入。目录值与
     * chat 侧 agent_work_dir 覆盖同源，不按 normalProject 门控——该覆盖对全会话类型
     * 生效，普通会话 + 自选目录的同型场景一并覆盖；返回 null 表示不注入。
     */
    static String resolveTerminalCwd(SandboxServerConfig.SandboxServer sandboxServer, boolean isPageApp) {
        if (isPageApp || sandboxServer == null || sandboxServer.getScope() != SandboxScopeEnum.USER) {
            return null;
        }
        return Optional.ofNullable(sandboxServer.getCurrentConversation())
                .map(ConversationDto::getAgentWorkspacePath)
                .filter(StringUtils::isNotBlank)
                .filter(ComputerProxyRequestHandler::isAbsoluteDirForm)
                .orElse(null);
    }

    /**
     * 目录形态预检（宽松子集，镜像下游 normalize_absolute_dir 的接受面）：POSIX /、
     * Windows 盘符 X:/ 或 X:\、UNC \\。非绝对形态（相对路径/脏数据）不注入——下游
     * 网关对形态非法的 cwd 直接 400（TTYD_CWD_INVALID），兜底注入不应把本可回落
     * 工作区根的终端打成硬失败（存在性缺失仍由下游 warn 回落，不在此检查）。
     */
    private static boolean isAbsoluteDirForm(String dir) {
        return dir.startsWith("/") || dir.startsWith("\\\\")
                || (dir.length() >= 3 && Character.isLetter(dir.charAt(0)) && dir.charAt(1) == ':'
                        && (dir.charAt(2) == '/' || dir.charAt(2) == '\\'));
    }

    /**
     * 原始 query 串中是否已含指定键（按 & 与 = 切分比较；键为纯 ASCII，无需解码，
     * 与 extractForwardableQuery 的剔除键匹配同一约定）。
     */
    private static boolean rawQueryHasKey(String rawQuery, String key) {
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String pairKey = eq >= 0 ? pair.substring(0, eq) : pair;
            if (key.equals(pairKey)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 提取可转发到上游（rcoder/本机 electron）的 query：剔除本代理消费的鉴权参数
     * _ticket（登录票据交换）与 sk（分享 key），防止凭据随转发泄漏到沙箱侧。
     *
     * 有意做「原始串透传、不解码不重编码」：终端契约参数（service_type/cwd，含
     * 空格/中文/字面 +）由上游按 form 语义自行解码（rcoder ttyd_params 的
     * form_decode、本机 electron 网关的 URLSearchParams）。若本层改走
     * QueryStringDecoder.parameters() 解码再重编码拼回，会引入形态漂移：空格
     * +↔%20 互换、非法 UTF-8 序列被替换字符固化（透传形态下 rcoder 会对其 400，
     * 重编码后变成合法但错误的值）、键序重排——透传是唯一零损耗方案。剔除键
     * （_ticket/sk）为纯 ASCII，原始形态即最终形态，无需解码即可精确匹配。
     *
     * query 提取用 Netty QueryStringDecoder.rawQuery()：取未解码 query 串。
     * 注意其切分点为首个 '?' 或 '#'——'#' 后的内容会被当作 query 返回（HTTP
     * request-target 规范不含 fragment，仅手工客户端可构造），故此处对返回值
     * 再截断 '#'，避免凭空造出 query。
     */
    private static String extractForwardableQuery(String uri) {
        String rawQuery = new QueryStringDecoder(uri).rawQuery();
        if (StringUtils.isBlank(rawQuery)) {
            return null;
        }
        int fragmentStart = rawQuery.indexOf('#');
        if (fragmentStart >= 0) {
            rawQuery = rawQuery.substring(0, fragmentStart);
            if (rawQuery.isEmpty()) {
                return null;
            }
        }
        StringBuilder forwardable = new StringBuilder();
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            if ("_ticket".equals(key) || "sk".equals(key)) {
                continue;
            }
            if (forwardable.length() > 0) {
                forwardable.append('&');
            }
            forwardable.append(pair);
        }
        return forwardable.length() > 0 ? forwardable.toString() : null;
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

