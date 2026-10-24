package com.xspaceagi.repo.application.search;

import com.xspaceagi.log.sdk.request.DocumentSearchRequest;
import com.xspaceagi.log.sdk.service.ISearchRpcService;
import com.xspaceagi.log.sdk.vo.SearchResult;
import com.xspaceagi.repo.domain.model.RepoPageDomain;
import com.xspaceagi.repo.spec.utils.HtmlTextUtils;
import com.xspaceagi.system.spec.common.RequestContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 文档页面搜索服务：封装 ES 索引写入/删除/检索。
 * <p>
 * 索引失败不阻断主流程（ES 不可用时仅告警，页面数据以 MySQL 为准）。
 * 检索时按当前租户过滤，空间级可见性由调用方负责后置过滤。
 */
@Slf4j
@Service
public class RepoPageSearchService {

    @Resource
    private ISearchRpcService iSearchRpcService;

    /**
     * 索引/更新单个页面（标题 + 正文纯文本）。
     */
    public void indexPage(RepoPageDomain page) {
        try {
            RepoPageSearchDocument document = toDocument(page);
            iSearchRpcService.bulkIndex(List.of(document));
        } catch (Exception e) {
            log.warn("repo page index failed, pageId={}, spaceId={}", page.getId(), page.getSpaceId(), e);
        }
    }

    /**
     * 批量删除页面索引（软删页面时调用）。
     */
    public void deletePages(List<Long> pageIds) {
        for (Long pageId : pageIds) {
            try {
                iSearchRpcService.deleteDocument(RepoPageSearchDocument.class, String.valueOf(pageId));
            } catch (Exception e) {
                log.warn("repo page index delete failed, pageId={}", pageId, e);
            }
        }
    }

    /**
     * 按关键字检索当前租户下的页面；spaceId 非空时限定空间。
     *
     * @param spaceId 空间ID，可空=全租户
     * @param keyword 关键字，可空=仅按过滤条件列出
     * @param from    分页起始，可空默认0
     * @param size    分页大小，可空默认20
     * @return 搜索结果（document 为 RepoPageSearchDocument）
     */
    public SearchResult search(Long spaceId, String keyword, Integer from, Integer size) {
        List<Map<String, Object>> filterFieldsAndValues = new ArrayList<>();
        filterFieldsAndValues.add(Map.of("tenantId", RequestContext.get().getTenantId()));
        if (spaceId != null) {
            filterFieldsAndValues.add(Map.of("spaceId", spaceId));
        }
        DocumentSearchRequest request = DocumentSearchRequest.builder()
                .searchDocumentClazz(RepoPageSearchDocument.class)
                .searchFields(List.of("title", "content"))
                .keyword(keyword)
                .filterFieldsAndValues(filterFieldsAndValues)
                .from(from == null ? 0 : from)
                .size(size == null ? 20 : size)
                .build();
        return iSearchRpcService.search(request);
    }

    private RepoPageSearchDocument toDocument(RepoPageDomain page) {
        RepoPageSearchDocument document = RepoPageSearchDocument.builder()
                .tenantId(page.getTenantId())
                .spaceId(page.getSpaceId())
                .pageId(page.getId())
                .slugId(page.getSlugId())
                .title(page.getTitle())
                .content(HtmlTextUtils.extractText(page.getHtmlSnapshot()))
                .lastEditorId(page.getLastEditorId())
                .editedAt(page.getEditedAt() == null ? null : page.getEditedAt().getTime())
                .build();
        document.setId(String.valueOf(page.getId()));
        return document;
    }
}
