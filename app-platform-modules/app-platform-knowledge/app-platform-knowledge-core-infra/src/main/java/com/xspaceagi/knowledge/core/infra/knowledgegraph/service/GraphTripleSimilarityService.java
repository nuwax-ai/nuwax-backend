package com.xspaceagi.knowledge.core.infra.knowledgegraph.service;

import com.xspaceagi.agent.core.adapter.application.ModelApplicationService;
import com.xspaceagi.knowledge.sdk.response.KnowledgeGraphSearchResponseVo.GraphTripleVo;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class GraphTripleSimilarityService {

    @Resource
    private ModelApplicationService modelApplicationService;

    public List<GraphTripleVo> filterBySubjectBidirectionalMatch(
            List<GraphTripleVo> candidates,
            List<String> querySubjects) {

        try {
            if (querySubjects == null || querySubjects.isEmpty()) {
                return candidates;
            }

            List<GraphTripleVo> filtered = new ArrayList<>();

            for (GraphTripleVo triple : candidates) {
                String dbSubject = triple.getSubject();
                if (StringUtils.isBlank(dbSubject)) {
                    continue;
                }

                for (String querySubject : querySubjects) {
                    if (StringUtils.isBlank(querySubject)) {
                        continue;
                    }

                    if (isBidirectionalMatch(dbSubject, querySubject)) {
                        filtered.add(triple);
                        break;
                    }
                }
            }

            return filtered.isEmpty() ? candidates : filtered;

        } catch (Exception e) {
            log.error("【相似度服务】subject双向匹配过滤失败: {}", e.getMessage(), e);
            return candidates;
        }
    }

    private boolean isBidirectionalMatch(String a, String b) {
        if (StringUtils.isBlank(a) || StringUtils.isBlank(b)) {
            return false;
        }

        String lowerA = a.toLowerCase();
        String lowerB = b.toLowerCase();

        return lowerA.contains(lowerB) || lowerB.contains(lowerA);
    }

    /**
     * 使用向量化相似度过滤谓语
     * 只要有一个查询谓语与候选谓语的相似度 >= 阈值，就保留该候选三元组
     *
     * @param candidates       候选三元组列表
     * @param queryPredicates  查询谓语列表（原始谓语 + 相似谓语）
     * @param threshold        相似度阈值（0-1）
     * @return 过滤后的三元组列表
     */
    public List<GraphTripleVo> filterByPredicateVectorSimilarity(
            List<GraphTripleVo> candidates,
            List<String> queryPredicates,
            double threshold) {

        try {
            if (queryPredicates == null || queryPredicates.isEmpty() || candidates == null || candidates.isEmpty()) {
                return candidates;
            }

            // 收集所有唯一的候选谓语
            Set<String> uniquePredicates = candidates.stream()
                    .map(GraphTripleVo::getPredicate)
                    .filter(StringUtils::isNotBlank)
                    .collect(Collectors.toSet());

            if (uniquePredicates.isEmpty()) {
                return candidates;
            }

            log.info("【向量相似度】开始计算，查询谓语数={}，候选谓语数={}，阈值={}",
                    queryPredicates.size(), uniquePredicates.size(), threshold);

            // 批量获取向量（使用TenantFunctions包装以处理线程上下文）
            List<String> allTexts = new ArrayList<>();
            allTexts.addAll(queryPredicates);
            allTexts.addAll(uniquePredicates);

            List<float[]> embeddings = TenantFunctions.callWithIgnoreCheck(() ->
                    modelApplicationService.embeddings(allTexts, null)
            );

            if (embeddings == null || embeddings.size() != allTexts.size()) {
                log.warn("【向量相似度】获取向量失败，返回原列表");
                return candidates;
            }

            // 分离查询谓语向量和候选谓语向量
            List<float[]> queryVectors = embeddings.subList(0, queryPredicates.size());
            List<float[]> candidateVectors = embeddings.subList(queryPredicates.size(), allTexts.size());

            // 计算候选谓语的相似度（只要有一个查询谓语满足阈值即可）
            Map<String, Double> similarityMap = new HashMap<>();
            List<String> candidateList = new ArrayList<>(uniquePredicates);

            // 打印前几个查询谓语和候选谓语用于调试
            log.info("【向量相似度】查询谓语: {}", queryPredicates.subList(0, Math.min(5, queryPredicates.size())));
            log.info("【向量相似度】候选谓语(前10个): {}", candidateList.subList(0, Math.min(10, candidateList.size())));

            for (int i = 0; i < candidateList.size(); i++) {
                String candidatePredicate = candidateList.get(i);
                float[] candidateVector = candidateVectors.get(i);
                double maxSimilarity = 0.0;

                // 与每个查询谓语比较
                for (int j = 0; j < queryVectors.size(); j++) {
                    float[] queryVector = queryVectors.get(j);
                    double similarity = calculateCosineSimilarity(queryVector, candidateVector);
                    if (i < 3 && j < 3) {
                        log.info("【向量相似度】 '{}' vs '{}' = {}", queryPredicates.get(j), candidatePredicate, String.format("%.4f", similarity));
                    }
                    if (similarity >= threshold) {
                        maxSimilarity = similarity;
                        break;
                    }
                    if (similarity > maxSimilarity) {
                        maxSimilarity = similarity;
                    }
                }

                similarityMap.put(candidatePredicate, maxSimilarity);
            }

            // 过滤并设置相似度分数
            List<GraphTripleVo> filtered = new ArrayList<>();
            for (GraphTripleVo triple : candidates) {
                Double similarity = similarityMap.get(triple.getPredicate());
                if (similarity != null && similarity >= threshold) {
                    triple.setSimilarityScore(similarity);
                    filtered.add(triple);
                }
            }

            // 按相似度降序排序
            filtered.sort((a, b) -> Double.compare(
                    b.getSimilarityScore() != null ? b.getSimilarityScore() : 0.0,
                    a.getSimilarityScore() != null ? a.getSimilarityScore() : 0.0
            ));

            log.info("【向量相似度】过滤完成，候选数={}，过滤后={}，阈值={}",
                    candidates.size(), filtered.size(), threshold);

            return filtered.isEmpty() ? candidates : filtered;

        } catch (Exception e) {
            log.error("【向量相似度】过滤失败: {}", e.getMessage(), e);
            return candidates;
        }
    }

    /**
     * 计算两个向量的余弦相似度
     *
     * @param vec1 向量1
     * @param vec2 向量2
     * @return 余弦相似度（-1 到 1）
     */
    private double calculateCosineSimilarity(float[] vec1, float[] vec2) {
        if (vec1 == null || vec2 == null || vec1.length != vec2.length) {
            return 0.0;
        }

        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;

        for (int i = 0; i < vec1.length; i++) {
            dotProduct += vec1[i] * vec2[i];
            norm1 += vec1[i] * vec1[i];
            norm2 += vec2[i] * vec2[i];
        }

        if (norm1 == 0.0 || norm2 == 0.0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }
}
