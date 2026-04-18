package com.xspaceagi.knowledge.core.infra.respository;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Repository;

import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.xspaceagi.knowledge.core.infra.dao.entity.KnowledgeDocument;
import com.xspaceagi.knowledge.core.infra.dao.entity.KnowledgeQaSegment;
import com.xspaceagi.knowledge.core.infra.dao.entity.KnowledgeRawSegment;
import com.xspaceagi.knowledge.core.infra.dao.entity.KnowledgeTask;
import com.xspaceagi.knowledge.core.infra.dao.mapper.KnowledgeDocumentMapper;
import com.xspaceagi.knowledge.core.infra.dao.service.KnowledgeConfigService;
import com.xspaceagi.knowledge.core.infra.dao.service.KnowledgeDocumentService;
import com.xspaceagi.knowledge.core.infra.dao.service.KnowledgeQaSegmentService;
import com.xspaceagi.knowledge.core.infra.dao.service.KnowledgeRawSegmentService;
import com.xspaceagi.knowledge.core.infra.dao.service.KnowledgeTaskService;
import com.xspaceagi.knowledge.core.infra.translator.IKnowledgeDocumentTranslator;
import com.xspaceagi.knowledge.sdk.enums.KnowledgeDocStatueEnum;
import com.xspaceagi.knowledge.sdk.enums.KnowledgePubStatusEnum;
import com.xspaceagi.knowledge.core.spec.enums.KnowledgeTaskRunTypeEnum;
import com.xspaceagi.knowledge.core.spec.enums.QaStatusEnum;
import com.xspaceagi.knowledge.core.spec.utils.CaffeineCacheUtil;
import com.xspaceagi.knowledge.core.spec.utils.ThreadTenantUtil;
import com.xspaceagi.knowledge.domain.model.KnowledgeDocumentModel;
import com.xspaceagi.knowledge.domain.repository.IKnowledgeDocumentRepository;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.KnowledgeException;
import com.xspaceagi.system.spec.tenant.thread.TenantRunnable;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Repository
public class KnowledgeDocumentRepository implements IKnowledgeDocumentRepository {

    @Resource
    private IKnowledgeDocumentTranslator knowledgeDocumentTranslator;

    @Resource
    private KnowledgeDocumentService knowledgeDocumentService;

    @Resource
    private KnowledgeDocumentMapper knowledgeDocumentMapper;

    @Resource
    private KnowledgeQaSegmentService knowledgeQaSegmentService;

    @Resource
    private KnowledgeRawSegmentService knowledgeRawSegmentService;

    @Resource
    private KnowledgeTaskService knowledgeTaskService;

    @Resource
    private KnowledgeConfigService knowledgeConfigService;

    @Resource
    private ThreadTenantUtil threadTenantUtil;

    @Lazy
    @Resource
    private IKnowledgeDocumentRepository self;

    @Override
    public List<KnowledgeDocumentModel> pageQuery(Map<String, Object> queryMap, List<OrderItem> orderColumns,
            Long startIndex, Long pageSize) {
        try {
            var dataList = this.knowledgeDocumentMapper.queryList(queryMap,
                    orderColumns, startIndex, pageSize);
            var ans = dataList.stream()
                    .map(sysUser -> this.knowledgeDocumentTranslator.convertToModel(sysUser))
                    .collect(Collectors.toList());

            if (ans.isEmpty()) {
                return ans;
            }

            // 批量更新文档状态
            updateDocumentsStatus(ans);

            return ans;
        } catch (Exception e) {
            log.error("Error in pageQuery: ", e);
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }
    }

    @Override
    public KnowledgeDocumentModel queryOneInfoById(Long id) {
        var data = this.knowledgeDocumentService.queryOneInfoById(id);
        if (Objects.isNull(data)) {
            return null;
        }

        var model = this.knowledgeDocumentTranslator.convertToModel(data);

        // 更新单个文档状态
        updateDocumentsStatus(List.of(model));

        return model;
    }

    /**
     * 批量更新文档状态
     * 此方法会查询所需的所有相关数据并更新文档状态
     *
     * @param documents 需要更新状态的文档列表
     */
    private void updateDocumentsStatus(List<KnowledgeDocumentModel> documents) {
        if (Objects.isNull(documents) || documents.isEmpty()) {
            return;
        }

        try {
            // 获取所有文档ID
            var docIds = documents.stream()
                    .map(KnowledgeDocumentModel::getId)
                    .collect(Collectors.toList());

            // 批量查询相关数据
            var qaSegments = this.knowledgeQaSegmentService.queryListByDocIds(docIds);
            var rawSegments = this.knowledgeRawSegmentService.queryListByDocIds(docIds);
            var retryTasks = this.knowledgeTaskService.queryListByDocIds(docIds);

            // 转换成map方便查询
            var qaSegmentMap = qaSegments.stream()
                    .collect(Collectors.groupingBy(KnowledgeQaSegment::getDocId));
            var rawSegmentMap = rawSegments.stream()
                    .collect(Collectors.groupingBy(KnowledgeRawSegment::getDocId));
            var retryTaskMap = retryTasks.stream()
                    .collect(Collectors.groupingBy(KnowledgeTask::getDocId));

            // 更新每个文档的状态
            for (KnowledgeDocumentModel document : documents) {
                updateDocumentStatus(
                        document,
                        retryTaskMap.get(document.getId()),
                        qaSegmentMap.get(document.getId()),
                        rawSegmentMap.get(document.getId()));
            }
        } catch (Exception e) {
            log.error("Error updating documents status for {} documents: ", documents.size(), e);
            // 发生异常时不中断流程，继续返回数据
        }
    }

    /**
     * 更新单个文档状态
     * 状态检查优先级：
     * 1. 重试失败
     * 2. 向量化处理中
     * 3. 问答生成中
     */
    private void updateDocumentStatus(
            KnowledgeDocumentModel document,
            List<KnowledgeTask> retryTasks,
            List<KnowledgeQaSegment> qaSegments,
            List<KnowledgeRawSegment> rawSegments) {

        // 默认设置为分析成功
        document.setDocStatus(KnowledgeDocStatueEnum.ANALYZED);

        try {
            // 检查重试失败 - 最高优先级
            if (checkRetryFailed(document, retryTasks)) {
                return;
            }

            // 检查问答生成状态 - 次高优先级
            checkQaGenerationStatus(document, rawSegments);

            // 检查向量化状态 - 最低优先级
            if (checkEmbeddingStatus(document, qaSegments)) {
                return;
            }

        } catch (Exception e) {
            log.error("Error updating document status for document {}: ", document.getId(), e);
            document.setDocStatus(KnowledgeDocStatueEnum.ANALYZE_FAILED);
            document.setDocStatusReason("状态更新过程中发生错误");
        }
    }

    /**
     * 检查重试任务是否失败
     */
    private boolean checkRetryFailed(KnowledgeDocumentModel document, List<KnowledgeTask> retryTasks) {
        if (Objects.nonNull(retryTasks) && !retryTasks.isEmpty()) {
            KnowledgeTask retryTask = retryTasks.get(0);
            if (retryTask.getRetryCnt() >= retryTask.getMaxRetryCnt()) {
                document.setDocStatus(KnowledgeDocStatueEnum.ANALYZE_FAILED);
                document.setDocStatusReason("重试次数达到最大值");
                log.warn("Document {} analysis failed due to max retry count reached", document.getId());
                return true;
            }
            //如果重试状态是成功,则认为是成功了
            if (Objects.nonNull(retryTask.getType()) && KnowledgeTaskRunTypeEnum.SUCCESS.getType() == retryTask.getType()) {
                document.setDocStatus(KnowledgeDocStatueEnum.ANALYZED);
                document.setDocStatusReason(KnowledgeDocStatueEnum.ANALYZED.getStepDesc());
                //重试任务显示成功,返回true,可以不用判断文档的qa状态和向量化状态
                return true;
            }
            // 检查文档状态,避免分段后续流程都还没走,就显示成功
            var qaFlag = document.getHasQa();
            var embeddingFlag = document.getHasEmbedding();
            if (qaFlag && embeddingFlag) {
                document.setDocStatus(KnowledgeDocStatueEnum.ANALYZED);
                document.setDocStatusReason(KnowledgeDocStatueEnum.ANALYZED.getStepDesc());
                return true;
            } else {
                var retryType = retryTask.getType();
                KnowledgeTaskRunTypeEnum runTypeEnum = KnowledgeTaskRunTypeEnum.getByType(retryType);
                if (Objects.nonNull(runTypeEnum)) {
                    switch (runTypeEnum) {
                        case SEGMENT:
                            document.setDocStatus(KnowledgeDocStatueEnum.ANALYZING_RAW);
                            document.setDocStatusReason(KnowledgeDocStatueEnum.ANALYZING_RAW.getStepDesc());
                            break;
                        case QA:
                            document.setDocStatus(KnowledgeDocStatueEnum.ANALYZED_QA);
                            document.setDocStatusReason(KnowledgeDocStatueEnum.ANALYZED_QA.getStepDesc());
                            break;
                        case EMBEDDING:
                            document.setDocStatus(KnowledgeDocStatueEnum.ANALYZED_EMBEDDING);
                            document.setDocStatusReason(KnowledgeDocStatueEnum.ANALYZED_EMBEDDING.getStepDesc());
                            break;
                        default:
                            document.setDocStatus(KnowledgeDocStatueEnum.ANALYZING);
                            document.setDocStatusReason(KnowledgeDocStatueEnum.ANALYZING.getStepDesc());
                            break;
                    }
                } else {
                    document.setDocStatus(KnowledgeDocStatueEnum.ANALYZING);
                    document.setDocStatusReason(KnowledgeDocStatueEnum.ANALYZING.getStepDesc());
                }
            }
        }
        return false;
    }

    /**
     * 检查向量化状态
     */
    private boolean checkEmbeddingStatus(KnowledgeDocumentModel document, List<KnowledgeQaSegment> qaSegments) {
        if (Objects.nonNull(qaSegments) && !qaSegments.isEmpty() &&
                qaSegments.stream().anyMatch(segment -> !segment.getHasEmbedding())) {
            document.setDocStatus(KnowledgeDocStatueEnum.ANALYZED_EMBEDDING);
            document.setDocStatusReason(KnowledgeDocStatueEnum.ANALYZED_EMBEDDING.getStepDesc());
            log.debug("Document {} is still in embedding generation process", document.getId());
            return true;
        }
        return false;
    }

    /**
     * 检查问答生成状态
     */
    private boolean checkQaGenerationStatus(KnowledgeDocumentModel document, List<KnowledgeRawSegment> rawSegments) {
        if (Objects.nonNull(rawSegments) && !rawSegments.isEmpty() &&
                rawSegments.stream()
                        .anyMatch(segment -> QaStatusEnum.PENDING.getCode().equals(segment.getQaStatus()))) {
            document.setDocStatus(KnowledgeDocStatueEnum.ANALYZED_QA);
            document.setDocStatusReason(KnowledgeDocStatueEnum.ANALYZED_QA.getStepDesc());
            log.debug("Document {} is still in QA generation process", document.getId());
            return true;
        }
        return false;
    }

    @Override
    public Long queryTotal(Map<String, Object> queryMap) {
        return this.knowledgeDocumentMapper.queryTotal(queryMap);
    }

    @DSTransactional(rollbackFor = Exception.class)
    @Override
    public void deleteById(Long id) {
        var existObj = this.knowledgeDocumentService.getById(id);
        if (existObj == null) {
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }

        this.knowledgeDocumentService.removeById(id);


    }

    /**
     * 触发异步更新知识库文件预估大小值
     *
     * @param id 知识库ID
     */
    @Override
    public void triggerUpdateKnowledgeFileSize(Long id) {
        // 触发更新知识库文件预估大小值
        TenantRunnable runnable = new TenantRunnable(() -> {
            // 更新知识库的文件预估大小值
            var fileSize = CaffeineCacheUtil.refreshFileSizeCache(id,
                    this.knowledgeDocumentService::queryTotalFileSize);
            this.knowledgeConfigService.updateKnowledgeConfigFileSize(id, fileSize);
        });
        threadTenantUtil.obtainOtherScheduledExecutor().execute(runnable);
    }

    @Override
    public Long updateInfo(KnowledgeDocumentModel model, UserContext userContext) {
        var docId = model.getId();
        var existObj = this.knowledgeDocumentService.queryOneInfoById(docId);
        if (Objects.isNull(existObj)) {
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }

        model.setCreatorId(null);
        model.setCreatorName(null);
        model.setModified(null);
        model.setModifiedId(userContext.getUserId());
        model.setModifiedName(userContext.getUserName());
        model.setHasQa(model.getHasQa());
        model.setHasEmbedding(model.getHasEmbedding());
        var entity = this.knowledgeDocumentTranslator.convertToEntity(model);
        this.knowledgeDocumentService.updateInfo(entity);

        return docId;
    }

    @Override
    public Long updateDocName(KnowledgeDocumentModel model, UserContext userContext) {
        var docId = model.getId();
        var existObj = this.knowledgeDocumentService.queryOneInfoById(docId);
        if (Objects.isNull(existObj)) {
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }

        KnowledgeDocument updateObj = new KnowledgeDocument();
        updateObj.setId(docId);
        updateObj.setName(model.getName());
        updateObj.setModifiedId(userContext.getUserId());
        updateObj.setModifiedName(userContext.getUserName());
        this.knowledgeDocumentService.updateInfo(updateObj);

        return docId;
    }

    @Override
    public Long addInfo(KnowledgeDocumentModel model, UserContext userContext) {
        model.setId(null);
        model.setCreatorId(userContext.getUserId());
        model.setCreatorName(userContext.getUserName());

        var entity = this.knowledgeDocumentTranslator.convertToEntity(model);
        var id = this.knowledgeDocumentService.addInfo(entity);

        model.setId(id);

        return id;
    }

    @Override
    public void changeHasQaStatus(Long docId, Boolean hasQa, UserContext userContext) {

        // 修改Qa完成状态
        var model = new KnowledgeDocumentModel();
        model.setId(docId);
        model.setHasQa(hasQa);

        model.setCreatorId(null);
        model.setCreatorName(null);
        model.setModified(null);
        model.setModifiedId(userContext.getUserId());
        model.setModifiedName(userContext.getUserName());
        var entity = this.knowledgeDocumentTranslator.convertToEntity(model);
        this.knowledgeDocumentService.updateInfo(entity);

    }

    @Override
    public void changeHasEmbeddingStatus(Long docId, Boolean hasEmbedding, UserContext userContext) {
        var model = new KnowledgeDocumentModel();
        model.setId(docId);
        model.setHasEmbedding(hasEmbedding);

        model.setCreatorId(null);
        model.setCreatorName(null);
        model.setModified(null);
        model.setModifiedId(userContext.getUserId());
        model.setModifiedName(userContext.getUserName());
        var entity = this.knowledgeDocumentTranslator.convertToEntity(model);
        this.knowledgeDocumentService.updateInfo(entity);

    }

    @Override
    public List<KnowledgeDocumentModel> queryListByConfigId(Long configId) {

        var dataList = this.knowledgeDocumentService.queryListByConfigId(configId);
        var ans = dataList.stream()
                .map(sysUser -> this.knowledgeDocumentTranslator.convertToModel(sysUser))
                .collect(Collectors.toList());
        return ans;
    }

    @Override
    public List<Long> queryDocIdsByConfigId(Long configId, KnowledgePubStatusEnum knowledgePubStatusEnum) {
        String knowledgePubStatus = null;

        if (Objects.nonNull(knowledgePubStatusEnum)) {
            knowledgePubStatus = knowledgePubStatusEnum.toString();
        }
        var ids = this.knowledgeDocumentMapper.queryDocIdsByConfigId(configId, knowledgePubStatus);
        return ids;
    }

    @Override
    public KnowledgeDocumentModel queryOneInfoByQaSegmentId(Long qaSegmentId) {
        var data = this.knowledgeDocumentMapper.queryOneInfoByQaSegmentId(qaSegmentId);
        return this.knowledgeDocumentTranslator.convertToModel(data);
    }

    @Override
    public List<KnowledgeDocumentModel> queryDocStatus(List<Long> docIds) {
        var dataList = this.knowledgeDocumentService.queryListByIds(docIds);
        var ans = dataList.stream()
                .map(sysUser -> this.knowledgeDocumentTranslator.convertToModel(sysUser))
                .collect(Collectors.toList());
        // 批量更新文档状态
        updateDocumentsStatus(ans);

        return ans;
    }

    @Override
    public List<KnowledgeDocumentModel> queryDocByKbId(Long kbId) {
        var dataList = this.knowledgeDocumentService.queryDocByKbId(kbId);
        var ans = dataList.stream()
                .map(sysUser -> this.knowledgeDocumentTranslator.convertToModel(sysUser))
                .collect(Collectors.toList());
        return ans;
    }

    @Override
    public List<KnowledgeDocumentModel> queryListByIds(List<Long> docIds) {
        if (Objects.isNull(docIds) || docIds.isEmpty()) {
            return List.of();
        }
        
        var dataList = this.knowledgeDocumentService.queryListByIds(docIds);
        return dataList.stream()
                .map(doc -> this.knowledgeDocumentTranslator.convertToModel(doc))
                .collect(Collectors.toList());
    }
}
