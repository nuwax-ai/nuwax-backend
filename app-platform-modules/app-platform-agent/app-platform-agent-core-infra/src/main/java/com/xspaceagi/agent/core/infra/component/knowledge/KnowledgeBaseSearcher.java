package com.xspaceagi.agent.core.infra.component.knowledge;

import com.xspaceagi.agent.core.infra.component.BaseComponent;
import com.xspaceagi.agent.core.infra.component.agent.AgentContext;
import com.xspaceagi.agent.core.infra.component.knowledge.dto.QueryText;
import com.xspaceagi.agent.core.spec.enums.SearchStrategyEnum;
import com.xspaceagi.file.sdk.IFileAccessService;
import com.xspaceagi.knowledge.sdk.request.KnowledgeFullTextSearchRequestVo;
import com.xspaceagi.knowledge.sdk.request.KnowledgeQaRequestVo;
import com.xspaceagi.knowledge.sdk.response.KnowledgeFullTextSearchResponseVo;
import com.xspaceagi.knowledge.sdk.response.KnowledgeFullTextSearchResultVo;
import com.xspaceagi.knowledge.sdk.response.KnowledgeQaResponseVo;
import com.xspaceagi.knowledge.sdk.response.KnowledgeQaVo;
import com.xspaceagi.knowledge.sdk.sevice.IKnowledgeDocumentSearchRpcService;
import com.xspaceagi.knowledge.sdk.sevice.IKnowledgeFullTextSearchRpcService;
import com.xspaceagi.knowledge.sdk.sevice.IKnowledgeQaSearchRpcService;
import com.xspaceagi.knowledge.sdk.vo.DocumentAddRequestVo;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
public class KnowledgeBaseSearcher extends BaseComponent {

    private IKnowledgeQaSearchRpcService knowledgeQaSearchRpcService;

    private IKnowledgeFullTextSearchRpcService knowledgeFullTextSearchRpcService;

    @Resource
    private IKnowledgeDocumentSearchRpcService iKnowledgeDocumentSearchRpcService;

    private IFileAccessService iFileAccessService;

    @Autowired
    public void setKnowledgeQaSearchRpcService(IKnowledgeQaSearchRpcService knowledgeQaSearchRpcService) {
        this.knowledgeQaSearchRpcService = knowledgeQaSearchRpcService;
    }

    @Autowired
    public void setKnowledgeFullTextSearchRpcService(IKnowledgeFullTextSearchRpcService knowledgeFullTextSearchRpcService) {
        this.knowledgeFullTextSearchRpcService = knowledgeFullTextSearchRpcService;
    }

    @Autowired
    public void setIFileAccessService(IFileAccessService iFileAccessService) {
        this.iFileAccessService = iFileAccessService;
    }

    public Mono<List<KnowledgeQaVo>> search(SearchContext searchContext) {
        return Mono.create(sink -> submit(() -> {
            try {
                List<KnowledgeQaVo> qaVoList = new Vector<>();
                List<QueryText> queryList = new ArrayList<>();
                if (searchContext.getSearchStrategy() == SearchStrategyEnum.MIXED) {
                    queryList.add(QueryText.builder().text(searchContext.getQuery()).searchStrategy(SearchStrategyEnum.SEMANTIC).build());
                    queryList.add(QueryText.builder().text(searchContext.getQuery()).searchStrategy(SearchStrategyEnum.FULL_TEXT).build());
                } else {
                    queryList.add(QueryText.builder().text(searchContext.getQuery()).searchStrategy(searchContext.getSearchStrategy()).build());
                }
                AtomicReference<Throwable> throwableAtomicReference = new AtomicReference<>();
                List<KnowledgeFullTextSearchResultVo> results = new ArrayList<>();
                //使用多线程并行调用queryList
                queryList.parallelStream().forEach(query -> {
                    log.debug("query: {}", query);
                    if (query.getSearchStrategy() == SearchStrategyEnum.SEMANTIC) {
                        searchContext.getKnowledgeBaseIds().parallelStream().forEach(knowledgeBaseId -> {
                            log.debug("search knowledgeBaseId: {}", knowledgeBaseId);
                            KnowledgeQaRequestVo knowledgeQaRequestVo = new KnowledgeQaRequestVo();
                            knowledgeQaRequestVo.setQuestion(query.getText());
                            knowledgeQaRequestVo.setTopK(searchContext.getMaxRecallCount());
                            knowledgeQaRequestVo.setIgnoreDocStatus(true);
                            knowledgeQaRequestVo.setKbId(knowledgeBaseId);
                            knowledgeQaRequestVo.setIgnoreTenantId(true);
                            try {
                                KnowledgeQaResponseVo knowledgeQaResponseVo = TenantFunctions.callWithIgnoreCheck(() -> knowledgeQaSearchRpcService.search(knowledgeQaRequestVo));
                                qaVoList.addAll(knowledgeQaResponseVo.getQaVoList());
                            } catch (Exception e) {
                                // 忽略
                                log.error("query error: {}", e.getMessage());
                                throwableAtomicReference.set(e);
                            }
                        });
                    } else if (query.getSearchStrategy() == SearchStrategyEnum.FULL_TEXT) {
                        KnowledgeFullTextSearchRequestVo requestVo = new KnowledgeFullTextSearchRequestVo();
                        requestVo.setQueryText(query.getText());
                        requestVo.setKbIds(searchContext.getKnowledgeBaseIds());
                        requestVo.setTopK(searchContext.getMaxRecallCount());
                        requestVo.setTenantId(searchContext.getAgentContext().getUser().getTenantId());
                        try {
                            KnowledgeFullTextSearchResponseVo knowledgeFullTextSearchResponseVo = TenantFunctions.callWithIgnoreCheck(() -> knowledgeFullTextSearchRpcService.search(requestVo));
                            if (knowledgeFullTextSearchResponseVo.getResults() != null) {
                                results.addAll(knowledgeFullTextSearchResponseVo.getResults());
                            }
                        } catch (Exception e) {
                            // 忽略
                            log.error("search error: {}", e.getMessage());
                            throwableAtomicReference.set(e);
                        }
                    }
                });

                //优先使用向量库返回的内容
                List<KnowledgeQaVo> newQaVoList = qaVoList.stream().filter(qaVo -> qaVo.getScore() > searchContext.getMatchingDegree()).collect(Collectors.toList());
                //根据得分排序
                newQaVoList.sort((o1, o2) -> Double.compare(o2.getScore(), o1.getScore()));
                List<KnowledgeQaVo> finalQaVoList = new ArrayList<>();
                if (newQaVoList.size() > searchContext.getMaxRecallCount()) {
                    finalQaVoList.addAll(newQaVoList.subList(0, searchContext.getMaxRecallCount()));
                } else {
                    finalQaVoList.addAll(newQaVoList);
                }

                Map<String, KnowledgeQaVo> rawTextMap = finalQaVoList.stream().collect(Collectors.toMap(qaVo -> qaVo.getRawTxt(), qaVo -> qaVo, (v1, v2) -> v1));
                Set<String> rawTextSet = new HashSet<>();
                results.removeIf(knowledgeFullTextSearchResultVo -> {
                    KnowledgeQaVo knowledgeQaVo = rawTextMap.get(knowledgeFullTextSearchResultVo.getRawText());
                    if (knowledgeQaVo != null) {
                        return true;
                    }
                    if (rawTextSet.contains(knowledgeFullTextSearchResultVo.getRawText())) {
                        return true;
                    }
                    rawTextSet.add(knowledgeFullTextSearchResultVo.getRawText());
                    return false;
                });

                int leftCount = searchContext.getMaxRecallCount() - finalQaVoList.size();
                if (leftCount > 0) {
                    List<KnowledgeFullTextSearchResultVo> finalResults;
                    if (results.size() > leftCount) {
                        finalResults = results.subList(0, leftCount);
                    } else {
                        finalResults = results;
                    }
                    finalResults.forEach(knowledgeFullTextSearchResultVo -> {
                        KnowledgeQaVo qaVo = new KnowledgeQaVo();
                        qaVo.setQaId(-1L);
                        qaVo.setQuestion("");
                        qaVo.setKbId(knowledgeFullTextSearchResultVo.getKbId());
                        qaVo.setDocId(knowledgeFullTextSearchResultVo.getDocId());
                        qaVo.setAnswer("");
                        qaVo.setRawTxt(knowledgeFullTextSearchResultVo.getRawText());
                        qaVo.setScore(searchContext.getMatchingDegree());
                        finalQaVoList.add(qaVo);
                    });
                }
                finalQaVoList.forEach(qaVo -> {
                    qaVo.setAnswer(completeFileUrlWithAk(searchContext.getAgentContext(), qaVo.getAnswer()));
                    qaVo.setRawTxt(completeFileUrlWithAk(searchContext.getAgentContext(), qaVo.getRawTxt()));
                });
                if (finalQaVoList.isEmpty() && throwableAtomicReference.get() != null) {
                    sink.error(throwableAtomicReference.get());
                    return;
                }

                List<KnowledgeQaVo> result = new ArrayList<>();

                if(finalQaVoList != null) {
                    Map<String, String> dd = new HashMap<>();
                    for(KnowledgeQaVo entry : finalQaVoList) {
                        if(entry.getRawTxt() != null && entry.getRawTxt().length() > 0 && !dd.containsKey(entry.getRawTxt())) {
                            result.add(entry);
                            dd.put(entry.getRawTxt(), "");
                        }
                    }
                }

                //sink.success(finalQaVoList);
                sink.success(result);
            } catch (Throwable e) {
                log.error("search error", e);
                sink.error(e);
            }
        }));
    }

    //将文件地址补上AK
    public String completeFileUrlWithAk(AgentContext agentContext, String text) {
        //从文本中提取所有URL
        if (text != null) {
            List<String> urls = extractUrls(text);
            for (String url : urls) {
                String fileUrlWithAk;
                if (agentContext != null && agentContext.getTenantConfig() != null) {
                    fileUrlWithAk = iFileAccessService.getFileUrlWithAk(agentContext.getTenantConfig().getSiteUrl(), url);
                } else {
                    fileUrlWithAk = iFileAccessService.getFileUrlWithAk(url);
                }
                text = text.replace(url, fileUrlWithAk);
            }
        }

        return text;
    }

    public Long documentAdd(DocumentAddRequestVo documentAddRequestVo) {
        return iKnowledgeDocumentSearchRpcService.documentAdd(documentAddRequestVo);
    }

    // 预编译一次，性能更好
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[\\w.-]+(?:\\.[\\w.-]+)+[/\\w .-?&%=]*",
            Pattern.CASE_INSENSITIVE);

    /**
     * 从任意文本中提取所有 URL（去重、保持原始顺序）
     */
    public static List<String> extractUrls(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> seen = new LinkedHashSet<>();  // LinkedHashSet 去重且保序
        Matcher m = URL_PATTERN.matcher(text);
        while (m.find()) {
            seen.add(m.group());
        }
        return new ArrayList<>(seen);
    }
}
