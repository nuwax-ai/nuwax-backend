package com.xspaceagi.system.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
public class UsageDto {
    @Schema(description = "今日TOKEN使用情况")
    private Usage todayTokenUsage;
    @Schema(description = "今日智能体提示使用情况")
    private Usage todayAgentPromptUsage;
    @Schema(description = "今日网页应用提示使用情况")
    private Usage todayPageAppPromptUsage;
    @Schema(description = "可创建工作空间数量")
    private Usage newWorkspaceUsage;
    @Schema(description = "可创建智能体数量")
    private Usage newAgentUsage;
    @Schema(description = "可创建网页应用数量")
    private Usage newPageAppUsage;
    @Schema(description = "可创建全栈应用数量")
    private Usage newUserAppUsage;
    @Schema(description = "可创建知识库数量")
    private Usage newKnowledgeBaseUsage;
    @Schema(description = "知识库存储上限")
    private Usage knowledgeBaseStorageUsage;
    @Schema(description = "可创建数据表数量")
    private Usage newTableUsage;
    @Schema(description = "可创建定时任务数量")
    private Usage newTaskUsage;
    @Schema(description = "智能体电脑最大内存")
    private Usage sandboxMemoryLimit;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Usage {
        @Schema(description = "限制")
        private String limit;
        @Schema(description = "使用情况")
        private String usage;
        @Schema(description = "描述")
        private String description;
    }
}
