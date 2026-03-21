package com.xspaceagi.im.application.dto;

import lombok.Data;

/**
 * IM 渠道配置聚合 DTO
 */
@Data
public class ImChannelConfigDto {

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
}

