package com.xspaceagi.knowledge.core.infra.knowledgegraph.service;

import com.xspaceagi.system.spec.common.UserContext;

import java.util.List;

/**
 * 知识图谱领域服务接口
 *
 * @author system
 * @date 2026-04-16
 */
public interface IKnowledgeGraphDomainService {

    /**
     * 异步生成知识图谱（单个文档）
     *
     * @param documentId  文档ID
     * @param userContext 用户上下文
     */
    void generateKnowledgeGraphAsync(Long documentId, UserContext userContext, Long taskId);

    /**
     * 从文本中提取三元组（用于文档内容）
     *
     * @param text 待提取的文本
     * @return 提取的三元组列表
     */
    List<TripleData> extractTriplesFromText(String text);

    /**
     * 从用户查询问题中提取检索用三元组（智能查询意图分析）
     *
     * @param query 用户查询问题
     * @return 提取的三元组列表
     */
    List<TripleData> extractTriplesFromQuery(String query);

    /**
     * 三元组数据模型
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    class TripleData {
        private String subject;
        private String predicate;
        private String object;
        private List<String> similarPredicates;
    }

}
