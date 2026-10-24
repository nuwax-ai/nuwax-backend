package com.xspaceagi.im.web.controller;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Universal Link / 域名回跳落地页：OAuth 成功后浏览器落到此页，App 可拦截同路径完成绑定。
 */
@Hidden
@RestController
public class ImNotifyBindReturnPageController {

    @GetMapping(value = "/api/v1/im-notify/bind/return", produces = MediaType.TEXT_HTML_VALUE)
    public void landing(@RequestParam(required = false) String bindId,
                        @RequestParam(required = false) String confirmToken,
                        @RequestParam(required = false) String channel,
                        @RequestParam(required = false) String status,
                        HttpServletResponse response) throws IOException {
        String title;
        String message;
        if ("authorized".equals(status) && StringUtils.isNotBlank(bindId) && StringUtils.isNotBlank(confirmToken)) {
            title = "授权成功";
            message = "请返回 App 完成绑定。若已安装 App 且配置了 Universal Link，一般会自动打开 App。";
        } else if ("bound".equals(status)) {
            title = "绑定已完成";
            message = "可以关闭此页面，返回 App 继续操作。";
        } else {
            title = "IM 账号绑定";
            message = "请从 App 内发起绑定；授权完成后将回到本页。";
        }
        String html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/>"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"/>"
                + "<title>" + esc(title) + "</title></head>"
                + "<body style=\"font-family:sans-serif;padding:40px;max-width:480px;margin:auto;line-height:1.6\">"
                + "<h2>" + esc(title) + "</h2><p>" + esc(message) + "</p>"
                + (StringUtils.isNotBlank(channel) ? "<p style=\"color:#666\">渠道：" + esc(channel) + "</p>" : "")
                + "</body></html>";
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.TEXT_HTML_VALUE + ";charset=UTF-8");
        response.getWriter().write(html);
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
