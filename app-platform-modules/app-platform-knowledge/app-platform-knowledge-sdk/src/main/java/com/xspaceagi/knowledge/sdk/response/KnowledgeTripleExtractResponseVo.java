package com.xspaceagi.knowledge.sdk.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 三元组提取响应VO
 *
 * @author system
 * @date 2026-04-17
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeTripleExtractResponseVo {

    /**
     * 提取的三元组列表
     */
    private List<TripleVo> triples;

    /**
     * 三元组数据
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TripleVo {
        /**
         * 主体
         */
        private String subject;

        /**
         * 谓语
         */
        private String predicate;

        /**
         * 客体
         */
        private String object;

        /**
         * 相似谓语列表（与predicate意思相近的词语，用于扩展检索）
         */
        private List<String> similarPredicates;
    }
}
