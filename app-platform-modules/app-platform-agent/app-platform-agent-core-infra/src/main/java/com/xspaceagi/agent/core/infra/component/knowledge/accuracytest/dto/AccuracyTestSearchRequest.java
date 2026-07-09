package com.xspaceagi.agent.core.infra.component.knowledge.accuracytest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

/**
 * 知识库准确性测试搜索请求
 */
@Getter
@Setter
@Schema(description = "知识库准确性测试搜索请求")
public class AccuracyTestSearchRequest implements Serializable {

    @Schema(description = "知识库ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "知识库ID不能为空")
    private Long knowledgeBaseId;

    @Schema(description = "搜索查询文本", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "搜索查询文本不能为空")
    private String query;

    @Schema(description = "搜索策略", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "搜索策略不能为空")
    private String searchStrategy;

    @Schema(description = "topK值", defaultValue = "20")
    private Integer topK = 20;

    @Schema(description = "匹配度阈值", defaultValue = "0.5")
    private Double matchingDegree = 0.5;

    private Boolean isShowGRAPH;
}