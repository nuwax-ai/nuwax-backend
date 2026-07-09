package com.xspaceagi.agent.core.infra.component.knowledge.accuracytest.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

/**
 * 知识库准确性测试记录响应
 */
@Getter
@Setter
@Schema(description = "知识库准确性测试记录响应")
public class AccuracyTestRecordResponse implements Serializable {

    @Schema(description = "记录ID")
    private Long recordId;

    @Getter
    @Setter
    public static class TestHistoryItem implements Serializable {
        @Schema(description = "记录ID")
        private Long id;

        @Schema(description = "知识库ID")
        private Long knowledgeBaseId;

        @Schema(description = "查询文本")
        private String query;

        @Schema(description = "搜索策略")
        private String searchStrategy;

        @Schema(description = "结果JSON")
        private String results;

        @Schema(description = "创建时间")
        private String createTime;
    }
}