package com.xspaceagi.knowledge.core.infra.knowledgegraph.triple.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 知识图谱统计返回对象
 *
 * @author system
 * @date 2025-04-14
 */
@Data
@Schema(description = "知识图谱统计返回对象")
public class KnowledgeGraphStatsVo {

    @Schema(description = "知识库ID")
    private Long knowledgeId;

    @Schema(description = "文档数量")
    private Integer documentCount;

    @Schema(description = "三元组总数")
    private Integer tripleCount;

    @Schema(description = "主语节点数")
    private Integer subjectCount;

    @Schema(description = "宾语节点数")
    private Integer objectCount;

    @Schema(description = "谓语类型列表")
    private List<PredicateStats> predicateStats;

    @Data
    @Schema(description = "谓语统计")
    public static class PredicateStats {
        @Schema(description = "谓语名称")
        private String predicate;

        @Schema(description = "数量")
        private Integer count;
    }
}
