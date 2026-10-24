package com.xspaceagi.system.web.controller;

import com.xspaceagi.system.application.dto.AuthIdpCallbackResult;
import com.xspaceagi.system.application.dto.AuthIdpLoginListVo;
import com.xspaceagi.system.application.dto.AuthIdpLoginResult;
import com.xspaceagi.system.application.dto.AuthIdpPendingVo;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.service.AuthIdpApplicationService;
import com.xspaceagi.system.application.service.AuthService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.web.dto.AuthIdpPendingTokenDto;
import com.xspaceagi.system.web.dto.LoginResDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * 外部登录（CAS / 标准 OAuth2 / 微信）公开端点
 */
@Slf4j
@Tag(name = "外部登录", description = "CAS / OAuth2 / 微信统一登录端点")
@RestController
@RequestMapping("/api/auth/idp")
public class AuthIdpController {

    @Resource
    private AuthIdpApplicationService authIdpApplicationService;

    @Resource
    private AuthService authService;

    @Operation(summary = "登录页可用的第三方登录方式列表")
    @GetMapping("/list")
    public ReqResult<AuthIdpLoginListVo> list() {
        return ReqResult.success(authIdpApplicationService.listForLogin());
    }

    @Operation(summary = "发起第三方登录（302 跳转到 IdP）。仅登录语义：绑定外部身份请走 /api/user/identity/bind/{providerId}")
    @GetMapping("/authorize")
    public void authorize(@Parameter(description = "登录方式ID", required = true) @RequestParam("provider") Long providerId,
            @Parameter(description = "登录完成后的回跳目标（本站相对路径，非法值会被重置为 /）") @RequestParam(value = "redirect", required = false) String redirect,
            HttpServletResponse response) throws Exception {
        // 浏览器顶层导航请求，异常 JSON 无消费方：与 callback 同口径 302 回登录页带错误提示
        try {
            String url = authIdpApplicationService.buildAuthorizeUrl(providerId, redirect, false);
            redirectTo(response, url);
        } catch (Exception e) {
            log.warn("idp authorize failed, providerId={}, error={}", providerId, e.getMessage());
            redirectTo(response, "/login?idpError=" + urlEncode(e.getMessage() == null ? "无法发起登录" : e.getMessage()));
        }
    }

    /**
     * 平台登出（导航端点，前端退出按钮改为 location.href 跳此地址）：
     * 清平台会话后，若会话来自 CAS 型 IdP，302 到 IdP 的 /cas/logout 清 TGT（单点登出），
     * 否则直接回登录页。两种情况都带 local=1，防止「未登录自动跳转」立刻把用户送回 IdP。
     */
    @Operation(summary = "平台登出（CAS 会话联动登出 IdP）")
    @GetMapping("/logout")
    public void logout(HttpServletRequest request, HttpServletResponse response) throws java.io.IOException {
        String idpLogoutUrl = null;
        if (RequestContext.get().isLogin()) {
            idpLogoutUrl = authIdpApplicationService.buildSessionIdpLogoutUrl();
            authService.expireToken(RequestContext.get().getToken());
        }
        Cookie cookie = new Cookie("ticket", "");
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
        // 见 redirectTo 注释：不走 sendRedirect，防止相对地址被容器按 http 补全
        redirectTo(response, idpLogoutUrl != null ? idpLogoutUrl : "/login?local=1");
    }


    @Operation(summary = "IdP 回跳处理（302 到最终目标或绑定/注册中间页）")
    @GetMapping("/callback")
    public void callback(@Parameter(description = "登录方式ID", required = true) @RequestParam("provider") Long providerId,
            @Parameter(description = "OAuth2 授权码（OAuth2/微信回跳携带，与 ticket 二选一）") @RequestParam(value = "code", required = false) String code,
            @Parameter(description = "CAS service ticket（CAS 回跳携带，与 code 二选一）") @RequestParam(value = "ticket", required = false) String ticket,
            @Parameter(description = "防 CSRF 随机串（发起授权时生成，CAS 经 service 参数携带回跳）") @RequestParam(value = "state", required = false) String state,
            @Parameter(description = "IdP 返回的错误码（授权失败回跳时）") @RequestParam(value = "error", required = false) String error,
            @Parameter(description = "IdP 返回的错误描述") @RequestParam(value = "error_description", required = false) String errorDescription,
            HttpServletResponse response) throws Exception {
        try {
            if (StringUtils.isNotBlank(error)) {
                throw new IllegalArgumentException(
                        StringUtils.defaultIfBlank(errorDescription, "身份提供方返回错误: " + error));
            }
            AuthIdpCallbackResult result = authIdpApplicationService.handleCallback(providerId, code, ticket, state);
            if (result.getResultType() == AuthIdpCallbackResult.ResultType.LOGIN) {
                writeTicketCookie(result.getToken(), response);
            }
            redirectTo(response, StringUtils.defaultIfBlank(result.getTarget(), "/"));
        } catch (Exception e) {
            log.warn("idp callback failed, providerId={}, error={}", providerId, e.getMessage());
            // 浏览器顶层导航场景，JSON 无消费方：统一 302 回登录页带错误提示
            redirectTo(response, "/login?idpError=" + urlEncode(e.getMessage() == null ? "登录失败" : e.getMessage()));
        }
    }

    @Operation(summary = "中间页读取待处理外部身份")
    @GetMapping("/pending/info")
    public ReqResult<AuthIdpPendingVo> pendingInfo(@Parameter(description = "中间页 pending token（回跳地址 ?token= 的值）", required = true) @RequestParam("token") String token) {
        return ReqResult.success(authIdpApplicationService.getPendingInfo(token));
    }

    @Operation(summary = "中间页-注册并登录（自动建号，受租户注册开关约束）")
    @PostMapping("/pending/register")
    public ReqResult<LoginResDto> pendingRegister(@RequestBody AuthIdpPendingTokenDto dto,
            HttpServletRequest request, HttpServletResponse response) {
        checkSameOrigin(request);
        AuthIdpLoginResult result = authIdpApplicationService.registerPending(dto.getToken());
        writeTicketCookie(result.getToken(), response);
        return ReqResult.success(buildLoginResDto(result));
    }

    @Operation(summary = "中间页-绑定已有账号（需登录态）")
    @PostMapping("/pending/bind")
    public ReqResult<Map<String, String>> pendingBind(@RequestBody AuthIdpPendingTokenDto dto, HttpServletRequest request) {
        checkSameOrigin(request);
        String redirect = authIdpApplicationService.bindPending(dto.getToken());
        return ReqResult.success(Map.of("redirect", StringUtils.defaultIfBlank(redirect, "/")));
    }

    /**
     * 中间页写操作同源校验：防跨站表单 POST 伪造绑定/注册（把攻击者外部身份绑到受害者账号）。
     * Sec-Fetch 元数据校验对 form 提交（navigate）豁免、SameSite=Lax 拦不住老浏览器，
     * 故按浏览器必带的 Origin（回退 Referer）比对租户站点 host 做纵深防御。
     */
    private void checkSameOrigin(HttpServletRequest request) {
        String fetchSite = request.getHeader("Sec-Fetch-Site");
        if ("same-origin".equalsIgnoreCase(fetchSite) || "same-site".equalsIgnoreCase(fetchSite)) {
            return;
        }
        String source = StringUtils.defaultIfBlank(request.getHeader("Origin"), request.getHeader("Referer"));
        String expectedHost = tenantSiteHost();
        // 站点地址未配置时整条授权流程本就无法发起，此处不叠加拦截
        if (expectedHost == null) {
            return;
        }
        if (StringUtils.isBlank(source)) {
            throw new BizException("4030", "拒绝跨站请求");
        }
        String sourceHost;
        try {
            sourceHost = new URL(source).getHost();
        } catch (MalformedURLException e) {
            throw new BizException("4030", "拒绝跨站请求");
        }
        if (!expectedHost.equalsIgnoreCase(sourceHost)
                && !(isLocalHost(expectedHost) && isLocalHost(sourceHost))) {
            log.warn("idp pending cross-site request blocked, expectedHost={}, sourceHost={}", expectedHost, sourceHost);
            throw new BizException("4030", "拒绝跨站请求");
        }
    }

    private static String tenantSiteHost() {
        TenantConfigDto tenantConfig = (TenantConfigDto) RequestContext.get().getTenantConfig();
        if (tenantConfig == null) {
            return null;
        }
        String base = StringUtils.defaultIfBlank(tenantConfig.getSiteConfigUrl(), tenantConfig.getSiteUrl());
        if (StringUtils.isBlank(base)) {
            return null;
        }
        try {
            return new URL(base).getHost();
        } catch (MalformedURLException e) {
            return null;
        }
    }

    private static boolean isLocalHost(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host);
    }

    /**
     * 302 统一走此方法而非 sendRedirect：sendRedirect 会把相对地址按【容器看到的 scheme】补全成
     * 绝对 Location，TLS 反代后置时容器 scheme 是 http，https 站点（如微信登录回跳中间页
     * /auth/bind-or-register）会被降级成 http。直接写相对 Location 由浏览器按当前源解析，天然保持 https；
     * 绝对地址（IdP authorize/logout）原样透传不受影响。
     * 与 AuthInterceptor.writeOAuth2AuthorizeLoginRedirect 同口径。
     */
    private static void redirectTo(HttpServletResponse response, String location) {
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader("Location", location);
        response.setHeader("Cache-Control", "no-store");
    }

    /**
     * 与 UserController.buildLoginResDto 同口径种 cookie：
     * maxAge 用租户 authExpire（分钟），默认 30 天；HttpOnly、Path=/
     */
    private void writeTicketCookie(String token, HttpServletResponse response) {
        TenantConfigDto tenantConfig = (TenantConfigDto) RequestContext.get().getTenantConfig();
        int expire = tenantConfig == null || tenantConfig.getAuthExpire() == null
                ? 86400 * 30 : tenantConfig.getAuthExpire().intValue() * 60;
        Cookie cookie = new Cookie("ticket", token);
        cookie.setMaxAge(expire);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        response.addCookie(cookie);
    }

    private LoginResDto buildLoginResDto(AuthIdpLoginResult result) {
        var userDto = authService.getLoginUserInfo(result.getToken());
        TenantConfigDto tenantConfig = (TenantConfigDto) RequestContext.get().getTenantConfig();
        int expire = tenantConfig == null || tenantConfig.getAuthExpire() == null
                ? 86400 * 30 : tenantConfig.getAuthExpire().intValue() * 60;
        return LoginResDto.builder()
                .resetPass(userDto == null ? null : userDto.getResetPass())
                .redirect(result.getRedirect())
                .expireDate(new Date(System.currentTimeMillis() + expire * 1000L))
                .token(result.getToken())
                .build();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(StringUtils.defaultString(value), StandardCharsets.UTF_8);
    }
}
