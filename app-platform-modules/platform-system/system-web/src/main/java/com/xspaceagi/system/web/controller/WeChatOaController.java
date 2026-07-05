package com.xspaceagi.system.web.controller;

import com.xspaceagi.system.infra.rpc.WeChatOaService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.web.dto.WeChatOaOAuthUrlResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/wechat/oa")
@Tag(name = "微信公众号 OAuth")
public class WeChatOaController {

    @Resource
    private WeChatOaService weChatOaService;

    @GetMapping("/oauth-url")
    @Operation(
            summary = "获取公众号 OAuth 授权页 URL",
            description = "微信内 H5 支付前跳转 snsapi_base 授权；redirectUri 须为 https 完整地址。"
                    + "推荐传后端固定回调地址 /api/wechat/oa/callback（携带 returnUrl），由后端 302 回前端页面并透传 code/state")
    public ReqResult<WeChatOaOAuthUrlResponse> getOAuthAuthorizeUrl(
            @RequestParam String redirectUri, @RequestParam(required = false) String state) {
        Assert.hasText(redirectUri, "redirectUri cannot be blank");
        String normalizedRedirectUri = redirectUri.trim();
        validateHttpsUrl(normalizedRedirectUri);
        Long tenantId = RequestContext.get() != null ? RequestContext.get().getTenantId() : null;
        if (tenantId == null) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "tenant context required");
        }
        URI redirectUriObj = URI.create(normalizedRedirectUri);
        log.info(
                "wechat oa oauth-url requested tenantId={} redirectUriHost={} redirectUri={} state={}",
                tenantId,
                redirectUriObj.getHost(),
                normalizedRedirectUri,
                state);
        String authorizeUrl = weChatOaService.buildOAuthAuthorizeUrl(normalizedRedirectUri, state, tenantId);
        return ReqResult.success(new WeChatOaOAuthUrlResponse(authorizeUrl));
    }

    @GetMapping("/callback")
    @Operation(
            summary = "公众号 OAuth 固定回调中转",
            description = "接收微信 OAuth 回调 code/state，并 302 跳转到 returnUrl，同时附加 code/state 参数")
    public void callbackRedirect(
            @RequestParam String returnUrl,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            HttpServletResponse response) {
        String target = appendOAuthParams(validateHttpsUrlAndReturn(returnUrl.trim()), code, state);
        log.info("wechat oa callback redirect targetUrl={} codePresent={} state={}",
                target,
                StringUtils.hasText(code),
                state);
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader("Location", target);
    }

    private static String appendOAuthParams(String returnUrl, String code, String state) {
        StringBuilder sb = new StringBuilder(returnUrl);
        char connector = returnUrl.contains("?") ? '&' : '?';
        if (StringUtils.hasText(code)) {
            sb.append(connector)
                    .append("code=")
                    .append(URLEncoder.encode(code.trim(), StandardCharsets.UTF_8));
            connector = '&';
        }
        if (StringUtils.hasText(state)) {
            sb.append(connector)
                    .append("state=")
                    .append(URLEncoder.encode(state.trim(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static void validateHttpsUrl(String url) {
        validateHttpsUrlAndReturn(url);
    }

    private static String validateHttpsUrlAndReturn(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
//            if (!"https".equalsIgnoreCase(scheme)) {
//                throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "redirectUri must use https");
//            }
            if (!StringUtils.hasText(uri.getHost())) {
                throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "Invalid redirectUri");
            }
            return url;
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "Invalid redirectUri");
        }
    }
}
