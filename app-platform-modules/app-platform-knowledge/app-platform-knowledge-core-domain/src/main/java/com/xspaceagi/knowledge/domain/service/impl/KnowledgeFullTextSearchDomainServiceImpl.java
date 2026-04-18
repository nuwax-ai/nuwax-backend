package com.xspaceagi.knowledge.domain.service.impl;

import com.xspaceagi.knowledge.core.adapter.client.KnowledgeFullTextSearchClient;
import com.xspaceagi.knowledge.core.adapter.client.dto.*;
import com.xspaceagi.knowledge.domain.model.fulltext.FullTextSearchRequestModel;
import com.xspaceagi.knowledge.domain.model.fulltext.FullTextSearchResultModel;
import com.xspaceagi.knowledge.domain.model.fulltext.FullTextStatsModel;
import com.xspaceagi.knowledge.domain.model.fulltext.RawSegmentFullTextModel;
import com.xspaceagi.knowledge.domain.service.IKnowledgeFullTextSearchDomainService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 知识库全文检索领域服务实现
 *
 * @author system
 * @date 2025-03-31
 */
@Slf4j
@Service
public class KnowledgeFullTextSearchDomainServiceImpl implements IKnowledgeFullTextSearchDomainService {

    @Resource
    private KnowledgeFullTextSearchClient fullTextSearchClient;

    @Resource
    private IKnowledgeFullTextSearchDomainService fullTextSearchDomainService;

    @Override
    public List<FullTextSearchResultModel> search(FullTextSearchRequestModel request) {
        log.info("Full-text search: kbIds={}, query={}, topK={}",
                request.getKbIds(), request.getQueryText(), request.getTopK());

        // 1. 参数校验
        validateSearchRequest(request);

        // 2. 构建 Adapter 层请求参数
        KnowledgeSearchParams searchParams = buildSearchParams(request);

        // 3. 调用 Adapter 层执行检索
        KnowledgeSearchResult searchResult = fullTextSearchClient.search(searchParams);

        // 4. 转换结果
        List<FullTextSearchResultModel> results = convertSearchResults(searchResult);

        log.info("Full-text done: kbIds={}, resultCount={}, tookMs={}",
                request.getKbIds(), results.size(), searchResult.getTookMs());

        return results;
    }

    @Override
    public PushResult pushSegments(List<RawSegmentFullTextModel> segments) {
        if (CollectionUtils.isEmpty(segments)) {
            log.warn("Empty segments, skip push");
            PushResult emptyResult = new PushResult();
            emptyResult.setIndexedCount(0L);
            emptyResult.setSuccessRawIds(Collections.emptyList());
            return emptyResult;
        }

        log.info("Push segments: segmentCount={}", segments.size());

        // 1. 转换为 Adapter DTO
        List<KnowledgeRawSegment> adapterSegments = segments.stream()
                .map(this::convertToAdapterSegment)
                .collect(Collectors.toList());

        // 2. 构建推送请求
        PushRequest pushRequest = new PushRequest();
        pushRequest.setSegments(adapterSegments);

        // 3. 执行推送
        PushResult result = fullTextSearchClient.pushSegments(pushRequest);

        // 4. 处理返回结果，防止 NPE
        if (result == null) {
            result = new PushResult();
            result.setIndexedCount(0L);
            result.setSuccessRawIds(Collections.emptyList());
        }
        if (result.getIndexedCount() == null) {
            result.setIndexedCount(0L);
        }
        if (result.getSuccessRawIds() == null) {
            result.setSuccessRawIds(Collections.emptyList());
        }

        log.info("Push done: indexedCount={}, successRawIdsCount={}",
                result.getIndexedCount(), result.getSuccessRawIds().size());

        return result;
    }

    @Override
    public Long deleteByKbId(Long kbId, Long tenantId) {
        log.info("Delete KB full-text: kbId={}, tenantId={}", kbId, tenantId);
        List<Long> segmentIds = fullTextSearchDomainService.queryAllSegmentIds(
                kbId, tenantId, null
        );
        DeleteParams deleteParams = new DeleteParams();
        deleteParams.setTenantId(tenantId);
        deleteParams.setKbId(Arrays.asList(kbId));
        deleteParams.setRawIds(segmentIds);

        DeleteResult result = fullTextSearchClient.deleteSegments(deleteParams);

        Long deletedCount = (result != null && result.getDeletedCount() != null)
                ? result.getDeletedCount()
                : 0L;

        log.info("Delete done: kbId={}, deletedCount={}", kbId, deletedCount);

        return deletedCount;
    }

    @Override
    public Long deleteByDocId(Long docId, Long kbId, Long tenantId) {
        log.info("Delete doc full-text: docId={}, kbId={}, tenantId={}", docId, kbId, tenantId);
        List<Long> segmentIds = fullTextSearchDomainService.queryAllSegmentIdsByDocId(
                kbId, tenantId, null, docId
        );
        DeleteParams deleteParams = new DeleteParams();
        deleteParams.setTenantId(tenantId);
        deleteParams.setKbId(Arrays.asList(kbId));
        deleteParams.setDocId(Arrays.asList(docId));
        deleteParams.setRawIds(segmentIds);

        DeleteResult result = fullTextSearchClient.deleteSegments(deleteParams);

        Long deletedCount = (result != null && result.getDeletedCount() != null)
                ? result.getDeletedCount()
                : 0L;

        log.info("Delete done: docId={}, kbId={}, deletedCount={}", docId, kbId, deletedCount);

        return deletedCount;
    }

    @Override
    public Long deleteByRawIds(List<Long> rawSegmentIds, Long tenantId) {
        if (CollectionUtils.isEmpty(rawSegmentIds)) {
            log.warn("Empty rawIds, skip delete");
            return 0L;
        }

        log.info("Delete segment full-text: rawIds={}, tenantId={}", rawSegmentIds, tenantId);

        DeleteParams deleteParams = new DeleteParams();
        deleteParams.setTenantId(tenantId);
        deleteParams.setRawIds(rawSegmentIds);

        DeleteResult result = fullTextSearchClient.deleteSegments(deleteParams);

        Long deletedCount = (result != null && result.getDeletedCount() != null)
                ? result.getDeletedCount()
                : 0L;

        log.info("Delete done: rawIds={}, deletedCount={}", rawSegmentIds, deletedCount);

        return deletedCount;
    }

    @Override
    public Long updateSegmentText(Long rawSegmentId, String newText, Long tenantId, Long spaceId) {
        log.info("Update segment text: rawId={}, tenantId={}", rawSegmentId, tenantId);

        UpdateRequest updateRequest = new UpdateRequest();
        updateRequest.setRawId(rawSegmentId);
        updateRequest.setRawTxt(newText);
        updateRequest.setTenantId(tenantId);
        if (spaceId != null) {
            updateRequest.setSpaceId(spaceId);
        }

        UpdateResult result = fullTextSearchClient.updateSegment(updateRequest);

        Long updatedCount = (result != null && result.getUpdatedCount() != null)
                ? result.getUpdatedCount()
                : 0L;

        log.info("Update done: rawId={}, updatedCount={}", rawSegmentId, updatedCount);

        return updatedCount;
    }

    @Override
    public FullTextStatsModel getStats(Long tenantId, Long kbId, Long spaceId) {
        log.info("Stats: tenantId={}, kbId={}, spaceId={}", tenantId, kbId, spaceId);

        StatsParams statsParams = new StatsParams();
        statsParams.setTenantId(tenantId);
        if (kbId != null) {
            statsParams.setKbId(kbId);
        }
        if (spaceId != null) {
            statsParams.setSpaceId(spaceId);
        }

        KnowledgeStatsResult result = fullTextSearchClient.getStats(statsParams);

        // 转换为 Domain 模型
        FullTextStatsModel statsModel = convertToStatsModel(result);

        log.info("Stats done: docCount={}, totalSegments={}",
                statsModel.getDocCount(), statsModel.getTotalSegments());

        return statsModel;
    }

    @Override
    public void validateSearchRequest(FullTextSearchRequestModel request) {
        if (request == null) {
            throw new IllegalArgumentException("Search request cannot be empty");
        }
        // kbId 和 kbIds 至少要有一个（或者都不传，表示检索所有知识库）
        // 这里不强制要求，允许不传知识库ID
        if (!StringUtils.hasText(request.getQueryText())) {
            throw new IllegalArgumentException("Query text cannot be empty");
        }
        if (request.getTenantId() == null) {
            throw new IllegalArgumentException("Tenant ID cannot be empty");
        }
        if (request.getTopK() != null && (request.getTopK() < 1 || request.getTopK() > 100)) {
            throw new IllegalArgumentException("Result size must be between 1 and 100");
        }
        if (request.getOffset() != null && request.getOffset() < 0) {
            throw new IllegalArgumentException("Page offset cannot be negative");
        }
    }

    /**
     * 构建 Adapter 层检索参数
     */
    private KnowledgeSearchParams buildSearchParams(FullTextSearchRequestModel request) {
        KnowledgeSearchParams params = new KnowledgeSearchParams();

        // 必填参数
        params.setQuery(request.getQueryText());
        params.setTenantId(request.getTenantId());

        // 知识库过滤（支持多个知识库或不传）
        if (request.getKbIds() != null && !request.getKbIds().isEmpty()) {
            // 优先使用 kbIds
            params.setKbIds(request.getKbIds());
            log.debug("Set kbIds: {}", request.getKbIds());
        } else if (request.getKbId() != null) {
            // 兼容旧版本的单个 kbId
            params.setKbIds(Arrays.asList(request.getKbId()));
            log.debug("Set kbIds (single compat): {}", request.getKbId());
        }
        // 如果都不传，则不设置 kbIds，表示检索所有知识库

        // 可选参数
        if (request.getTopK() != null) {
            params.setLimit(request.getTopK().longValue());
        }
        if (request.getOffset() != null) {
            params.setOffset(request.getOffset());
        }
        if (request.getSpaceId() != null) {
            params.setSpaceId(request.getSpaceId());
        }

        // 文档过滤
        if (!CollectionUtils.isEmpty(request.getDocIds())) {
            params.setDocIds(request.getDocIds());
        }

        return params;
    }

    /**
     * 转换检索结果
     */
    private List<FullTextSearchResultModel> convertSearchResults(KnowledgeSearchResult searchResult) {
        if (searchResult == null || CollectionUtils.isEmpty(searchResult.getResults())) {
            return Collections.emptyList();
        }

        return searchResult.getResults().stream()
                .map(this::convertToResultModel)
                .collect(Collectors.toList());
    }

    /**
     * 转换单个检索结果
     */
    private FullTextSearchResultModel convertToResultModel(KnowledgeSearchHit hit) {
        FullTextSearchResultModel model = new FullTextSearchResultModel();

        // 基础字段（现在都是 Long 类型）
        model.setRawSegmentId(hit.getRawId());
        model.setKbId(hit.getKbId());
        model.setDocId(hit.getDocId());
        model.setTenantId(hit.getTenantId());
        model.setSpaceId(hit.getSpaceId());

        // 文本和分数
        model.setRawText(hit.getRawTxt());
        model.setScore(hit.getScore());
        model.setHighlight(hit.getHighlight());

        // 排序和时间
        if (hit.getSortIndex() != null) {
            model.setSortIndex(hit.getSortIndex().intValue());
        }
        model.setCreated(hit.getCreated());

        return model;
    }

    /**
     * 转换为 Adapter 分段数据
     */
    private KnowledgeRawSegment convertToAdapterSegment(RawSegmentFullTextModel segment) {
        KnowledgeRawSegment dto = new KnowledgeRawSegment();
        dto.setRawId(segment.getRawId());
        dto.setKbId(segment.getKbId());
        dto.setDocId(segment.getDocId());
        dto.setRawTxt(segment.getRawText());
        dto.setSortIndex(segment.getSortIndex());
        dto.setTenantId(segment.getTenantId());
        dto.setSpaceId(segment.getSpaceId());
        dto.setCreated(segment.getCreated());
        return dto;
    }

    @Override
    public List<Long> queryAllSegmentIds(Long kbId, Long tenantId, Long spaceId) {
        log.info("List all segment IDs: kbId={}, tenantId={}, spaceId={}", kbId, tenantId, spaceId);

        // 构建查询参数
        SegmentIdsParams params = new SegmentIdsParams();
        params.setTenantId(tenantId);
        params.setKbId(kbId);
        if (spaceId != null) {
            params.setSpaceId(spaceId);
        }

        // 调用 Client 查询
        SegmentIdsResult result = fullTextSearchClient.querySegmentIds(params);

        // 现在直接返回 Long 列表，不需要转换
        List<Long> segmentIds = Collections.emptyList();
        if (result != null && !CollectionUtils.isEmpty(result.getSegmentIds())) {
            segmentIds = result.getSegmentIds();
        }

        log.info("Query done: kbId={}, totalCount={}", kbId, segmentIds.size());

        return segmentIds;
    }

    @Override
    public List<Long> queryAllSegmentIdsByDocId(Long kbId, Long tenantId, Long spaceId, Long docId) {
        log.info("List segment IDs (kb/doc): kbId={}, tenantId={}, spaceId={}, docId={}", kbId, tenantId, spaceId, docId);

        // 构建查询参数
        SegmentIdsParams params = new SegmentIdsParams();
        params.setTenantId(tenantId);
        params.setKbId(kbId);
        if (spaceId != null) {
            params.setSpaceId(spaceId);
        }
        if (docId != null) {
            params.setDocId(docId);
        }

        // 调用 Client 查询
        SegmentIdsResult result = fullTextSearchClient.querySegmentIds(params);

        // 现在直接返回 Long 列表，不需要转换
        List<Long> segmentIds = Collections.emptyList();
        if (result != null && !CollectionUtils.isEmpty(result.getSegmentIds())) {
            segmentIds = result.getSegmentIds();
        }

        log.info("Query done: kbId={}, docId={}, totalCount={}", kbId, docId, segmentIds.size());

        return segmentIds;
    }

    /**
     * 转换统计结果
     */
    private FullTextStatsModel convertToStatsModel(KnowledgeStatsResult result) {
        FullTextStatsModel model = new FullTextStatsModel();

        // 基础信息（现在都是 Long 类型）
        model.setTenantId(result.getTenantId());
        model.setKbId(result.getKbId());
        model.setSpaceId(result.getSpaceId());

        // 统计数据
        model.setDocCount(result.getDocCount());
        model.setTotalSegments(result.getTotalSegments());
        model.setStatsTime(result.getStatsTime());

        // 文档统计
        if (!CollectionUtils.isEmpty(result.getDocStats())) {
            List<FullTextStatsModel.DocumentStatsModel> docStats = result.getDocStats().stream()
                    .map(docStat -> {
                        FullTextStatsModel.DocumentStatsModel statsModel = new FullTextStatsModel.DocumentStatsModel();
                        statsModel.setDocId(docStat.getDocId());
                        statsModel.setDocName(docStat.getDocName());
                        statsModel.setSegmentCount(docStat.getSegmentCount());
                        return statsModel;
                    })
                    .collect(Collectors.toList());
            model.setDocStats(docStats);
        }

        return model;
    }
}
