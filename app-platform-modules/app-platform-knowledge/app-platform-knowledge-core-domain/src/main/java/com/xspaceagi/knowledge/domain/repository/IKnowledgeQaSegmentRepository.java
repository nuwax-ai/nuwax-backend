package com.xspaceagi.knowledge.domain.repository;

import com.xspaceagi.knowledge.domain.dto.EmbeddingStatusDto;
import com.xspaceagi.knowledge.domain.model.KnowledgeQaSegmentModel;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.infra.service.IQueryViewService;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识库问答分片仓库
 */
public interface IKnowledgeQaSegmentRepository extends IQueryViewService<KnowledgeQaSegmentModel> {

    /**
     * 模板配置详情
     *
     * @param id 模板id
     */
    KnowledgeQaSegmentModel queryOneInfoById(Long id);

    /**
     * 根据ID集合查询列表
     *
     * @param ids id集合
     * @return 列表
     */
    List<KnowledgeQaSegmentModel> queryListByIds(List<Long> ids);

    /**
     * 根据文档id查询没有嵌入的问答
     *
     * @param docId 文档id
     * @return 没有嵌入的问答
     */
    List<KnowledgeQaSegmentModel> queryListByDocIdAndNoEmbedding(Long docId);

    /**
     * 根据分段id查询问答列表
     * 
     * @param rawId 分段id
     * @return 问答列表
     */
    List<KnowledgeQaSegmentModel> queryListInfoByRawId(Long rawId);

    /**
     * 根据文档id查询没有嵌入的问答总数,用于判断是否全部向量化完毕
     * 
     * @param docId
     * @return
     */
    Long queryCountByDocIdAndNoEmbedding(Long docId);

    /**
     * 根据rawId列表查询没有嵌入的问答
     *
     * @param rawIdList rawId列表
     * @return 没有嵌入的问答
     */
    List<KnowledgeQaSegmentModel> queryListByRawIdsAndNoEmbedding(List<Long> rawIdList);

    /**
     * 删除根据主键id
     *
     * @param id id
     */
    void deleteById(Long id);

    /**
     * 根据文档的id,进行批量删除
     *
     * @param docId 文档id
     */
    void deleteByDocumentId(Long docId);

    /**
     * 根据rawId删除
     *
     * @param rawId rawId
     */
    void deleteByRawId(Long rawId);

    /**
     * 更新
     *
     * @param model 模型
     * @return id
     */
    Long updateInfo(KnowledgeQaSegmentModel model, UserContext userContext);

    /**
     * 新增
     */
    Long addInfo(KnowledgeQaSegmentModel model, UserContext userContext);

    /**
     * 批量添加
     */
    List<Long> batchAddInfo(List<KnowledgeQaSegmentModel> modelList, UserContext userContext);

    /**
     * 查询文档的嵌入状态
     *
     * @param docId 文档id
     * @return
     */
    EmbeddingStatusDto queryEmbeddingStatus(Long docId);

    /**
     * 批量更改文档的嵌入状态
     *
     * @param ids          文档id
     * @param hasEmbedding 是否已经嵌入
     */
    void batchChangeEmbeddingStatus(List<Long> ids, Boolean hasEmbedding, UserContext userContext);

    /**
     * 查询待向量化的问答列表,对问答做向量化,根据字段:qaStatus=-1,created在days天前,
     * 
     * @param days     天数
     * @param pageSize 每页大小
     * @param pageNum  页码
     * @return
     */
    List<KnowledgeQaSegmentModel> queryListForEmbeddingQaAndEmbeddings(Integer days, Integer pageSize, Integer pageNum);

    /**
     * 查询待向量化的问答列表,且rawId为空,手动添加的问答,对问答做向量化,根据字段:qaStatus=-1,created在days天前,
     * 
     * @param days     天数
     * @param pageSize 每页大小
     * @param pageNum  页码
     * @return
     */
    List<KnowledgeQaSegmentModel> queryListForEmbeddingQaAndEmbeddingsAndRawIdIsNull(Integer days, Integer pageSize,
            Integer pageNum);

    /**
     * 根据rawId查询问答id列表
     *
     * @param rawId rawId
     * @return
     */
    List<Long> queryQaIdList(Long rawId);

    /**
     * 全量重置问答的向量化状态(跨所有租户),用于迁移到新向量库后重新向量化。
     * 将所有有效(yn=1)问答的 has_embedding 置为 0、created 置为指定时间(当天0点),
     * 使其重新进入定时任务的向量化查询窗口(has_embedding=0 且 created 在最近N天内)。
     * <p>
     * 注意:属一次性迁移操作,重复调用会把已重灌的 has_embedding=1 又改回 0 导致重复向量化。
     *
     * @param todayZero 重置后的创建时间(当天0点)
     * @return 受影响行数
     */
    int resetAllForVectorRebuild(LocalDateTime todayZero);
}
