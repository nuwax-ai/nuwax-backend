package com.xspaceagi.knowledge.domain.service.impl;

import com.alibaba.fastjson2.JSON;
import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.google.common.collect.Lists;
import com.xspaceagi.agent.core.adapter.application.ModelApplicationService;
import com.xspaceagi.knowledge.core.adapter.client.dto.PushResult;
import com.xspaceagi.knowledge.core.spec.enums.FulltextSyncStatusEnum;
import com.xspaceagi.knowledge.core.spec.enums.KnowledgeTaskRunTypeEnum;
import com.xspaceagi.knowledge.core.spec.enums.QaStatusEnum;
import com.xspaceagi.knowledge.core.spec.enums.RawTextFulltextSyncStatusEnum;
import com.xspaceagi.knowledge.core.spec.utils.Constants;
import com.xspaceagi.knowledge.core.spec.utils.ThreadTenantUtil;
import com.xspaceagi.knowledge.domain.docparser.DocParserService;
import com.xspaceagi.knowledge.domain.dto.EmbeddingStatusDto;
import com.xspaceagi.knowledge.domain.dto.qa.QAEmbeddingDto;
import com.xspaceagi.knowledge.domain.dto.task.AutoRecordTask;
import com.xspaceagi.knowledge.domain.model.*;
import com.xspaceagi.knowledge.domain.repository.*;
import com.xspaceagi.knowledge.domain.service.IKnowledgeDocumentDomainService;
import com.xspaceagi.knowledge.domain.service.IKnowledgeFullTextSearchDomainService;
import com.xspaceagi.knowledge.domain.service.IKnowledgeTaskDomainService;
import com.xspaceagi.knowledge.domain.vectordb.VectorDBService;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.service.TenantConfigApplicationService;
import com.xspaceagi.system.domain.log.LogPrint;
import com.xspaceagi.system.domain.log.LogRecordPrint;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.KnowledgeException;
import com.xspaceagi.system.spec.tenant.thread.TenantRunnable;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.xspaceagi.knowledge.core.spec.utils.Prompts.GENERATE_QA_PROMPT;

@Slf4j
@Service
public class KnowledgeDocumentDomainService implements IKnowledgeDocumentDomainService {

    @Resource
    private IKnowledgeConfigRepository knowledgeConfigRepository;

    @Resource
    private IKnowledgeDocumentRepository knowledgeDocumentRepository;

    @Resource
    private IKnowledgeQaSegmentRepository knowledgeQaSegmentRepository;

    @Resource
    private IKnowledgeRawSegmentRepository knowledgeRawSegmentRepository;

    @Resource
    private IKnowledgeTaskRepository knowledgeTaskRepository;

    @Resource
    private VectorDBService vectorDBService;

    @Resource
    private ModelApplicationService modelApplicationService;

    /**
     * 文件解析服务
     */
    @Resource
    private DocParserService docParserService;

    @Resource
    private ThreadTenantUtil threadTenantUtil;

    /**
     * 记录任务服务,用于查询状态
     */
    @Resource
    private IKnowledgeTaskDomainService knowledgeTaskDomainService;

    @Lazy
    @Resource
    private KnowledgeDocumentDomainService self;

    @Resource
    private TenantConfigApplicationService tenantConfigApplicationService;

    @Resource
    private IKnowledgeFullTextSearchDomainService fullTextSearchDomainService;

    @Override
    public KnowledgeDocumentModel queryOneInfoById(Long id) {
        var model = knowledgeDocumentRepository.queryOneInfoById(id);

        return model;
    }

    @DSTransactional(rollbackFor = Exception.class)
    @Override
    public void deleteById(Long id, UserContext userContext) {

        var existObj = this.knowledgeDocumentRepository.queryOneInfoById(id);
        if (existObj == null) {
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }

        // 删除文档后，需要删除文档对应的qa和raw
        this.knowledgeQaSegmentRepository.deleteByDocumentId(id);
        this.knowledgeRawSegmentRepository.deleteByConfigDocumentId(id);

        this.knowledgeDocumentRepository.deleteById(id);
        this.knowledgeTaskRepository.deleteByDocId(id);

        // 删除向量数据库的数据（语义检索）
        var kbId = existObj.getKbId();
        this.vectorDBService.removeDoc(kbId, id);

        // 删除全文检索数据（在事务内，保证一致性）
        try {
            fullTextSearchDomainService.deleteByDocId(id, kbId, userContext.getTenantId());
            log.info("Delete doc full-text OK: docId={}, kbId={}", id, kbId);
        } catch (Exception e) {
            log.error("Delete doc full-text failed: docId={}, kbId={}", id, kbId, e);
            // 抛出异常，触发事务回滚
            throw KnowledgeException.build(BizExceptionCodeEnum.knowledgeDeleteDocFulltextFailed, e);
        }

        // 更新知识库最后操作时间
        this.knowledgeConfigRepository.updateLatestModifyTime(existObj.getKbId(), userContext);

    }

    @Override
    public void triggerUpdateKnowledgeFileSize(Long kbId) {
        this.knowledgeDocumentRepository.triggerUpdateKnowledgeFileSize(kbId);
    }

    @DSTransactional(rollbackFor = Exception.class)
    @Override
    public Long updateInfo(KnowledgeDocumentModel model, UserContext userContext) {
        // 修改文档后，需要删除文档对应的qa和raw
        var docId = model.getId();
        var existObj = this.knowledgeDocumentRepository.queryOneInfoById(docId);
        if (existObj == null) {
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }
        this.knowledgeQaSegmentRepository.deleteByDocumentId(docId);
        this.knowledgeRawSegmentRepository.deleteByConfigDocumentId(docId);

        var id = this.knowledgeDocumentRepository.updateInfo(model, userContext);

        // 删除向量数据库的数据（语义检索）
        var kbId = existObj.getKbId();
        this.vectorDBService.removeDoc(kbId, id);

        // 注意：全文检索数据的删除由 Application 层的 KnowledgeFullTextSyncService 处理

        try {
            // 线程池提交生成向量数据库的数据的任务
            Runnable runnable = () -> {
                // 开始分段（分段完成后会自动同步全文检索）
                this.self.workRunTaskForDocument(model, userContext, id);

            };
            TenantRunnable tenantRunnable = new TenantRunnable(runnable);

            threadTenantUtil.obtainRawExecutor().execute(tenantRunnable);
        } catch (Exception e) {
            log.error("Failed to submit vector DB generation task to executor, reason:", e);
        }
        // 更新知识库最后操作时间
        this.knowledgeConfigRepository.updateLatestModifyTime(model.getKbId(), userContext);

        return id;
    }

    @Override
    public Long updateDocName(KnowledgeDocumentModel model, UserContext userContext) {

        var id = this.knowledgeDocumentRepository.updateDocName(model, userContext);

        // 更新知识库最后操作时间
        this.knowledgeConfigRepository.updateLatestModifyTime(model.getKbId(), userContext);

        return id;
    }

    @DSTransactional(rollbackFor = Exception.class)
    @Override
    public KnowledgeDocumentIdAndTaskIdVo addDocument(KnowledgeDocumentModel model, UserContext userContext) {
        var docId = this.knowledgeDocumentRepository.addInfo(model, userContext);

        // 记录一个重试任务,用户线程池没有执行的时候,也会有对应的重试任务记录
        var autoRecordTask = AutoRecordTask.builder()
                .docId(docId)
                .spaceId(model.getSpaceId())
                .kbId(model.getKbId())
                .build();
        var taskId = this.knowledgeTaskDomainService.createNewTask(autoRecordTask, KnowledgeTaskRunTypeEnum.SEGMENT,
                userContext);

        var result = KnowledgeDocumentIdAndTaskIdVo.builder()
                .docId(docId)
                .taskId(taskId)
                .build();
        if (log.isDebugEnabled()) {
            log.debug("Add doc, result:{}", JSON.toJSONString(result));
        }
        return result;
    }

    @Override
    public Long addInfo(KnowledgeDocumentModel model, UserContext userContext) {
        var documentIdAndTaskIdVo = this.self.addDocument(model, userContext);

        var docId = documentIdAndTaskIdVo.getDocId();

        try {
            // 线程池提交生成向量数据库的数据的任务
            Runnable runnable = () -> this.self.workRunTaskForDocument(model, userContext, docId);

            TenantRunnable tenantRunnable = new TenantRunnable(runnable);
            threadTenantUtil.obtainRawExecutor().execute(tenantRunnable);
        } catch (Exception e) {
            log.error("Failed to submit vector DB generation task to executor, reason:", e);
        }

        // 更新知识库最后操作时间
        this.knowledgeConfigRepository.updateLatestModifyTime(model.getKbId(), userContext);

        return docId;
    }

    @Override
    public Long customAddInfo(KnowledgeDocumentModel model, UserContext userContext) {

        var id = this.self.addInfo(model, userContext);

        return id;
    }

    @Override
    public List<Long> batchAddInfo(List<KnowledgeDocumentModel> modelList, UserContext userContext) {

        var ids = new ArrayList<Long>();
        for (KnowledgeDocumentModel knowledgeDocumentModel : modelList) {
            var id = this.self.addInfo(knowledgeDocumentModel, userContext);
            ids.add(id);
        }

        return ids;
    }

    /**
     * 文档分段,问答,以及对问答生成向量化的任务处理
     *
     * @param model       文档
     * @param userContext 操作用户
     * @param docId       文档id
     */
    @Override
    public void workRunTaskForDocument(KnowledgeDocumentModel model, UserContext userContext, Long docId) {
        log.debug("Vector task submit start, docId={}", docId);

        // 1. 执行文档分段
        this.self.workRunTaskForRawSegment(model, userContext, docId);
        log.info("Segment task done: kbId={}, docId={}", model.getKbId(), docId);

        // 2. 同步执行全文检索同步
        this.self.workRunTaskForFullTextSync(model, userContext, docId);
        log.info("FTS sync done, async QA+embedding: kbId={}, docId={}", model.getKbId(), docId);

        // 3. 异步执行 QA 和 Embedding
        TenantRunnable tenantRunnable = new TenantRunnable(() -> {
            // 开始生成QA,生成每个问答后,会开始向量化
            this.self.workRunTaskForQaAndEmbedding(model, userContext, docId);
        });

        threadTenantUtil.obtainCommonExecutor().execute(tenantRunnable);

    }

    @Override
    public void workRunTaskForRawSegment(KnowledgeDocumentModel model, UserContext userContext, Long docId) {
        // 分段前，清空该文档已有的 QA 和原始分段，避免重复数据
        this.knowledgeQaSegmentRepository.deleteByDocumentId(docId);
        this.knowledgeRawSegmentRepository.deleteByConfigDocumentId(docId);

        var autoRecordTask = AutoRecordTask.builder()
                .docId(docId)
                .spaceId(model.getSpaceId())
                .kbId(model.getKbId())
                .build();

        // 开始分段
        this.knowledgeTaskDomainService.changeTaskStatus(autoRecordTask, KnowledgeTaskRunTypeEnum.SEGMENT, userContext);
        this.docParserService.parse(model, userContext);
        log.info("Doc segmented: kbId={}, docId={}", model.getKbId(), docId);

    }

    @Override
    public void workRunTaskForQaAndEmbedding(KnowledgeDocumentModel model, UserContext userContext, Long docId) {
        // 开始生成QA,生成每个问答后,会开始向量化
        this.self.generateForQa(docId, userContext);

        log.info("QA+embedding done, docId={}", docId);

        // 判断任务是否完毕,以及有无待向量化的问答
        this.self.updateDocumentEmbeddingStatus(docId, userContext);
    }

    @LogPrint(step = "知识库-文档自动处理任务")
    @Override
    public void workRetryRunTaskForDocument(KnowledgeTaskRunTypeEnum runTypeEnum, KnowledgeDocumentModel model,
            UserContext userContext, Long docId) {

        switch (runTypeEnum) {
            case SEGMENT -> {
                // 文档分段,全文检索,问答,向量化,全部走重试
                this.self.workRunTaskForRawSegment(model, userContext, docId);
                this.self.workRunTaskForFullTextSync(model, userContext, docId);
                this.self.workRunTaskForQaAndEmbedding(model, userContext, docId);
            }
            case FULLTEXT_SYNC -> {
                // 全文检索同步阶段失败，重新执行同步，并继续后续流程
                this.self.workRunTaskForFullTextSync(model, userContext, docId);
                this.self.workRunTaskForQaAndEmbedding(model, userContext, docId);
            }
            case QA -> {
                // 开始生成QA,生成每个问答后,会开始向量化
                this.self.workRunTaskForQaAndEmbedding(model, userContext, docId);

            }
            case EMBEDDING -> {
                // 开始生成QA的向量化
                this.self.generateEmbeddings(docId, userContext);
                log.info("Vector task submit OK, docId={}", docId);

                // 判断任务是否完毕,还是有向量化的问答
                this.self.updateDocumentEmbeddingStatus(docId, userContext);

            }
            default -> {
                log.info("No retry needed, docId={}", docId);
            }
        }

    }

    @Override
    public void generateForQa(Long docId, UserContext userContext) {
        var docModel = this.knowledgeDocumentRepository.queryOneInfoById(docId);

        if (Objects.isNull(docModel)) {
            log.warn("Document not found, docId={}", docId);
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }
        List<KnowledgeRawSegmentModel> segments = this.knowledgeRawSegmentRepository
                .queryListForPendingQaByDocId(docId);

        this.self.generateForQaByRawSegment(segments, userContext);

        this.self.checkIsQaFinishAndChangeDocStatus(docId, userContext);
    }

    @Override
    public void checkIsQaFinishAndChangeDocStatus(Long docId, UserContext userContext) {
        // 查询分段的问答生成状态,如果全部成功,在修改重试任务状态为:向量化
        var count = this.knowledgeRawSegmentRepository.queryCountForPendingQaByDocId(docId);
        if (count == 0) {
            // 更新文档的QA状态
            this.knowledgeDocumentRepository.changeHasQaStatus(docId, Boolean.TRUE, userContext);

            var docModel = this.knowledgeDocumentRepository.queryOneInfoById(docId);
            if (Objects.isNull(docModel)) {
                log.warn("Document not found, docId={}", docId);
                throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
            }
            // 问答全部生成完毕,现在修改重试任务状态为:向量化
            var autoRecordTask = AutoRecordTask.builder()
                    .docId(docId)
                    .spaceId(docModel.getSpaceId())
                    .kbId(docModel.getKbId())
                    .build();
            this.knowledgeTaskDomainService.changeTaskStatus(autoRecordTask, KnowledgeTaskRunTypeEnum.EMBEDDING,
                    userContext);
        }
    }

    @Override
    public void generateEmbeddings(Long docId, UserContext userContext) {

        var docModel = this.knowledgeDocumentRepository.queryOneInfoById(docId);

        if (Objects.isNull(docModel)) {
            log.info("Document not found, docId={}", docId);
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }

        // only return the segments that do not have embeddings
        List<KnowledgeQaSegmentModel> segments = this.knowledgeQaSegmentRepository
                .queryListByDocIdAndNoEmbedding(docId);
        List<Long> rawIds = segments.stream()
                .filter(Objects::nonNull)
                .map(KnowledgeQaSegmentModel::getRawId).toList();

        if (CollectionUtils.isEmpty(rawIds)) {
            // 没有需要向量化的数据
            log.info("Nothing to embed, docId={}", docId);
            // 更新文档的嵌入状态
            this.self.updateDocumentEmbeddingStatus(docId, userContext);
            return;
        }

        // 批量处理每个批次
        this.self.generateEmbeddingsByQaSegment(segments, userContext);

        // 更新文档的嵌入状态
        this.self.updateDocumentEmbeddingStatus(docId, userContext);

    }

    /**
     * 更新文档的嵌入状态,检查每个分段是否生成了对应的问答,如果生成了,且问答都向量化完毕,则更新文档的嵌入状态
     *
     * @param docId       文档ID
     * @param userContext 用户上下文
     */
    @Override
    public void updateDocumentEmbeddingStatus(Long docId, UserContext userContext) {
        var count = this.knowledgeRawSegmentRepository.queryCountForPendingQaByDocId(docId);
        // count为0,标识分段全部生成了对应的问答
        if (count == 0) {
            var docModel = this.knowledgeDocumentRepository.queryOneInfoById(docId);

            if (Objects.isNull(docModel)) {
                log.warn("Document not found, docId={}", docId);
                throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
            }
            EmbeddingStatusDto embeddingStatusDto = this.knowledgeQaSegmentRepository.queryEmbeddingStatus(docId);
            var successFlag = false;
            if (Objects.nonNull(embeddingStatusDto)
                    && embeddingStatusDto.getQaCount().equals(embeddingStatusDto.getQaEmbeddingCount())) {
                successFlag = true;
            } else if (Objects.isNull(embeddingStatusDto)) {
                // embeddingStatusDto 为空,表示没有任何问答的情况,但分段生成的问答已经全部完毕,那就是没有抽取出问答,此时也是任务成功
                log.info("docId={}, QA pass done, maybe no QAs extracted", docId);
                var noEmbeddingCount = this.knowledgeQaSegmentRepository.queryCountByDocIdAndNoEmbedding(docId);
                if (noEmbeddingCount == 0) {
                    successFlag = true;
                }
            }
            if (successFlag) {
                // 更新文档的QA状态
                this.knowledgeDocumentRepository.changeHasEmbeddingStatus(docId, Boolean.TRUE, userContext);

                // 向量化完成,任务完毕
                var autoRecordTask = AutoRecordTask.builder()
                        .docId(docId)
                        .spaceId(docModel.getSpaceId())
                        .kbId(docModel.getKbId())
                        .build();
                this.knowledgeTaskDomainService.changeTaskStatus(autoRecordTask, KnowledgeTaskRunTypeEnum.SUCCESS,
                        userContext);
            }
        }
    }

    /**
     * 生成问答,根据文本
     *
     * @param rawId
     * @param text
     * @param docID
     * @param kbId
     * @param userContext
     */
    @LogRecordPrint(content = "[知识库文档]-根据分段来生成问答")
    protected void processQa(Long rawId, String text, Long docID, Long kbId, Long spaceId, UserContext userContext) {

        Long modelId = 1L;
        if (RequestContext.get() != null && RequestContext.get().getTenantId() != null) {
            TenantConfigDto tenantConfig = tenantConfigApplicationService
                    .getTenantConfig(RequestContext.get().getTenantId());
            if (tenantConfig != null) {
                modelId = tenantConfig.getDefaultKnowledgeModelId();
            }
        }
        // 转义用户文本中的花括号,不然导致问题: ST模板引擎默认使用{}作为定界符，若用户输入包含未转义的{或}符号会破坏模板结构
        String userText = text.replace("{", "\\{").replace("}", "\\}");
        // 调用大模型,生成问答
        KnowledgeQaForModelListVo qaListVo = modelApplicationService.call(
                modelId,
                GENERATE_QA_PROMPT,
                userText,
                new ParameterizedTypeReference<KnowledgeQaForModelListVo>() {
                });

        if (Objects.isNull(qaListVo) || CollectionUtils.isEmpty(qaListVo.getQaList())) {
            log.info("LLM QA empty (text too short?), docID={}, rawId={}", docID, rawId);
            return;
        }
        List<KnowledgeQaSegmentModel> qaSegments = new ArrayList<>();
        for (KnowledgeQaForModelResponseVo qa : qaListVo.getQaList()) {
            KnowledgeQaSegmentModel qaSegment = new KnowledgeQaSegmentModel();
            qaSegment.setDocId(docID);
            qaSegment.setKbId(kbId);
            qaSegment.setSpaceId(spaceId);
            qaSegment.setRawId(rawId);
            if (StringUtils.isBlank(qa.getQuestion())) {
                log.error("LLM Q&A generation empty, docID={}, rawId={}", docID, rawId);
                throw KnowledgeException.build(BizExceptionCodeEnum.knowledgeLlmQuestionEmpty, rawId);
            }
            qaSegment.setQuestion(qa.getQuestion());
            if (StringUtils.isBlank(qa.getAnswer())) {
                log.error("LLM Q&A generation empty, docID={}, rawId={}", docID, rawId);
                throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound, rawId);
            }
            qaSegment.setAnswer(qa.getAnswer());
            qaSegments.add(qaSegment);
        }

        this.knowledgeQaSegmentRepository.batchAddInfo(qaSegments, userContext);
        log.debug("Generate QA for rawId: {}", rawId);
    }

    @Override
    public void generateForQaByRawSegment(List<KnowledgeRawSegmentModel> segments, UserContext userContext) {

        // 按照 docId 分组执行
        Map<Long, List<KnowledgeRawSegmentModel>> docIdGroup = segments.stream()
                .collect(Collectors.groupingBy(KnowledgeRawSegmentModel::getDocId));

        for (Map.Entry<Long, List<KnowledgeRawSegmentModel>> entry : docIdGroup.entrySet()) {
            Long docId = entry.getKey();
            var document = this.knowledgeDocumentRepository.queryOneInfoById(docId);

            if (Objects.isNull(document)) {
                log.info("Document not found, skip, docId={}", docId);
                continue;
            }

            List<KnowledgeRawSegmentModel> segmentList = entry.getValue();
            // 按照分好的段内容,生成QA问答
            for (KnowledgeRawSegmentModel segment : segmentList) {
                var rawId = segment.getId();
                var rawText = segment.getRawTxt();
                if (StringUtils.isBlank(rawText)) {
                    log.info("Empty segment, skip, rawId={}", rawId);
                    segment.setQaStatus(QaStatusEnum.GENERATED.getCode());
                    // 更新每个分段的问答生成状态
                    var updateStatueSegmentList = Lists.newArrayList(segment);
                    this.knowledgeRawSegmentRepository.batchUpdateQaStatus(updateStatueSegmentList, userContext);
                    continue;
                }
                // 因为可能和定时任务有重叠执行,这里在查询下库里的分段数据,对应的问答状态,如果生成了问答,则不在执行
                var existRawSegment = this.knowledgeRawSegmentRepository.queryOneInfoById(rawId);
                if (Objects.nonNull(existRawSegment)
                        && QaStatusEnum.GENERATED.getCode().equals(existRawSegment.getQaStatus())) {
                    log.info("Segment already has QA, skip, rawId={}, docId={}", rawId, docId);
                    continue;
                }

                boolean flag = false;
                try {
                    // 删除分段对应的qa问答,然后在重新生成问答
                    this.knowledgeQaSegmentRepository.deleteByRawId(rawId);
                    this.self.processQa(rawId, rawText, docId, document.getKbId(), document.getSpaceId(), userContext);
                    segment.setQaStatus(QaStatusEnum.GENERATED.getCode());

                    flag = true;

                } catch (Exception e) {
                    log.error("QA gen failed, rawId={}, reason:", rawId, e);
                    segment.setQaStatus(QaStatusEnum.PENDING.getCode());
                }
                // 更新每个分段的问答生成状态
                var updateStatueSegmentList = Lists.newArrayList(segment);
                this.knowledgeRawSegmentRepository.batchUpdateQaStatus(updateStatueSegmentList, userContext);

                // 执行对应问答的向量化逻辑
                if (flag) {
                    var rawList = Lists.newArrayList(segment);
                    this.self.generateQaEmbeddingsByRawSegment(rawList, userContext);
                } else {
                    log.info("QA gen failed, rawId={}, cannot embed QA", rawId);
                }

            }

            // 检查 docId 下的rawText是否全部生成QA了,是的话,更新状态
            this.self.checkIsQaFinishAndChangeDocStatus(docId, userContext);

        }

    }

    @LogRecordPrint(content = "[知识库文档]-根据分段来生成问答后,生成问答向量化")
    @Override
    public void generateQaEmbeddingsByRawSegment(List<KnowledgeRawSegmentModel> segments, UserContext userContext) {

        // 按照文档id,进行分组处理
        Map<Long, List<KnowledgeRawSegmentModel>> docIdGroup = segments.stream()
                .collect(Collectors.groupingBy(KnowledgeRawSegmentModel::getDocId));

        for (Map.Entry<Long, List<KnowledgeRawSegmentModel>> entry : docIdGroup.entrySet()) {
            Long docId = entry.getKey();
            // 根据分段id查询问答
            var rawIds = segments.stream().map(KnowledgeRawSegmentModel::getId).toList();
            List<KnowledgeQaSegmentModel> qaSegments = this.knowledgeQaSegmentRepository
                    .queryListByRawIdsAndNoEmbedding(rawIds);

            // 批量处理每个批次
            this.self.generateEmbeddingsByQaSegment(qaSegments, userContext);

            // 更新文档的嵌入状态
            this.self.updateDocumentEmbeddingStatus(docId, userContext);

        }

    }

    @Override
    public void generateEmbeddingsByQaSegment(List<KnowledgeQaSegmentModel> segments, UserContext userContext) {
        // 按照文档id,进行分组处理
        Map<Long, List<KnowledgeQaSegmentModel>> docIdGroup = segments.stream()
                .collect(Collectors.groupingBy(KnowledgeQaSegmentModel::getDocId));

        var kbIds = segments.stream().map(KnowledgeQaSegmentModel::getKbId).distinct().toList();
        var knowledgeConfigList = this.knowledgeConfigRepository.queryListByIds(kbIds);
        var kbId2EmbeddingModelIdMap = knowledgeConfigList.stream()
                .filter(item -> Objects.nonNull(item.getEmbeddingModelId()))
                .collect(Collectors.toMap(KnowledgeConfigModel::getId, KnowledgeConfigModel::getEmbeddingModelId,
                        (a, b) -> a));

        // 查询问答对应的分段列表
        var rawIds = segments.stream().map(KnowledgeQaSegmentModel::getRawId).toList();
        var rawList = this.knowledgeRawSegmentRepository.queryListInfoByIds(rawIds);
        var rawByRawIdMap = rawList.stream()
                .collect(Collectors.toMap(KnowledgeRawSegmentModel::getId, Function.identity(), (a, b) -> a));

        for (Map.Entry<Long, List<KnowledgeQaSegmentModel>> entry : docIdGroup.entrySet()) {
            Long docId = entry.getKey();
            var embeddingModelId = entry.getValue().stream()
                    .map(KnowledgeQaSegmentModel::getKbId).distinct()
                    .findFirst().map(kbId2EmbeddingModelIdMap::get).orElse(null);
            var document = this.knowledgeDocumentRepository.queryOneInfoById(docId);
            if (Objects.isNull(document)) {
                log.info("Document not found, skip, docId={}", docId);
                continue;
            }
            if (log.isDebugEnabled()) {
                log.debug("Process doc, docId={}, segmentCount={}", docId, entry.getValue().size());
            }
            var batches = Lists.partition(entry.getValue(), Constants.BATCH_SIZE);

            // 批量处理每个批次
            for (List<KnowledgeQaSegmentModel> batch : batches) {
                List<QAEmbeddingDto> qaEmbeddingDtos = new ArrayList<>();
                for (KnowledgeQaSegmentModel segment : batch) {

                    var rawId = segment.getRawId();
                    var rawModel = rawByRawIdMap.get(rawId);
                    String rawText = null;
                    if (Objects.nonNull(rawModel)) {
                        // 记录问答对应的原始分段文本
                        rawText = rawModel.getRawTxt();
                    }
                    QAEmbeddingDto qaEmbeddingDto = QAEmbeddingDto.convertFromModelOnly(segment, rawText);
                    qaEmbeddingDtos.add(qaEmbeddingDto);
                }
                // 批量查询,进行向量化
                List<String> questions = batch.stream().map(KnowledgeQaSegmentModel::getQuestion)
                        .toList();

                List<float[]> embeddings = modelApplicationService.embeddings(questions, embeddingModelId);
                for (int i = 0; i < qaEmbeddingDtos.size(); i++) {
                    List<BigDecimal> embeddingRes = QAEmbeddingDto.convertToBigDecimalList(embeddings.get(i));
                    qaEmbeddingDtos.get(i).setEmbeddings(embeddingRes);
                }
                vectorDBService.addEmbeddingQaForBatch(qaEmbeddingDtos, false);
                // update hasEmbedding
                var qaIds = batch.stream()
                        .map(KnowledgeQaSegmentModel::getId)
                        .toList();

                this.knowledgeQaSegmentRepository.batchChangeEmbeddingStatus(qaIds, Boolean.TRUE, userContext);

            }
        }
    }

    @Override
    public List<KnowledgeDocumentModel> queryDocStatus(List<Long> docIds) {
        // 查询文档状态
        var docStatusList = this.knowledgeDocumentRepository.queryDocStatus(docIds);
        return docStatusList;
    }

    @Override
    public List<KnowledgeDocumentModel> queryDocByKbId(Long kbId) {
        var docList = this.knowledgeDocumentRepository.queryDocByKbId(kbId);
        return docList;
    }

    // ========== 全文检索相关方法 ========== //

    /**
     * 执行全文检索同步任务（用于正常流程）
     *
     * @param model       文档模型
     * @param userContext 用户上下文
     * @param docId       文档ID
     */
    public void workRunTaskForFullTextSync(KnowledgeDocumentModel model, UserContext userContext, Long docId) {
        var autoRecordTask = AutoRecordTask.builder()
                .docId(docId)
                .spaceId(model.getSpaceId())
                .kbId(model.getKbId())
                .build();

        // 1. 标记为全文检索同步阶段
        this.knowledgeTaskDomainService.changeTaskStatus(autoRecordTask,
            KnowledgeTaskRunTypeEnum.FULLTEXT_SYNC, userContext);
        // 2. 对应的知识库，标记为全文检索待同步，方便知识库全文检索重试，来进行重试;
        this.knowledgeConfigRepository.updateFulltextSyncStatus(model.getKbId(), FulltextSyncStatusEnum.UNSYNCED.getCode());


        try {
            // 2. 执行全文检索同步
            Long kbId = model.getKbId();
            Long tenantId = userContext.getTenantId();
            Long spaceId = model.getSpaceId();

            // 查询该文档的所有分段
            List<KnowledgeRawSegmentModel> rawSegments = knowledgeRawSegmentRepository.queryListByDocId(docId);

            if (!CollectionUtils.isEmpty(rawSegments)) {
                // 转换为全文检索模型
                List<com.xspaceagi.knowledge.domain.model.fulltext.RawSegmentFullTextModel> fullTextModels =
                    rawSegments.stream()
                        .map(raw -> com.xspaceagi.knowledge.domain.model.fulltext.RawSegmentFullTextModel.builder()
                            .rawId(raw.getId())
                            .docId(raw.getDocId())
                            .kbId(raw.getKbId())
                            .rawText(raw.getRawTxt())
                            .tenantId(tenantId)
                            .spaceId(spaceId)
                            .build())
                        .toList();

                // 批量推送到 Quickwit
                PushResult pushResult =
                    fullTextSearchDomainService.pushSegments(fullTextModels);

                // ✅ 根据 success_raw_ids 精确更新同步状态
                if (pushResult != null && !CollectionUtils.isEmpty(pushResult.getSuccessRawIds())) {
                    // 直接使用 Long 类型的 success_raw_ids，无需转换
                    List<Long> successRawIds = pushResult.getSuccessRawIds();

                    // 只更新成功推送的分段状态为已同步（status = 1）
                    knowledgeRawSegmentRepository.batchUpdateSyncStatus(successRawIds, RawTextFulltextSyncStatusEnum.SYNCED.getCode());

                    log.info("FTS sync status updated: docId={}, kbId={}, totalSegments={}, successCount={}",
                        docId, kbId, rawSegments.size(), successRawIds.size());
                } else {
                    log.warn("Push empty or no success: docId={}, kbId={}", docId, kbId);
                }

                log.info("Doc FTS sync done: docId={}, kbId={}, segmentCount={}, indexedCount={}",
                    docId, kbId, rawSegments.size(),
                    pushResult != null ? pushResult.getIndexedCount() : 0);
            } else {
                log.warn("No segments, skip FTS sync: docId={}", docId);
            }
        } catch (Exception e) {
            log.error("FTS sync failed: docId={}, kbId={}", docId, model.getKbId(), e);
            // 不抛出异常，继续后续流程（QA生成）
            // 可以通过重试任务机制重新同步
        }

        // 3. 改为QA状态
        this.knowledgeTaskDomainService.changeTaskStatus(autoRecordTask,
            KnowledgeTaskRunTypeEnum.QA, userContext);
        log.info("Task status set to QA: kbId={}, docId={}", model.getKbId(), docId);
    }

    // 注意：全文检索的同步和删除已迁移到 Application 层
    // 使用 KnowledgeFullTextSyncService 处理
    // - syncDocumentToQuickwit() - 同步文档到 Quickwit
    // - deleteDocumentFromQuickwit() - 删除文档的全文检索数据

}
