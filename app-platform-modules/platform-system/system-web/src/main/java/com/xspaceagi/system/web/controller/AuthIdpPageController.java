package com.xspaceagi.system.web.controller;

import io.swagger.v3.oas.annotations.Operation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 外部身份处理中间页。路径在 /api/** 之外，不经登录拦截器
 */
@Slf4j
@RestController
public class AuthIdpPageController {

    private static final String PAGE_RESOURCE = "/idp-bind-or-register.html";

    @Operation(summary = "外部身份绑定/注册中间页")
    @GetMapping(value = "/auth/bind-or-register", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String bindOrRegisterPage() {
        try (InputStream in = getClass().getResourceAsStream(PAGE_RESOURCE)) {
            if (in == null) {
                return "<!DOCTYPE html><html><body><p>页面资源缺失</p></body></html>";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("加载绑定/注册中间页失败", e);
            return "<!DOCTYPE html><html><body><p>页面加载失败</p></body></html>";
        }
    }
}
