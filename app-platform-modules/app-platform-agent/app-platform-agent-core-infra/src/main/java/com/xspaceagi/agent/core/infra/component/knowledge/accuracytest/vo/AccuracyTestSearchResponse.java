package com.xspaceagi.agent.core.infra.component.knowledge.accuracytest.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.List;

/**
 * 知识库准确性测试搜索响应
 */
@Getter
@Setter
@Schema(description = "知识库准确性测试搜索响应")
public class AccuracyTestSearchResponse implements Serializable {

    @Schema(description = "搜索结果列表")
    private List<SearchResultItem> results;

    @Schema(description = "总结果数")
    private Integer total;

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

        @Schema(description = "元数据")
        private Object metadata;

        private String answer;
    }
}