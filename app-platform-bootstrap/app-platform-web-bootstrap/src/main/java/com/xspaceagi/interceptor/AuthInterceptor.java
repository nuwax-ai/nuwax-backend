package com.xspaceagi.interceptor;

import com.xspaceagi.custompage.domain.model.CustomPageConfigModel;
import com.xspaceagi.custompage.infra.service.ProxyConfigService;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.AuthService;
import com.xspaceagi.system.application.service.TenantConfigApplicationService;
import com.xspaceagi.system.application.service.UserRequestApplicationService;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.infra.dao.entity.UserReq;
import com.xspaceagi.system.spec.auth.UserAuthProperties;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.enums.HttpStatusEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.utils.IPUtil;
import com.xspaceagi.system.spec.utils.JwtUtils;
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
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthInterceptor.class);

    @Value("${jwt.secretKey}")
    private String jwtSecretKey;

    @Resource
    private UserAuthProperties userAuthProperties;

    @Resource
    private AuthService authService;

    // 页面开发代理，查询域名映射关系
    @Resource
    private ProxyConfigService proxyConfigService;

    @Resource
    private TenantConfigApplicationService tenantConfigApplicationService;

    @Resource
    private UserRequestApplicationService userRequestApplicationService;

    @Value("${server.port:8080}")
    private Integer port;

    @Value("${license:}")
    private String license;

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

        //license check
        checkLicense();

        String domainName = request.getHeader("Host");
        if (domainName == null) {
            return false;
        }
        String referer = request.getHeader("Referer");
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
            // 对于无需鉴权的路径（如飞书/钉钉 webhook），使用默认租户，不要求登录
            if (isExcludedPath(request.getRequestURI())) {
                tenantId = 1L;
            } else if (domainName.contains("nuwax.com")) {
                throw new BizException("4011", "https://nuwax.com");
            }
            String headerTenantId = request.getHeader("x-tenant-id");
            if (StringUtils.isNotBlank(headerTenantId)) {
                try {
                    tenantId = Long.parseLong(headerTenantId);
                } catch (NumberFormatException e) {
                    //  ignore
                }
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

        String token = null;
        //优先使用cookie中的token
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (cookie.getName().equals("ticket")) {
                    token = cookie.getValue();
                    break;
                }
            }
        }
        String authorization = request.getHeader("Authorization");
        if (token == null && authorization != null && authorization.length() > 35) {//避免前端传递不符合token的字符串
            token = authorization.replaceFirst("Basic", "").replaceFirst("Bearer", "").trim();
        }

        if (StringUtils.isNotBlank(token)) {
            try {
                log.debug("jwt token: {}", token);
                UserDto userDto = authService.getLoginUserInfo(token);
                if (userDto != null && userDto.getTenantId().equals(tenantId)) {
                    if (userDto.getStatus() == User.Status.Disabled) {
                        authService.expireUserAllToken(userDto.getId());
                        throw new BizException(ErrorCodeEnum.PERMISSION_DENIED.getCode(), "账号已被禁用");
                    }
                    var userContent = UserDto.convertToUserContext(userDto);
                    requestContext.setUserContext(userContent);
                    requestContext.setUser(userDto);
                    requestContext.setUserId(userDto.getId());
                    requestContext.setTenantId(userDto.getTenantId());
                    requestContext.setClientIp(IPUtil.getIpAddr(request));
                    requestContext.setLogin(true);
                    requestContext.setToken(token);
                    log.debug("jwt token验证通过, userId: {} ", userDto.getId());
                    if (request.getRequestURI().startsWith("/api/system/")) {
                        //判断是不是管理员
                        if (userDto.getRole() != User.Role.Admin) {
                            throw new BizException("你没有权限");
                        }
                    }

                    String header = request.getHeader("X-Client-Type");
                    if (header != null || token.startsWith("ticket")) {
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
                log.warn("jwt token验证错误 {}", e.getMessage());
            }
        }
        log.debug("jwt token is null, check uri: {}", request.getRequestURI());
        try {
            checkAndThrowAuthException(request.getRequestURI());
        } catch (Exception e) {
            if (StringUtils.isNotBlank(referer)) {
                throw new BizException("4011", "/login?redirect=" + URLEncoder.encode(referer, StandardCharsets.UTF_8));
            }
            throw e;
        }
        return true;
    }

    private void checkLicense() {
        if (StringUtils.isNotBlank(license)) {
            String expireDate;
            try {
                String hexKey = "a0189de032115b6b7031a8fb5194ed68b3c716d3e09c21592fe4187b4586048b"; // 256位
                String hexIV = "0ac97ac7d1081ebae42bfd987c5739eb";
                expireDate = AESCrypto.decryptWithHexKey(license, hexKey, hexIV);
            } catch (Exception e) {
                log.error("license error: {}", e.getMessage());
                throw new BizException("0400", "error license content");
            }
            if (parseToTimestamp(expireDate) * 1000L < System.currentTimeMillis()) {
                throw new BizException("4011", "/license-expired");
            }

        }
    }

    public static long parseToTimestamp(String expireDate) {
        if (expireDate == null || expireDate.isEmpty()) return -1;

        try {
            // 标准化分隔符
            String normalized = expireDate
                    .replace('/', '-')
                    .replace('.', '-');

            // 处理 8 位纯数字
            if (normalized.matches("\\d{8}")) {
                return LocalDate.parse(normalized, DateTimeFormatter.BASIC_ISO_DATE)
                        .atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
            }

            // 处理带 T 和时区的 ISO 格式
            if (normalized.contains("T")) {
                if (normalized.endsWith("Z")) {
                    return Instant.parse(normalized).getEpochSecond();
                }
                return OffsetDateTime.parse(normalized, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                        .toInstant().getEpochSecond();
            }

            // 处理日期 + 时间（空格分隔）
            if (normalized.contains(" ")) {
                return LocalDateTime.parse(normalized,
                                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        .atZone(ZoneId.systemDefault()).toEpochSecond();
            }

            // 仅日期
            return LocalDate.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE)
                    .atStartOfDay(ZoneId.systemDefault()).toEpochSecond();

        } catch (Exception e) {
            return -1;
        }
    }

    private boolean isExcludedPath(String uri) {
        List<String> excludePath = userAuthProperties.getExcludePath();
        for (String ignoreLoginUri : excludePath) {
            if (uri.startsWith(ignoreLoginUri)) {
                return true;
            }
        }
        return false;
    }

    private void checkAndThrowAuthException(String uri) {
        if (isExcludedPath(uri)) {
            return;
        }
        throw new BizException(HttpStatusEnum.UNAUTHORIZED, ErrorCodeEnum.UNAUTHORIZED);
    }
}