package com.xspaceagi.knowledge.core.application.service.impl;

import com.xspaceagi.knowledge.core.adapter.client.KnowledgeFullTextSearchClient;
import com.xspaceagi.knowledge.core.adapter.client.dto.*;
import com.xspaceagi.knowledge.core.infra.rpc.SearchRpcService;
import com.xspaceagi.knowledge.core.infra.rpc.vo.KnowledgeSearchDocument;
import com.xspaceagi.log.sdk.request.DocumentSearchRequest;
import com.xspaceagi.log.sdk.vo.SearchResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识库全文检索 HTTP Client 实现
 *
 * <p>基于 OkHttp 实现与 Quickwit 服务的 HTTP 通信</p>
 *
 * @author system
 * @date 2025-03-31
 */
@Slf4j
@Component
public class KnowledgeFullTextSearchClientImpl implements KnowledgeFullTextSearchClient {

    @Resource
    private SearchRpcService searchRpcService;

    @Override
    public PushResult pushSegments(PushRequest request) {

        log.info("Push segments, segmentCount={}", request.getSegments() != null ? request.getSegments().size() : 0);
        searchRpcService.bulkIndex(request.getSegments().stream().map(segment -> KnowledgeSearchDocument.builder()
                .tenantId(segment.getTenantId())
                .id(segment.getRawId().toString())
                .docId(segment.getDocId())
                .kbId(segment.getKbId())
                .rawText(segment.getRawTxt())
                .build()).collect(Collectors.toList()));

        PushResult result = new PushResult();
        result.setIndexedCount(request.getSegments() != null ? request.getSegments().size() * 1L : 0);
        result.setSuccessRawIds(request.getSegments() != null ? request.getSegments().stream().map(KnowledgeRawSegment::getRawId).collect(Collectors.toList()) : Collections.emptyList());
        log.info("Push done: indexedCount={}", result != null ? result.getIndexedCount() : 0);
        return result;
    }

    @Override
    public KnowledgeSearchResult search(KnowledgeSearchParams params) {


        log.info("Full-text: query={}, tenantId={}, kbIds={} (type: {})",
                params.getQuery(), params.getTenantId(), params.getKbIds(),
                params.getKbIds() != null && !params.getKbIds().isEmpty() ? params.getKbIds().get(0).getClass().getSimpleName() : "null");


        List<Map<String, Object>> filterFieldsAndValues = new ArrayList<>();
        if (params.getDocIds() != null && params.getDocIds().size() > 0) {
            filterFieldsAndValues.add(Map.of("docId", params.getDocIds()));
        }
        filterFieldsAndValues.add(Map.of("tenantId", params.getTenantId()));
        if (params.getKbIds() != null && params.getKbIds().size() > 0) {
            filterFieldsAndValues.add(Map.of("kbId", params.getKbIds()));
        }

        DocumentSearchRequest documentSearchRequest = DocumentSearchRequest.builder()
                .searchDocumentClazz(KnowledgeSearchDocument.class)
                .searchFields(List.of("rawText"))
                .keyword(params.getQuery()).filterFieldsAndValues(filterFieldsAndValues).build();

        SearchResult search = searchRpcService.search(documentSearchRequest);

        KnowledgeSearchResult result = new KnowledgeSearchResult();
        result.setResults(search.getItems().stream().map(item -> {
            KnowledgeSearchDocument document = (KnowledgeSearchDocument) item.getDocument();
            KnowledgeSearchHit hit = new KnowledgeSearchHit();
            hit.setId(document.getId());
            hit.setRawId(Long.parseLong(document.getId()));
            hit.setKbId(document.getKbId());
            hit.setDocId(document.getDocId());
            hit.setRawTxt(document.getRawText());
            hit.setScore(item.getScore().floatValue());
            hit.setTenantId(document.getTenantId());
            return hit;
        }).collect(Collectors.toList()));


        Long total = result != null ? result.getTotal() : 0L;
        Long tookMs = result != null ? result.getTookMs() : null;
        log.info("Search done: total={}, tookMs={}", total, tookMs != null ? tookMs : 0);

        return result;
    }

    @Override
    public DeleteResult deleteSegments(DeleteParams params) {

        log.info("Delete data: tenantId={}, kbIds={}, docIds={}",
                params.getTenantId(), params.getKbId(), params.getDocId());
        params.getRawIds().forEach(rawId -> {
            log.info("Delete data: rawId={}", rawId);
            searchRpcService.deleteDocument(KnowledgeSearchDocument.class, rawId.toString());
        });
        DeleteResult result = new DeleteResult();
        result.setDeletedCount(params.getRawIds().size() * 1L);
        log.info("Delete done: deletedCount={}", result != null ? result.getDeletedCount() : 0);

        return result;
    }

    @Override
    public UpdateResult updateSegment(UpdateRequest request) {

        log.info("Update segment: rawId={}, tenantId={}",
                request.getRawId(), request.getTenantId());
        return new UpdateResult();
    }

    @Override
    public KnowledgeStatsResult getStats(StatsParams params) {
        return new KnowledgeStatsResult();
    }

    @Override
    public ClearResult clearAll() {
        return new ClearResult();
    }

    @Override
    public boolean createIndex() {
        return false;
    }

    @Override
    public SegmentIdsResult querySegmentIds(SegmentIdsParams params) {
        log.info("List segment IDs: tenantId={}, kbId={}, spaceId={}, docId={}",
                params.getTenantId(), params.getKbId(), params.getSpaceId(), params.getDocId());

        List<Map<String, Object>> filterFieldsAndValues = new ArrayList<>();
        filterFieldsAndValues.add(Map.of("tenantId", params.getTenantId()));
        if (params.getKbId() != null) {
            filterFieldsAndValues.add(Map.of("kbId", params.getKbId()));
        }
        if (params.getDocId() != null) {
            filterFieldsAndValues.add(Map.of("docId", params.getDocId()));
        }

        DocumentSearchRequest documentSearchRequest = DocumentSearchRequest.builder()
                .searchDocumentClazz(KnowledgeSearchDocument.class)
                .size(10000)
                .filterFieldsAndValues(filterFieldsAndValues).build();
        SearchResult searchResult = searchRpcService.search(documentSearchRequest);
        SegmentIdsResult result = new SegmentIdsResult();
        result.setSegmentIds(searchResult.getItems().stream().map(item -> {
            KnowledgeSearchDocument document = (KnowledgeSearchDocument) item.getDocument();
            return Long.parseLong(document.getId());
        }).collect(Collectors.toList()));
        return result;
    }
}
