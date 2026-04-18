package com.xspaceagi.system.web.controller.api;

import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.service.AuthService;
import com.xspaceagi.system.spec.common.RequestContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@Tag(name = "开放API-用户认证跳转")
@RestController
@RequestMapping("/api")
public class RedirectApiController {
    @Resource
    private AuthService authService;

    @Operation(summary = "根据ticket认证校验并跳转")
    @RequestMapping(path = "/ticket/redirect", method = RequestMethod.GET)
    public void redirect(@RequestParam(name = "ticket") String ticket, @RequestParam(name = "redirectUrl") String redirectUrl,
                         HttpServletResponse response) {
        String token = authService.getTokenByTicket(ticket);
        if (token == null) {
            throw new IllegalArgumentException("Error ticket");
        }
        TenantConfigDto tenantConfig = (TenantConfigDto) RequestContext.get().getTenantConfig();
        int expire = tenantConfig.getAuthExpire() == null ? 86400 * 30 : tenantConfig.getAuthExpire().intValue() * 60;
        Cookie cookie = new Cookie("ticket", token);
        cookie.setHttpOnly(true);
        cookie.setMaxAge(expire);
        cookie.setPath("/");
        // iframe SameSite=None; Secure
        cookie.setSecure(true);
        cookie.setAttribute("SameSite","None");
        response.addCookie(cookie);
        try {
            response.sendRedirect(URLDecoder.decode(redirectUrl, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}