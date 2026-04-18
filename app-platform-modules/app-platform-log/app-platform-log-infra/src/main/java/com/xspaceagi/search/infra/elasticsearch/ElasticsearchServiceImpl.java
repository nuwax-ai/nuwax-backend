package com.xspaceagi.search.infra.elasticsearch;

import cn.hutool.core.collection.ConcurrentHashSet;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.*;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.xspaceagi.log.sdk.annotation.SearchField;
import com.xspaceagi.log.sdk.annotation.SearchIndex;
import com.xspaceagi.log.sdk.request.DocumentSearchRequest;
import com.xspaceagi.log.sdk.service.ISearchRpcService;
import com.xspaceagi.log.sdk.vo.SearchDocument;
import com.xspaceagi.log.sdk.vo.SearchResult;
import com.xspaceagi.search.infra.SearchService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ElasticsearchServiceImpl implements SearchService, ISearchRpcService {

    private final Set<String> indexNameSet = new ConcurrentHashSet<>();

    @Value("${search.elasticsearch.url}")
    private String esUrl;

    @Value("${search.elasticsearch.api_key:}")
    private String apiKey;

    @Value("${search.elasticsearch.username:}")
    private String username;

    @Value("${search.elasticsearch.password:}")
    private String password;

    private ElasticsearchClient client;

    @PostConstruct
    private void init() {
        if (StringUtils.isNotBlank(username) && StringUtils.isNotBlank(password)) {
            client = ElasticsearchClient.of(b -> b
                    .host(esUrl)
                    .usernameAndPassword(username, password)
            );
        } else {
            client = ElasticsearchClient.of(b -> b
                    .host(esUrl)
                    .apiKey(apiKey)
            );
        }
    }

    @PreDestroy
    private void destroy() {
        client.shutdown();
    }

    @Override
    public void bulkIndex(List<SearchDocument> list) {
        Assert.noNullElements(list, "list cannot be left blank.");
        String finalIndexName = createIndexIfNotExists(list.get(0).getClass());
        BulkRequest.Builder br = new BulkRequest.Builder();
        for (SearchDocument object : list) {
            log.info("构建文档索引, id {}, finalIndexName {}", object.getId(), finalIndexName);
            br.operations(op -> op.index(idx -> idx.index(finalIndexName).id(object.getId()).document(object)));
        }

        BulkResponse result;
        try {
            result = client.bulk(br.build());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        if (result.errors()) {
            throw new RuntimeException("Failed to batch build indexes.");
        }
    }

    private String createIndexIfNotExists(Class<? extends SearchDocument> searchDocumentClazz) {
        Assert.notNull(searchDocumentClazz, "searchDocumentClazz cannot be left blank.");
        SearchIndex annotation = searchDocumentClazz.getAnnotation(SearchIndex.class);
        String indexName = searchDocumentClazz.getSimpleName();
        int shards = 3;
        int replicas = 1;
        if (annotation != null) {
            indexName = annotation.indexName();
            shards = annotation.shards();
            replicas = annotation.replicas();
        }
        if (indexNameSet.contains(indexName)) {
            return indexName;
        }
        final String finalIndexName = indexName;
        final int finalShards = shards;
        final int finalReplicas = replicas;
        synchronized (indexNameSet) {
            BooleanResponse exists;
            try {
                exists = client.indices().exists(request -> request.index(finalIndexName));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            if (!exists.value()) {
                try {
                    log.info("创建索引, indexName {}", finalIndexName);
                    client.indices().create(request -> request.index(finalIndexName)
                            .settings(builder -> builder.numberOfShards(String.valueOf(finalShards)).numberOfReplicas(String.valueOf(finalReplicas)))
                            .mappings(builder -> {
                                Field[] declaredFields = searchDocumentClazz.getDeclaredFields();
                                for (Field declaredField : declaredFields) {
                                    AtomicBoolean index = new AtomicBoolean(true);
                                    AtomicBoolean keyword = new AtomicBoolean(false);
                                    AtomicBoolean store = new AtomicBoolean(false);
                                    AtomicReference<String> name = new AtomicReference<>(declaredField.getName());
                                    SearchField fieldAnnotation = declaredField.getAnnotation(SearchField.class);
                                    if (fieldAnnotation != null) {
                                        index.set(fieldAnnotation.index());
                                        keyword.set(fieldAnnotation.keyword());
                                        store.set(fieldAnnotation.store());
                                    }
                                    JsonProperty annotation1 = declaredField.getAnnotation(JsonProperty.class);
                                    if (annotation1 != null) {
                                        name.set(annotation1.value());
                                    }
                                    if (declaredField.getType() == String.class) {
                                        if (keyword.get()) {
                                            builder.properties(name.get(), property -> property.keyword(kw -> kw.index(index.get()).store(store.get())));
                                        } else {
                                            builder.properties(name.get(), property -> property.text(text -> text.analyzer("ik_max_word")
                                                    .searchAnalyzer("ik_smart").store(store.get()).index(index.get())
                                            ));
                                        }
                                    } else if (declaredField.getType() == Integer.class) {
                                        builder.properties(name.get(), property -> property.integer(integer -> integer.index(index.get()).store(store.get())));
                                    } else if (declaredField.getType() == Long.class) {
                                        builder.properties(name.get(), property -> property.long_(long_ -> long_.index(index.get()).store(store.get())));
                                    } else if (declaredField.getType() == Double.class || declaredField.getType() == BigDecimal.class) {
                                        builder.properties(name.get(), property -> property.double_(double_ -> double_.index(index.get()).store(store.get())));
                                    } else if (declaredField.getType() == Float.class) {
                                        builder.properties(name.get(), property -> property.float_(float_ -> float_.index(index.get()).store(store.get())));
                                    } else if (declaredField.getType() == Boolean.class) {
                                        builder.properties(name.get(), property -> property.boolean_(boolean_ -> boolean_.index(index.get()).store(store.get())));
                                    } else if (declaredField.getType() == Date.class) {
                                        builder.properties(name.get(), property -> property.date(date -> date.index(index.get()).store(store.get())));
                                    } else {
                                        builder.properties(name.get(), property -> property.text(text -> text.analyzer("ik_max_word")
                                                .searchAnalyzer("ik_smart").store(store.get()).index(index.get())
                                        ));
                                    }
                                }
                                return builder;
                            })
                    );
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            indexNameSet.add(finalIndexName);
        }
        return finalIndexName;
    }

    @Override
    public void deleteDocument(Class<? extends SearchDocument> searchDocumentClazz, String id) {
        String indexName = createIndexIfNotExists(searchDocumentClazz);
        log.info("删除文档, indexName {}, id {}", indexName, id);
        DeleteRequest request = new DeleteRequest.Builder().index(indexName).id(id).build();
        try {
            client.delete(request);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public SearchResult search(DocumentSearchRequest documentSearchRequest) {
        if (documentSearchRequest == null) {
            throw new IllegalArgumentException("Search parameters cannot be empty");
        }
        log.info("搜索文档, documentSearchRequest {}", documentSearchRequest);
        Integer from = documentSearchRequest.getFrom();
        Integer size = documentSearchRequest.getSize();
        if (from == null || from < 0) {
            from = 0;
        }
        if (size == null || size <= 0) {
            size = 10;
        }
        String indexName = createIndexIfNotExists(documentSearchRequest.getSearchDocumentClazz());
        SearchRequest.Builder requestBuilder = new SearchRequest.Builder().index(indexName).query(q -> {
                    q.bool(bool -> {
                        if (CollectionUtils.isNotEmpty(documentSearchRequest.getFilterFieldsAndValues())) {
                            for (Map<String, Object> filterFieldAndValue : documentSearchRequest.getFilterFieldsAndValues()) {
                                bool.filter(f -> {
                                    for (Map.Entry<String, Object> entry : filterFieldAndValue.entrySet()) {
                                        if (entry.getValue() instanceof List<?>) {
                                            f.terms(TermsQuery.of(t -> t.field(entry.getKey()).terms(
                                                    TermsQueryField.of(t1 -> t1.value(((List<?>) entry.getValue()).stream().map(m -> FieldValue.of(m)).collect(Collectors.toList())))
                                            )));
                                        } else if (entry.getValue() instanceof Map<?, ?> && ((Map<?, ?>) entry.getValue()).containsKey("express")) {
                                            Object express = ((Map<?, ?>) entry.getValue()).get("express");
                                            if (express != null) {
                                                if (express.equals("range")) {
                                                    f.range(RangeQuery.of(r -> r.term(TermRangeQuery.of(t -> {
                                                        t.field(entry.getKey());
                                                        Object gt = ((Map<?, ?>) entry.getValue()).get("gt");
                                                        if (gt != null) {
                                                            t.gt(gt.toString());
                                                        }
                                                        Object lt = ((Map<?, ?>) entry.getValue()).get("lt");
                                                        if (lt != null) {
                                                            t.lt(lt.toString());
                                                        }
                                                        return t;
                                                    }))));
                                                }
                                                if (express.equals("match")) {
                                                    f.match(MatchQuery.of(m -> m.field(entry.getKey()).query(((Map<?, ?>) entry.getValue()).get("query").toString())));
                                                }
                                            }
                                        } else {
                                            f.term(TermQuery.of(t -> t.field(entry.getKey()).value(FieldValue.of(entry.getValue()))));
                                        }
                                    }
                                    return f;
                                });
                            }
                        }
                        if (CollectionUtils.isNotEmpty(documentSearchRequest.getSearchFields()) && StringUtils.isNotBlank(documentSearchRequest.getKeyword())) {
                            bool.must(m1 -> m1.multiMatch(m -> m.query(documentSearchRequest.getKeyword()).fields(documentSearchRequest.getSearchFields())));
                        }
                        return bool;
                    });

                    return q;
                })
                .from(from)
                .size(size);
        if (documentSearchRequest.getSortFieldsAndValues() != null) {
            requestBuilder.sort(s -> {
                for (Map.Entry<String, Object> entry : documentSearchRequest.getSortFieldsAndValues().entrySet()) {
                    s.field(f -> f.field(entry.getKey()).order(SortOrder.valueOf(entry.getValue().toString())));
                }
                return s;
            });
        }
        try {
            SearchResponse<?> response = client.search(requestBuilder.build(), documentSearchRequest.getSearchDocumentClazz());
            List<SearchResult.SearchResultItem> collect = response.hits().hits().stream().map(hit -> SearchResult.SearchResultItem.builder()
                    .score(hit.score())
                    .document((SearchDocument) hit.source())
                    .build()).collect(Collectors.toList());
            log.info("搜索结果, total {}, items {}", response.hits().total().value(), collect);
            return SearchResult.builder()
                    .total(response.hits().total().value())
                    .items(collect)
                    .build();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
