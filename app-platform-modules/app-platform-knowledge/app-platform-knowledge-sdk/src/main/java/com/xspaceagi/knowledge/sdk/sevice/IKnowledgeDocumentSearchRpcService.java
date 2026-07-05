package com.xspaceagi.knowledge.sdk.sevice;

import com.xspaceagi.knowledge.sdk.request.KnowledgeDocumentRequestVo;
import com.xspaceagi.knowledge.sdk.response.KnowledgeDocumentResponseVo;
import com.xspaceagi.knowledge.sdk.vo.DocumentAddRequestVo;

/**
 * Q&A搜索接口
 */
public interface IKnowledgeDocumentSearchRpcService {


    /**
     * 文档搜索
     *
     * @param requestVo 搜索参数
     * @return 列表
     */
    KnowledgeDocumentResponseVo documentSearch(KnowledgeDocumentRequestVo requestVo);

    /**
     * 文档添加
     *
     * @param documentAddRequestVo 文档添加参数
     * @return 文档id
     */
    Long documentAdd(DocumentAddRequestVo documentAddRequestVo);
}
