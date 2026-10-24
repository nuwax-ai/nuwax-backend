package com.xspaceagi.userapp.infra.proxy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.util.HtmlUtils;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.spec.cache.SimpleJvmHashCache;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.userapp.domain.model.UserAppDomainModel;
import com.xspaceagi.userapp.domain.model.UserAppModel;
import com.xspaceagi.userapp.sdk.dto.UserAppDomainType;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;

/**
 * userapp 代理请求处理：路由解析 → 登录/权限校验（fail-closed）→ 转发项目绑定沙箱。
 * 支持路由：域名直连/{*path} 或 /api/userapp/proxy/{service}/{env}/{app_id}/{*path}；
 * 认证信息：_ticket 兑换、ticket cookie、Authorization 头。
 */
public class UserAppProxyRequestHandler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(UserAppProxyRequestHandler.class);

    /** cookie 名精确为 ticket 的取值（避免误匹配 myticket= 之类应用自有 cookie） */
    private static final Pattern TICKET_COOKIE_PATTERN = Pattern.compile("(?:^|;\\s*)ticket=([^;]*)");

    /** cookie 名精确为 lang 的取值（平台前端种的语言 cookie，同 AuthInterceptor 的 clientCookieLang） */
    private static final Pattern LANG_COOKIE_PATTERN = Pattern.compile("(?:^|;\\s*)lang=([^;]*)");

    /** 状态页图标：SVG 与明暗底色（停止页/错误页共用骨架） */
    private record PageIcon(String svg, String bgLight, String bgDark) {
    }

    private static final PageIcon ICON_STOPPED = new PageIcon(
            "<svg width=\"26\" height=\"26\" viewBox=\"0 0 24 24\" fill=\"none\" aria-hidden=\"true\">"
                    + "<rect x=\"6\" y=\"5\" width=\"4\" height=\"14\" rx=\"1.2\" fill=\"#e8a33d\"/>"
                    + "<rect x=\"14\" y=\"5\" width=\"4\" height=\"14\" rx=\"1.2\" fill=\"#e8a33d\"/></svg>",
            "#fdf1e3", "#3a2f1e");

    private static final PageIcon ICON_ERROR = new PageIcon(
            "<svg width=\"26\" height=\"26\" viewBox=\"0 0 24 24\" fill=\"none\" aria-hidden=\"true\">"
                    + "<circle cx=\"12\" cy=\"12\" r=\"9.2\" stroke=\"#d5494a\" stroke-width=\"1.8\"/>"
                    + "<rect x=\"11.1\" y=\"7\" width=\"1.8\" height=\"7.5\" rx=\"0.9\" fill=\"#d5494a\"/>"
                    + "<circle cx=\"12\" cy=\"16.6\" r=\"1.1\" fill=\"#d5494a\"/></svg>",
            "#fdecec", "#38211f");

    private final Queue<Object> receivedLastMsgsWhenConnect = new LinkedList<>();
    private final Bootstrap proxyClientBootstrap;
    private final Bootstrap httpsProxyClientBootstrap;
    private Channel targetChannel;

    /** 当前后端标识 host:port：同一前端连接被复用访问其他应用（后端变化）时必须重连，避免串扰 */
    private String targetBackendKey;

    private final UserAppProxyConfigService userAppProxyConfigService;

    /** 待转发挂起队列上限：溢出直接 503 关闭，防止连接建立期间内存被占满 */
    private final int pendingMax;

    public UserAppProxyRequestHandler(Bootstrap proxyClientBootstrap, Bootstrap httpsProxyClientBootstrap,
            UserAppProxyConfigService userAppProxyConfigService, int pendingMax) {
        this.proxyClientBootstrap = proxyClientBootstrap;
        this.httpsProxyClientBootstrap = httpsProxyClientBootstrap;
        this.userAppProxyConfigService = userAppProxyConfigService;
        this.pendingMax = pendingMax;
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
        if (!(msg instanceof HttpRequest request)) {
            forwardToTarget(ctx, msg);
            return;
        }
        try {
            handleHttpRequest(ctx, request);
        } finally {
            // 代理过程不跨请求复用线程上下文
            RequestContext.remove();
        }
    }

    private void handleHttpRequest(final ChannelHandlerContext ctx, final HttpRequest request) {
        String uri = request.uri() == null ? "/" : request.uri();

        UserAppProxyRoute route = UserAppProxyRoute.parse(uri);
        if (route == null) {
            route = resolveRouteByHost(request, uri);
        }
        if (route == null) {
            // 先取值再 release：Host/Cookie 头属于请求对象，释放后访问违反引用计数契约
            logger.info("[UserAppProxy] no route, uri={}, host={}", uri, request.headers().get("Host"));
            // 无 appId 定位不到租户：语言仅剩 lang cookie 与 Accept-Language 两级
            String lang = pageLang(null, null,
                    cookieValue(LANG_COOKIE_PATTERN, request.headers().get(HttpHeaderNames.COOKIE)),
                    request.headers().get(HttpHeaderNames.ACCEPT_LANGUAGE));
            ReferenceCountUtil.release(request);
            // 此处路由未解析出（无 appId），无法查租户白名单，跨域头留空
            writeErrorResponse(ctx, HttpResponseStatus.NOT_FOUND, lang, null, null);
            return;
        }

        // 应用根路径无尾斜杠（…/dev/9）时，页面内相对引用（css/js 等）会以 …/dev/ 为基准解析而丢掉
        // appId（…/dev/xxx.css 不匹配路由 → 404）。308 补斜杠让浏览器以 …/9/ 为基准解析相对资源；
        // 放在 token 解析之前，避免一次性 _ticket 在跳转前的这次请求上被消耗。
        // websocket 升级请求除外：握手不跟随重定向，3xx 会直接断开（vnc/ttyd 的正常连接形态）
        int queryIdx = uri.indexOf('?');
        String pathOnly = queryIdx >= 0 ? uri.substring(0, queryIdx) : uri;
        if ("/".equals(route.getSubPath()) && !pathOnly.endsWith("/") && !isWebSocketUpgrade(request)) {
            String location = pathOnly + "/" + (queryIdx >= 0 ? uri.substring(queryIdx) : "");
            logger.info("[UserAppProxy] app root missing trailing slash, redirect, uri={}", uri);
            ReferenceCountUtil.release(request);
            FullHttpResponse redirect = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                    HttpResponseStatus.PERMANENT_REDIRECT, Unpooled.EMPTY_BUFFER);
            redirect.headers().set(HttpHeaderNames.LOCATION, location);
            ctx.channel().writeAndFlush(redirect).addListener(ChannelFutureListener.CLOSE);
            return;
        }

        // CORS（租户 siteUrl 白名单）：父页面跨域 fetch 才带 Origin（iframe 导航不带，零开销）。
        // 必须在 _ticket 兑换之前：预检请求也要走这里，不能消耗一次性票据
        String corsOrigin = userAppProxyConfigService.matchSiteOrigin(route.getAppId(),
                request.headers().get(HttpHeaderNames.ORIGIN));
        if (corsOrigin != null && UserAppProxyCors.isPreflight(request)) {
            // 预检在代理侧终结：不转发下游、不消耗票据，浏览器问什么放行什么（Origin 已被白名单限定）
            ReferenceCountUtil.release(request);
            ctx.channel().writeAndFlush(UserAppProxyCors.preflightResponse(corsOrigin, request))
                    .addListener(ChannelFutureListener.CLOSE);
            return;
        }

        // 语言线索先取值（Cookie/语言头属于请求对象，释放后访问违反引用计数契约），
        // 供停止页/错误页按系统惯例逐级回退解析语言（用户资料 > lang cookie > 租户默认 > Accept-Language）
        String langCookie = cookieValue(LANG_COOKIE_PATTERN, request.headers().get(HttpHeaderNames.COOKIE));
        String acceptLanguage = request.headers().get(HttpHeaderNames.ACCEPT_LANGUAGE);

        // 生产应用已停止：不转发下游，先于 token 与端点解析拦截（停止态不消耗一次性 _ticket；
        // 私服离线/沙箱解绑等解析失败场景也出停止页而非 403/500）
        UserAppModel stoppedApp;
        try {
            stoppedApp = userAppProxyConfigService.prodStoppedApp(route);
        } catch (Exception e) {
            ReferenceCountUtil.release(request);
            logger.warn("[UserAppProxy] stopped state check failed, uri={}", uri, e);
            writeErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, langCookie, null, corsOrigin);
            return;
        }
        if (stoppedApp != null) {
            // _ticket 不为取语言而兑换——一次性票据留给重启后重访
            String token = StringUtils.defaultString(cookieValue(TICKET_COOKIE_PATTERN,
                    request.headers().get(HttpHeaderNames.COOKIE)));
            if (token.isEmpty()) {
                String authorization = request.headers().get(HttpHeaderNames.AUTHORIZATION);
                token = authorization == null ? ""
                        : authorization.replaceFirst("Basic", "").replaceFirst("Bearer", "").trim();
            }
            ReferenceCountUtil.release(request);
            logger.info("[UserAppProxy] prod app stopped, uri={}, appId={}", uri, route.getAppId());
            writeServiceStoppedPage(ctx, stoppedApp.getName(),
                    pageLang(route.getAppId(), token, langCookie, acceptLanguage), corsOrigin);
            return;
        }

        // token：_ticket 兑换优先，其次 ticket cookie、Authorization 头
        String token = resolveToken(ctx, request, uri, route);

        UserAppProxyBackend backend;
        try {
            backend = userAppProxyConfigService.authorizeAndResolve(route, token);
        } catch (BizException e) {
            ReferenceCountUtil.release(request);
            handleAuthFailure(ctx, e, uri, pageLang(route.getAppId(), token, langCookie, acceptLanguage), corsOrigin);
            return;
        } catch (Exception e) {
            ReferenceCountUtil.release(request);
            logger.warn("[UserAppProxy] resolve failed, uri={}", uri, e);
            // 页面不透出内部异常细节，详情进日志
            writeErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    pageLang(route.getAppId(), token, langCookie, acceptLanguage), null, corsOrigin);
            return;
        }
        if (backend == null) {
            ReferenceCountUtil.release(request);
            // 与下游透传回来的 404 区分开：代理自身判定项目不存在/预删除
            logger.info("[UserAppProxy] project not found or pre-deleted, appId={}", route.getAppId());
            writeErrorResponse(ctx, HttpResponseStatus.NOT_FOUND,
                    pageLang(route.getAppId(), token, langCookie, acceptLanguage), null, corsOrigin);
            return;
        }

        // 下游路径由代理拼回创建者 user_id；Authorization 原样透传（应用自己的认证头不由代理抹掉，
        // 平台前端不应向代理路径附带平台 token——附带即进沙箱）
        request.setUri(route.downstreamUri(backend.getOwnerUserId()));
        // 平台域名路径访问：浏览器自动携带的是平台会话 cookie，ticket 不透传下游；
        // 绑定域名访问：该域属应用命名空间（平台不在此种 ticket cookie，见 resolveToken），
        // ticket 可能是应用自有会话，原样透传
        if (!route.isDomainDirect()) {
            stripTicketCookie(request);
        }
        // Host 带端口：下游按 Host 构建绝对地址/跳转时不丢非默认端口
        request.headers().set("Host", backend.getHost() + ":" + backend.getPort());
        request.headers().set("x-api-key", backend.getApiKey());
        // vnc/ttyd 等交互服务改写 Origin 为目标站点，避免下游（websockify/ttyd）CORS 校验导致握手失败
        if (route.isInteractiveService() && request.headers().contains(HttpHeaderNames.ORIGIN)) {
            request.headers().set(HttpHeaderNames.ORIGIN,
                    buildOrigin(backend.getScheme(), backend.getHost(), backend.getPort()));
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
        if (request.headers().get("X-Forwarded-Proto") == null) {
            request.headers().set("X-Forwarded-Proto",
                    ctx.pipeline().get(SslHandler.class) != null ? "https" : "http");
        }

        // route 在方法开头有重赋值（非 effectively final），进回调 lambda 前拷贝
        final Long routeAppId = route.getAppId();
        String backendKey = backend.getHost() + ":" + backend.getPort();
        if (targetChannel != null && !backendKey.equals(targetBackendKey)) {
            // 同一前端连接上切换了目标应用（同源 keep-alive 复用）：摘除回指后断开旧后端重连，
            // 否则请求会被发往旧应用所在沙箱
            Channel oldChannel = targetChannel;
            oldChannel.attr(UserAppProxyServerContainer.nextChannelAttributeKey).set(null);
            oldChannel.close();
            targetChannel = null;
        }
        if (targetChannel == null) {
            ctx.channel().config().setOption(ChannelOption.AUTO_READ, false);
            Bootstrap clientBootstrap = "https".equals(backend.getScheme())
                    ? httpsProxyClientBootstrap : proxyClientBootstrap;
            String ip = resolveDns(backend.getHost());
            clientBootstrap.connect(ip, backend.getPort()).addListener((ChannelFutureListener) future -> {
                // 连接后端服务器成功
                if (future.isSuccess()) {
                    future.channel().attr(UserAppProxyServerContainer.relayContextKey)
                            .set(new UserAppProxyRelayContext(request.method().name(), uri, backendKey,
                                    System.nanoTime(), corsOrigin));
                    future.channel().writeAndFlush(request);
                    synchronized (receivedLastMsgsWhenConnect) {
                        Object msg0;
                        while ((msg0 = receivedLastMsgsWhenConnect.poll()) != null) {
                            future.channel().writeAndFlush(msg0);
                        }
                        targetChannel = future.channel();
                        targetBackendKey = backendKey;
                        targetChannel.attr(UserAppProxyServerContainer.nextChannelAttributeKey).set(ctx.channel());
                        ctx.channel().attr(UserAppProxyServerContainer.nextChannelAttributeKey).set(targetChannel);
                    }
                    upgradeIfWebSocket(ctx.channel(), future.channel(), request);
                    ctx.channel().config().setOption(ChannelOption.AUTO_READ, true);
                } else {
                    ReferenceCountUtil.release(request);
                    clearReceivedWhenConnect();
                    ctx.channel().config().setOption(ChannelOption.AUTO_READ, true);
                    // 内网沙箱地址不透出给终端浏览器，详情进日志
                    logger.warn("[UserAppProxy] connect backend failed, backend={}", backendKey, future.cause());
                    writeErrorResponse(ctx, HttpResponseStatus.BAD_GATEWAY,
                            pageLang(routeAppId, token, langCookie, acceptLanguage), null, corsOrigin);
                }
            });
        } else {
            // 写与摘编解码器串行放到后端事件循环内执行：跨线程先摘 codec 会让排队中的写任务
            // 错过 httpClientCodec，导致升级请求未被编码就发不出去
            final Channel backendChannel = targetChannel;
            try {
                backendChannel.eventLoop().execute(() -> {
                    backendChannel.attr(UserAppProxyServerContainer.relayContextKey)
                            .set(new UserAppProxyRelayContext(request.method().name(), uri, backendKey,
                                    System.nanoTime(), corsOrigin));
                    backendChannel.writeAndFlush(request);
                    // 已建连的 keep-alive 前端连接上也可能收到升级请求（连接复用）：同样切原生透传，
                    // 否则后续 ws 帧仍会被两侧 HTTP 编解码器解析导致连接中断
                    upgradeIfWebSocket(ctx.channel(), backendChannel, request);
                });
            } catch (RejectedExecutionException e) {
                // 后端事件循环已关闭（连接正在关闭）：释放请求避免泄漏
                ReferenceCountUtil.release(request);
            }
        }
    }

    /**
     * 域名直连（免登录访问）
     * Dev 域名进开发环境，Prod/自定义域名进生产环境。
     */
    private UserAppProxyRoute resolveRouteByHost(HttpRequest request, String uri) {
        String hostHeader = request.headers().get("Host");
        if (StringUtils.isBlank(hostHeader)) {
            return null;
        }
        String domain = hostHeader.trim().toLowerCase().split(":")[0];
        UserAppDomainModel binding = userAppProxyConfigService.resolveDomainBinding(domain);
        if (binding == null) {
            return null;
        }
        String env = UserAppDomainType.Dev == binding.getDomainType()
                ? UserAppProxyRoute.ENV_DEV : UserAppProxyRoute.ENV_PROD;
        UserAppProxyRoute route = UserAppProxyRoute.parse("/api/userapp/proxy/" + UserAppProxyRoute.SERVICE_APP
                + "/" + env + "/" + binding.getAppId() + uri);
        if (route != null) {
            route.setDomainDirect(true);
        }
        return route;
    }

    /** websocket 升级请求识别（与 upgradeIfWebSocket 同一判定，两处共用） */
    private static boolean isWebSocketUpgrade(HttpRequest request) {
        String connection = request.headers().get("Connection");
        String upgrade = request.headers().get("Upgrade");
        return StringUtils.containsIgnoreCase(connection, "Upgrade")
                || StringUtils.containsIgnoreCase(upgrade, "websocket");
    }

    /** websocket 协议升级：摘除前后端 HTTP 编解码器，之后帧数据原生透传 */
    private void upgradeIfWebSocket(Channel frontend, Channel backend, HttpRequest request) {
        if (isWebSocketUpgrade(request)) {
            UserAppProxyRelayContext relay = backend.attr(UserAppProxyServerContainer.relayContextKey).get();
            // 切原生透传后不再有 HTTP 级日志（含 relay 回传行），升级时刻单独记录；同时摘除在途上下文，
            // 避免长连接回收时被响应侧误报为 closed before response
            logger.info("[UserAppProxy] websocket upgrade, uri={}, backend={}",
                    relay == null ? request.uri() : relay.uri(),
                    relay == null ? String.valueOf(backend.remoteAddress()) : relay.backendKey());
            backend.attr(UserAppProxyServerContainer.relayContextKey).set(null);
            // 通道可能已在注销流程中摘除自身 handler，先探测再摘避免 NoSuchElementException
            if (frontend.pipeline().get("httpServerCodec") != null) {
                frontend.pipeline().remove("httpServerCodec");
            }
            if (backend.pipeline().get("httpClientCodec") != null) {
                backend.pipeline().remove("httpClientCodec");
            }
        }
    }

    /** 默认端口省略的 Origin 形式（http:80 / https:443），与浏览器 Origin 头习惯一致 */
    private static String buildOrigin(String scheme, String host, int port) {
        boolean isHttpDefault = "http".equalsIgnoreCase(scheme) && port == 80;
        boolean isHttpsDefault = "https".equalsIgnoreCase(scheme) && port == 443;
        if (isHttpDefault || isHttpsDefault) {
            return scheme + "://" + host;
        }
        return scheme + "://" + host + ":" + port;
    }

    /** token 未取到时部分请求（body 分片等）可能在连接建立前到达：入队或直发 */
    private void forwardToTarget(ChannelHandlerContext ctx, Object msg) {
        if (targetChannel == null) {
            synchronized (receivedLastMsgsWhenConnect) {
                if (targetChannel == null) {
                    if (receivedLastMsgsWhenConnect.size() >= pendingMax) {
                        // 不在此 release(msg)：入队后 ctx.close() 会走 channelInactive -> clearReceivedWhenConnect 统一释放，此处再释放会双重释放
                        logger.warn("[UserAppProxy] pending queue overflow, closing channel. pendingMax={}", pendingMax);
                        // 分片阶段无请求级语言线索，按缺省语言渲染
                        writeErrorResponse(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, null, null, null);
                        ctx.close();
                        return;
                    }
                    receivedLastMsgsWhenConnect.offer(msg);
                    return;
                }
            }
        }
        targetChannel.writeAndFlush(msg);
    }

    /** 依次尝试 _ticket、ticket cookie、Authorization 头；_ticket 兑换成功时挂到通道上由响应侧下发 cookie（绑定域名访问除外：该域属应用命名空间，平台不种 ticket cookie，兑换仅消耗一次性票据） */
    private String resolveToken(ChannelHandlerContext ctx, HttpRequest request, String uri, UserAppProxyRoute route) {
        if (uri.contains("_ticket=")) {
            Map<String, String> params = parseQueryString(uri);
            String ticket = params.get("_ticket");
            if (ticket != null) {
                String token = null;
                try {
                    token = userAppProxyConfigService.exchangeTicket(ticket);
                } catch (Exception e) {
                    logger.warn("[UserAppProxy] exchange ticket failed", e);
                }
                if (token != null) {
                    if (!route.isDomainDirect()) {
                        ctx.channel().attr(UserAppProxyServerContainer.tokenKey).set(token);
                    }
                    return token;
                }
            }
        }
        String cookie = request.headers().get("Cookie");
        // 边界匹配 cookie 名必须是 ticket：indexOf 会误命中 myticket= 等应用自有 cookie
        String cookieToken = cookieValue(TICKET_COOKIE_PATTERN, cookie);
        if (StringUtils.isNotEmpty(cookieToken)) {
            return cookieToken;
        }
        String authorization = request.headers().get("Authorization");
        if (authorization != null) {
            return authorization.replaceFirst("Basic", "").replaceFirst("Bearer", "").trim();
        }
        return null;
    }

    /** 平台域名路径访问时调用：cookie 中的 ticket 是平台会话凭据，任何取值都不透传下游（含与生效 token 不同的残留值）；绑定域名访问不调用（ticket 属应用自有会话） */
    private void stripTicketCookie(HttpRequest request) {
        String cookie = request.headers().get("Cookie");
        if (cookie == null) {
            return;
        }
        cookie = cookie.replaceAll("(^|;\\s*)ticket=[^;]*", "").replaceAll("^[;\\s]+", "").trim();
        if (cookie.isEmpty()) {
            request.headers().remove("Cookie");
        } else {
            request.headers().set("Cookie", cookie);
        }
    }

    /** 鉴权失败：未登录跳登录页（页面导航），无权限/沙箱不可用给出明确页面 */
    private void handleAuthFailure(ChannelHandlerContext ctx, BizException e, String uri, String lang,
            String corsOrigin) {
        if (ErrorCodeEnum.UNAUTHORIZED_REDIRECT.getCode().equals(e.getCode())) {
            // 未登录/会话失效跳转本身不是异常，但静默会掩盖会话失效风暴，记一行便于观测
            logger.info("[UserAppProxy] not logged in, redirect to login, uri={}", uri);
            String baseUrl = "";
            RequestContext<?> requestContext = RequestContext.get();
            if (requestContext != null && requestContext.getTenantConfig() instanceof TenantConfigDto tenantConfig
                    && StringUtils.isNotBlank(tenantConfig.getSiteUrl())) {
                baseUrl = tenantConfig.getSiteUrl();
                baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            }
            String location = baseUrl + "/login?redirect=" + URLEncoder.encode(uri, StandardCharsets.UTF_8);
            ReqResult<Void> error = ReqResult.error(ErrorCodeEnum.UNAUTHORIZED_REDIRECT.getCode(), location);
            FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.FOUND,
                    Unpooled.copiedBuffer(JSON.toJSONString(error), CharsetUtil.UTF_8));
            resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json;charset=UTF-8");
            resp.headers().set(HttpHeaderNames.LOCATION, location);
            if (corsOrigin != null) {
                UserAppProxyCors.apply(resp.headers(), corsOrigin);
            }
            ctx.channel().writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
            return;
        }
        logger.warn("[UserAppProxy] access denied, uri={}, code={}, message={}", uri, e.getCode(), e.getMessage());
        // 具体原因（已本地化的业务提示）作为页面补充说明
        writeErrorResponse(ctx, HttpResponseStatus.FORBIDDEN, lang, e.getMessage(), corsOrigin);
    }

    /** 错误页：按状态映射文案（detailOverride 作为补充说明展示，如 403 的具体原因），与停止页共用骨架 */
    private void writeErrorResponse(ChannelHandlerContext ctx, HttpResponseStatus status, String lang,
            String detailOverride, String corsOrigin) {
        boolean zh = isChinesePreferred(lang);
        String heading;
        String detail;
        if (HttpResponseStatus.NOT_FOUND.equals(status)) {
            heading = zh ? "应用不存在" : "App Not Found";
            detail = zh ? "应用不存在或已下线，请确认访问地址是否正确。"
                    : "This app does not exist or has been taken offline. Please check the address.";
        } else if (HttpResponseStatus.FORBIDDEN.equals(status)) {
            heading = zh ? "无权访问" : "Access Denied";
            detail = zh ? "你没有访问该应用的权限。" : "You do not have permission to access this app.";
        } else if (HttpResponseStatus.BAD_GATEWAY.equals(status)) {
            heading = zh ? "应用服务无法访问" : "App Service Unreachable";
            detail = zh ? "应用服务暂时无法连接，可能正在重启或已停止，请稍后重试。"
                    : "The app's service is temporarily unreachable. It may be restarting or stopped; "
                            + "please retry later.";
        } else if (HttpResponseStatus.SERVICE_UNAVAILABLE.equals(status)) {
            heading = zh ? "服务繁忙" : "Service Busy";
            detail = zh ? "平台服务繁忙，请稍后重试。" : "The platform is busy. Please retry later.";
        } else {
            heading = zh ? "服务异常" : "Service Error";
            detail = zh ? "服务暂时异常，请稍后重试。" : "Something went wrong. Please retry later.";
        }
        String extraLine = StringUtils.isBlank(detailOverride) ? null
                : HtmlUtils.htmlEscape(detailOverride.trim());
        writeStatusPage(ctx, status, zh, heading, null, detail, extraLine, corsOrigin, ICON_ERROR);
    }

    /** 生产停止页：按解析出的语言标记选 zh/en（空值缺省中文） */
    private void writeServiceStoppedPage(ChannelHandlerContext ctx, String appName, String lang,
            String corsOrigin) {
        boolean zh = isChinesePreferred(lang);
        // 应用名用户可控，进页面前转义（防注入）
        String escapedName = HtmlUtils.htmlEscape(StringUtils.defaultString(appName));
        String appLine = zh ? "应用「" + escapedName + "」当前已停止运行"
                : "The app \"" + escapedName + "\" is currently stopped";
        String detail = zh ? "该应用的服务已被停止，暂时无法访问。应用所有者重新启动服务后即可恢复访问。"
                : "This app's service has been stopped and is temporarily unavailable. "
                        + "It will be back once the app owner restarts it.";
        writeStatusPage(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, zh, zh ? "服务已停止" : "Service Stopped",
                appLine, detail, null, corsOrigin, ICON_STOPPED);
    }

    /** 代理状态页（停止页/错误页共用骨架）：自包含样式、无外部资源依赖；状态短暂一律不缓存 */
    private void writeStatusPage(ChannelHandlerContext ctx, HttpResponseStatus status, boolean zh, String heading,
            String appLine, String detail, String extraLine, String corsOrigin, PageIcon icon) {
        String html = "<!DOCTYPE html>\n"
                + "<html lang=\"" + (zh ? "zh-CN" : "en") + "\">\n"
                + "<head>\n"
                + "<meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n"
                + "<title>" + heading + "</title>\n"
                + "<style>\n"
                + "*{margin:0;padding:0;box-sizing:border-box}\n"
                + "html,body{height:100%}\n"
                + "body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC',"
                + "'Hiragino Sans GB','Microsoft YaHei',sans-serif;background:#f6f7f9;color:#252a33;"
                + "display:flex;flex-direction:column;align-items:center;justify-content:center;padding:24px}\n"
                + ".card{max-width:440px;width:100%;background:#fff;border:1px solid #e8eaef;border-radius:16px;"
                + "padding:44px 36px;text-align:center;box-shadow:0 8px 24px rgba(15,20,35,.06)}\n"
                + ".icon{width:56px;height:56px;margin:0 auto 22px;border-radius:50%;background:" + icon.bgLight() + ";"
                + "display:flex;align-items:center;justify-content:center}\n"
                + "h1{font-size:19px;font-weight:600;margin-bottom:12px}\n"
                + ".app{font-size:14px;color:#4b5566;margin-bottom:10px;word-break:break-all}\n"
                + "p{font-size:13px;line-height:1.8;color:#8a93a6}\n"
                + ".extra{margin-top:6px;font-size:12px;color:#a2a9b8}\n"
                + "@media (prefers-color-scheme:dark){\n"
                + "body{background:#14161b;color:#e6e8ec}\n"
                + ".card{background:#1e2128;border-color:#2a2e37;box-shadow:none}\n"
                + ".app{color:#a8b0bf}\n"
                + "p{color:#7d8595}\n"
                + ".extra{color:#6a7280}\n"
                + ".icon{background:" + icon.bgDark() + "}\n"
                + "}\n"
                + "</style>\n"
                + "</head>\n"
                + "<body>\n"
                + "<main class=\"card\">\n"
                + "<div class=\"icon\">" + icon.svg() + "</div>\n"
                + "<h1>" + heading + "</h1>\n"
                + (appLine == null ? "" : "<div class=\"app\">" + appLine + "</div>\n")
                + "<p>" + detail + "</p>\n"
                + (extraLine == null ? "" : "<p class=\"extra\">" + extraLine + "</p>\n")
                + "</main>\n"
                + "</body>\n"
                + "</html>";
        FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
                Unpooled.copiedBuffer(html, CharsetUtil.UTF_8));
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
        resp.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store");
        if (corsOrigin != null) {
            // 跨域 fetch 下的错误页也带 CORS 头：真实 4xx/5xx 能进 JS，而不是在控制台伪装成跨域错误
            UserAppProxyCors.apply(resp.headers(), corsOrigin);
        }
        ctx.channel().writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
    }

    /** 语言解析（停止页/错误页共用，异常回退缺省语言）：无 appId 时跳过用户/租户默认两级 */
    private String pageLang(Long appId, String token, String langCookie, String acceptLanguage) {
        try {
            return userAppProxyConfigService.resolvePageLang(appId, token, langCookie, acceptLanguage);
        } catch (Exception e) {
            logger.warn("[UserAppProxy] resolve page lang failed, appId={}", appId, e);
            return null;
        }
    }

    /** 语言标记是否中文（用户资料/lang cookie/租户默认/Accept-Language 段，如 zh-cn/zh）；空值缺省中文 */
    private static boolean isChinesePreferred(String lang) {
        return StringUtils.isBlank(lang) || lang.trim().toLowerCase().startsWith("zh");
    }

    /** 按 Pattern 从 Cookie 头取值（边界匹配，避免误命中应用自有 cookie）；未命中返回 null */
    private static String cookieValue(Pattern pattern, String cookieHeader) {
        if (StringUtils.isBlank(cookieHeader)) {
            return null;
        }
        Matcher matcher = pattern.matcher(cookieHeader);
        return matcher.find() ? matcher.group(1) : null;
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        if (targetChannel != null) {
            targetChannel.config().setOption(ChannelOption.AUTO_READ, ctx.channel().isWritable());
        }
        super.channelWritabilityChanged(ctx);
    }

    /** 释放挂起消息（通道关闭/异常时避免泄漏） */
    private void clearReceivedWhenConnect() {
        synchronized (receivedLastMsgsWhenConnect) {
            Object pending;
            while ((pending = receivedLastMsgsWhenConnect.poll()) != null) {
                ReferenceCountUtil.release(pending);
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (targetChannel != null) {
            // 前端断开会连带关闭后端：摘除在途上下文，避免响应侧误报 closed before response（用户主动离开属正常）
            targetChannel.attr(UserAppProxyServerContainer.relayContextKey).set(null);
            try {
                targetChannel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            } catch (Exception e) {
                // ignore
            }
        }
        clearReceivedWhenConnect();
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        // 不记的话异常只会到 pipeline 尾部按 DEBUG 输出，生产日志级别下不可见；
        // 连接中断类 IOException 高频（用户关页面/切路由），单行记录避免刷屏
        if (cause instanceof IOException) {
            logger.info("[UserAppProxy] frontend channel io exception: {}", cause.toString());
        } else {
            logger.warn("[UserAppProxy] frontend channel exception", cause);
        }
        clearReceivedWhenConnect();
        super.exceptionCaught(ctx, cause);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            // 前端空闲超时（含 ws 隧道）：双端回收，避免僵尸连接长期占用
            logger.info("[UserAppProxy] frontend idle timeout, closing channel");
            ctx.close();
            Channel peer = ctx.channel().attr(UserAppProxyServerContainer.nextChannelAttributeKey).get();
            if (peer != null) {
                peer.close();
            }
            return;
        }
        super.userEventTriggered(ctx, evt);
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
        } catch (UnknownHostException e) {
            // keep domain as-is, connect will fail and surface 502
        }
        SimpleJvmHashCache.putHash("dns", domain, ip, 600);
        return ip;
    }

    private static Map<String, String> parseQueryString(String url) {
        Map<String, String> params = new HashMap<>();
        try {
            URI parsed = new URI(url);
            String queryString = parsed.getQuery();
            if (queryString != null && !queryString.isEmpty()) {
                String[] pairs = queryString.split("&");
                for (String pair : pairs) {
                    int idx = pair.indexOf("=");
                    if (idx > 0) {
                        params.put(pair.substring(0, idx), pair.substring(idx + 1));
                    }
                }
            }
        } catch (URISyntaxException e) {
            logger.warn("Invalid URL: " + url);
        }
        return params;
    }
}
