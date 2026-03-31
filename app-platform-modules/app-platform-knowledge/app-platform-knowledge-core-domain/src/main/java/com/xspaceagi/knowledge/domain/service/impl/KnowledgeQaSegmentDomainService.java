package com.xspaceagi.knowledge.domain.service.impl;

import cn.idev.excel.FastExcelFactory;
import cn.idev.excel.read.listener.PageReadListener;
import com.xspaceagi.knowledge.core.spec.utils.Commons;
import com.xspaceagi.knowledge.core.spec.utils.ThreadTenantUtil;
import com.xspaceagi.knowledge.domain.dto.EmbeddingStatusDto;
import com.xspaceagi.knowledge.domain.dto.qa.QAEmbeddingDto;
import com.xspaceagi.knowledge.domain.dto.qa.QAQueryDto;
import com.xspaceagi.knowledge.domain.dto.qa.QAResDto;
import com.xspaceagi.knowledge.domain.model.KnowledgeConfigModel;
import com.xspaceagi.knowledge.domain.model.KnowledgeQaSegmentModel;
import com.xspaceagi.knowledge.domain.model.excel.KnowledgeQaExcelModel;
import com.xspaceagi.knowledge.domain.repository.IKnowledgeConfigRepository;
import com.xspaceagi.knowledge.domain.repository.IKnowledgeQaSegmentRepository;
import com.xspaceagi.knowledge.domain.repository.IKnowledgeRawSegmentRepository;
import com.xspaceagi.knowledge.domain.service.IKnowledgeQaSegmentDomainService;
import com.xspaceagi.knowledge.domain.vectordb.VectorDBService;
import com.xspaceagi.knowledge.sdk.enums.KnowledgePubStatusEnum;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.KnowledgeException;
import com.xspaceagi.system.spec.tenant.thread.TenantRunnable;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
public class KnowledgeQaSegmentDomainService implements IKnowledgeQaSegmentDomainService {

    @Resource
    private IKnowledgeQaSegmentRepository knowledgeQaSegmentRepository;

    @Resource
    private IKnowledgeConfigRepository knowledgeConfigRepository;

    @Resource
    private IKnowledgeRawSegmentRepository knowledgeRawSegmentRepository;

    @Resource
    private ThreadTenantUtil threadTenantUtil;

    @Resource
    private VectorDBService vectorDBService;

    @Resource
    private KnowledgeRawSegmentDomainService knowledgeRawSegmentDomainService;

    /**
     * 当前对象
     */
    @Lazy
    @Resource
    private IKnowledgeQaSegmentDomainService self;

    @Override
    public KnowledgeQaSegmentModel queryOneInfoById(Long id) {

        return knowledgeQaSegmentRepository.queryOneInfoById(id);
    }

    @Override
    public void deleteById(Long id) {

        var existModel = this.knowledgeQaSegmentRepository.queryOneInfoById(id);
        if (Objects.isNull(existModel)) {
            throw KnowledgeException.build(BizExceptionCodeEnum.KNOWLEDGE_ERROR_5001);
        }

        var kbId = existModel.getKbId();

        // 删除向量数据库的数据
        vectorDBService.removeEmbeddingQA(id, kbId);
        // 删除问答
        knowledgeQaSegmentRepository.deleteById(id);

    }

    @Override
    public Long updateInfo(KnowledgeQaSegmentModel model, UserContext userContext) {
        model.setHasEmbedding(Boolean.TRUE);
        var id = knowledgeQaSegmentRepository.updateInfo(model, userContext);

        // 更新向量数据库的数据

        var existModel = this.knowledgeQaSegmentRepository.queryOneInfoById(model.getId());

        var kbId = existModel.getKbId();
        var knowledgeConfig = this.knowledgeConfigRepository.queryOneInfoById(kbId);
        if (Objects.isNull(knowledgeConfig)) {
            throw KnowledgeException.build(BizExceptionCodeEnum.KNOWLEDGE_ERROR_5001);
        }
        var embeddingModelId = knowledgeConfig.getEmbeddingModelId();

        var rawId = existModel.getRawId();

        String rawText = "";
        if (Objects.nonNull(rawId)) {
            // 查询对应的归属分段文本内容
            var rawModel = this.knowledgeRawSegmentRepository.queryOneInfoById(rawId);
            if (Objects.nonNull(rawModel)) {
                rawText = rawModel.getRawTxt();
            }
        }

        var qAEmbeddingDto = QAEmbeddingDto.convertFromModelAndEmbedding(existModel, rawText,embeddingModelId);
        // 更新向量数据库的数据
        vectorDBService.addEmbeddingQa(qAEmbeddingDto, true);

        // 更新问答的embedding状态
        this.knowledgeQaSegmentRepository.batchChangeEmbeddingStatus(Collections.singletonList(id), true, userContext);

        return id;
    }

    @Override
    public Long addInfo(KnowledgeQaSegmentModel model, UserContext userContext) {
        var id = knowledgeQaSegmentRepository.addInfo(model, userContext);
        var existModel = this.knowledgeQaSegmentRepository.queryOneInfoById(id);

        var kbId = existModel.getKbId();
        var knowledgeConfig = this.knowledgeConfigRepository.queryOneInfoById(kbId);
        if (Objects.isNull(knowledgeConfig)) {
            throw KnowledgeException.build(BizExceptionCodeEnum.KNOWLEDGE_ERROR_5001);
        }
        var embeddingModelId = knowledgeConfig.getEmbeddingModelId();

        var rawId = existModel.getRawId();
        String rawText = "";
        if (Objects.nonNull(rawId)) {
            // 查询对应的归属分段文本内容
            var rawModel = this.knowledgeRawSegmentRepository.queryOneInfoById(rawId);
            if (Objects.nonNull(rawModel)) {
                rawText = rawModel.getRawTxt();
            }
        }

        // 更新向量数据库的数据
        var qAEmbeddingDto = QAEmbeddingDto.convertFromModelAndEmbedding(model, rawText,embeddingModelId);
        // 更新向量数据库的数据
        vectorDBService.addEmbeddingQa(qAEmbeddingDto, false);
        // 更新问答的embedding状态
        this.knowledgeQaSegmentRepository.batchChangeEmbeddingStatus(Collections.singletonList(id), true, userContext);

        return id;
    }

    @Override
    public List<QAResDto> search(QAQueryDto qaQueryDto, boolean ignoreKBStatus) {

        var kbId = qaQueryDto.getKbId();
        var knowledgeConfig = this.knowledgeConfigRepository.queryOneInfoById(kbId);
        if (Objects.isNull(knowledgeConfig)) {
            return Collections.emptyList();
        }

        if (!ignoreKBStatus && (knowledgeConfig.getPubStatus() == KnowledgePubStatusEnum.Waiting)) {
            return Collections.emptyList();
        }
        var embeddingModelId = knowledgeConfig.getEmbeddingModelId();

        var qaQueryEmbeddingDto = QAQueryDto.convertTEmbeddingDto(qaQueryDto,embeddingModelId);

        // 检查向量数据库是否存在
        String collectionName = Commons.collectionName(kbId);

        var existFlag = vectorDBService.checkCollectionExists(collectionName);
        if (!existFlag) {
            log.error("知识库[{}]向量数据库不存在", kbId);
            throw KnowledgeException.build(BizExceptionCodeEnum.KNOWLEDGE_ERROR_5011, kbId);
        }

        return vectorDBService.searchEmbedding(qaQueryEmbeddingDto);
    }

    @Override
    public List<QAResDto> search(List<QAQueryDto> qaQueryDtoList) {
        return qaQueryDtoList.stream() // 使用普通流而不是并行流
                .flatMap(qaQueryDto -> search(qaQueryDto, false).stream())
                .toList();
    }

    @Override
    public EmbeddingStatusDto queryEmbeddingStatus(Long docId) {
        return this.knowledgeQaSegmentRepository.queryEmbeddingStatus(docId);
    }

    @Override
    public List<KnowledgeQaSegmentModel> queryListForEmbeddingQaAndEmbeddings(Integer days, Integer pageSize,
                                                                              Integer pageNum) {
        return this.knowledgeQaSegmentRepository.queryListForEmbeddingQaAndEmbeddings(days, pageSize, pageNum);
    }

    @Override
    public List<KnowledgeQaSegmentModel> queryListForEmbeddingQaAndEmbeddingsAndRawIdIsNull(Integer days,
                                                                                            Integer pageSize, Integer pageNum) {
        return this.knowledgeQaSegmentRepository.queryListForEmbeddingQaAndEmbeddingsAndRawIdIsNull(days, pageSize,
                pageNum);
    }

    @Override
    public List<KnowledgeQaSegmentModel> queryListInfoByRawId(Long rawId) {
        return this.knowledgeQaSegmentRepository.queryListInfoByRawId(rawId);
    }

    @Override
    public void deleteByRawId(Long rawId) {
        var qaIds = this.knowledgeQaSegmentRepository.queryQaIdList(rawId);
        // 删除向量数据库中的问答
        var docId = this.knowledgeRawSegmentRepository.queryOneInfoById(rawId).getDocId();
        // 修改编辑的问题，交换顺序
        //KnowledgeQaSegmentModel knowledgeQaSegmentModel = this.knowledgeQaSegmentRepository.queryOneInfoById(rawId);
        //knowledgeQaSegmentModel.getQuestion()
        //Integer qaStatus = knowledgeQaSegmentModel.getQaStatus();
        /*
        var existObj = this.knowledgeRawSegmentDomainService.queryOneInfoById(rawId);
        //if(qaIds == null || qaIds.size() == 0 ) {
        if(existObj.getQaStatus() != null && existObj.getQaStatus() != 1) {
            throw new BizException("操作失败：当前数据正在生成问答，请稍后重试！");
        }*/
        if(qaIds != null && qaIds.size() > 0) {
            this.vectorDBService.removeEmbeddingQaIds(docId, qaIds);
            this.knowledgeQaSegmentRepository.deleteByRawId(rawId);
        }
    }


    @Override
    public void addInfoBatch(List<KnowledgeQaSegmentModel> models, UserContext userContext) {
        var qaIds = this.knowledgeQaSegmentRepository.batchAddInfo(models, userContext);

        // 根据主键id,重新查询
        var qaModelList = this.knowledgeQaSegmentRepository.queryListByIds(qaIds);

        // 批量对新增的问答,进行向量化
        var runnable = new TenantRunnable(() -> {
            this.batchAddEmbeddingQa(qaModelList, userContext);
        });

        threadTenantUtil.obtainOtherScheduledExecutor().execute(runnable);

    }

    @Override
    public void importQaFromCsv(Long kbId, MultipartFile file, UserContext userContext) {
        // 校验知识库是否存在
        var knowledgeConfig = this.knowledgeConfigRepository.queryOneInfoById(kbId);
        if (Objects.isNull(knowledgeConfig)) {
            throw KnowledgeException.build(BizExceptionCodeEnum.KNOWLEDGE_ERROR_5001);
        }
        var spaceId = knowledgeConfig.getSpaceId();
        List<KnowledgeQaSegmentModel> qaSegmentList = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
             CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT.builder()
                     .setHeader("问题", "答案")
                     .setSkipHeaderRecord(true)
                     .build())) {

            for (CSVRecord record : csvParser) {
                if (record.size() < 2) {
                    continue; // 跳过格式不正确的行
                }

                String question = record.get(0);
                String answer = record.get(1);

                // 验证问题和答案不为空
                if (StringUtils.isBlank(question) || StringUtils.isBlank(answer)) {
                    continue;
                }

                KnowledgeQaSegmentModel qaSegment = new KnowledgeQaSegmentModel();
                qaSegment.setKbId(kbId);
                qaSegment.setSpaceId(spaceId);
                qaSegment.setQuestion(question.trim());
                qaSegment.setAnswer(answer.trim());
                qaSegment.setDocId(0L); // 手动添加的问答，默认docId为0

                qaSegmentList.add(qaSegment);
            }

            // 批量添加问答
            var qaIds = this.knowledgeQaSegmentRepository.batchAddInfo(qaSegmentList, userContext);

            // 根据主键id,重新查询
            var qaModelList = this.knowledgeQaSegmentRepository.queryListByIds(qaIds);

            // 批量对新增的问答,进行向量化
            var runnable = new TenantRunnable(() -> {
                this.batchAddEmbeddingQa(qaModelList, userContext);
            });

            threadTenantUtil.obtainOtherScheduledExecutor().execute(runnable);

        } catch (IOException e) {
            log.error("导入CSV文件失败", e);
            throw KnowledgeException.build(BizExceptionCodeEnum.KNOWLEDGE_ERROR_5019, e.getMessage());
        }
    }

    @Override
    public void importQaFromExcel(Long kbId, MultipartFile file, UserContext userContext) {
        // 校验知识库是否存在
        var knowledgeConfig = this.knowledgeConfigRepository.queryOneInfoById(kbId);
        if (Objects.isNull(knowledgeConfig)) {
            throw KnowledgeException.build(BizExceptionCodeEnum.KNOWLEDGE_ERROR_5001);
        }
        var spaceId = knowledgeConfig.getSpaceId();

        try {
            // 使用PageReadListener处理Excel数据，边读取边校验边处理
            List<KnowledgeQaSegmentModel> qaSegmentList = new ArrayList<>();
            // 用于跟踪当前处理的行号，初始值为1，因为第一行是标题行
            final AtomicInteger currentRowNumber = new AtomicInteger(1); // 使用AtomicInteger来确保线程安全和准确计数

            FastExcelFactory.read(file.getInputStream(), KnowledgeQaExcelModel.class,
                            new PageReadListener<KnowledgeQaExcelModel>(dataList -> {
                                for (KnowledgeQaExcelModel excelModel : dataList) {
                                    // 增加行号计数
                                    int rowNumber = currentRowNumber.incrementAndGet();

                                    // 验证问题和答案不为空
                                    String question = excelModel.getQuestion();
                                    String answer = excelModel.getAnswer();

                                    // 检查是否为空行
                                    if (StringUtils.isBlank(question) && StringUtils.isBlank(answer)) {
                                        continue;
                                    }

                                    // 验证问题和答案的长度
                                    if (StringUtils.isNotBlank(question) && question.trim().length() > 500) {
                                        throw KnowledgeException.build(BizExceptionCodeEnum.KNOWLEDGE_ERROR_5019,
                                                String.format("第%d行数据校验失败：问题长度不能超过500字符，当前长度：%d",
                                                        rowNumber, question.trim().length()));
                                    }

                                    if (StringUtils.isNotBlank(answer) && answer.trim().length() > 5000) {
                                        throw KnowledgeException.build(BizExceptionCodeEnum.KNOWLEDGE_ERROR_5019,
                                                String.format("第%d行数据校验失败：答案长度不能超过5000字符，当前长度：%d",
                                                        rowNumber, answer.trim().length()));
                                    }

                                    // 如果问题或答案为空，则抛出异常
                                    if (StringUtils.isBlank(question)) {
                                        throw KnowledgeException.build(BizExceptionCodeEnum.KNOWLEDGE_ERROR_5019,
                                                String.format("第%d行数据校验失败：问题不能为空", rowNumber));
                                    }

                                    if (StringUtils.isBlank(answer)) {
                                        throw KnowledgeException.build(BizExceptionCodeEnum.KNOWLEDGE_ERROR_5019,
                                                String.format("第%d行数据校验失败：答案不能为空", rowNumber));
                                    }

                                    // 创建问答模型
                                    KnowledgeQaSegmentModel qaSegment = new KnowledgeQaSegmentModel();
                                    qaSegment.setKbId(kbId);
                                    qaSegment.setSpaceId(spaceId);
                                    qaSegment.setQuestion(question.trim());
                                    qaSegment.setAnswer(answer.trim());
                                    qaSegment.setDocId(0L); // 手动添加的问答，默认docId为0

                                    qaSegmentList.add(qaSegment);
                                }
                            }, 100))
                    .headRowNumber(1)
                    .sheet()
                    .doRead();

            // 如果所有数据都校验通过，再进行批量插入
            if (!qaSegmentList.isEmpty()) {
                // 批量添加问答
                var qaIds = knowledgeQaSegmentRepository.batchAddInfo(qaSegmentList, userContext);

                // 根据主键id,重新查询
                var qaModelList = knowledgeQaSegmentRepository.queryListByIds(qaIds);

                // 批量对新增的问答,进行向量化
                var runnable = new TenantRunnable(() -> {
                    batchAddEmbeddingQa(qaModelList, userContext);
                });

                threadTenantUtil.obtainOtherScheduledExecutor().execute(runnable);
            }

        } catch (KnowledgeException e) {
            throw e;
        } catch (Exception e) {
            log.error("导入Excel文件失败", e);

            throw KnowledgeException.build(BizExceptionCodeEnum.KNOWLEDGE_ERROR_5019, "导入Excel文件失败：" + e.getMessage());
        }
    }

    /**
     * 批量对新增的问答,进行向量化
     *
     * @param models 新增的问答列表
     */
    @Override
    public void batchAddEmbeddingQa(List<KnowledgeQaSegmentModel> models, UserContext userContext) {


        var kbIds = models.stream().map(KnowledgeQaSegmentModel::getKbId).distinct().toList();
        var knowledgeConfigList = this.knowledgeConfigRepository.queryListByIds(kbIds);
        var kbId2EmbeddingModelIdMap = knowledgeConfigList.stream()
                .filter(item -> Objects.nonNull(item.getEmbeddingModelId()))
                .collect(Collectors.toMap(KnowledgeConfigModel::getId, KnowledgeConfigModel::getEmbeddingModelId, (a, b) -> a));



        try {
            for (KnowledgeQaSegmentModel model : models) {

                var kbId = model.getKbId();
                var embeddingModelId = kbId2EmbeddingModelIdMap.get(kbId);

                // 更新向量数据库的数据, rawText为空
                var qAEmbeddingDto = QAEmbeddingDto.convertFromModelAndEmbedding(model, "",embeddingModelId);

                // 更新向量数据库的数据
                vectorDBService.addEmbeddingQa(qAEmbeddingDto, false);
                // 更新问答的embedding状态
                var qaId = model.getId();
                var qaIds = Collections.singletonList(qaId);
                this.knowledgeQaSegmentRepository.batchChangeEmbeddingStatus(qaIds, true, userContext);
            }
        } catch (Exception e) {
            log.error("批量对新增的问答,进行向量化失败", e);
            throw KnowledgeException.build(BizExceptionCodeEnum.KNOWLEDGE_ERROR_5022, e.getMessage());
        }

    }

}
