package com.xspaceagi.custompage.infra.proxy;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.agent.core.adapter.application.PublishApplicationService;
import com.xspaceagi.agent.core.adapter.dto.PublishedDto;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.custompage.domain.model.CustomPageConfigModel;
import com.xspaceagi.custompage.infra.service.ProxyAuthService;
import com.xspaceagi.custompage.infra.service.ProxyConfigService;
import com.xspaceagi.custompage.infra.vo.BackendVo;
import com.xspaceagi.custompage.infra.vo.ProxyAuthVo;
import com.xspaceagi.custompage.sdk.dto.ProxyConfig;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.sdk.permission.IUserDataPermissionRpcService;
import com.xspaceagi.system.sdk.service.dto.UserDataPermissionDto;
import com.xspaceagi.system.spec.cache.SimpleJvmHashCache;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.exception.BizException;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLHandshakeException;
import java.io.InputStream;
import java.net.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HttpProxyRequestHandler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(HttpProxyRequestHandler.class);

    private static final String PAGE_403_HTML = load403PageHtml();

    private final Queue<Object> receivedLastMsgsWhenConnect = new LinkedList<>();
    private final Bootstrap proxyClientBootstrap;
    private final Bootstrap httpsProxyClientBootstrap;
    private Channel targetChannel;

    private final ProxyConfigService proxyConfigService;
    private final ProxyAuthService proxyAuthService;
    private final PublishApplicationService publishApplicationService;
    private final IUserDataPermissionRpcService userDataPermissionRpcService;

    public HttpProxyRequestHandler(Bootstrap proxyClientBootstrap, Bootstrap httpsProxyClientBootstrap, ProxyConfigService proxyConfigService,
                                   ProxyAuthService proxyAuthService, PublishApplicationService publishApplicationService, IUserDataPermissionRpcService userDataPermissionRpcService) {
        this.proxyClientBootstrap = proxyClientBootstrap;
        this.httpsProxyClientBootstrap = httpsProxyClientBootstrap;
        this.proxyConfigService = proxyConfigService;
        this.proxyAuthService = proxyAuthService;
        this.publishApplicationService = publishApplicationService;
        this.userDataPermissionRpcService = userDataPermissionRpcService;
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
        if (msg instanceof HttpRequest) {
            DefaultHttpRequest request = (DefaultHttpRequest) msg;
            String uri = request.uri() == null ? "/" : request.uri();
            String token = null;
            // 处理_ticket兑换token
            if (uri != null && uri.contains("_ticket=")) {
                Map<String, String> parseQueryString = parseQueryString(uri);
                if (parseQueryString.containsKey("_ticket")) {
                    token = proxyAuthService.getTokenByTicket(parseQueryString.get("_ticket"));
                    if (token != null) {
                        request.headers().set("Authorization", "Bearer " + token);
                        ctx.channel().attr(ProxyServerContainer.tokenKey).set(token);
                    }
                }
            }

            String basePath = null;
            Long agentId = null;
            Long pageId = null;
            ProxyConfig.ProxyEnv env = null;
            String realUri = "/";
            boolean isPage = false;
            boolean isCustomDomain = false;
            // uri样式 /page/动态字符1-动态数字/动态字符2/xxxxxx 或 /page/动态字符1-动态数字/动态字符2（可选斜杠）
            String pattern = "/page/(\\w+)(?:-(\\d+))?/(\\w+)/?(.*)";
            Pattern regex = Pattern.compile(pattern);
            Matcher matcher = regex.matcher(uri);
            if (!matcher.matches()) {
                CustomPageConfigModel configModel = proxyConfigService.queryCustomPageConfigByDomain(request.headers().get("Host"));
                if (configModel != null) {
                    uri = "/page/" + configModel.getId() + "-" + configModel.getDevAgentId() + "/prod" + uri;
                    matcher = regex.matcher(uri);
                    isCustomDomain = true;
                }
            }
            if (matcher.matches()) {
                String pageIdStr = matcher.group(1);
                basePath = "/" + pageIdStr;
                try {
                    pageId = Long.parseLong(pageIdStr);
                } catch (NumberFormatException e) {
                    // ignore pageId parse error
                }
                try {
                    if (matcher.group(2) != null) {
                        agentId = Long.parseLong(matcher.group(2));
                        isPage = true;
                    }
                } catch (NumberFormatException e) {
                    // ignore
                }
                env = ProxyConfig.ProxyEnv.get(matcher.group(3));
                String u = matcher.group(4);
                if (u != null && !u.isEmpty()) {
                    realUri = realUri + u;
                }
                logger.debug("URL解析成功 - URI: {}, basePath: {}, agentId: {}, env: {}, realUri: {}",
                        uri, basePath, agentId, env, realUri);
            } else {
                logger.warn("URL解析失败，不匹配正则表达式 - URI: {}", uri);
            }

            if (agentId == null) {
                String refer = request.headers().get("Referer");
                if (refer != null) {
                    matcher = regex.matcher(refer);
                    if (matcher.matches()) {
                        try {
                            if (matcher.group(2) != null) {
                                agentId = Long.parseLong(matcher.group(2));
                            }
                        } catch (NumberFormatException e) {
                            // ignore
                        }
                    }
                }
            }

            BackendVo backendVo;
            try {
                if (!proxyAuthService.initTenantContext(agentId)) {
                    ReferenceCountUtil.release(msg);
                    writeErrorResponse(ctx, HttpResponseStatus.NOT_FOUND, "Agent Not Match");
                    return;
                }
                backendVo = proxyConfigService.selectBackend(basePath, realUri, env, agentId);
            } catch (Exception e) {
                RequestContext.remove();
                ReferenceCountUtil.release(msg);
                writeErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                        "500 Internal Server Error (" + e.getMessage() + ")");
                return;
            }
            if (backendVo == null) {
                RequestContext.remove();
                ReferenceCountUtil.release(msg);
                writeErrorResponse(ctx, HttpResponseStatus.NOT_FOUND, "404 Not Found");
                return;
            }

            request.setUri(backendVo.getUri());
            String cookie = request.headers().get("Cookie");
            String authorization = request.headers().get("Authorization");
            request.headers().remove("Authorization");
            // 对于API补充认证信息

            // 从cookie中获取ticket认证信息
            if (cookie != null && token == null) {
                //去除多余的空内容
                cookie = cookie.replace("ticket=;", "");
                int start = cookie.indexOf("ticket=");
                if (start >= 0) {
                    int end = cookie.indexOf(";", start);
                    if (end > 0) {
                        token = cookie.substring(start + 7, end);
                    } else {
                        token = cookie.substring(start + 7);
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
                }
            }

            try {
                ProxyAuthVo proxyAuthVo = proxyAuthService.getProxyAuthVo(token, agentId, env, backendVo);
                // prod 环境页面访问权限校验：无权限返回一个无权限页面
                if (env == ProxyConfig.ProxyEnv.prod && isPage && publishApplicationService != null) {
                    Long userId = proxyAuthVo.getUser() != null ? proxyAuthVo.getUser().getUserId() : null;
                    if (userId != null) {
                        PublishedDto published = publishApplicationService.queryPublished(Published.TargetType.Agent, agentId);
                        if (published != null && published.getAccessControl() != null && published.getAccessControl().equals(YesOrNoEnum.Y.getKey())) {
                            JSONObject jsonObject = JSON.parseObject(published.getConfig());
                            if (!userId.equals(jsonObject.getLong("creatorId"))) {
                                UserDataPermissionDto dp = userDataPermissionRpcService.getUserDataPermission(userId);
                                boolean hasPermission = dp != null && dp.getPageAgentIds() != null && dp.getPageAgentIds().contains(agentId);
                                if (!hasPermission) {
                                    ReferenceCountUtil.release(msg);
                                    write403Page(ctx);
                                    return;
                                }
                            }
                        }
                    }
                }
                // 增加空间信息、用户角色、判读用户权限（没有权限时不能访问）
                String loginInfo = JSON.toJSONString(proxyAuthVo);
                String encodeBase64String = Base64.encodeBase64String(loginInfo.getBytes(Charset.forName("UTF-8")));
                request.headers().set("Authorization", "Basic " + encodeBase64String);
            } catch (Exception e) {
                if ((e instanceof BizException) && ((BizException) e).getCode().equals(ErrorCodeEnum.UNAUTHORIZED_REDIRECT.getCode())
                        && backendVo.isRequireAuth()) {
                    String redirectUrl = "/";
                    String referer = request.headers().get("Referer");
                    String baseUrl = "";
                    if (StringUtils.isNotBlank(referer) && !isCustomDomain) {
                        try {
                            URL url = new URL(referer);
                            baseUrl = url.getProtocol() + "://" + url.getHost() + (url.getPort() == 443 || url.getPort() == 80 || url.getPort() == -1 ? "" : ":" + url.getPort());
                        } catch (MalformedURLException ex) {
                            // ignore
                        }
                    } else {
                        TenantConfigDto tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
                        baseUrl = tenantConfigDto.getSiteUrl() != null ? tenantConfigDto.getSiteUrl() : baseUrl;
                        baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
                    }
                    if (isPage) {
                        redirectUrl = baseUrl + uri;
                    } else {
                        String fragment = request.headers().get("Fragment");
                        if (StringUtils.isNotBlank(referer) && StringUtils.isNotBlank(fragment)) {
                            redirectUrl = referer + "#" + fragment;
                        }
                    }
                    String location = baseUrl + "/login?redirect=" + URLEncoder.encode(redirectUrl, StandardCharsets.UTF_8);
                    ReqResult<Void> error = ReqResult.error(ErrorCodeEnum.UNAUTHORIZED_REDIRECT.getCode(), location);
                    FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.FOUND,
                            Unpooled.copiedBuffer(JSON.toJSONString(error), CharsetUtil.UTF_8));
                    resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json;charset=UTF-8");
                    resp.headers().set(HttpHeaderNames.LOCATION, location);
                    ReferenceCountUtil.release(msg);
                    ctx.channel().writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
                    return;
                } else if (!(e instanceof BizException)) {
                    if (!isPage) {
                        ReqResult<Void> error = ReqResult.error(ErrorCodeEnum.PERMISSION_DENIED.getCode(), e.getMessage());
                        FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                                Unpooled.copiedBuffer(JSON.toJSONString(error), CharsetUtil.UTF_8));
                        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json;charset=UTF-8");
                        ReferenceCountUtil.release(msg);
                        ctx.channel().writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
                        return;
                    }
                }
                RequestContext.remove();
            }

            request.headers().set("Host", backendVo.getHost());

            String remoteIp = ((InetSocketAddress) ctx.channel().remoteAddress()).getAddress().getHostAddress();
            if (request.headers().get("X-Forwarded-For") == null) {
                request.headers().set("X-Forwarded-For", remoteIp);
            } else {
                request.headers().set("X-Forwarded-For", request.headers().get("X-Forwarded-For") + "," + remoteIp);
            }
            if (request.headers().get("X-Real-IP") == null) {
                request.headers().set("X-Real-IP", remoteIp);
            }
            if (targetChannel == null) {
                ctx.channel().config().setOption(ChannelOption.AUTO_READ, false);
                Bootstrap clientBootstrap = proxyClientBootstrap;
                if (backendVo.getScheme().equals("https")) {
                    clientBootstrap = httpsProxyClientBootstrap;
                }
                String ip = resolveDns(backendVo.getHost());
                clientBootstrap.connect(ip, backendVo.getPort()).addListener((ChannelFutureListener) future -> {
                    // 连接后端服务器成功
                    if (future.isSuccess()) {
                        future.channel().writeAndFlush(msg);
                        synchronized (receivedLastMsgsWhenConnect) {
                            Object msg0;
                            while ((msg0 = receivedLastMsgsWhenConnect.poll()) != null) {
                                future.channel().writeAndFlush(msg0);
                            }
                            targetChannel = future.channel();
                            targetChannel.attr(ProxyServerContainer.nextChannelAttributeKey).set(ctx.channel());
                            ctx.channel().attr(ProxyServerContainer.nextChannelAttributeKey).set(targetChannel);
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
                        ReferenceCountUtil.release(msg);
                        clearReceivedWhenConnect();
                        ctx.channel().config().setOption(ChannelOption.AUTO_READ, true);
                        writeErrorResponse(ctx, HttpResponseStatus.BAD_GATEWAY,
                                "502 Bad Gateway (" + backendVo.getHost() + ":" + backendVo.getPort() + ")");
                    }
                });
            } else {
                targetChannel.writeAndFlush(msg);
            }

            // 移除上下文信息
            RequestContext.remove();
        } else {
            if (targetChannel == null) {
                synchronized (receivedLastMsgsWhenConnect) {
                    if (targetChannel == null) {
                        receivedLastMsgsWhenConnect.offer(msg);
                    } else {
                        targetChannel.writeAndFlush(msg);
                    }
                }
            } else {
                targetChannel.writeAndFlush(msg);
            }
        }
    }

    private void writeErrorResponse(ChannelHandlerContext ctx, HttpResponseStatus httpResponseStatus, String message) {
        FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, httpResponseStatus,
                Unpooled.copiedBuffer("<html> <head><title>" + message + "</title></head> <body>" +
                        "<center><h1>" + message + "</h1></center> <hr><center>NUWAX/0.1</center>" +
                        "</body></html>", CharsetUtil.UTF_8));
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
        ctx.channel().writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
    }

    private static String load403PageHtml() {
        try (InputStream in = HttpProxyRequestHandler.class.getResourceAsStream("/403.html")) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            logger.warn("加载 403 页面失败，将使用默认文案: {}", e.getMessage());
        }
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\"><title>403 - 无权限访问</title></head><body><div style=\"text-align:center;padding:40px;\"><h1>403</h1><p>抱歉，您没有权限访问此页面</p></div></body></html>";
    }

    private void write403Page(ChannelHandlerContext ctx) {
        FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.FORBIDDEN,
                Unpooled.copiedBuffer(PAGE_403_HTML, CharsetUtil.UTF_8));
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
        ctx.channel().writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        if (targetChannel != null) {
            targetChannel.config().setOption(ChannelOption.AUTO_READ, ctx.channel().isWritable());
        }
        super.channelWritabilityChanged(ctx);
    }

    /**
     * 释放挂起消息（通道关闭/异常时避免泄漏）
     */
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
            try {
                targetChannel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            } catch (Exception e) {
            }
        }
        clearReceivedWhenConnect();
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (cause.getCause() != null && (cause.getCause() instanceof SSLHandshakeException)) {
            logger.warn("SSLHandshakeException: {}", cause.getCause().getMessage());
            return;
        }
        clearReceivedWhenConnect();
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
        } catch (Exception e) {
        }
        SimpleJvmHashCache.putHash("dns", domain, ip, 600);
        return ip;
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
            logger.warn("Invalid URL: " + url);
        }
        return params;
    }
}
