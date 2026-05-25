package com.xspaceagi.bill.sdk.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
@Schema(description = "资源统计汇总")
public class ResourceStatSummaryDTO implements Serializable {

    @Schema(description = "消费方向统计")
    private StatGroup consumption;

    @Schema(description = "销售方向统计")
    private StatGroup sales;

    @Data
    @Schema(description = "统计分组")
    public static class StatGroup implements Serializable {

        @Schema(description = "总输入Token")
        private Long totalInputTokens;

        @Schema(description = "总输出Token")
        private Long totalOutputTokens;

        @Schema(description = "总缓存输入Token")
        private Long totalCacheInputTokens;

        @Schema(description = "工具总个数")
        private Long toolCount;

        @Schema(description = "工具调用总次数")
        private Long toolCallCount;

        @Schema(description = "智能体个数")
        private Long agentCount;

        @Schema(description = "智能体调用总次数")
        private Long agentCallCount;

        @Schema(description = "模型调用总次数")
        private Long modelCallCount;

        @Schema(description = "模型调用失败次数")
        private Long failedModelCallCount;

        @Schema(description = "工具调用失败次数")
        private Long failedToolCallCount;

        @Schema(description = "智能体调用失败次数")
        private Long failedAgentCallCount;

        @Schema(description = "总积分")
        private BigDecimal totalCreditAmount;

        @Schema(description = "总金额")
        private BigDecimal totalAmount;
    }
}
