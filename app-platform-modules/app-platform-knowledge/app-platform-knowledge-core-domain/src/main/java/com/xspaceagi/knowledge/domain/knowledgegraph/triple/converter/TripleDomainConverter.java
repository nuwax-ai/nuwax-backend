package com.xspaceagi.knowledge.domain.knowledgegraph.triple.converter;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.knowledge.domain.knowledgegraph.triple.vo.DocumentGraphVo;
import com.xspaceagi.knowledge.domain.knowledgegraph.triple.vo.KnowledgeGraphListItemVo;
import com.xspaceagi.knowledge.domain.knowledgegraph.triple.vo.KnowledgeGraphStatsVo;
import com.xspaceagi.knowledge.domain.knowledgegraph.triple.vo.TripleVo;
import com.xspaceagi.knowledge.domain.model.DocumentParseStatusModel;
import com.xspaceagi.knowledge.domain.model.KnowledgeDocumentModel;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识图谱转换器
 *
 * @author system
 * @date 2026-04-16
 */
@Slf4j
@Component
public class TripleDomainConverter {

    // ==================== 三元组解析相关 ====================

    /**
     * 解析三元组查询结果
     *
     * @param jsonResult SurrealDB返回的JSON字符串
     * @return 三元组列表
     */
    public List<TripleVo> parseTripleResult(String jsonResult) {
        List<TripleVo> triples = new ArrayList<>();

        if (StringUtils.isBlank(jsonResult)) {
            return triples;
        }

        try {
            JSONArray resultArray = parseResultArray(jsonResult);
            if (resultArray == null || resultArray.isEmpty()) {
                return triples;
            }

            for (int i = 0; i < resultArray.size(); i++) {
                JSONObject obj = resultArray.getJSONObject(i);
                TripleVo vo = new TripleVo();
                vo.setId(obj.getString("id"));
                vo.setSubject(obj.getString("subject"));
                vo.setPredicate(obj.getString("predicate"));
                vo.setObject(obj.getString("object"));

                // 扁平结构，直接获取字段
                if (obj.containsKey("knowledgeId")) {
                    vo.setKnowledgeId(obj.getLong("knowledgeId"));
                }
                if (obj.containsKey("documentId")) {
                    vo.setDocumentId(obj.getLong("documentId"));
                }
                vo.setDocumentName(obj.getString("documentName"));
                vo.setFileType(obj.getString("fileType"));
                if (obj.containsKey("segmentId")) {
                    vo.setSegmentId(obj.getLong("segmentId"));
                }
                vo.setSegmentContent(obj.getString("segmentContent"));

                triples.add(vo);
            }
        } catch (Exception e) {
            log.warn("解析三元组结果失败: {}", e.getMessage());
        }

        return triples;
    }

    /**
     * 解析SurrealDB返回的JSON数组
     *
     * @param jsonResult SurrealDB返回的JSON字符串
     * @return 结果数组
     */
    public JSONArray parseResultArray(String jsonResult) {
        if (StringUtils.isBlank(jsonResult)) {
            return null;
        }

        try {
            // SurrealDB 返回格式: [{"result":{"database":"main",...},"status":"OK"}, {"result":[...],"status":"OK"}]
            // 第一个元素是 USE NS/DB 的结果，第二个元素才是查询结果
            JSONArray outerArray = JSON.parseArray(jsonResult);
            if (outerArray == null || outerArray.isEmpty()) {
                return null;
            }

            // 遍历找到 result 是数组的那个元素
            for (int i = 0; i < outerArray.size(); i++) {
                JSONObject outerObj = outerArray.getJSONObject(i);
                if (outerObj.containsKey("result")) {
                    Object resultObj = outerObj.get("result");
                    if (resultObj instanceof JSONArray) {
                        return (JSONArray) resultObj;
                    }
                }
            }

            return null;
        } catch (Exception e) {
            log.warn("解析SurrealDB结果失败: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 文档图谱转换 ====================

    /**
     * 将三元组列表转换为文档知识图谱VO
     *
     * @param documentId 文档ID
     * @param triples    三元组列表
     * @return 文档知识图谱VO
     */
    public DocumentGraphVo convertToDocumentGraphVo(Long documentId, List<TripleVo> triples) {
        DocumentGraphVo vo = new DocumentGraphVo();
        vo.setDocumentId(documentId);
        vo.setTriples(triples);
        vo.setTripleCount(triples.size());

        if (!triples.isEmpty()) {
            TripleVo first = triples.get(0);
            vo.setDocumentName(first.getDocumentName());
            vo.setKnowledgeId(first.getKnowledgeId());
            vo.setFileType(first.getFileType());
        }

        return vo;
    }

    // ==================== 统计相关转换 ====================

    /**
     * 从统计结果中提取数量
     *
     * @param statsResult 统计结果JSON
     * @return 数量
     */
    public Integer extractCount(String statsResult) {
        try {
            JSONArray resultArray = parseResultArray(statsResult);
            if (resultArray != null && !resultArray.isEmpty()) {
                JSONObject obj = resultArray.getJSONObject(0);
                return obj.getIntValue("tripleCount", 0);
            }
        } catch (Exception e) {
            log.warn("提取统计数量失败: {}", e.getMessage());
        }
        return 0;
    }

    /**
     * 解析谓语统计
     *
     * @param predicateResult 谓语统计结果JSON
     * @return 谓语统计列表
     */
    public List<KnowledgeGraphStatsVo.PredicateStats> parsePredicateStats(String predicateResult) {
        List<KnowledgeGraphStatsVo.PredicateStats> statsList = new ArrayList<>();

        try {
            JSONArray resultArray = parseResultArray(predicateResult);
            if (resultArray == null || resultArray.isEmpty()) {
                return statsList;
            }

            for (int i = 0; i < resultArray.size(); i++) {
                JSONObject obj = resultArray.getJSONObject(i);
                KnowledgeGraphStatsVo.PredicateStats stats = new KnowledgeGraphStatsVo.PredicateStats();
                stats.setPredicate(obj.getString("predicate"));
                stats.setCount(obj.getIntValue("count", 0));
                statsList.add(stats);
            }
        } catch (Exception e) {
            log.warn("解析谓语统计失败: {}", e.getMessage());
        }

        return statsList;
    }

    /**
     * 构建知识图谱统计VO
     *
     * @param knowledgeId     知识库ID
     * @param tripleCount     三元组数量
     * @param predicateStats  谓语统计
     * @param documentCount   文档数量
     * @return 知识图谱统计VO
     */
    public KnowledgeGraphStatsVo convertToKnowledgeGraphStatsVo(
            Long knowledgeId,
            Integer tripleCount,
            List<KnowledgeGraphStatsVo.PredicateStats> predicateStats,
            Integer documentCount) {
        KnowledgeGraphStatsVo statsVo = new KnowledgeGraphStatsVo();
        statsVo.setKnowledgeId(knowledgeId);
        statsVo.setTripleCount(tripleCount);
        statsVo.setPredicateStats(predicateStats);
        statsVo.setDocumentCount(documentCount);
        return statsVo;
    }

    // ==================== 列表项转换 ====================

    /**
     * 从URL或文件名中提取文件类型
     *
     * @param urlOrName URL或文件名
     * @return 文件类型
     */
    public String extractFileType(String urlOrName) {
        if (StringUtils.isBlank(urlOrName)) {
            return null;
        }
        String lower = urlOrName.toLowerCase();
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
     * 将文档模型转换为知识图谱列表项
     *
     * @param docList        文档列表
     * @param tripleStatusMap 三元组状态映射
     * @return 知识图谱列表项列表
     */
    public List<KnowledgeGraphListItemVo> convertToGraphListItemList(
            List<KnowledgeDocumentModel> docList,
            Map<Long, Integer> tripleStatusMap) {
        return docList.stream().map(doc -> {
            KnowledgeGraphListItemVo vo = new KnowledgeGraphListItemVo();
            vo.setDocumentId(doc.getId());
            vo.setDocumentName(doc.getName());
            vo.setTripleStatus(tripleStatusMap.getOrDefault(doc.getId(), 0));
            vo.setFileType(extractFileType(doc.getDocUrl()));
            vo.setCreatedTime(doc.getCreated());
            vo.setUpdatedTime(doc.getModified());
            return vo;
        }).collect(Collectors.toList());
    }

    /**
     * 将文档解析状态列表转换为映射
     *
     * @param statusList 状态列表
     * @return 文档ID到三元组状态的映射
     */
    public Map<Long, Integer> convertToTripleStatusMap(List<DocumentParseStatusModel> statusList) {
        if (statusList == null || statusList.isEmpty()) {
            return Map.of();
        }
        return statusList.stream()
                .collect(Collectors.toMap(
                        DocumentParseStatusModel::getDocumentId,
                        DocumentParseStatusModel::getTripleStatus,
                        (v1, v2) -> v1
                ));
    }
}
