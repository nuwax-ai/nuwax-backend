package com.xspaceagi.knowledge.sdk.sevice;

import com.xspaceagi.knowledge.sdk.request.KnowledgeTripleExtractRequestVo;
import com.xspaceagi.knowledge.sdk.request.KnowledgeGraphSearchRequestVo;
import com.xspaceagi.knowledge.sdk.response.KnowledgeTripleExtractResponseVo;
import com.xspaceagi.knowledge.sdk.response.KnowledgeGraphSearchResponseVo;

/**
 * 三元组提取RPC服务接口
 *
 * @author system
 * @date 2026-04-17
 */
public interface IKnowledgeTripleRpcService {

    /**
     * 从文本中提取三元组
     *
     * @param requestVo 请求参数
     * @return 提取的三元组结果
     */
    KnowledgeTripleExtractResponseVo extractTriples(KnowledgeTripleExtractRequestVo requestVo);

    /**
     * 知识图谱搜索（两阶段匹配）
     * 第一阶段：图数据库按主体模糊查询
     * 第二阶段：embedding 相似度过滤 predicate
     *
     * @param requestVo 请求参数
     * @return 匹配的三元组结果
     */
    KnowledgeGraphSearchResponseVo searchGraph(KnowledgeGraphSearchRequestVo requestVo);
}