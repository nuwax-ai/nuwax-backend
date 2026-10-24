package com.xspaceagi.knowledge.sdk.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 知识图谱搜索响应VO
 *
 * @author system
 * @date 2026-04-17
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeGraphSearchResponseVo {

    /**
     * 匹配的三元组列表
     */
    private List<GraphTripleVo> triples;

    /**
     * 图谱三元组数据（带文档信息）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GraphTripleVo {
        /**
         * 主体
         */
        private String subject;

        /**
         * 谓语/关系
         */
        private String predicate;

        /**
         * 客体
         */
        private String object;

        /**
         * 知识库ID
         */
        private Long knowledgeId;

        /**
         * 文档ID
         */
        private Long documentId;

        /**
         * 文档名称
         */
        private String documentName;

        /**
         * 文件类型
         */
        private String fileType;

        /**
         * predicate 相似度分数
         */
        private Double similarityScore;
    }
}
