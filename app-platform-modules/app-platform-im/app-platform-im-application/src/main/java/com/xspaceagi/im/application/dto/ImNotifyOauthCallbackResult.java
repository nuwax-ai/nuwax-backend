package com.xspaceagi.im.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * OAuth 浏览器回调结果：优先 302 回跳 App，否则返回提示页 HTML。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImNotifyOauthCallbackResult {
    /** 非空则 HTTP 302 */
    private String redirectUrl;
    /** redirectUrl 为空时输出的 HTML */
    private String html;
}
