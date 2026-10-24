package com.xspaceagi.knowledge.core.infra.knowledgegraph.rpc;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.knowledge.core.infra.knowledgegraph.service.GraphTripleSimilarityService;
import com.xspaceagi.knowledge.core.infra.knowledgegraph.docparser.SurrealDbService;
import com.xspaceagi.knowledge.domain.repository.IDocumentParseStatusRepository;
import com.xspaceagi.knowledge.core.infra.knowledgegraph.service.IKnowledgeGraphDomainService;
import com.xspaceagi.knowledge.sdk.request.KnowledgeTripleExtractRequestVo;
import com.xspaceagi.knowledge.sdk.request.KnowledgeGraphSearchRequestVo;
import com.xspaceagi.knowledge.sdk.response.KnowledgeTripleExtractResponseVo;
import com.xspaceagi.knowledge.sdk.response.KnowledgeGraphSearchResponseVo;
import com.xspaceagi.knowledge.sdk.sevice.IKnowledgeTripleRpcService;
import com.xspaceagi.system.domain.log.LogRecordPrint;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 三元组提取RPC服务实现
 *
 * @author system
 * @date 2026-04-17
 */
@Slf4j
@Service
public class KnowledgeTripleRpcService implements IKnowledgeTripleRpcService {

    @Resource
    private IKnowledgeGraphDomainService knowledgeGraphDomainService;

    @Resource
    private SurrealDbService surrealDbService;

    @Resource
    private IDocumentParseStatusRepository documentParseStatusRepository;

    @Resource
    private GraphTripleSimilarityService similarityService;

    @LogRecordPrint(content = "[知识图谱]-三元组提取")
    @Override
    public KnowledgeTripleExtractResponseVo extractTriples(KnowledgeTripleExtractRequestVo requestVo) {
        log.info("【三元组RPC】开始提取三元组, text length: {}", requestVo.getText() != null ? requestVo.getText().length() : 0);

        try {
            // 调用领域服务提取三元组（使用查询意图分析）

            List<Long> kbIdsWithGraph = TenantFunctions.callWithIgnoreCheck(() ->
                    documentParseStatusRepository.findKbIdsWithTripleStatus(
                            requestVo.getKnowledgeBaseIds(), 2));  // 2 = 成功状态

            if (!CollectionUtils.isEmpty(kbIdsWithGraph)) {
                var triples = TenantFunctions.callWithIgnoreCheck(() ->
                        knowledgeGraphDomainService.extractTriplesFromQuery(requestVo.getText()));

                // 转换为响应VO
                var tripleVoList = triples.stream()
                        .map(t -> KnowledgeTripleExtractResponseVo.TripleVo.builder()
                                .subject(t.getSubject())
                                .predicate(t.getPredicate())
                                .object(t.getObject())
                                .similarPredicates(t.getSimilarPredicates())
                                .build())
                        .toList();

                log.info("【三元组RPC】提取完成, 共 {} 个三元组", tripleVoList.size());

                return KnowledgeTripleExtractResponseVo.builder()
                        .triples(tripleVoList)
                        .build();
            }

            return KnowledgeTripleExtractResponseVo.builder()
                    .triples(Collections.emptyList())
                    .build();

        } catch (Exception e) {
            log.error("【三元组RPC】提取失败: {}", e.getMessage(), e);
            return KnowledgeTripleExtractResponseVo.builder()
                    .triples(Collections.emptyList())
                    .build();
        }
    }

    @LogRecordPrint(content = "[知识图谱]-图检索")
    @Override
    public KnowledgeGraphSearchResponseVo searchGraph(KnowledgeGraphSearchRequestVo requestVo) {
        log.info("【图谱搜索RPC】开始搜索, knowledgeBaseIds: {}, subjectKeywords: {}, queryPredicate: {}",
                requestVo.getKnowledgeBaseIds(), requestVo.getSubjectKeywords(), requestVo.getQueryPredicate());

        try {
            // 参数校验
            if (CollectionUtils.isEmpty(requestVo.getKnowledgeBaseIds()) ||
                CollectionUtils.isEmpty(requestVo.getSubjectKeywords())) {
                return KnowledgeGraphSearchResponseVo.builder()
                        .triples(Collections.emptyList())
                        .build();
            }

            // -----------------------------------------------------------------------
            // 前置判断：检查知识库列表中是否有已生成的图谱数据
            // -----------------------------------------------------------------------
            List<Long> kbIdsWithGraph = TenantFunctions.callWithIgnoreCheck(() ->
                    documentParseStatusRepository.findKbIdsWithTripleStatus(
                            requestVo.getKnowledgeBaseIds(), 2));  // 2 = 成功状态

            if (CollectionUtils.isEmpty(kbIdsWithGraph)) {
                log.info("【图谱搜索RPC】知识库列表 {} 均无图谱数据，跳过图检索", requestVo.getKnowledgeBaseIds());
                return KnowledgeGraphSearchResponseVo.builder()
                        .triples(Collections.emptyList())
                        .build();
            }

            log.info("【图谱搜索RPC】知识库列表中有 {} 个知识库存在图谱数据: {}",
                    kbIdsWithGraph.size(), kbIdsWithGraph);

            // -----------------------------------------------------------------------
            // 第一阶段：图数据库查询（只按主体查询，不限制谓语）
            // -----------------------------------------------------------------------
            String jsonResult = TenantFunctions.callWithIgnoreCheck(() ->
                    surrealDbService.queryTriplesBySubjectKeywords(
                            kbIdsWithGraph,
                            requestVo.getSubjectKeywords()
                    ));

            if (StringUtils.isBlank(jsonResult)) {
                log.info("【图谱搜索RPC】图数据库未返回结果");
                return KnowledgeGraphSearchResponseVo.builder()
                        .triples(Collections.emptyList())
                        .build();
            }

            // 解析图数据库返回的三元组
            List<KnowledgeGraphSearchResponseVo.GraphTripleVo> candidateTriples = parseTriplesFromSurrealDB(jsonResult);
            log.info("【图谱搜索RPC】第一阶段完成，图数据库返回 {} 条候选三元组", candidateTriples.size());

            if (candidateTriples.isEmpty()) {
                return KnowledgeGraphSearchResponseVo.builder()
                        .triples(Collections.emptyList())
                        .build();
            }

            // -----------------------------------------------------------------------
            // 第二阶段：subject 双向匹配过滤
            // -----------------------------------------------------------------------
            List<KnowledgeGraphSearchResponseVo.GraphTripleVo> subjectFilteredTriples = similarityService.filterBySubjectBidirectionalMatch(
                    candidateTriples,
                    requestVo.getSubjectKeywords()
            );
            log.info("【图谱搜索RPC】第二阶段完成，subject双向匹配后剩余 {} 条三元组", subjectFilteredTriples.size());

            // -----------------------------------------------------------------------
            // 第三阶段：predicate 向量相似度过滤
            // 使用向量化计算相似度，阈值0.65
            // -----------------------------------------------------------------------
            List<KnowledgeGraphSearchResponseVo.GraphTripleVo> filteredTriples;

            if (StringUtils.isNotBlank(requestVo.getQueryPredicate())) {
                // 构建查询谓语列表（原始谓语 + 相似谓语）
                List<String> queryPredicates = new ArrayList<>();
                queryPredicates.add(requestVo.getQueryPredicate());
                if (CollectionUtils.isNotEmpty(requestVo.getSimilarPredicates())) {
                    queryPredicates.addAll(requestVo.getSimilarPredicates());
                }

                log.info("【图谱搜索RPC】查询谓语列表共 {} 个: {}", queryPredicates.size(), queryPredicates);

                // 使用向量相似度过滤（阈值0.65）
                filteredTriples = similarityService.filterByPredicateVectorSimilarity(
                        subjectFilteredTriples,
                        queryPredicates,
                        0.65
                );
                log.info("【图谱搜索RPC】第三阶段完成，向量相似度过滤后剩余 {} 条三元组", filteredTriples.size());
            } else {
                filteredTriples = subjectFilteredTriples;
                log.info("【图谱搜索RPC】未提供查询谓语，返回 {} 条三元组", filteredTriples.size());
            }

            return KnowledgeGraphSearchResponseVo.builder()
                    .triples(filteredTriples)
                    .build();

        } catch (Exception e) {
            log.error("【图谱搜索RPC】搜索失败: {}", e.getMessage(), e);
            return KnowledgeGraphSearchResponseVo.builder()
                    .triples(Collections.emptyList())
                    .build();
        }
    }

    /**
     * 解析 SurrealDB 返回的 JSON 结果
     * SurrealDB 返回格式: [{"result": [...] 或 {...}, "status":"OK"}, ...]
     * 注意：result 可能是数组（多条记录）或对象（单条记录）
     */
    private List<KnowledgeGraphSearchResponseVo.GraphTripleVo> parseTriplesFromSurrealDB(String jsonResult) {
        List<KnowledgeGraphSearchResponseVo.GraphTripleVo> triples = new ArrayList<>();

        try {
            JSONArray resultArray = JSONArray.parseArray(jsonResult);
            if (resultArray == null || resultArray.isEmpty()) {
                return triples;
            }

            // SurrealDB 返回多条语句的结果，每条是一个对象
            for (int i = 0; i < resultArray.size(); i++) {
                JSONObject statementResult = resultArray.getJSONObject(i);
                if (statementResult == null) {
                    continue;
                }

                Object resultObj = statementResult.get("result");
                if (resultObj == null) {
                    continue;
                }

                // result 可能是 JSONArray（多条记录）或 JSONObject（单条记录）
                if (resultObj instanceof JSONArray) {
                    // 多条记录
                    JSONArray results = (JSONArray) resultObj;
                    for (int j = 0; j < results.size(); j++) {
                        parseTripleFromJson(results.getJSONObject(j), triples);
                    }
                } else if (resultObj instanceof JSONObject) {
                    // 单条记录
                    parseTripleFromJson((JSONObject) resultObj, triples);
                }
            }
        } catch (Exception e) {
            log.error("解析SurrealDB结果失败: {}, 原始数据: {}", e.getMessage(), jsonResult, e);
        }

        return triples;
    }

    private void parseTripleFromJson(JSONObject obj, List<KnowledgeGraphSearchResponseVo.GraphTripleVo> triples) {
        if (obj == null) return;

        try {
            String subject = obj.getString("subject");
            String predicate = obj.getString("predicate");
            String object = obj.getString("object");

            if (StringUtils.isNotBlank(subject) && StringUtils.isNotBlank(predicate) && StringUtils.isNotBlank(object)) {
                KnowledgeGraphSearchResponseVo.GraphTripleVo triple = KnowledgeGraphSearchResponseVo.GraphTripleVo.builder()
                        .subject(subject.trim())
                        .predicate(predicate.trim())
                        .object(object.trim())
                        .knowledgeId(obj.getLong("knowledgeId"))
                        .documentId(obj.getLong("documentId"))
                        .documentName(obj.getString("documentName"))
                        .fileType(obj.getString("fileType"))
                        .build();
                triples.add(triple);
            }
        } catch (Exception e) {
            log.warn("解析单个三元组失败: {}", e.getMessage());
        }
    }
}
