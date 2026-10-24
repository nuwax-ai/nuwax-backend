package com.xspaceagi.system.spec.enums;

import lombok.Getter;

/**
 * 微信登录的应用形态：不同形态对应不同的 appid 与授权端点，一行配置只能是其中一种
 */
@Getter
public enum WeChatIdpModeEnum {

    /**
     * 网站扫码（开放平台网站应用，PC 端浏览器场景）
     */
    QRCODE("QRCODE", "网站扫码（开放平台）"),

    /**
     * 公众号网页授权（微信内浏览器打开站点场景）
     */
    OA("OA", "公众号网页授权");

    private final String code;
    private final String desc;

    WeChatIdpModeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static WeChatIdpModeEnum of(String code) {
        if (code == null) {
            return null;
        }
        for (WeChatIdpModeEnum mode : values()) {
            if (mode.code.equalsIgnoreCase(code)) {
                return mode;
            }
        }
        return null;
    }
}
