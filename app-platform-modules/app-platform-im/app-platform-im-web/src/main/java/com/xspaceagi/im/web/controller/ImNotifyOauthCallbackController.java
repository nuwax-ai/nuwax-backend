package com.xspaceagi.im.web.controller;

import com.xspaceagi.im.application.ImNotifyOauthCallbackApplicationService;
import com.xspaceagi.im.application.dto.ImNotifyOauthCallbackResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v1/im-notify/oauth")
@Slf4j
@Tag(name = "IM OAuth 回调")
public class ImNotifyOauthCallbackController {

    @Resource
    private ImNotifyOauthCallbackApplicationService imNotifyOauthCallbackApplicationService;

    @GetMapping(value = "/{channel}/callback", produces = MediaType.TEXT_HTML_VALUE)
    @Operation(summary = "飞书/钉钉/企微 OAuth 回调", hidden = true)
    public void callback(@PathVariable String channel,
                         @RequestParam(required = false) String code,
                         @RequestParam(required = false) String state,
                         @RequestParam(required = false) String authCode,
                         @RequestParam(required = false) String error,
                         @RequestParam(required = false) String error_description,
                         HttpServletResponse response) throws IOException {
        String effectiveCode = code != null ? code : authCode;
        String err = error != null ? error : error_description;
        ImNotifyOauthCallbackResult result = imNotifyOauthCallbackApplicationService.handleCallback(
                channel, effectiveCode, state, err);
        writeResult(response, result);
    }

    @GetMapping(value = "/consent", produces = MediaType.TEXT_HTML_VALUE)
    @Operation(summary = "IM 用户确认将身份绑定到指定平台账号", hidden = true)
    public void consent(@RequestParam String bindId,
                        @RequestParam String consentToken,
                        HttpServletResponse response) throws IOException {
        ImNotifyOauthCallbackResult result = imNotifyOauthCallbackApplicationService.handleConsent(
                bindId, consentToken);
        writeResult(response, result);
    }

    private static void writeResult(HttpServletResponse response, ImNotifyOauthCallbackResult result)
            throws IOException {
        if (result != null && StringUtils.isNotBlank(result.getRedirectUrl())) {
            response.sendRedirect(result.getRedirectUrl());
            return;
        }
        String html = result != null && result.getHtml() != null
                ? result.getHtml()
                : "<html><body>授权处理失败</body></html>";
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.TEXT_HTML_VALUE + ";charset=UTF-8");
        response.getWriter().write(html);
    }
}
