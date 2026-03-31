package com.xspaceagi.im.infra.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * IM 渠道类型
 */
@Getter
@AllArgsConstructor
public enum ImChannelEnum {

    FEISHU("feishu", "飞书"),
    DINGTALK("dingtalk", "钉钉"),
    WEWORK("wework", "企业微信"),
    /** 个人微信 iLink 机器人（非公众号/非企微回调） */
    WECHAT_ILINK("wechat_ilink", "微信");

    private final String code;
    private final String name;

    public static ImChannelEnum fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (ImChannelEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}

