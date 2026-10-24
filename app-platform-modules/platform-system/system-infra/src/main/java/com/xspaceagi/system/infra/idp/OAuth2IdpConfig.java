package com.xspaceagi.system.infra.idp;

import lombok.Data;

import java.util.Map;

/**
 * 标准 OAuth2 类型配置，
 * 提供方为预设（飞书/企业微信/钉钉/GitHub）时端点由适配层内置，仅填 clientId/clientSecret 等凭证；
 * 自定义（CUSTOM）须填完整端点，可选 PKCE 与字段映射。
 */
@Data
public class OAuth2IdpConfig {

    private String provider;

    private String clientId;

    /**
     * 回显脱敏；更新时传空/掩码表示不修改
     */
    private String clientSecret;

    /**
     * 企业微信专用：agentid（扫码登录 wwlogin 页面必填）
     */
    private String agentId;

    // ===== 以下仅 CUSTOM 提供方使用 =====

    private String authorizeUrl;

    private String tokenUrl;

    private String userinfoUrl;

    /**
     * 授权 scope，空格分隔
     */
    private String scope;

    /**
     * 是否启用 PKCE（S256），默认不启用
     */
    private Boolean usePkce;

    /**
     * token 端点凭证方式：post-表单提交（默认）、basic-Basic 认证头
     */
    private String authMethod;

    /**
     * 字段映射：平台字段 ← userinfo 返回字段名。
     * key 取值 externalId / userName / nickName / email / avatar，
     * 默认 {"externalId":"sub","userName":"preferred_username","nickName":"name","email":"email","avatar":"picture"}
     */
    private Map<String, String> fieldMapping;
}
