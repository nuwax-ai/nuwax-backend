package com.xspaceagi.knowledge.domain.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.web.multipart.MultipartFile;

import com.xspaceagi.knowledge.domain.dto.EmbeddingStatusDto;
import com.xspaceagi.knowledge.domain.dto.qa.QAQueryDto;
import com.xspaceagi.knowledge.domain.dto.qa.QAResDto;
import com.xspaceagi.knowledge.domain.model.KnowledgeQaSegmentModel;
import com.xspaceagi.system.spec.common.UserContext;

public interface IKnowledgeQaSegmentDomainService {

    /**
     * 模板配置详情
     *
     * @param id 模板id
     */
    KnowledgeQaSegmentModel queryOneInfoById(Long id);

    /**
     * 根据分段id查询问答列表
     * 
     * @param rawId 分段id
     * @return 问答列表
     */
    List<KnowledgeQaSegmentModel> queryListInfoByRawId(Long rawId);

    /**
     * 删除根据主键id
     *
     * @param id id
     */
    void deleteById(Long id);

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
     * 批量新增
     */
    void addInfoBatch(List<KnowledgeQaSegmentModel> models, UserContext userContext);

    /**
     * 知识库问答查询
     * 
     * @param qaQueryDto     问答查询
     * @param ignoreKBStatus 是否忽略知识库状态
     * @return
     */
    List<QAResDto> search(QAQueryDto qaQueryDto, boolean ignoreKBStatus);

    /**
     * 知识库问答查询
     * 
     * @param qaQueryDtoList 问答查询
     * @return
     */
    List<QAResDto> search(List<QAQueryDto> qaQueryDtoList);

    /**
     * 查询文档的嵌入状态
     * 
     * @param docId 文档id
     * @return
     */
    EmbeddingStatusDto queryEmbeddingStatus(Long docId);

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
     * 从CSV文件导入问答
     * 
     * @param kbId        知识库id
     * @param file        文件
     * @param userContext 用户上下文
     */
    void importQaFromCsv(Long kbId, MultipartFile file, UserContext userContext);

    /**
     * 从Excel文件导入问答
     * 
     * @param kbId        知识库id
     * @param file        文件
     * @param userContext 用户上下文
     */
    void importQaFromExcel(Long kbId, MultipartFile file, UserContext userContext);

    /**
     * 批量对新增的问答,进行向量化
     * 
     * @param models 新增的问答列表
     */
    void batchAddEmbeddingQa(List<KnowledgeQaSegmentModel> models, UserContext userContext);

    /**
     * 全量重置问答向量化状态(跨所有租户),用于迁移到新向量库后重新向量化。
     * 将所有有效问答的 has_embedding 置为 0、created 置为指定时间(当天0点)。
     *
     * @param todayZero 重置后的创建时间(当天0点)
     * @return 受影响行数
     */
    int resetAllForVectorRebuild(LocalDateTime todayZero);

}
