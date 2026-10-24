package com.xspaceagi.system.infra.idp;

import com.xspaceagi.system.spec.enums.WeChatIdpModeEnum;
import lombok.Data;

/**
 * 微信类型配置
 */
@Data
public class WeChatIdpConfig {

    /**
     * 应用形态：QRCODE-网站扫码（开放平台）、OA-公众号网页授权
     * @see WeChatIdpModeEnum
     */
    private String mode;

    private String appId;

    /**
     * 回显脱敏；更新时传空/掩码表示不修改
     */
    private String appSecret;
}
