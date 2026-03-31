package com.xspaceagi.im.wechat.ilink;

import org.apache.commons.lang3.StringUtils;

/**
 * 对齐 openclaw-weixin 1.0.3 {@code redact.ts}：日志中的 JSON 敏感字段打码。
 */
public final class WechatIlinkLogRedaction {

    private WechatIlinkLogRedaction() {
    }

    public static String redactJsonBodyForLog(String body) {
        if (StringUtils.isBlank(body)) {
            return body;
        }
        return body.replaceAll(
                "\"(context_token|bot_token|token|Authorization|authorization)\"\\s*:\\s*\"[^\"]*\"",
                "\"$1\":\"<redacted>\"");
    }
}
