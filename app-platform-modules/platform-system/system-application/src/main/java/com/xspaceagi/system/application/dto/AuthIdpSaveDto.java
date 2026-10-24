package com.xspaceagi.system.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

@Schema(description = "登录方式配置新增/编辑请求")
@Data
public class AuthIdpSaveDto {

    @Schema(description = "配置ID，更新时必填，新增不传", example = "1")
    private Long id;

    @Schema(description = "登录方式类型：CAS-CAS单点登录、OAUTH2-标准OAuth2、WECHAT-微信登录（编辑时不允许变更）", required = true, example = "WECHAT")
    private String type;

    @Schema(description = "配置名称（登录页展示名）", required = true, example = "微信登录")
    private String name;

    @Schema(description = "登录图标 URL（可选）")
    private String icon;

    @Schema(description = "排序值，越小越靠前", example = "0")
    private Integer sort;

    @Schema(description = "自动注册/绑定：1-开启（免中间页：邮箱/手机号已注册直接绑定、未注册自动注册并绑定；等于信任该 IdP 的邮箱/手机号）、0/不传-关闭（进中间页由用户选择）")
    private Integer autoRegisterBind;

    /**
     * type=CAS 时必填
     */
    @Schema(description = "CAS 配置，type=CAS 时必填")
    private CasConfigDto casConfig;

    /**
     * type=OAUTH2 时必填
     */
    @Schema(description = "标准 OAuth2 配置，type=OAUTH2 时必填")
    private OAuth2ConfigDto oauth2Config;

    /**
     * type=WECHAT 时必填
     */
    @Schema(description = "微信配置，type=WECHAT 时必填")
    private WeChatConfigDto wechatConfig;

    @Schema(description = "CAS 配置")
    @Data
    public static class CasConfigDto {

        @Schema(description = "CAS 服务地址", required = true, example = "https://sso.example.com/cas")
        private String serverUrl;

        @Schema(description = "字段映射（平台字段 ← CAS 返回属性名），key 取值 userName/nickName/email/phone",
                example = "{\"userName\":\"userName\",\"nickName\":\"nickName\",\"email\":\"mail\",\"phone\":\"mobile\"}")
        private Map<String, String> fieldMapping;
    }

    @Schema(description = "标准 OAuth2 配置")
    @Data
    public static class OAuth2ConfigDto {

        @Schema(description = "提供方：FEISHU-飞书、WECOM-企业微信、DINGTALK-钉钉、GITHUB、CUSTOM-自定义", required = true, example = "FEISHU")
        private String provider;

        @Schema(description = "Client ID（企业微信为 corpid）", required = true, example = "cli_a1b2c3")
        private String clientId;

        @Schema(description = "Client Secret（回显掩码，编辑传空/******表示不修改）", required = true)
        private String clientSecret;

        /**
         * 企业微信专用：agentid
         */
        @Schema(description = "企业微信专用：应用 agentid（企微必填）", example = "1000002")
        private String agentId;

        /**
         * 以下仅 CUSTOM 使用
         */
        @Schema(description = "（仅 CUSTOM 配置）授权端点", example = "https://idp.example.com/oauth2/authorize")
        private String authorizeUrl;

        @Schema(description = "（仅 CUSTOM 配置）token 端点", example = "https://idp.example.com/oauth2/token")
        private String tokenUrl;

        @Schema(description = "（仅 CUSTOM 配置）用户信息端点", example = "https://idp.example.com/oauth2/userinfo")
        private String userinfoUrl;

        @Schema(description = "（仅 CUSTOM 配置）授权 scope，空格分隔", example = "openid profile email")
        private String scope;

        /**
         * 仅 CUSTOM：飞书/企微/钉钉/GitHub 走各自官方适配，client_secret 已保护兑换，预设端点不需要也不感知这个配置；
         * 默认 false：部分老式/自建 OAuth2 网关不认识 code_challenge 参数，遇到不支持的参数可能直接报 invalid_request——所以保守默认关。
         * 怎么选：自定义 IdP 是现代 OIDC 产品（Keycloak、Authing、Casdoor、Auth0 等）→ 建议开（更安全）；
         * 如果开启后授权或换 token 报错提到 code_challenge/code_verifier → 说明对方不支持，关掉即可。
         */
        @Schema(description = "（仅 CUSTOM 配置）是否启用 PKCE(S256)，默认 false")
        private Boolean usePkce;

        /**
         * post-表单提交（默认）、basic-Basic 认证头
         */
        @Schema(description = "（仅 CUSTOM 配置）token 端点凭证方式：post-表单提交（默认）、basic-Basic 认证头", example = "post")
        private String authMethod;

        /**
         * 字段映射：平台字段 ← userinfo 字段名，key 取值 externalId / userName / nickName / email / avatar
         */
        @Schema(description = "（仅 CUSTOM 配置）字段映射（平台字段 ← userinfo 返回字段名），key 取值 externalId/userName/nickName/email/avatar，不传用默认值",
                example = "{\"externalId\":\"sub\",\"userName\":\"preferred_username\",\"nickName\":\"name\",\"email\":\"email\",\"avatar\":\"picture\"}")
        private Map<String, String> fieldMapping;
    }

    @Schema(description = "微信配置")
    @Data
    public static class WeChatConfigDto {

        @Schema(description = "应用形态：QRCODE-网站扫码（开放平台网站应用）、OA-公众号网页授权（需认证服务号）", required = true, example = "QRCODE")
        private String mode;

        @Schema(description = "AppID（两种形态是不同 appid，各配一行）", required = true, example = "wx8a7c52f1d9036b44")
        private String appId;

        @Schema(description = "AppSecret（回显掩码，编辑传空表示不修改）", required = true)
        private String appSecret;
    }
}
