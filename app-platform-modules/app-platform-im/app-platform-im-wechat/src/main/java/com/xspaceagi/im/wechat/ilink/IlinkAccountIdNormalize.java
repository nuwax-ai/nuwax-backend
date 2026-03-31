package com.xspaceagi.im.wechat.ilink;

import org.apache.commons.lang3.StringUtils;

/**
 * 将 ilink 账号 id（如 xxx@im.wechat）规范为可作为 targetId 的字符串（对齐 openclaw-weixin 文件命名规则）。
 */
public final class IlinkAccountIdNormalize {

    private IlinkAccountIdNormalize() {
    }

    public static String normalizeForStorage(String rawAccountId) {
        if (StringUtils.isBlank(rawAccountId)) {
            return rawAccountId;
        }
        String s = rawAccountId.trim();
        if (s.endsWith("@im.wechat")) {
            return s.substring(0, s.length() - "@im.wechat".length()) + "-im-wechat";
        }
        if (s.endsWith("@im.bot")) {
            return s.substring(0, s.length() - "@im.bot".length()) + "-im-bot";
        }
        return s.replace('@', '_');
    }
}
