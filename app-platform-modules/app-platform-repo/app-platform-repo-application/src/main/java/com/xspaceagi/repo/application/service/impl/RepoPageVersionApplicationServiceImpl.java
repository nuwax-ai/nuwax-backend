package com.xspaceagi.repo.application.service.impl;

import com.xspaceagi.repo.application.service.RepoAccessService;
import com.xspaceagi.repo.application.service.RepoPageVersionApplicationService;
import com.xspaceagi.repo.application.service.dto.RepoPageVersionListResult;
import com.xspaceagi.repo.domain.model.RepoPageDomain;
import com.xspaceagi.repo.domain.model.RepoPageVersionDomain;
import com.xspaceagi.repo.domain.repository.RepoPageRepository;
import com.xspaceagi.repo.domain.repository.RepoPageVersionRepository;
import com.xspaceagi.repo.spec.constants.RepoConstants;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * 文档版本应用服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepoPageVersionApplicationServiceImpl implements RepoPageVersionApplicationService {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private static final int MAX_PAGE_SIZE = 100;

    private final RepoPageRepository repoPageRepository;

    private final RepoPageVersionRepository repoPageVersionRepository;

    private final RepoAccessService repoAccessService;

    @Override
    public RepoPageVersionListResult listVersions(Long pageId, Integer page, Integer size) {
        mustGetViewablePage(pageId);
        int pageNum = page == null || page < 1 ? 1 : page;
        int pageSize = size == null || size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        long total = repoPageVersionRepository.countByPageId(pageId);
        List<RepoPageVersionDomain> items = total == 0
                ? new ArrayList<>()
                : repoPageVersionRepository.listByPageId(pageId, (pageNum - 1) * pageSize, pageSize);
        return RepoPageVersionListResult.builder().total(total).items(items).build();
    }

    @Override
    public RepoPageVersionDomain getVersionPreview(Long pageId, Long versionId) {
        mustGetViewablePage(pageId);
        RepoPageVersionDomain version = mustGetVersion(pageId, versionId);
        // 预览只需 HTML，二进制快照不外传
        version.setYdocSnapshot(null);
        return version;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RepoPageVersionDomain restoreVersion(Long pageId, Long versionId) {
        mustGetEditablePage(pageId);
        RepoPageVersionDomain version = mustGetVersion(pageId, versionId);

        Long userId = RequestContext.get().getUserId();
        Date now = new Date();
        // 覆盖 CRDT 权威源，下次 Sidecar 加载文档即生效
        if (!repoPageRepository.updateYdocState(pageId, version.getYdocSnapshot(), userId, now)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.resourceDataNotFound);
        }
        // 追加 restore 版本记录（快照内容与源版本一致，编辑人记为操作者）
        RepoPageVersionDomain restore = RepoPageVersionDomain.builder()
                .tenantId(RequestContext.get().getTenantId())
                .pageId(pageId)
                .ydocSnapshot(version.getYdocSnapshot())
                .html(version.getHtml())
                .editorId(userId)
                .reason(RepoConstants.VERSION_REASON_RESTORE)
                .created(now)
                .build();
        Long newId = repoPageVersionRepository.insert(restore);
        restore.setId(newId);
        log.info("repo page version restored: pageId={}, sourceVersionId={}, newVersionId={}, userId={}",
                pageId, versionId, newId, userId);
        return restore;
    }

    @Override
    public RepoPageVersionDomain createManualVersion(Long pageId) {
        RepoPageDomain page = mustGetEditablePage(pageId);
        byte[] ydocState = repoPageRepository.findYdocStateById(pageId);
        if (ydocState == null || ydocState.length == 0) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                    "page has no synced content yet");
        }
        Long userId = RequestContext.get().getUserId();
        Date now = new Date();
        RepoPageVersionDomain manual = RepoPageVersionDomain.builder()
                .tenantId(page.getTenantId())
                .pageId(pageId)
                .ydocSnapshot(ydocState)
                .html(page.getHtmlSnapshot())
                .editorId(userId)
                .reason(RepoConstants.VERSION_REASON_MANUAL)
                .created(now)
                .build();
        Long newId = repoPageVersionRepository.insert(manual);
        manual.setId(newId);
        log.info("repo page manual version created: pageId={}, versionId={}, userId={}", pageId, newId, userId);
        return manual;
    }

    /**
     * 取页面并校验可查看（读路径）。
     */
    private RepoPageDomain mustGetViewablePage(Long pageId) {
        RepoPageDomain page = repoPageRepository.findById(pageId);
        if (page == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.resourceDataNotFound);
        }
        repoAccessService.requireViewable(page);
        return page;
    }

    /**
     * 取页面并校验可编辑（恢复版本/手动版本为写操作）。
     */
    private RepoPageDomain mustGetEditablePage(Long pageId) {
        RepoPageDomain page = repoPageRepository.findById(pageId);
        if (page == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.resourceDataNotFound);
        }
        repoAccessService.requireEditable(page);
        return page;
    }

    /**
     * 取版本并校验归属页面一致。
     */
    private RepoPageVersionDomain mustGetVersion(Long pageId, Long versionId) {
        RepoPageVersionDomain version = repoPageVersionRepository.findById(versionId);
        if (version == null || !Objects.equals(version.getPageId(), pageId)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.resourceDataNotFound);
        }
        return version;
    }
}
