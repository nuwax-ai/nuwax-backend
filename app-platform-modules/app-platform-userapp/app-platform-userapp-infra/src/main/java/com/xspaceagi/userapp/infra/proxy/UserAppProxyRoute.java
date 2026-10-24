package com.xspaceagi.userapp.infra.proxy;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.Data;

/**
 * 代理路由解析结果
 *
 * 前端（nginx 转发到本代理）：/api/userapp/proxy/{service}/{env}/{app_id}/{*path}
 * 下游：/api/v1/userapp/proxy/{service}/{env}/{user_id}/{app_id}/{*path}
 */
@Data
public class UserAppProxyRoute {

    public static final String ENV_DEV = "dev";

    public static final String ENV_PROD = "prod";

    public static final String SERVICE_APP = "app";

    public static final String SERVICE_DBX = "dbx";

    public static final String SERVICE_VNC = "vnc";

    public static final String SERVICE_TTYD = "ttyd";

    public static final String SERVICE_IME = "ime";

    public static final String SERVICE_AUDIO = "audio";

    /** 前端入口 */
    private static final Pattern ROUTE_PATTERN =
            Pattern.compile("^/api/userapp/proxy/(app|dbx|vnc|ttyd|ime|audio)/(dev|prod)/(\\d+)(/.*)?$");

    /** 代理的服务 */
    private String service;

    /** 环境（dev/prod） */
    private String env;

    /** 应用ID */
    private Long appId;

    /** 应用内子路径（透传下游，缺省 "/"） */
    private String subPath;

    /** 原始 query（去掉平台内部参数 _ticket 后透传下游，可为 null） */
    private String query;

    /** 域名直连路由（Host 解析而来）：鉴权一律免登录（区别于路径路由的 app/dev） */
    private boolean domainDirect;

    public boolean isApp() {
        return SERVICE_APP.equals(service);
    }

    public boolean isDbx() {
        return SERVICE_DBX.equals(service);
    }

    /** 交互类服务（vnc/ttyd/ime/audio）：websocket 长连接访问沙箱桌面/终端/输入法/音频 */
    public boolean isInteractiveService() {
        return SERVICE_VNC.equals(service) || SERVICE_TTYD.equals(service)
                || SERVICE_IME.equals(service) || SERVICE_AUDIO.equals(service);
    }

    /** 已开通的服务 */
    public boolean isSupportedService() {
        return isApp() || isDbx() || isInteractiveService();
    }

    public static UserAppProxyRoute parse(String uri) {
        if (uri == null || uri.isEmpty()) {
            return null;
        }
        // 路由只按路径部分匹配（?_ticket=xxx 等查询串不参与），query 处理后透传
        int queryStart = uri.indexOf('?');
        String path = queryStart >= 0 ? uri.substring(0, queryStart) : uri;
        String query = queryStart >= 0 ? stripInternalParams(uri.substring(queryStart + 1)) : null;
        Matcher matcher = ROUTE_PATTERN.matcher(path);
        if (!matcher.matches()) {
            return null;
        }
        Long appId;
        try {
            appId = Long.parseLong(matcher.group(3));
        } catch (NumberFormatException e) {
            // 超长数字溢出：按路由不匹配处理
            return null;
        }
        UserAppProxyRoute route = new UserAppProxyRoute();
        route.setService(matcher.group(1));
        route.setEnv(matcher.group(2));
        route.setAppId(appId);
        String subPath = matcher.group(4);
        route.setSubPath(subPath == null || subPath.isEmpty() ? "/" : subPath);
        route.setQuery(query == null || query.isEmpty() ? null : query);
        return route;
    }

    /** 剔除平台内部参数（_ticket 是一次性登录票据，属于平台侧凭据，不下发沙箱） */
    private static String stripInternalParams(String query) {
        String stripped = query.replaceAll("(^|&)_ticket=[^&]*", "");
        return stripped.startsWith("&") ? stripped.substring(1) : stripped;
    }

    /**
     * 下游目标路径
     */
    public String downstreamUri(Long userId) {
        String base = "/api/v1/userapp/proxy/" + service + "/" + env + "/" + userId + "/" + appId + subPath;
        return query == null ? base : base + "?" + query;
    }
}
