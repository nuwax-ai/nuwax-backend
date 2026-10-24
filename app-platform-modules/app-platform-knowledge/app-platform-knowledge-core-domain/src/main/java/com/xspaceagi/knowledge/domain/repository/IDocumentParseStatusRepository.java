package com.xspaceagi.knowledge.domain.repository;

import com.xspaceagi.knowledge.domain.model.DocumentParseStatusModel;
import com.xspaceagi.system.spec.common.UserContext;

import java.util.List;

/**
 * 文档解析状态仓储接口
 *
 * @author system
 * @date 2026-04-16
 */
public interface IDocumentParseStatusRepository {

    /**
     * 根据文档ID查询
     *
     * @param documentId 文档ID
     * @return 解析状态
     */
    DocumentParseStatusModel queryByDocumentId(Long documentId);

    /**
     * 根据知识库ID查询列表
     *
     * @param kbId 知识库ID
     * @return 解析状态列表
     */
    List<DocumentParseStatusModel> queryListByKbId(Long kbId);

    /**
     * 批量根据文档ID查询
     *
     * @param documentIds 文档ID列表
     * @return 解析状态列表
     */
    List<DocumentParseStatusModel> queryListByDocumentIds(List<Long> documentIds);

    /**
     * 新增
     *
     * @param model model
     * @param userContext 用户上下文
     * @return id
     */
    Long addInfo(DocumentParseStatusModel model, UserContext userContext);

    /**
     * 更新
     *
     * @param model model
     * @param userContext 用户上下文
     * @return id
     */
    Long updateInfo(DocumentParseStatusModel model, UserContext userContext);

    /**
     * 根据文档ID删除
     *
     * @param documentId 文档ID
     */
    void deleteByDocumentId(Long documentId);

    /**
     * 查询知识库列表中是否存在指定状态的三元组解析记录
     *
     * @param kbIds 知识库ID列表
     * @param tripleStatus 三元组解析状态
     * @return 存在图谱数据的知识库ID列表
     */
    List<Long> findKbIdsWithTripleStatus(List<Long> kbIds, Integer tripleStatus);

    /**
     * 根据三元组状态查询列表
     *
     * @param tripleStatus 三元组解析状态
     * @return 解析状态列表
     */
    List<DocumentParseStatusModel> queryListByTripleStatus(Integer tripleStatus);
}