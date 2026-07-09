package com.xspaceagi.agent.core.infra.component.knowledge.accuracytest.dto;

import com.xspaceagi.agent.core.infra.component.knowledge.accuracytest.vo.AccuracyTestSearchResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

/**
 * 知识库准确性测试记录请求
 */
@Getter
@Setter
@Schema(description = "知识库准确性测试记录请求")
public class AccuracyTestRecordRequest implements Serializable {

    @Schema(description = "知识库ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "知识库ID不能为空")
    private Long knowledgeBaseId;

    @Schema(description = "测试查询文本", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "测试查询文本不能为空")
    private String query;

    @Schema(description = "搜索策略", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "搜索策略不能为空")
    private String searchStrategy;

    @Schema(description = "测试结果列表")
    private AccuracyTestSearchResponse results;

    @Getter
    @Setter
    public static class SearchResultItem implements Serializable {
        @Schema(description = "文档ID")
        private Long docId;

        @Schema(description = "文档名称")
        private String docName;

        @Schema(description = "相关性得分")
        private Double score;

        @Schema(description = "内容片段")
        private String content;

        @Schema(description = "排名")
        private Integer rank;
    }
}