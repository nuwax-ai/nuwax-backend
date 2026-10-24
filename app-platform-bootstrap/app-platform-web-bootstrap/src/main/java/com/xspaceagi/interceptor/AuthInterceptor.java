package com.xspaceagi.interceptor;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.custompage.domain.model.CustomPageConfigModel;
import com.xspaceagi.custompage.infra.service.ProxyConfigService;
import com.xspaceagi.sandbox.SandboxRequestAttributes;
import com.xspaceagi.system.application.dto.I18nLangDto;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.*;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.infra.dao.entity.UserReq;
import com.xspaceagi.system.sdk.service.ILicenseRpcService;
import com.xspaceagi.system.spec.auth.UserAuthProperties;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.EditionEnum;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.enums.HttpStatusEnum;
import com.xspaceagi.system.spec.enums.I18nSideEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.license.LicenseResult;
import com.xspaceagi.system.spec.utils.IPUtil;
import com.xspaceagi.system.spec.utils.JwtUtils;
import com.xspaceagi.userapp.application.oauth2.OAuth2CredentialGenerator;
import com.xspaceagi.userapp.sdk.IOAuth2TokenRpcService;
import com.xspaceagi.userapp.sdk.dto.OAuth2TokenContextDto;
import io.jsonwebtoken.Claims;
import jakarta.annotation.Resource;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthInterceptor.class);

    @Value("${jwt.secretKey}")
    private String jwtSecretKey;

    @Resource
    private UserAuthProperties userAuthProperties;

    @Resource
    private AuthService authService;

    @Resource
    private AuthIdpApplicationService authIdpApplicationService;

    // 页面开发代理，查询域名映射关系
    @Resource
    private ProxyConfigService proxyConfigService;

    @Resource
    private TenantConfigApplicationService tenantConfigApplicationService;

    @Resource
    private UserRequestApplicationService userRequestApplicationService;

    @Resource
    private I18nApplicationService i18nApplicationService;

    @Resource
    private I18nLangApplicationService i18nLangApplicationService;

    /**
     * OAuth2 access_token 校验（scope 白名单判定在实现内，本拦截器只负责填充上下文）
     */
    @Resource
    private IOAuth2TokenRpcService oAuth2TokenRpcService;

    @Resource
    private UserApplicationService userApplicationService;

    /**
     * antiXSS 受限 referer 规则：referer path 前缀 → 该上下文唯一放行的 API 命名空间前缀。
     * 页面应用（/page/）与电脑使用静态页（/api/computer/）承载用户生成内容，属不可信执行环境。
     */
    private static final Map<String, String> ANTI_XSS_REFERER_RULES;

    /**
     * OAuth2 授权端点完整路径，与 {@code OAuth2Controller} 的
     * {@code @RequestMapping("/api/oauth2") + @GetMapping("/authorize")} 对应。
     * <p>该端点是本拦截器唯一按「浏览器顶层导航」处理的接口：未登录时 302 到登录页而非返回 JSON，
     * 若控制器路径调整，此处需同步（失配时的降级表现是退回 4010 JSON，不会误放行）。
     */
    private static final String OAUTH2_AUTHORIZE_PATH = "/api/oauth2/authorize";

    /**
     * excludePath 内仍对变更类请求（如 POST）强制 Sec-Fetch 校验的前缀。
     * 只读访问的豁免不应外溢到写操作（CSRF 面）。
     */
    private static final List<String> SEC_FETCH_MUTATING_ENFORCE_PREFIXES = List.of("/api/computer/static");

    /**
     * 变更类 HTTP 方法（OPTIONS 已在 preHandle 入口放行，此处防御性排除）
     */
    private static boolean isMutatingMethod(String method) {
        return !"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method) && !"OPTIONS".equalsIgnoreCase(method);
    }

    private static boolean isSecFetchMutatingEnforcePrefix(String uri) {
        for (String prefix : SEC_FETCH_MUTATING_ENFORCE_PREFIXES) {
            if (uri.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    static {
        Map<String, String> rules = new LinkedHashMap<>();
        rules.put("/page/", "/api/page/");
        rules.put("/api/computer/", "/api/computer/");
        ANTI_XSS_REFERER_RULES = Map.copyOf(rules);
    }

    @Value("${server.port:8080}")
    private Integer port;

    /**
     * 授权码读取（验签结果按授权串缓存，见 LicenseRpcServiceImpl）；本拦截器只按分档决定放行
     */
    @Resource
    private ILicenseRpcService licenseRpcService;

    /**
     * 授权校验不通过时仍予放行的「自救通道」，逗号分隔。授权串本身存在系统配置里，
     * 若把它一并拦掉，客户填错串或换机器后就再也进不去改回来（只能改库）。
     *
     * <p>条目按前缀（{@code startsWith}）匹配，与 {@code auth.excludePath} 第一段同口径；
     * 含通配符的条目走 Ant 匹配。留空 = 完全 fail-closed（连管理面一起拦）。
     */
    @Value("${license-verify.recovery-paths:}")
    private String licenseRecoveryPaths;

    /**
     * 请求处理完之后
     */
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object obj, Exception exc) throws Exception {
        RequestContext.remove();
    }

    /**
     * 请求处理完成
     */
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object obj, ModelAndView model) throws Exception {
        if (RequestContext.get() != null) {
            userRequestApplicationService.addUserRequest(
                    UserReq.builder()
                            .tenantId(RequestContext.get().getTenantId())
                            .userId(RequestContext.get().getUserId() == null ? -1L : RequestContext.get().getUserId())
                            .build()
            );
        }
    }

    /**
     * 请求处理之前
     */
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        request.setCharacterEncoding("UTF-8");
        if (request.getMethod().equals("OPTIONS")) {
            return true;
        }

        // 优先使用 Sandbox Filter 记录的“原始 URI”
        String originalRequestUri = getOriginalRequestUri(request);

        String domainName = request.getHeader("Host");
        if (domainName == null) {
            return false;
        }
        String referer = request.getHeader("Referer");
        // XSS 防护：受限页面上下文（/page/、/api/computer/）只能调用各自命名空间与白名单 API，
        // 用原始 Referer 头判定（不依赖下方可能被改写/拼接的 referer 变量），违规直接拒绝
        antiXSS(referer, originalRequestUri);
        // Sec-Fetch 元数据校验：堵住 antiXSS 的两类规避（跨站 CSRF、no-referrer 隐藏来源），
        // Sec-Fetch-* 由浏览器自动附加，页面脚本无法伪造或删除
        checkFetchMetadata(request, referer, originalRequestUri);
        Long tenantId = tenantConfigApplicationService.queryTenantIdByDomainName(domainName.split(":")[0]);
        // 获取页面应用绑定域名场景下的租户ID
        if (tenantId == null && request.getRequestURI().contains("/api/page/")) {
            CustomPageConfigModel configModel = proxyConfigService.queryCustomPageConfigByDomain(domainName.split(":")[0]);
            if (configModel != null) {
                tenantId = configModel.getTenantId();
                //构造referer，页面的工作流插件调用基于referer中的页面项目id和关联的智能体ID进行关系确认
                referer = "https://" + domainName + "/page/" + configModel.getId() + "-" + configModel.getDevAgentId() + "/prod";
                request.setAttribute("referer", referer);//后面使用
            }
        }
        if (tenantId == null) {
            String headerTenantId = request.getHeader("x-tenant-id");
            if (StringUtils.isNotBlank(headerTenantId)) {
                try {
                    tenantId = Long.parseLong(headerTenantId);
                } catch (NumberFormatException e) {
                    //  ignore
                }
            }
        }
        if (tenantId == null) {
            // 对于无需鉴权的路径（如飞书/钉钉 webhook），使用默认租户，不要求登录
            if (isExcludedPath(originalRequestUri)) {
                tenantId = 1L;
            } else if (domainName.contains("nuwax.com")) {
                throw BizException.of(ErrorCodeEnum.UNAUTHORIZED_REDIRECT, BizExceptionCodeEnum.bootstrapAuthRedirectUrl, "https://nuwax.com");
            }
            if (tenantId == null) {
                tenantId = 1L;
            }
        }
        RequestContext<UserDto> requestContext = RequestContext.<UserDto>builder().tenantId(tenantId).build();
        RequestContext.set(requestContext);
        TenantConfigDto tenantConfig = tenantConfigApplicationService.getTenantConfig(requestContext.getTenantId());
        String fragment = request.getHeader("Fragment");
        if (StringUtils.isNotBlank(referer) && StringUtils.isNotBlank(fragment)) {
            referer = referer + "#" + fragment;
        }
        if (referer != null && !log.isDebugEnabled() && !referer.contains("https://servicewechat.com")) {
            try {
                URL url = new URL(referer);
                String baseUrl;
                if (url.getHost().equals("localhost") || url.getHost().equals("127.0.0.1")) {
                    baseUrl = url.getProtocol() + "://" + url.getHost() + ":" + port;
                } else {
                    baseUrl = url.getProtocol() + "://" + url.getHost() + (url.getPort() == 443 || url.getPort() == 80 || url.getPort() == -1 ? "" : ":" + url.getPort());
                }
                tenantConfig.setSiteConfigUrl(tenantConfig.getSiteUrl());
                tenantConfig.setSiteUrl(baseUrl);
            } catch (MalformedURLException e) {
                // ignore
                log.error("referer url error: {}", e.getMessage());
            }
        }
        tenantConfig.setTenantId(requestContext.getTenantId());
        requestContext.setTenantConfig(tenantConfig);

        //获取终端语言
        requestContext.setClientLang("en-us");
        Locale locale = request.getLocale();
        requestContext.setClientLang(locale.toLanguageTag().toLowerCase());

        //license check
        checkLicense(tenantConfig, originalRequestUri);

        String token = null;
        //优先使用cookie中的token
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (cookie.getName().equals("ticket")) {
                    token = cookie.getValue();
                }
                if (cookie.getName().equals("lang")) {
                    requestContext.setClientCookieLang(cookie.getValue());
                }
            }
        }
        String authorization = request.getHeader("Authorization");
        if (token == null && authorization != null && authorization.length() > 35) {//避免前端传递不符合token的字符串
            token = authorization.replaceFirst("Basic", "").replaceFirst("Bearer", "").trim();
        }

        // OAuth2 access_token（oa- 前缀）：独立令牌体系，按令牌自身携带的租户与 scope 鉴权。
        // 客户端校验与用户查询都沿用本请求解析出的租户（接入应用不跨租户），不做租户切换
        if (token != null && token.startsWith(OAuth2CredentialGenerator.ACCESS_TOKEN_PREFIX)) {
            OAuth2TokenContextDto oauth2Context = oAuth2TokenRpcService.validateAccessToken(token, originalRequestUri);
            if (oauth2Context != null) {
                UserDto userDto = userApplicationService.queryById(oauth2Context.getUserId());
                if (userDto == null || userDto.getStatus() == User.Status.Disabled
                        || userDto.getStatus() == User.Status.Deleted) {
                    throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.systemAccountDisabled);
                }
                requestContext.setUserContext(UserDto.convertToUserContext(userDto));
                requestContext.setUser(userDto);
                requestContext.setUserId(userDto.getId());
                // ★与下面 JWT 分支（本方法 L303）保持一致：线程租户必须取令牌所属用户的租户，
                // 而不是 Host 解析出的那个。MyBatis 的租户行级过滤读的是 RequestContext.tenantId，
                // 漏设会让 /api/oauth2/** 门面端点里的业务查询按错误租户过滤（查不到数据 / 串租户）。
                // 令牌自身的 tenantId 在签发时就取自授权用户，二者必然一致，这里以用户行为准。
                requestContext.setTenantId(userDto.getTenantId());
                requestContext.setClientIp(IPUtil.getIpAddr(request));
                requestContext.setLogin(true);
                requestContext.setToken(token);
                // 有效 scope（已在 validateAccessToken 内按应用当前允许范围收窄），供门面端点做字段级权限判定
                requestContext.setOauth2Scope(oauth2Context.getScope());
                requestContext.setLangMap(userDto.getLangMap());
                requestContext.setLang(userDto.getLang());
                log.debug("OAuth2 access token OK, clientId: {}, userId: {}", oauth2Context.getClientId(),
                        userDto.getId());
                return true;
            }
            // 令牌无效 / 过期 / 已撤销：落回原有逻辑，最终以未登录处理
            log.debug("OAuth2 access token invalid: {}", originalRequestUri);
        }

        if (StringUtils.isNotBlank(token)) {
            try {
                log.debug("jwt token: {}", token);
                UserDto userDto = authService.getLoginUserInfo(token);
                if (userDto != null && userDto.getTenantId().equals(tenantId)) {
                    if (userDto.getStatus() == User.Status.Disabled || userDto.getStatus() == User.Status.Deleted) {
                        authService.expireUserAllToken(userDto.getId());
                        throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.systemAccountDisabled);
                    }
                    var userContent = UserDto.convertToUserContext(userDto);
                    requestContext.setUserContext(userContent);
                    requestContext.setUser(userDto);
                    requestContext.setUserId(userDto.getId());
                    requestContext.setTenantId(userDto.getTenantId());
                    requestContext.setClientIp(IPUtil.getIpAddr(request));
                    requestContext.setLogin(true);
                    requestContext.setToken(token);
                    requestContext.setLangMap(userDto.getLangMap());
                    requestContext.setLang(userDto.getLang());
                    log.debug("JWT token OK, userId: {} ", userDto.getId());
                    if (originalRequestUri.startsWith("/api/system/")) {
                        //判断是不是管理员
                        if (userDto.getRole() != User.Role.Admin) {
                            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
                        }
                    }

                    String header = request.getHeader("X-Client-Type");
                    if (header != null || request.getHeader("Authorization") != null || token.startsWith("ticket")) {
                        authService.renewToken(token);
                    } else {
                        int expire = (int) (tenantConfig.getAuthExpire() == null ? 86400 : tenantConfig.getAuthExpire() * 60);
                        Claims claims = JwtUtils.parseJwt(token, jwtSecretKey);
                        if (claims.getExpiration().getTime() < System.currentTimeMillis() + expire * 1000L * 0.8) {
                            //refresh token
                            String newToken = authService.refreshToken(token);
                            Cookie cookie = new Cookie("ticket", newToken);
                            cookie.setMaxAge(tenantConfig.getAuthExpire() == null ? 86400 * 30 : tenantConfig.getAuthExpire().intValue() * 60);
                            cookie.setPath("/");
                            response.addCookie(cookie);
                            requestContext.setToken(newToken);
                        }
                    }
                    return true;
                }
                log.debug("jwt token expired: {}", token);
            } catch (Exception e) {
                if (e instanceof BizException) {
                    throw e;
                }
                log.warn("JWT token error {}", e.getMessage());
            }
        }
        log.debug("jwt token is null, check uri: {}", originalRequestUri);
        try {
            checkAndThrowAuthException(originalRequestUri);
        } catch (Exception e) {
            if (OAUTH2_AUTHORIZE_PATH.equals(originalRequestUri)) {
                // 授权端点由第三方应用以「浏览器顶层导航」打开：页面不在平台 SPA 内、请求通常没有 Referer，
                // 4011/4010 的 JSON 没有消费方，用户只会看到一屏错误内容。这里直接 302 到登录页并带上回跳地址。
                writeOAuth2AuthorizeLoginRedirect(request, response, originalRequestUri, tenantConfig);
                return false;
            }
            if (StringUtils.isNotBlank(referer)) {
                // 租户配置了「未登录自动跳转」时直接给 IdP authorize 地址，否则维持登录页；originalTarget 带 local=1 时服务端强制回落登录页（逃生门）
                String loginUrl = "/login?redirect=" + URLEncoder.encode(referer, StandardCharsets.UTF_8);
                throw BizException.of(ErrorCodeEnum.UNAUTHORIZED_REDIRECT, BizExceptionCodeEnum.bootstrapAuthRedirectUrl,
                        authIdpApplicationService.resolveUnauthRedirectUrl(loginUrl, referer));
            }
            throw e;
        }
        if (requestContext.getLangMap() == null) {
            String lang = requestContext.getClientCookieLang();
            if (lang == null) {
                I18nLangDto aDefault = i18nLangApplicationService.getDefault(tenantId);
                if (aDefault != null) {
                    lang = aDefault.getLang();
                } else {
                    lang = requestContext.getClientLang();
                }
            }
            requestContext.setLang(lang);
            requestContext.setLangMap(i18nApplicationService.querySystemLangMap(tenantId, I18nSideEnum.Backend.getSide(), lang));
        }
        return true;
    }

    private String getOriginalRequestUri(HttpServletRequest request) {
        Object val = request.getAttribute(SandboxRequestAttributes.ORIGINAL_REQUEST_URI);
        if (val instanceof String s && !s.isEmpty()) {
            return s;
        }
        return request.getRequestURI();
    }

    /**
     * 授权码校验：验签结果按授权串缓存（{@link ILicenseRpcService}），这里只按分档决定放行还是拦截。
     *
     * <p>分档：未配置授权（社区版）放行；验签通过放行（宽限期打告警）；
     * 已过期 → 302 到 /license-expired；无效/被篡改 → 授权内容无效错误码；
     * 设备绑定不符（{@code MISMATCH}）→ 独立错误码 3615，不跳转（跳登录页会让人误以为还能登进去）。
     *
     * <p>★被拦的档位若命中「自救通道」则放行并记 ERROR —— 授权串本身存在系统配置里，若连登录与
     * 配置读写一起拦掉，客户填错串、换机器后就再也进不去改回来。清单见 {@link #licenseRecoveryPaths}。
     * API 与浏览器返回的分流（授权方案 §4.6）尚未实施。
     */
    private void checkLicense(TenantConfigDto tenantConfig, String uri) {
        LicenseResult result = licenseRpcService.getResult(tenantConfig.getLicenseContent());
        if (isLicenseBlocked(result) && isLicenseRecoveryPath(uri)) {
            log.error("授权码校验未通过（{}），但当前请求命中自救通道，予以放行：uri={}，原因={}",
                    result.getStatus(), uri, result.getReason());
            return;
        }
        switch (result.getStatus()) {
            case ABSENT, VALID -> {
                // 放行：无授权（社区版）或授权有效
            }
            case GRACE -> log.warn("授权码处于宽限期：{}", result.getReason());
            case EXPIRED -> throw BizException.of(ErrorCodeEnum.UNAUTHORIZED_REDIRECT,
                    BizExceptionCodeEnum.bootstrapAuthRedirectUrl, "/license-expired");
            case INVALID -> throw BizException.of(ErrorCodeEnum.LICENSE_CONTENT_INVALID,
                    BizExceptionCodeEnum.bootstrapLicenseContentInvalid);
            // 设备绑定不符：串是真的，只是不属于本机。对外码仍是「许可证内容无效」契约码，
            // 但带独立错误码 3615，客户报障时能一眼分清「串被改过」和「拷机器了」
            case MISMATCH -> throw BizException.of(ErrorCodeEnum.LICENSE_CONTENT_INVALID,
                    BizExceptionCodeEnum.bootstrapLicenseDeviceMismatch);
        }
        if (result.getEdition() == EditionEnum.AIOS) {
            tenantConfig.setAiOSCommercialEdition(true);
        }
        if (result.getEdition() == EditionEnum.AISP) {
            tenantConfig.setAiOSCommercialEdition(true);
            tenantConfig.setWorkCommercialEdition(true);
        }
    }

    /**
     * 该档位是否属于「拦下来」：社区版与有效期内的授权放行（含宽限期），其余都拦
     */
    private static boolean isLicenseBlocked(LicenseResult result) {
        return switch (result.getStatus()) {
            case ABSENT, VALID, GRACE -> false;
            case EXPIRED, INVALID, MISMATCH -> true;
        };
    }

    /**
     * 是否命中自救通道：条目按前缀匹配（与 {@code auth.excludePath} 第一段同口径），含通配符的走 Ant 匹配
     */
    private boolean isLicenseRecoveryPath(String uri) {
        if (StringUtils.isBlank(licenseRecoveryPaths) || StringUtils.isBlank(uri)) {
            return false;
        }
        for (String entry : licenseRecoveryPaths.split(",")) {
            String pattern = entry.trim();
            if (pattern.isEmpty()) {
                continue;
            }
            if (hasPathPattern(pattern) ? PATH_MATCHER.match(pattern, uri) : uri.startsWith(pattern)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Ant 风格路径匹配器（Spring 标准：? 单字符、* 单段、** 任意段、{name:regex} 带约束变量段）
     */
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    /**
     * 免登录路径判定（auth.excludePath，两段式）：
     * <ol>
     * <li>先按字符串前缀 startsWith 匹配——存量字面量条目的原有"前缀匹配"约定，零变化；</li>
     * <li>未命中再对含模式字符的条目做 Ant 风格匹配（{@link AntPathMatcher}），语法：
     * {@code ?} 单字符、{@code *} 单段、{@code **} 任意段（支持中部）、{@code {name:regex}}
     * 带正则约束的变量段——如 {@code /api/computer/static/{cId:\d+}/**} 只豁免数字会话段的
     * 静态文件，同前缀下 fs/*、file-list 等登录端点不豁免。注意裸 {@code {name}}（不带约束）
     * 会匹配任意一段，安全敏感条目务必带 {@code :regex} 约束。</li>
     * </ol>
     * 字面量条目不能只靠第二段：Ant 匹配是全路径匹配而非前缀，与存量约定不同，故第一段必须先行
     * （如 /api/file 靠前缀语义放行 /api/file/xxx）。附带效果：历史条目 /webjars/**、/v3/**
     * 在 startsWith 下从未命中（死条目），现经第二段按其 Ant 原意生效（knife4j 文档资源匿名）。
     */
    private boolean isExcludedPath(String uri) {
        List<String> excludePath = userAuthProperties.getExcludePath();
        if (excludePath == null) {
            return false;
        }
        for (String entry : excludePath) {
            if (uri.startsWith(entry)) {
                return true;
            }
        }
        for (String entry : excludePath) {
            if (hasPathPattern(entry) && PATH_MATCHER.match(entry, uri)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否含 Ant 模式字符（? / * / {name}），决定是否进入第二段模式匹配
     */
    private static boolean hasPathPattern(String entry) {
        return entry != null && (entry.contains("{") || entry.contains("*") || entry.contains("?"));
    }

    /**
     * XSS 防护：按 Referer 识别不可信页面上下文，将其可访问的 API 收敛到各自命名空间 + 白名单。
     * <ul>
     * <li>referer path 以 {@code /page/} 开头 → 仅放行 {@code /api/page/**} 与白名单；</li>
     * <li>referer path 以 {@code /api/computer/} 开头 → 仅放行 {@code /api/computer/**} 与白名单；</li>
     * <li>无 referer、referer 非法或非受限上下文 → 不拦截（本方法只收敛这两类页面来源）。</li>
     * </ul>
     * 页面应用与电脑使用静态页承载用户生成内容，一旦被注入脚本，可携带用户登录态调用平台任意接口；
     * 按来源收敛后，越权调用直接拒绝。白名单经 {@code auth.antiXssApiWhitelist} 配置。
     * <p>
     * 注意：Referer 可被客户端伪造/省略，本方法是纵深防御的一层（约束正常浏览器行为的页面上下文），
     * 不能替代接口自身的鉴权与权限校验。
     *
     * @param referer 原始 Referer 请求头（可为空）
     * @param uri     当前请求原始路径（沙箱重写前）
     */
    private void antiXSS(String referer, String uri) {
        if (StringUtils.isBlank(referer) || StringUtils.isBlank(uri)) {
            return;
        }
        String refererPath;
        try {
            refererPath = new URL(referer).getPath();
        } catch (MalformedURLException e) {
            // referer 非法：不拦截，交由后续鉴权处理（与本类既有 referer 解析的容错口径一致）
            log.debug("antiXSS skip, malformed referer: {}", referer);
            return;
        }
        String ownNamespace = null;
        for (Map.Entry<String, String> rule : ANTI_XSS_REFERER_RULES.entrySet()) {
            if (refererPath.startsWith(rule.getKey())) {
                ownNamespace = rule.getValue();
                break;
            }
        }
        if (ownNamespace == null) {
            // 非受限上下文：不拦截
            return;
        }
        if (uri.startsWith(ownNamespace)) {
            return;
        }
        List<String> whitelist = userAuthProperties.getAntiXssApiWhitelist();
        if (whitelist != null) {
            for (String pattern : whitelist) {
                if (antiXssPathMatches(uri, pattern)) {
                    return;
                }
            }
        }
        log.warn("antiXSS blocked, refererPath: {}, uri: {}", refererPath, uri);
        throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
    }

    /**
     * 白名单路径匹配：以 {@code **} 结尾按前缀放行，否则精确或前缀匹配（与 excludePath 的 startsWith 口径一致）
     */
    private static boolean antiXssPathMatches(String uri, String pattern) {
        if (StringUtils.isBlank(pattern)) {
            return false;
        }
        if (pattern.endsWith("**")) {
            return uri.startsWith(pattern.substring(0, pattern.length() - 2));
        }
        return uri.equals(pattern) || uri.startsWith(pattern);
    }

    /**
     * Sec-Fetch 元数据校验：利用浏览器自动附加、脚本无法伪造/删除的 Sec-Fetch-* 请求头，
     * 补上 {@link #antiXSS} 的两类浏览器侧规避：
     * <ul>
     * <li>R1 跨站收紧：{@code Sec-Fetch-Site: cross-site} + {@code Mode: cors/no-cors/websocket} + 携带登录 Cookie
     * —— 典型 CSRF 特征，合法业务中不存在「第三方站点用浏览器带平台 Cookie 跨站调 API」；</li>
     * <li>R2 同源无 Referer：{@code Site: same-origin} + {@code Mode: cors/same-origin} + {@code Dest: empty}
     * （fetch/XHR）+ 携带登录 Cookie + 无 Referer —— 默认 referrer 策略下同源 fetch 必带完整 Referer，
     * 缺失通常意味着脚本刻意 {@code referrerPolicy:'no-referrer'} 规避 antiXSS 的来源判定。</li>
     * </ul>
     * 豁免层（按序）：策略 off、无 Sec-Fetch-Site 头（非浏览器/老浏览器/服务端调用一律放行）、
     * 导航请求（Mode: navigate，如 OAuth 回跳、外部链接进入）、无 ticket Cookie（第三方回调与匿名接口）、
     * excludePath 命中（例外：{@link #SEC_FETCH_MUTATING_ENFORCE_PREFIXES} 前缀下的变更类请求不豁免，
     * 白名单前缀挂有写操作端点时防跨站带登录态写）、{@code auth.secFetchAllow} 白名单命中。
     * same-site 不拦（前端拆子域名部署不受影响）。
     * <p>
     * 档位：{@code auth.secFetchPolicy}（R1 与全局默认）、{@code auth.secFetchNoRefererPolicy}（R2 单独覆盖），
     * 取值 off/log/enforce；log 只记日志，enforce（及其它非 log 值）抛 4030。
     * <p>
     * 注意：裸 HTTP 客户端可伪造 Sec-Fetch 头，本校验只针对浏览器驱动的攻击（XSS/CSRF）威胁模型，
     * 与 antiXSS 同属纵深防御层，不替代接口自身鉴权。
     *
     * @param request 当前请求（读取 Sec-Fetch-* 头与 Cookie）
     * @param referer 原始 Referer 请求头（可为空）
     * @param uri     当前请求原始路径（沙箱重写前）
     */
    private void checkFetchMetadata(HttpServletRequest request, String referer, String uri) {
        String globalPolicy = userAuthProperties.getSecFetchPolicy();
        if (StringUtils.isBlank(globalPolicy) || "off".equalsIgnoreCase(globalPolicy)) {
            return;
        }
        String site = request.getHeader("Sec-Fetch-Site");
        if (StringUtils.isBlank(site)) {
            // 非浏览器客户端 / 老浏览器：无元数据可校验，放行（接口自身鉴权兜底）
            return;
        }
        String mode = request.getHeader("Sec-Fetch-Mode");
        String dest = request.getHeader("Sec-Fetch-Dest");
        if ("navigate".equalsIgnoreCase(mode)) {
            // 浏览器导航（OAuth 回跳、/api/ticket/redirect、外部链接进入等）放行
            return;
        }
        if (!hasTicketCookie(request)) {
            // 不携带登录 Cookie：第三方回调 / 匿名调用，不在 CSRF 威胁面内
            return;
        }
        if (isExcludedPath(uri)) {
            // 免登录白名单接口本就设计为可被第三方调用，不叠加限制。例外：白名单前缀下挂有
            // 写操作端点时（见 SEC_FETCH_MUTATING_ENFORCE_PREFIXES），变更类请求不豁免——跨站带
            // 登录态写（CSRF）不在白名单接口的合法使用面内，继续走下方 R1/R2 校验；
            // GET/HEAD 维持豁免（静态资源跨站引用合法）。
            if (!isMutatingMethod(request.getMethod()) || !isSecFetchMutatingEnforcePrefix(uri)) {
                return;
            }
        }
        List<String> allow = userAuthProperties.getSecFetchAllow();
        if (allow != null) {
            for (String pattern : allow) {
                if (antiXssPathMatches(uri, pattern)) {
                    return;
                }
            }
        }
        String rule;
        String policy;
        if ("cross-site".equalsIgnoreCase(site)
                && ("cors".equalsIgnoreCase(mode) || "no-cors".equalsIgnoreCase(mode)
                || "websocket".equalsIgnoreCase(mode))) {
            rule = "cross-site";
            policy = globalPolicy;
        } else if ("same-origin".equalsIgnoreCase(site)
                && ("cors".equalsIgnoreCase(mode) || "same-origin".equalsIgnoreCase(mode))
                && "empty".equalsIgnoreCase(dest)
                && StringUtils.isBlank(referer)) {
            rule = "same-origin-no-referer";
            policy = StringUtils.defaultIfBlank(userAuthProperties.getSecFetchNoRefererPolicy(), globalPolicy);
        } else {
            return;
        }
        if ("log".equalsIgnoreCase(policy)) {
            log.warn("secFetch suspicious request (log-only), rule: {}, uri: {}, site: {}, mode: {}, dest: {}",
                    rule, uri, site, mode, dest);
            return;
        }
        log.warn("secFetch blocked, rule: {}, uri: {}, site: {}, mode: {}, dest: {}",
                rule, uri, site, mode, dest);
        throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
    }

    /**
     * 是否携带平台登录态 Cookie（ticket）
     */
    private static boolean hasTicketCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        for (Cookie cookie : cookies) {
            if ("ticket".equals(cookie.getName()) && StringUtils.isNotBlank(cookie.getValue())) {
                return true;
            }
        }
        return false;
    }

    private void checkAndThrowAuthException(String uri) {
        if (isExcludedPath(uri)) {
            return;
        }
        throw BizException.of(HttpStatusEnum.UNAUTHORIZED, ErrorCodeEnum.UNAUTHORIZED,
                BizExceptionCodeEnum.systemUnauthorizedOrSessionExpired);
    }

    /**
     * OAuth2 授权端点未登录时的浏览器跳转（302），替代把 4011/4010 的 JSON 直接渲染给用户。
     * <p>跳转目标为 {@code {站点地址}/login?redirect={本次授权请求的完整地址}}，与页面代理
     * （{@code HttpProxyRequestHandler}、{@code UserAppProxyRequestHandler}）的未登录跳转同一约定；
     * 登录页登录成功后按 {@code redirect} 回到授权端点，届时带上登录态即可正常签发授权码并 302
     * 回客户端登记的 {@code redirect_uri}，整条授权流程无缝续上。
     * <p>两个关键细节：
     * <ol>
     * <li><b>查询串原样往返</b>：{@code client_id}、{@code redirect_uri}、{@code scope}、{@code state}
     * 必须逐字保留（RFC 6749 要求 {@code state} 原样回传），故直接拼 {@link HttpServletRequest#getQueryString()}，
     * 不做解析再编码；查询串整体只作为 {@code redirect} 参数值做一次百分号编码。</li>
     * <li><b>站点地址取配置原值</b>：本方法之前的 referer 分支会把 {@code siteUrl} 改写成 Referer 的源，
     * 第三方应用带 Referer 跳转过来时会指向对方域名；{@code siteConfigUrl} 保存的才是改写前的租户站点地址，
     * 故优先取它。站点地址未配置时退化为相对地址，与登录页同源，仍然可用。</li>
     * </ol>
     * <p>响应体沿用页面代理的写法（带 4011 的 ReqResult JSON）：302 的 body 浏览器不消费，
     * 但保留它可让 {@code curl -i} 等调试场景直接看出跳转原因。
     */
    private static void writeOAuth2AuthorizeLoginRedirect(HttpServletRequest request, HttpServletResponse response,
                                                          String uri, TenantConfigDto tenantConfig) throws IOException {
        String queryString = request.getQueryString();
        String target = StringUtils.isBlank(queryString) ? uri : uri + "?" + queryString;
        String base = tenantConfig == null ? null
                : StringUtils.defaultIfBlank(tenantConfig.getSiteConfigUrl(), tenantConfig.getSiteUrl());
        base = StringUtils.trimToEmpty(base);
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        // 站点地址可用时用绝对地址（与既有约定一致），否则退化为相对地址，避免依赖可伪造的 Host 头
        String location = base + "/login?redirect="
                + URLEncoder.encode(base.isEmpty() ? target : base + target, StandardCharsets.UTF_8);
        log.info("OAuth2 authorize requires login, redirect to login page, uri={}", uri);
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader("Location", location);
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JSON.toJSONString(
                ReqResult.error(ErrorCodeEnum.UNAUTHORIZED_REDIRECT.getCode(), location)));
    }
}