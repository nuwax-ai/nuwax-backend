package com.xspaceagi.repo.application.rpc;

import com.xspaceagi.log.sdk.vo.SearchResult;
import com.xspaceagi.repo.application.search.RepoPageSearchDocument;
import com.xspaceagi.repo.application.service.RepoPageApplicationService;
import com.xspaceagi.repo.domain.model.RepoPageDomain;
import com.xspaceagi.repo.domain.model.RepoPageTreeNode;
import com.xspaceagi.repo.sdk.IRepoPageRpcService;
import com.xspaceagi.repo.sdk.dto.RepoPageCopyRequest;
import com.xspaceagi.repo.sdk.dto.RepoPageCreateRequest;
import com.xspaceagi.repo.sdk.dto.RepoPageDeleteRequest;
import com.xspaceagi.repo.sdk.dto.RepoPageGetBySlugRequest;
import com.xspaceagi.repo.sdk.dto.RepoPageGetRequest;
import com.xspaceagi.repo.sdk.dto.RepoPageListChildrenRequest;
import com.xspaceagi.repo.sdk.dto.RepoPageListRootRequest;
import com.xspaceagi.repo.sdk.dto.RepoPageMoveRequest;
import com.xspaceagi.repo.sdk.dto.RepoPageRecentRequest;
import com.xspaceagi.repo.sdk.dto.RepoPageRenameRequest;
import com.xspaceagi.repo.sdk.dto.RepoPageRpcDto;
import com.xspaceagi.repo.sdk.dto.RepoPageRpcRequest;
import com.xspaceagi.repo.sdk.dto.RepoPageSearchRequest;
import com.xspaceagi.repo.sdk.dto.RepoPageSearchRpcResult;
import com.xspaceagi.repo.sdk.dto.RepoPageSnapshotRequest;
import com.xspaceagi.repo.sdk.dto.RepoPageTreeRequest;
import com.xspaceagi.repo.sdk.dto.RepoPageTreeNodeRpcDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import jakarta.annotation.Resource;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 资料库「目录 + 文档」管理 RPC 实现：以请求体里调用方显式传入的 userId + tenantId 切换 {@link RequestContext} 后
 * 复用 {@link RepoPageApplicationService}（权限裁决/租户隔离语义与 HTTP 入口一致），并做 domain→SDK 投影。
 */
@Service
public class RepoPageRpcServiceImpl implements IRepoPageRpcService {

    @Resource
    private RepoPageApplicationService repoPageApplicationService;

    @Override
    public RepoPageRpcDto getPage(RepoPageGetRequest request) {
        return runAs(request, () -> toRpcDto(repoPageApplicationService.getPage(request.getPageId())));
    }

    @Override
    public RepoPageRpcDto getPageBySlug(RepoPageGetBySlugRequest request) {
        return runAs(request, () -> toRpcDto(repoPageApplicationService.getPageBySlug(request.getSlugId())));
    }

    @Override
    public List<RepoPageRpcDto> listSpaceRootPages(RepoPageListRootRequest request) {
        return runAs(request, () -> toRpcDtos(repoPageApplicationService.listSpaceRootPages(request.getSpaceId())));
    }

    @Override
    public RepoPageRpcDto createPage(RepoPageCreateRequest request) {
        return runAs(request, () -> toRpcDto(repoPageApplicationService.createPage(
                request.getSpaceId(), request.getParentId(), request.getTitle(), request.getPageType(),
                request.getSourceExt(), request.getOriginalFileKey())));
    }

    @Override
    public RepoPageRpcDto copyPage(RepoPageCopyRequest request) {
        return runAs(request, () -> toRpcDto(repoPageApplicationService.copyPage(
                request.getPageId(), null, request.getTargetSpaceId(), request.getTargetParentId())));
    }

    @Override
    public RepoPageRpcDto renamePage(RepoPageRenameRequest request) {
        return runAs(request, () -> toRpcDto(repoPageApplicationService.renamePage(request.getPageId(), request.getTitle())));
    }

    @Override
    public void deletePage(RepoPageDeleteRequest request) {
        runAs(request, () -> repoPageApplicationService.deletePage(request.getPageId()));
    }

    @Override
    public RepoPageRpcDto movePage(RepoPageMoveRequest request) {
        return runAs(request, () -> toRpcDto(repoPageApplicationService.movePage(
                request.getPageId(), request.getTargetParentId(), request.getBeforeId(), request.getAfterId())));
    }

    @Override
    public List<RepoPageRpcDto> listChildren(RepoPageListChildrenRequest request) {
        return runAs(request, () -> toRpcDtos(repoPageApplicationService.listChildren(request.getPageId())));
    }

    @Override
    public List<RepoPageTreeNodeRpcDto> getSpaceTree(RepoPageTreeRequest request) {
        return runAs(request, () -> repoPageApplicationService.getSpaceTree(request.getSpaceId()).stream()
                .map(this::toTreeNodeRpcDto)
                .collect(Collectors.toList()));
    }

    @Override
    public RepoPageRpcDto updateSnapshot(RepoPageSnapshotRequest request) {
        return runAs(request, () -> toRpcDto(repoPageApplicationService.updateSnapshot(
                request.getPageId(), request.getHtml(), request.getContentJson())));
    }

    @Override
    public RepoPageSearchRpcResult searchPages(RepoPageSearchRequest request) {
        return runAs(request, () -> toSearchRpcResult(repoPageApplicationService.searchPages(
                request.getSpaceId(), request.getKeyword(), request.getFrom(), request.getSize())));
    }

    @Override
    public List<RepoPageRpcDto> getRecentPages(RepoPageRecentRequest request) {
        return runAs(request, () -> toRpcDtos(repoPageApplicationService.getRecentPages(request.getSize())));
    }

    // ------------------------------------------------------------------ 上下文切换

    private <T> T runAs(RepoPageRpcRequest request, Supplier<T> action) {
        return runAs(request.getUserId(), request.getTenantId(), action);
    }

    private void runAs(RepoPageRpcRequest request, Runnable action) {
        runAs(request.getUserId(), request.getTenantId(), action);
    }

    private <T> T runAs(Long userId, Long tenantId, Supplier<T> action) {
        RequestContext<?> prev = RequestContext.get();
        RequestContext<?> ctx = buildContext(userId, tenantId);
        RequestContext.set(ctx);
        try {
            return action.get();
        } finally {
            if (prev == null) {
                RequestContext.remove();
            } else {
                RequestContext.set(prev);
            }
        }
    }

    private void runAs(Long userId, Long tenantId, Runnable action) {
        RequestContext<?> prev = RequestContext.get();
        RequestContext<?> ctx = buildContext(userId, tenantId);
        RequestContext.set(ctx);
        try {
            action.run();
        } finally {
            if (prev == null) {
                RequestContext.remove();
            } else {
                RequestContext.set(prev);
            }
        }
    }

    private RequestContext<?> buildContext(Long userId, Long tenantId) {
        if (userId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemUserIdInvalid);
        }
        if (tenantId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentRequiredParamEmpty);
        }
        RequestContext<?> ctx = new RequestContext<>();
        ctx.setUserId(userId);
        ctx.setTenantId(tenantId);
        ctx.setLogin(true);
        return ctx;
    }

    // ------------------------------------------------------------------ 投影转换

    private RepoPageRpcDto toRpcDto(RepoPageDomain domain) {
        if (domain == null) {
            return null;
        }
        RepoPageRpcDto dto = new RepoPageRpcDto();
        BeanUtils.copyProperties(domain, dto);
        return dto;
    }

    private List<RepoPageRpcDto> toRpcDtos(List<RepoPageDomain> domains) {
        if (domains == null) {
            return new ArrayList<>();
        }
        return domains.stream().map(this::toRpcDto).collect(Collectors.toList());
    }

    private RepoPageTreeNodeRpcDto toTreeNodeRpcDto(RepoPageTreeNode node) {
        if (node == null) {
            return null;
        }
        RepoPageTreeNodeRpcDto dto = new RepoPageTreeNodeRpcDto();
        dto.setPage(toRpcDto(node.getPage()));
        if (node.getChildren() != null) {
            dto.setChildren(node.getChildren().stream().map(this::toTreeNodeRpcDto).collect(Collectors.toList()));
        }
        return dto;
    }

    private RepoPageSearchRpcResult toSearchRpcResult(SearchResult result) {
        List<RepoPageSearchRpcResult.RepoPageSearchRpcItem> items = new ArrayList<>();
        if (result != null && result.getItems() != null) {
            for (SearchResult.SearchResultItem item : result.getItems()) {
                if (item.getDocument() instanceof RepoPageSearchDocument doc) {
                    items.add(RepoPageSearchRpcResult.RepoPageSearchRpcItem.builder()
                            .score(item.getScore())
                            .spaceId(doc.getSpaceId())
                            .pageId(doc.getPageId())
                            .slugId(doc.getSlugId())
                            .title(doc.getTitle())
                            .content(doc.getContent())
                            .lastEditorId(doc.getLastEditorId())
                            .editedAt(doc.getEditedAt())
                            .build());
                }
            }
        }
        return RepoPageSearchRpcResult.builder()
                .total(result == null ? 0L : result.getTotal())
                .items(items)
                .build();
    }
}
