package com.xspaceagi.system.infra.idp;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.system.infra.dao.entity.AuthIdpConfig;
import org.apache.commons.lang3.StringUtils;

public final class IdpConfigParser {

    private IdpConfigParser() {
    }

    public static CasIdpConfig parseCas(AuthIdpConfig config) {
        return parse(config == null ? null : config.getConfig(), CasIdpConfig.class);
    }

    public static OAuth2IdpConfig parseOAuth2(AuthIdpConfig config) {
        return parse(config == null ? null : config.getConfig(), OAuth2IdpConfig.class);
    }

    public static WeChatIdpConfig parseWeChat(AuthIdpConfig config) {
        return parse(config == null ? null : config.getConfig(), WeChatIdpConfig.class);
    }

    public static OAuth2IdpConfig parseOAuth2Json(String configJson) {
        return parse(configJson, OAuth2IdpConfig.class);
    }

    public static WeChatIdpConfig parseWeChatJson(String configJson) {
        return parse(configJson, WeChatIdpConfig.class);
    }

    private static <T> T parse(String configJson, Class<T> clazz) {
        try {
            if (StringUtils.isNotBlank(configJson)) {
                T parsed = JSON.parseObject(configJson, clazz);
                if (parsed != null) {
                    return parsed;
                }
            }
            return clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new IllegalStateException("解析 IdP 配置 JSON 失败: " + clazz.getSimpleName(), e);
        }
    }
}
