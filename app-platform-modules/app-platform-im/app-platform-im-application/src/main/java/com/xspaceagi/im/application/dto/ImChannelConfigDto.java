package com.xspaceagi.im.application.dto;

import lombok.Data;

/**
 * IM 渠道配置聚合 DTO
 */
@Data
public class ImChannelConfigDto {

    /** 配置主键（列表/长轮询等场景） */
    private Long id;

    private Boolean enabled;

    // 通用元信息
    private Long tenantId;
    private Long userId;
    private Long agentId;

    // 输出方式：stream(流式输出)/once(一次性输出)
    private String outputMode;

    // 当前记录所属平台、类型
    private String channel;
    private String targetType;

    // 各渠道配置
    private FeishuConfig feishu;
    private DingtalkConfig dingtalk;
    private WeworkBotConfig weworkBot;
    private WeworkAppConfig weworkApp;
    private WechatIlinkConfig wechatIlink;

    @Data
    public static class FeishuConfig {
        private String appId;
        private String appSecret;
        private String verificationToken;
        private String encryptKey;
    }

    @Data
    public static class DingtalkConfig {
        private String clientId;
        private String clientSecret;
        private String robotCode;
    }

    @Data
    public static class WeworkBotConfig {
        private String aibotId;
        private String corpId;
        private String corpSecret;
        private String token;
        private String encodingAesKey;
    }

    @Data
    public static class WeworkAppConfig {
        private String agentId;
        private String corpId;
        private String corpSecret;
        private String token;
        private String encodingAesKey;
    }

    @Data
    public static class WechatIlinkConfig {
        /** 网关基址，如 https://ilinkai.weixin.qq.com */
        private String baseUrl;
        private String botToken;
        /** 默认与 openclaw 插件一致为 "3" */
        private String botType;
        /** CDN 基址，默认可用 IlinkConstants.CDN_BASE_URL */
        private String cdnBaseUrl;
        /** 规范后的账号 id，作为 targetId */
        private String ilinkAccountId;
        /** 扫码绑定的微信用户 id（可选） */
        private String ilinkUserId;
    }
}

