package com.xspaceagi.knowledge.domain.knowledgegraph.service.impl;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.agent.core.adapter.application.ModelApplicationService;
import com.xspaceagi.knowledge.domain.knowledgegraph.docparser.SurrealDbDomainService;
import com.xspaceagi.knowledge.domain.knowledgegraph.model.TripleDataModel;
import com.xspaceagi.knowledge.domain.knowledgegraph.service.IKnowledgeGraphDomainExtService;
import com.xspaceagi.knowledge.core.spec.utils.Constants;
import com.xspaceagi.knowledge.core.spec.utils.ThreadTenantUtil;
import com.xspaceagi.knowledge.domain.model.DocumentParseStatusModel;
import com.xspaceagi.knowledge.domain.model.DocumentParseStatusRecordModel;
import com.xspaceagi.knowledge.domain.model.KnowledgeDocumentModel;
import com.xspaceagi.knowledge.domain.model.KnowledgeRawSegmentModel;
import com.xspaceagi.knowledge.domain.repository.IDocumentParseStatusRecordRepository;
import com.xspaceagi.knowledge.domain.repository.IDocumentParseStatusRepository;
import com.xspaceagi.knowledge.domain.repository.IKnowledgeDocumentRepository;
import com.xspaceagi.knowledge.domain.repository.IKnowledgeRawSegmentRepository;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.KnowledgeException;
import com.xspaceagi.system.spec.tenant.thread.TenantRunnable;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 知识图谱领域服务实现
 *
 * @author system
 * @date 2026-04-16
 */
@Slf4j
@Service
public class KnowledgeGraphDomainExtService implements IKnowledgeGraphDomainExtService {

    @Resource
    private IKnowledgeDocumentRepository knowledgeDocumentRepository;

    @Resource
    private IKnowledgeRawSegmentRepository knowledgeRawSegmentRepository;

    @Resource
    private IDocumentParseStatusRepository documentParseStatusRepository;

    //@Resource
    private SurrealDbDomainService surrealDbService = new SurrealDbDomainService();

    @Resource
    private ModelApplicationService modelApplicationService;

    @Resource
    private ThreadTenantUtil threadTenantUtil;

    @Resource
    private IDocumentParseStatusRecordRepository documentParseStatusRecordRepository;


    /**
     * 三元组提取系统提示词（用于文档内容）
     */
    private static final String TRIPLE_EXTRACTION_SYS_PROMPT = """
        你是一个知识图谱构建专家。请从以下文本中提取实体关系三元组。

        要求：
        1. 识别文本中的主体(subject)、谓语(predicate)、客体(object)
        2. 谓语要简洁准确，使用常见关系词（如：属于、包含、导致、位于、具有、相关、依赖于等）
        3. 只提取文本中明确表达的关系，不要推断或臆测
        4. 每个三元组应该是一个有意义的知识单元
        5. 返回JSON数组格式，每个元素格式：{"subject":"主体","predicate":"谓语","object":"客体"}
        6. 如果文本中没有明确的关系，返回空数组 []
        7. 不要添加任何解释性文字，只返回JSON数组
        """;

    /**
     * 查询意图分析系统提示词（基于汉语语法规则精准抽取）
     */
    private static final String QUERY_INTENT_ANALYSIS_PROMPT = """
        你是一个专业的自然语言处理专家，专注于汉语句子的主谓宾三元组（S+P+O）精准抽取。

        === 任务目标 ===
        从输入文本中，依据汉语语法规则，识别并提取核心主语（S）、谓语（P）、宾语（O）三元组，输出结构化结果。

        === 拆解规则（语文公式） ===

        【1. 主语（S）识别规则】
        定义：句子中动作的发出者、主题或被描述的对象，是谓语的陈述对象。
        语法特征：通常位于谓语前，可由以下成分充当：
        - 名词（如"天气""高铁票"）
        - 代词（如"我""他"）
        - 名词性短语（如"明天的高铁票""北京的天气"）
        排除项：定语、状语等修饰成分（如"明天的""去北京的"中的修饰部分不作为主语核心）

        【2. 谓语（P）识别规则】
        定义：表示动作、状态或判断，是主语的行为或属性描述。
        语法特征：通常位于主语后，可由以下成分充当：
        - 动词（如"去""是""怎么样"）
        - 形容词（如"好""冷"）
        - 动词短语（如"多少钱""吃点好的"）
        核心判断：谓语是句子的核心动作/状态，连接主语和宾语。

        【3. 宾语（O）识别规则】
        定义：动作的承受者、对象或结果，是谓语作用的目标。
        语法特征：通常位于谓语后，可由以下成分充当：
        - 名词（如"北京""书"）
        - 代词（如"它""什么"）
        - 名词性短语（如"一杯咖啡""好的食物"）
        排除项：补语、状语等后续修饰成分

        【4. 疑问词处理规则】
        疑问词（什么、怎么、如何、为什么、哪些、谁、哪、几）不能作为subject或object。
        疑问词应转换为通用概念：
        - 什么 → 内容/信息
        - 怎么 → 方式/方法
        - 如何 → 方法
        - 为什么 → 原因
        - 哪些 → 列表/内容
        - 谁 → 人物/主体
        - 哪 → 地点/位置
        - 几 → 数量

        === 三元组构建规则 ===

        核心结构：提取"主语（S）+ 谓语（P）+ 宾语（O）"的最简核心组合，忽略修饰成分（定语、状语、补语等）。

        多元组处理：若句子包含多个主谓宾结构（如复合句），按逻辑层次或出现顺序提取，优先提取核心句的主谓宾。

        缺省处理：
        - 如果缺少subject：提取句子核心名词作为subject
        - 如果缺少predicate：使用"相关"
        - 如果缺少object：使用"信息"

        === 示例解析 ===

        示例1："云计算是一种技术"
        → subject=云计算, predicate=是, object=技术

        示例2："党的二十大说了什么"
        → subject=党的二十大, predicate=说, object=内容

        示例3："腾讯云有什么"
        → subject=腾讯云, predicate=有, object=服务

        示例4："什么是专精特新"
        → subject=专精特新, predicate=是, object=信息

        示例5："A导致B"
        → subject=A, predicate=导致, object=B

        示例6："专精特新企业的发展"
        → subject=专精特新企业, predicate=发展, object=情况

        === 输出格式 ===

        必须返回JSON数组，格式：[{"subject":"...","predicate":"...","object":"...","similarPredicates":["...","..."]}]
        - 至少返回1个三元组
        - 疑问词不能出现在subject或object中
        - 禁止返回空数组 []
        - 不要添加任何解释性文字，只返回JSON数组

        === 相似谓语生成规则 ===
        为每个提取的谓语（predicate）生成20个语义相同但字面不同的词语，用于扩展检索范围。
        要求：
        - 语义必须与原谓语高度相关，可用于同义替换
        - **字面差异要大**，避免字形相近的词（如"说"和"说出"）
        - 优先选择语义丰富、表达多样的词汇
        - 包括：同义词、近义词、不同表达方式、专业术语等
        - 保持词语简洁（1-4个字）
        - 例如：predicate="说"，similarPredicates=["指出","强调","阐述","宣称","论述","表达","提及","叙述","解释","表明","认为","提到","说明","讲述","介绍","谈及","声明","宣称","主张","答复"]
        - 例如：predicate="有"，similarPredicates=["包含","具有","拥有","包括","含有","具备","配备","配置","搭载","集成","内置","附带","涵盖","囊括","容纳","承载","设置","安排","部署","安装"]
        """;

    @Override
    public void generateKnowledgeGraphAsync(Long documentId, UserContext userContext) {
        // 提交到线程池异步执行
        threadTenantUtil.obtainRawExecutor().execute(new TenantRunnable(() -> {
            try {
                doGenerateKnowledgeGraph(documentId, userContext);
            } catch (Exception e) {
                log.error("异步生成知识图谱失败: documentId={}", documentId, e);
                // 更新状态为失败
                updateTripleStatus(documentId, null, 10, null, userContext);
            }
        }));
    }

    @Override
    public void generateKnowledgeGraph(Long documentId, UserContext userContext) throws Exception  {
        // 提交到线程池异步执行
        try {
            doGenerateKnowledgeGraph(documentId, userContext);
        } catch (Exception e) {
            log.error("异步生成知识图谱失败: documentId={}", documentId, e);
            // 更新状态为失败
            updateTripleStatus(documentId, null, 10, null, userContext);
            throw e;
        }
    }

    /**
     * 实际执行知识图谱生成
     */
    private void doGenerateKnowledgeGraph(Long documentId, UserContext userContext) throws Exception {
        log.info("【知识图谱生成】开始处理文档: documentId={}", documentId);

        // 1. 获取文档信息
        KnowledgeDocumentModel doc = knowledgeDocumentRepository.queryOneInfoById(documentId);
        if (doc == null) {
            log.error("【知识图谱生成】文档不存在: documentId={}", documentId);
            updateTripleStatus(documentId, null, 10, null, userContext);
            return;
        }

        Long kbId = doc.getKbId();

        try {
            // 2. 查询文档的所有分段
            List<KnowledgeRawSegmentModel> segments = knowledgeRawSegmentRepository.queryListByDocId(documentId);
            if (segments == null || segments.isEmpty()) {
                log.warn("【知识图谱生成】文档没有分段数据: documentId={}", documentId);
                updateTripleStatus(documentId, kbId, 10, null, userContext);
                return;
            }

            // 3. 提取三元组
            List<SurrealDbDomainService.Triple> allTriples = new ArrayList<>();
            int processedSegments = 0;

            for (KnowledgeRawSegmentModel segment : segments) {
                String rawText = segment.getRawTxt();
                if (StringUtils.isBlank(rawText) || rawText.length() < Constants.SEGMENT_MIN_WORDS) {
                    continue;
                }

                try {
                    List<TripleData> triples = extractTriples(rawText);
                    processedSegments++;

                    // 转换为 SurrealDbService.Triple
                    for (TripleData td : triples) {
                        SurrealDbDomainService.Triple triple = new SurrealDbDomainService.Triple();
                        triple.setSubject(td.getSubject());
                        triple.setPredicate(td.getPredicate());
                        triple.setObject(td.getObject());
                        triple.setKnowledgeId(kbId);
                        triple.setDocumentId(documentId);
                        triple.setDocumentName(doc.getName());
                        triple.setFileType(extractFileType(doc.getDocUrl()));
                        triple.setSegmentId(segment.getId());
                        triple.setSegmentContent(segment.getRawTxt());
                        allTriples.add(triple);
                    }

                    log.info("【知识图谱生成】分段 {}/{} 完成，提取 {} 个三元组",
                            processedSegments, segments.size(), triples.size());
                } catch (Exception e) {
                    // Single segment failed (typically a model call error): Fail Fast — log and
                    // re-throw so the outer catch marks the whole document status=10 for retry.
                    log.warn("[KnowledgeGraph] Segment processing failed: segmentId={}, error={}", segment.getId(), e.getMessage());
                    throw e;
                }
            }

            // 4. 保存到 SurrealDB
            int savedCount = 0;
            if (!allTriples.isEmpty()) {
                savedCount = surrealDbService.saveTriples(allTriples);
            }

            // 5. 根据保存结果更新状态
            if (allTriples.isEmpty() || savedCount > 0) {
                // 没有三元组或保存成功，更新为完成状态
                updateTripleStatus(documentId, kbId, 2, null, userContext);
                log.info("【知识图谱生成完成】documentId={}, 处理 {} 个分段，提取 {} 个三元组，保存 {} 个",
                        documentId, processedSegments, allTriples.size(), savedCount);
            } else {
                // 保存失败，更新为失败状态
                log.error("【知识图谱生成失败】SurrealDB保存失败: documentId={}, 提取 {} 个三元组，保存 {} 个",
                        documentId, allTriples.size(), savedCount);
                updateTripleStatus(documentId, kbId, 10, null, userContext);
            }

        } catch (Exception e) {
            log.error("【知识图谱生成】处理失败: documentId={}", documentId, e);
            updateTripleStatus(documentId, kbId, 10, null, userContext);
            throw e;
        }
    }

    /**
     * 从文本中提取三元组（公开方法，用于文档内容）
     */
    @Override
    public List<TripleData> extractTriplesFromText(String text) {
        return extractTriples(text);
    }

    /**
     * 从用户查询问题中提取检索用三元组（智能查询意图分析）
     */
    @Override
    public List<TripleData> extractTriplesFromQuery(String query) {
        try {
            /*
            String result = modelApplicationService.call(
                    QUERY_INTENT_ANALYSIS_PROMPT,
                    query,
                    new ParameterizedTypeReference<String>() {}
            );*/

            TripleDataModel obj = modelApplicationService.call(
                    QUERY_INTENT_ANALYSIS_PROMPT, query, new ParameterizedTypeReference<TripleDataModel>() {
                    });
            /*
            List<TripleData> tripleData = new ArrayList<>();
            for(TripleDataModel entry : obj) {
                String[] array = entry.getSimilarPredicates().split(",");
                List<String> similarPredicates = new ArrayList<>();
                for(String str : array) {
                    similarPredicates.add(str);
                }
                TripleData newData = new TripleData();
                newData.setObject(entry.getObject());
                newData.setPredicate(entry.getPredicate());
                newData.setSubject(entry.getSubject());
                newData.setSimilarPredicates(similarPredicates);
                tripleData.add(newData);
            }*/
            List<TripleData> tripleData = obj.getTripleData();

            String result = JSON.toJSONString(tripleData);

            if (StringUtils.isBlank(result)) {
                log.warn("【查询意图分析】AI返回空结果，使用备用策略");
                return extractTriplesWithFallback(query);
            }

            List<TripleData> triples = parseTriplesFromJson(extractAndCleanJson(result));

            if (triples.isEmpty()) {
                log.warn("【查询意图分析】未提取到三元组，使用备用策略");
                return extractTriplesWithFallback(query);
            }

            log.info("【查询意图分析】成功提取 {} 个三元组 from query: {}", triples.size(), query);
            return triples;
        } catch (Exception e) {
            log.error("【查询意图分析】分析失败: {}", e.getMessage(), e);
            return extractTriplesWithFallback(query);
        }
    }

    /**
     * 备用策略：当AI提取失败时使用
     * 从查询中提取关键词作为三元组
     */
    private List<TripleData> extractTriplesWithFallback(String query) {
        List<TripleData> triples = new ArrayList<>();
        if (StringUtils.isBlank(query)) {
            return triples;
        }

        try {
            // 清理查询，移除常见疑问词
            String cleanedQuery = query
                    .replaceAll("什么是|有什么|怎么样|如何|哪些|为什么|关于|的|是|吗|呢|吧|啊", " ")
                    .trim()
                    .replaceAll("\\s+", " ");

            if (StringUtils.isNotBlank(cleanedQuery)) {
                TripleData triple = new TripleData();
                triple.setSubject(cleanedQuery);
                triple.setPredicate("相关");
                triple.setObject("信息");
                triples.add(triple);

                // 保留原始查询作为第二个三元组
                TripleData originalTriple = new TripleData();
                originalTriple.setSubject(query);
                originalTriple.setPredicate("查询");
                originalTriple.setObject("意图");
                triples.add(originalTriple);

                log.info("【备用策略】使用备用提取策略: {}", triples);
            }
        } catch (Exception e) {
            log.error("【备用策略】备用提取失败: {}", e.getMessage());
        }

        return triples;
    }

    /**
     * 从分段文本中提取三元组
     */
    private List<TripleData> extractTriples(String segmentText) {
        try {
            /*
            String result = modelApplicationService.call(
                    TRIPLE_EXTRACTION_SYS_PROMPT,
                    segmentText,
                    new ParameterizedTypeReference<String>() {}
            );*/

            TripleDataModel obj = modelApplicationService.call(
                    TRIPLE_EXTRACTION_SYS_PROMPT, segmentText, new ParameterizedTypeReference<TripleDataModel>() {
                    });
            if (obj == null) {
                // 大模型正常返回但无内容(未提取到数据),算成功,返回空列表
                return new ArrayList<>();
            }
            /*
            List<TripleData> tripleData = new ArrayList<>();
            for(TripleDataModel entry : obj) {
                String[] array = entry.getSimilarPredicates().split(",");
                List<String> similarPredicates = new ArrayList<>();
                for(String str : array) {
                    similarPredicates.add(str);
                }
                TripleData newData = new TripleData();
                newData.setObject(entry.getObject());
                newData.setPredicate(entry.getPredicate());
                newData.setSubject(entry.getSubject());
                newData.setSimilarPredicates(similarPredicates);
                tripleData.add(newData);
            }*/
            List<TripleData> tripleData = obj.getTripleData();

            String result = JSON.toJSONString(tripleData);

            if (StringUtils.isBlank(result)) {
                return new ArrayList<>();
            }

            return parseTriplesFromJson(extractAndCleanJson(result));
        } catch (Exception e) {
            // Model call error (model unavailable/timeout) must propagate so the caller marks the
            // whole document failed for retry; swallowing it into an empty list would disguise a
            // model error as "no triples extracted" and falsely report success.
            // Note: a normal model response that genuinely yields no triples returns the empty
            // list above and is NOT treated as a failure.
            log.error("[KnowledgeGraph] AI triple extraction failed: {}", e.getMessage(), e);
            throw new KnowledgeException(
                    BizExceptionCodeEnum.knowledgeTripleExtractFailed.getCode(),
                    BizExceptionCodeEnum.knowledgeTripleExtractFailed.formatMessage(e.getMessage()),
                    e);
        }
    }

    /**
     * 清理并提取JSON
     */
    private String extractAndCleanJson(String rawResult) {
        if (rawResult == null || rawResult.trim().isEmpty()) {
            return "[]";
        }

        int startIndex = rawResult.indexOf('[');
        int endIndex = rawResult.lastIndexOf(']');

        if (startIndex == -1 || endIndex == -1) {
            return "[]";
        }

        String jsonContent = rawResult.substring(startIndex, endIndex + 1);
        jsonContent = jsonContent.trim();
        jsonContent = jsonContent.replaceAll("```json", "").replaceAll("```", "");
        jsonContent = jsonContent.replaceAll("\\s+", " ").trim();
        jsonContent = jsonContent.replaceAll("\\]\\s*\\[", ",[");

        return jsonContent;
    }

    /**
     * 从JSON字符串解析三元组列表
     */
    private List<TripleData> parseTriplesFromJson(String jsonResponse) {
        List<TripleData> triples = new ArrayList<>();

        try {
            com.alibaba.fastjson2.JSONArray jsonArray = com.alibaba.fastjson2.JSONArray.parseArray(jsonResponse);
            if (jsonArray == null || jsonArray.isEmpty()) {
                return triples;
            }

            for (int i = 0; i < jsonArray.size(); i++) {
                try {
                    com.alibaba.fastjson2.JSONObject obj = jsonArray.getJSONObject(i);
                    String subject = obj.getString("subject");
                    String predicate = obj.getString("predicate");
                    String object = obj.getString("object");
                    List<String> similarPredicates = obj.getList("similarPredicates", String.class);

                    if (StringUtils.isNotBlank(subject) && StringUtils.isNotBlank(predicate) && StringUtils.isNotBlank(object)) {
                        TripleData triple = new TripleData();
                        triple.setSubject(subject.trim());
                        triple.setPredicate(predicate.trim());
                        triple.setObject(object.trim());
                        triple.setSimilarPredicates(similarPredicates);
                        triples.add(triple);
                    }
                } catch (Exception e) {
                    log.warn("解析单个三元组失败: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("解析三元组JSON失败: {}", e.getMessage(), e);
        }

        return triples;
    }

    /**
     * 从URL中提取文件类型
     */
    private String extractFileType(String docUrl) {
        if (StringUtils.isBlank(docUrl)) {
            return null;
        }
        String lower = docUrl.toLowerCase();
        int queryIndex = lower.indexOf('?');
        if (queryIndex > 0) {
            lower = lower.substring(0, queryIndex);
        }
        int dotIndex = lower.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < lower.length() - 1) {
            return lower.substring(dotIndex + 1);
        }
        return null;
    }

    /**
     * 更新文档的三元组解析状态
     */
    private void updateTripleStatus(Long documentId, Long kbId, Integer tripleStatus, Long taskId, UserContext userContext) {
        try {
            DocumentParseStatusModel existingStatus = documentParseStatusRepository.queryByDocumentId(documentId);

            DocumentParseStatusModel model = new DocumentParseStatusModel();
            model.setDocumentId(documentId);
            model.setKbId(kbId);
            model.setTripleStatus(tripleStatus);
            model.setTaskId(taskId);

            if (existingStatus == null) {
                documentParseStatusRepository.addInfo(model, userContext);
            } else {
                model.setId(existingStatus.getId());
                documentParseStatusRepository.updateInfo(model, userContext);
            }
        } catch (Exception e) {
            log.error("更新三元组解析状态失败: docId={}, kbId={}, status={}", documentId, kbId, tripleStatus, e);
        }
    }

    public Long syncTaskGraphTriple() throws Exception {

        UserContext userContext = UserContext.builder()
                .userId(0L)
                .userName("知识图谱生成")
                .build();

        LocalDateTime currentTime = LocalDateTime.now();

        // 创建执行记录
        DocumentParseStatusRecordModel recordModel = new DocumentParseStatusRecordModel();
        recordModel.setCreateTime(currentTime);
        recordModel.setExecuteState("0");
        recordModel.setStartTime(currentTime);
        recordModel.setSuccessCount(0L);
        recordModel.setErrorCount(0L);

        Long recordId = documentParseStatusRecordRepository.addInfo(recordModel, userContext);
        recordModel.setId(recordId.intValue());

        try {
            // 查询所有triple_status为1的数据
            List<DocumentParseStatusModel> statusList = documentParseStatusRepository.queryListByTripleStatus(1);


            if (statusList == null || statusList.isEmpty()) {
                log.info("没有需要同步的三元组数据");
                updateRecordSuccess(recordModel, 0, 0, "无需要同步的数据", null);
                return null;
            }

            int successCount = 0;
            int errorCount = 0;
            StringBuilder executeContent = new StringBuilder();

            for (DocumentParseStatusModel status : statusList) {
                Long documentId = status.getDocumentId();

                try {
                    // 1. 删除 SurrealDB 中该文档的所有三元组
                    boolean surrealDbSuccess = surrealDbService.deleteTriplesByDocumentId(documentId);
                    log.info("删除 SurrealDB 三元组: documentId={}, success={}", documentId, surrealDbSuccess);
                } catch (Exception e) {

                }

                // 调用异步生成知识图谱方法
                generateKnowledgeGraph(documentId, userContext);
                successCount++;
                executeContent.append("文档ID: ").append(documentId).append(" - 成功; ");
                log.info("文档ID: {} 三元组同步成功", documentId);
            }

            // 更新记录为成功状态
            updateRecordSuccess(recordModel, successCount, errorCount, executeContent.toString(), null);

            return statusList.get(0).getTaskId();

        } catch (Exception e) {
            log.error("同步三元组任务执行异常", e);
            // 更新记录为失败状态
            updateRecordError(recordModel, e.getMessage());
            throw e;
        }

        //log.info("Syncing task graph triple end");
    }

    private void updateRecordSuccess(DocumentParseStatusRecordModel recordModel, int successCount, int errorCount, String executeContent, String executeErrorLogs) {
        LocalDateTime endTime = LocalDateTime.now();

        recordModel.setExecuteState("1");
        recordModel.setSuccessCount((long) successCount);
        recordModel.setErrorCount((long) errorCount);
        recordModel.setEndTime(endTime);
        recordModel.setExecuteContent(executeContent);
        if (executeErrorLogs != null) {
            recordModel.setExecuteErrorLogs(executeErrorLogs);
        }

        documentParseStatusRecordRepository.updateInfo(recordModel, null);
    }

    private void updateRecordError(DocumentParseStatusRecordModel recordModel, String errorMessage) {
        LocalDateTime endTime = LocalDateTime.now();

        recordModel.setExecuteState("2");
        recordModel.setEndTime(endTime);
        recordModel.setExecuteErrorLogs(errorMessage);

        documentParseStatusRecordRepository.updateInfo(recordModel, null);
    }
}
