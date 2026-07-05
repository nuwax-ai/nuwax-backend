package com.xspaceagi.system.sdk.common;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Builder
@AllArgsConstructor
@NoArgsConstructor
@Data
public class TraceContext {

    @Schema(description = "调用轨迹ID，之前系统中已有的requestId")
    private String traceId;

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "请求用户ID")
    private Long userId;

    @Schema(description = "会话ID")
    private String conversationId;

    @Schema(description = "用户名")
    private String userName;

    @Schema(description = "用户昵称")
    private String nickName;

    @Schema(description = "支付账单的用户ID")
    private Long billUserId;

    @Schema(description = "是否启用订阅")
    private boolean enableSubscription;

    @Schema(description = "是否开发测试")
    private boolean devTest;

    @Schema(description = "调用轨迹")
    private List<TraceTarget> traceTargets;

    @Schema(description = "日志")
    private Object log;

    private TokenUsage tokenUsage;
    private DurationUsage durationUsage;

    private Long subscriptionId;
    private String apiKey;

    private boolean error;
    private String errorCode;
    private String errorMessage;

    public TraceContext next(TraceTargetType type, String targetId, String name, String description, String icon) {
        return next(type, targetId, name, description, icon, null);
    }

    public TraceContext next(TraceTargetType type, String targetId, String name, String description, String icon, Long targetBillUserId) {
        ArrayList<TraceTarget> nextTraceTargets = new ArrayList<>(traceTargets);
        nextTraceTargets.add(TraceTarget.builder().icon(icon).targetId(targetId).targetType(type).description(description)
                .name(name).billUserId(targetBillUserId).build());
        return TraceContext.builder()
                .traceId(traceId)
                .tenantId(tenantId)
                .userId(userId)
                .userName(userName)
                .nickName(nickName)
                .billUserId(billUserId)
                .enableSubscription(enableSubscription)
                .conversationId(conversationId)
                .apiKey(apiKey)
                .traceTargets(nextTraceTargets)
                .build();
    }

    public static String maskApiKey(String apiKey) {
        //apiKey只取前面4位和后4位
        if (apiKey != null && apiKey.length() >= 8) {
            return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
        }
        return apiKey;
    }


    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class TraceTarget {
        @Schema(description = "目标类型")
        private TraceTargetType targetType;
        @Schema(description = "目标ID")
        private String targetId;
        @Schema(description = "目标名称")
        private String name;
        @Schema(description = "目标描述")
        private String description;
        @Schema(description = "目标图标")
        private String icon;
        private Long spaceId;
        private Long billUserId;
    }

    public enum TraceTargetType {
        Agent,
        Mcp,
        Plugin,
        Workflow,
        Model
    }

    //模型时有效
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class TokenUsage {
        public long cacheInputTokens = 0;
        public long inputTokens = 0;
        public long outputTokens = 0;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DurationUsage {
        public long duration = 0;
    }
}
