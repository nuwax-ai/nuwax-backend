package com.xspaceagi.system.infra.idp;

import lombok.Data;

import java.util.Map;

/**
 * IdP 换取到的外部身份
 */
@Data
public class ExternalIdentity {

    /**
     * 外部稳定标识（唯一键组成部分）：CAS user / OAuth2 sub·unionId / 微信 unionid(优先)或 openid
     */
    private String externalId;

    /**
     * 微信 unionid（可能为空）：用于跨应用识别同一人
     */
    private String unionId;

    /**
     * 外部用户名（登录名，如 GitHub login、CAS uid）
     */
    private String userName;

    /**
     * 外部昵称/姓名（展示用）
     */
    private String nickName;

    private String avatar;

    private String email;

    private String phone;

    /**
     * IdP 返回的原始属性（脱敏后，存 raw_profile 快照）
     */
    private Map<String, Object> attributes;
}
