package com.xspaceagi.agent.core.infra.component.knowledge.accuracytest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xspaceagi.agent.core.infra.component.agent.AgentContext;
import com.xspaceagi.agent.core.infra.component.knowledge.KnowledgeBaseSearcher;
import com.xspaceagi.agent.core.infra.component.knowledge.SearchContext;
import com.xspaceagi.agent.core.infra.component.knowledge.accuracytest.dto.AccuracyTestRecordRequest;
import com.xspaceagi.agent.core.infra.component.knowledge.accuracytest.dto.AccuracyTestSearchRequest;
import com.xspaceagi.agent.core.infra.component.knowledge.accuracytest.vo.AccuracyTestRecordResponse;
import com.xspaceagi.agent.core.infra.component.knowledge.accuracytest.vo.AccuracyTestSearchResponse;
import com.xspaceagi.agent.core.infra.component.knowledge.accuracytest.vo.DocumentListResponse;
import com.xspaceagi.agent.core.spec.enums.SearchStrategyEnum;
import com.xspaceagi.knowledge.sdk.request.KnowledgeFullTextSearchRequestVo;
import com.xspaceagi.knowledge.sdk.request.KnowledgeQaRequestVo;
import com.xspaceagi.knowledge.sdk.response.KnowledgeFullTextSearchResponseVo;
import com.xspaceagi.knowledge.sdk.response.KnowledgeQaResponseVo;
import com.xspaceagi.knowledge.sdk.response.KnowledgeQaVo;
import com.xspaceagi.knowledge.sdk.sevice.IKnowledgeFullTextSearchRpcService;
import com.xspaceagi.knowledge.sdk.sevice.IKnowledgeQaSearchRpcService;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.spec.common.RequestContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.text.DecimalFormat;
import java.util.*;

/**
 * 知识库准确性测试服务
 */
@Service
@Slf4j
public class AccuracyTestService {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private IKnowledgeQaSearchRpcService knowledgeQaSearchRpcService;

    @Resource
    private IKnowledgeFullTextSearchRpcService knowledgeFullTextSearchRpcService;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Resource
    private KnowledgeBaseSearcher knowledgeBaseSearcher;

    /**
     * 保存测试记录
     */
    @Transactional(rollbackFor = Exception.class)
    public Long saveTestRecord(AccuracyTestRecordRequest request, UserDto currentUser) {
        try {
            String sql = "INSERT INTO knowledge_recall_verification " +
                    "(kb_id, query_text, search_strategy, creator_id, created, _tenant_id, query_result) " +
                    "VALUES (?, ?, ?, ?, NOW(), ?, ?)";

            // 转换结果列表为JSON字符串
            String resultsJson = request.getResults() != null ?
                    objectMapper.writeValueAsString(request.getResults()) : "[]";

            // 获取租户ID和用户ID
            String tenantId = currentUser != null ? String.valueOf(currentUser.getTenantId()) : null;
            Long userId = currentUser != null ? currentUser.getId() : null;

            jdbcTemplate.update(sql,
                    request.getKnowledgeBaseId(),
                    request.getQuery(),
                    request.getSearchStrategy(),
                    userId,
                    tenantId,
                    resultsJson
            );

            //// 获取插入的ID
            //Long insertedId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

            /// log.info("保存准确性测试记录成功, ID: {}", insertedId);
            return 1l;

        } catch (JsonProcessingException e) {
            log.error("JSON序列化失败", e);
            throw new RuntimeException("数据序列化失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("保存测试记录失败", e);
            throw new RuntimeException("保存失败: " + e.getMessage());
        }
    }

    /**
     * 查询测试历史（按用户过滤）
     */
    public List<AccuracyTestRecordResponse.TestHistoryItem> getTestHistory(Long knowledgeBaseId, UserDto currentUser) {
        try {
            String tenantId = currentUser != null ? String.valueOf(currentUser.getTenantId()) : null;
            Long userId = currentUser != null ? currentUser.getId() : null;

            String sql = "SELECT id, kb_id as knowledge_base_id, query_text as query, '' as search_strategy, '' as results, created as create_time, query_result " +
                    "FROM knowledge_recall_verification " +
                    "WHERE _tenant_id = ? AND creator_id = ? AND kb_id = ? " +
                    "ORDER BY created DESC LIMIT 20";

            List<AccuracyTestRecordResponse.TestHistoryItem> records = jdbcTemplate.query(
                    sql,
                    (rs, rowNum) -> {
                        AccuracyTestRecordResponse.TestHistoryItem record = new AccuracyTestRecordResponse.TestHistoryItem();
                        record.setId(rs.getLong("id"));
                        record.setKnowledgeBaseId(rs.getLong("knowledge_base_id"));
                        record.setQuery(rs.getString("query"));
                        record.setSearchStrategy(rs.getString("search_strategy"));
                        record.setResults(rs.getString("query_result"));
                        record.setCreateTime(rs.getString("create_time"));
                        /*
                        // 2. 定义格式化模板
                        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

                        System.out.println(rs.getString("create_time"));

                        // 3. 直接格式化 Timestamp
                        String formattedString = sdf.format(rs.getTimestamp("create_time").toString());
                        record.setCreateTime(formattedString);*/

                        return record;
                    },
                    tenantId, userId, knowledgeBaseId
            );

            log.info("查询测试历史成功, records: {}", records.size());
            return records;

        } catch (Exception e) {
            log.error("查询测试历史失败", e);
            throw new RuntimeException("查询失败: " + e.getMessage());
        }
    }

    /**
     * 执行搜索测试（复用KnowledgeBaseSearcher.java 92-173行搜索逻辑）
     */
    public AccuracyTestSearchResponse performSearch(AccuracyTestSearchRequest request) {
        try {

            AccuracyTestRecordRequest recordRequest = new AccuracyTestRecordRequest();
            recordRequest.setQuery(request.getQuery());
            recordRequest.setSearchStrategy(request.getSearchStrategy());
            recordRequest.setKnowledgeBaseId(request.getKnowledgeBaseId());

            SearchContext searchContext = new SearchContext();
            searchContext.setSearchStrategy(request.getSearchStrategy().equals("SEMANTIC")?SearchStrategyEnum.SEMANTIC:request.getSearchStrategy().equals("SEMANTIC")?SearchStrategyEnum.FULL_TEXT:SearchStrategyEnum.MIXED);
            searchContext.setQuery(request.getQuery());
            List<Long> knowledgeBaseIds = new ArrayList<>();
            knowledgeBaseIds.add(request.getKnowledgeBaseId());
            searchContext.setKnowledgeBaseIds(knowledgeBaseIds);
            searchContext.setMatchingDegree(request.getMatchingDegree());
            searchContext.setMaxRecallCount(20);
            //searchContext.getAgentContext().getUser().getTenantId()
            UserDto currentUser = (UserDto) RequestContext.get().getUser();
            //searchContext.setAgentContext(RequestContext.get().getUser());
            AgentContext agentContext = new AgentContext();
            agentContext.setUser(currentUser);
            TenantConfigDto tenantConfig = new TenantConfigDto();
            tenantConfig.setCommercialEdition(request.getIsShowGRAPH());
            agentContext.setTenantConfig(tenantConfig);
            searchContext.setAgentContext(agentContext);

            String sql = "SELECT id, name as doc_name, data_type as file_type, kb_id, file_size, _tenant_id " +
                    "FROM knowledge_document " +
                    "WHERE kb_id = ? ";
            List<Map<String, Object>> documents = jdbcTemplate.queryForList(sql, request.getKnowledgeBaseId());
            Map<String, String> map_dd = new HashMap<>();
            Long _tenant_id = null;
            if (documents != null) {
                for(Map<String, Object> map : documents) {
                    map_dd.put(map.get("id").toString(), map.get("doc_name").toString());
                    _tenant_id = Long.valueOf(map.get("_tenant_id").toString());
                }
            }


            Mono<List<KnowledgeQaVo>> result =  knowledgeBaseSearcher.search(searchContext);
            List<KnowledgeQaVo> list = result.block();

            AccuracyTestSearchResponse response = new AccuracyTestSearchResponse();

            List<AccuracyTestSearchResponse.SearchResultItem> results = new ArrayList<>();
            for(KnowledgeQaVo entry : list) {
                //entry.get
                if(map_dd.get(entry.getDocId().toString()) != null) {
                    AccuracyTestSearchResponse.SearchResultItem  item = new AccuracyTestSearchResponse.SearchResultItem();
                    item.setDocName(map_dd.get(entry.getDocId().toString()));

                    //BigDecimal bd = BigDecimal.valueOf(entry.getScore());
                    //bd = bd.setScale(2, RoundingMode.HALF_UP);
                    DecimalFormat df = new DecimalFormat("0.00"); // "0.00" 表示保留两位，不足补零
                    Double score = Double.parseDouble(df.format(entry.getScore()));

                    item.setScore(score);
                    item.setDocId(entry.getDocId());
                    item.setContent(entry.getRawTxt());
                    item.setAnswer(entry.getAnswer());
                    results.add(item);
                }

            }

            // 按 score 属性倒序排序
            results.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

            response.setResults(results);
            response.setTotal(results.size());

            recordRequest.setResults(response);
            this.saveTestRecord(recordRequest, currentUser);

            /*
            AccuracyTestSearchResponse response = new AccuracyTestSearchResponse();
            List<AccuracyTestSearchResponse.SearchResultItem> items = new ArrayList<>();

            if ("SEMANTIC".equalsIgnoreCase(request.getSearchStrategy())) {
                // 语义搜索
                items = performSemanticSearch(request);

            } else if ("FULL_TEXT".equalsIgnoreCase(request.getSearchStrategy())) {
                // 全文搜索
                items = performFullTextSearch(request);

            } else if ("MIXED".equalsIgnoreCase(request.getSearchStrategy())) {
                // 混合搜索：结合语义和全文搜索结果
                items = performMixedSearch(request);
            }

            // 限制结果数量为20
            if (items.size() > 20) {
                items = items.subList(0, 20);
            }

            // 设置排名
            for (int i = 0; i < items.size(); i++) {
                items.get(i).setRank(i + 1);
            }

            response.setResults(items);
            response.setTotal(items.size());*/

            return response;

        } catch (Exception e) {
            log.error("执行搜索测试失败", e);
            throw new RuntimeException("搜索失败: " + e.getMessage());
        }
    }

    /**
     * 语义搜索
     */
    private List<AccuracyTestSearchResponse.SearchResultItem> performSemanticSearch(AccuracyTestSearchRequest request) {
        List<AccuracyTestSearchResponse.SearchResultItem> items = new ArrayList<>();

        try {
            KnowledgeQaRequestVo qaRequest = new KnowledgeQaRequestVo();
            qaRequest.setQuestion(request.getQuery());
            qaRequest.setTopK(request.getTopK());
            qaRequest.setKbId(request.getKnowledgeBaseId());
            qaRequest.setIgnoreDocStatus(true);
            qaRequest.setIgnoreTenantId(true);

            KnowledgeQaResponseVo qaResponse = knowledgeQaSearchRpcService.search(qaRequest);
            if (qaResponse != null && qaResponse.getQaVoList() != null) {
                qaResponse.getQaVoList().forEach(qaVo -> {
                    if (qaVo.getScore() >= request.getMatchingDegree().floatValue()) {
                        AccuracyTestSearchResponse.SearchResultItem item = new AccuracyTestSearchResponse.SearchResultItem();
                        item.setDocId(qaVo.getDocId());
                        item.setScore(qaVo.getScore());
                        item.setContent(qaVo.getRawTxt());
                        items.add(item);
                    }
                });
            }

            // 按得分降序排序
            items.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

        } catch (Exception e) {
            log.error("语义搜索失败", e);
        }

        return items;
    }

    /**
     * 全文搜索
     */
    private List<AccuracyTestSearchResponse.SearchResultItem> performFullTextSearch(AccuracyTestSearchRequest request) {
        List<AccuracyTestSearchResponse.SearchResultItem> items = new ArrayList<>();

        try {
            KnowledgeFullTextSearchRequestVo ftRequest = new KnowledgeFullTextSearchRequestVo();
            ftRequest.setQueryText(request.getQuery());
            ftRequest.setKbIds(Arrays.asList(request.getKnowledgeBaseId()));
            ftRequest.setTopK(request.getTopK());

            KnowledgeFullTextSearchResponseVo ftResponse = knowledgeFullTextSearchRpcService.search(ftRequest);
            if (ftResponse != null && ftResponse.getResults() != null) {
                ftResponse.getResults().forEach(result -> {
                    if (result.getScore() >= request.getMatchingDegree().floatValue()) {
                        AccuracyTestSearchResponse.SearchResultItem item = new AccuracyTestSearchResponse.SearchResultItem();
                        item.setDocId(result.getDocId());
                        item.setScore(result.getScore().doubleValue());
                        item.setContent(result.getRawText());
                        items.add(item);
                    }
                });
            }

            // 按得分降序排序
            items.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

        } catch (Exception e) {
            log.error("全文搜索失败", e);
        }

        return items;
    }

    /**
     * 混合搜索（结合语义和全文搜索）
     */
    private List<AccuracyTestSearchResponse.SearchResultItem> performMixedSearch(AccuracyTestSearchRequest request) {
        // 先执行语义搜索
        List<AccuracyTestSearchResponse.SearchResultItem> semanticItems = performSemanticSearch(request);

        // 再执行全文搜索
        List<AccuracyTestSearchResponse.SearchResultItem> fullTextItems = performFullTextSearch(request);

        // 合并结果，语义搜索优先（使用LinkedHashMap保持顺序）
        Map<String, AccuracyTestSearchResponse.SearchResultItem> contentMap = new LinkedHashMap<>();

        // 添加语义搜索结果
        for (AccuracyTestSearchResponse.SearchResultItem item : semanticItems) {
            String key = item.getContent();
            if (key != null && !key.isEmpty()) {
                contentMap.put(key, item);
            }
        }

        // 添加全文搜索结果（去重）
        for (AccuracyTestSearchResponse.SearchResultItem item : fullTextItems) {
            String key = item.getContent();
            if (key != null && !key.isEmpty() && !contentMap.containsKey(key)) {
                contentMap.put(key, item);
            }
        }

        List<AccuracyTestSearchResponse.SearchResultItem> mergedItems = new ArrayList<>(contentMap.values());

        // 按得分降序排序
        mergedItems.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

        return mergedItems;
    }

    /**
     * 获取文档列表
     */
    public List<DocumentListResponse.DocumentItem> getDocumentList(Long knowledgeBaseId) {
        try {
            String sql = "SELECT id, name as doc_name, data_type as file_type, kb_id, file_size " +
                    "FROM knowledge_document " +
                    "WHERE kb_id = ? and has_embedding = ? ";

            List<DocumentListResponse.DocumentItem> documents = jdbcTemplate.query(
                    sql,
                    (rs, rowNum) -> {
                        DocumentListResponse.DocumentItem item = new DocumentListResponse.DocumentItem();
                        item.setId(rs.getLong("id"));
                        item.setName(rs.getString("doc_name"));
                        item.setFileType(rs.getString("file_type"));
                        item.setKnowledgeBaseId(rs.getLong("kb_id"));
                        return item;
                    },
                    knowledgeBaseId,
                    1
            );

            log.info("获取文档列表成功, documents: {}", documents.size());
            return documents;

        } catch (Exception e) {
            log.error("获取文档列表失败", e);
            throw new RuntimeException("获取失败: " + e.getMessage());
        }
    }
}