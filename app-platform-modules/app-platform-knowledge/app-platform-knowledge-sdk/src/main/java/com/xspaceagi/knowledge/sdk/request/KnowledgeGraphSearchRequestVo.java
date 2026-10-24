package com.xspaceagi.knowledge.sdk.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 知识图谱搜索请求VO
 *
 * @author system
 * @date 2026-04-17
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeGraphSearchRequestVo {

    /**
     * 知识库ID列表（限定搜索范围）
     */
    private List<Long> knowledgeBaseIds;

    /**
     * 主体关键词列表（从三元组中提取）
     */
    private List<String> subjectKeywords;

    /**
     * 查询的关系词（用于 embedding 相似度匹配）
     */
    private String queryPredicate;

    /**
     * 相似谓语列表（AI生成的与queryPredicate意思相近的词语）
     */
    private List<String> similarPredicates;

    /**
     * 返回数量限制
     */
    private Integer limit;

    /**
     * predicate 相似度阈值（0-1）
     */
    private Double similarityThreshold;
}
