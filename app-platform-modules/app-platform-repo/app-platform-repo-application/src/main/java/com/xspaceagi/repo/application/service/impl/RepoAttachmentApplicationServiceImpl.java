package com.xspaceagi.repo.application.service.impl;

import com.xspaceagi.repo.application.service.RepoAccessService;
import com.xspaceagi.repo.application.service.RepoAttachmentApplicationService;
import com.xspaceagi.repo.domain.model.RepoAttachmentDomain;
import com.xspaceagi.repo.domain.model.RepoPageDomain;
import com.xspaceagi.repo.domain.repository.RepoAttachmentRepository;
import com.xspaceagi.repo.domain.repository.RepoPageRepository;
import com.xspaceagi.repo.spec.constants.RepoConstants;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 文档附件应用服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepoAttachmentApplicationServiceImpl implements RepoAttachmentApplicationService {

    private static final int FILE_NAME_MAX_LENGTH = 500;

    private static final int MIME_MAX_LENGTH = 100;

    private final RepoAttachmentRepository repoAttachmentRepository;

    private final RepoPageRepository repoPageRepository;

    private final SpacePermissionService spacePermissionService;

    private final RepoAccessService repoAccessService;

    @Override
    public RepoAttachmentDomain addPageAttachment(Long pageId, String fileKey, String fileName, String mime, Long size) {
        RepoPageDomain page = repoPageRepository.findById(pageId);
        if (page == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.resourceDataNotFound);
        }
        // 挂附件属写操作，要求可编辑
        repoAccessService.requireEditable(page);
        return doAdd(page.getSpaceId(), pageId, fileKey, fileName, mime, size);
    }

    @Override
    public RepoAttachmentDomain addSpaceAttachment(Long spaceId, String fileKey, String fileName, String mime, Long size) {
        spacePermissionService.checkSpaceUserPermission(spaceId);
        return doAdd(spaceId, null, fileKey, fileName, mime, size);
    }

    @Override
    public List<RepoAttachmentDomain> listPageAttachments(Long pageId) {
        RepoPageDomain page = repoPageRepository.findById(pageId);
        if (page == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.resourceDataNotFound);
        }
        repoAccessService.requireViewable(page);
        return repoAttachmentRepository.listByPageId(pageId);
    }

    @Override
    public List<RepoAttachmentDomain> listSpaceAttachments(Long spaceId) {
        spacePermissionService.checkSpaceUserPermission(spaceId);
        return repoAttachmentRepository.listBySpaceId(spaceId);
    }

    @Override
    public void deleteAttachment(Long attachmentId) {
        RepoAttachmentDomain attachment = repoAttachmentRepository.findById(attachmentId);
        if (attachment == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.resourceDataNotFound);
        }
        spacePermissionService.checkSpaceUserPermission(attachment.getSpaceId());

        Long userId = RequestContext.get().getUserId();
        if (!Objects.equals(attachment.getUserId(), userId)) {
            String role = spacePermissionService.getSpaceUserRole(attachment.getSpaceId(), userId);
            if (!RepoConstants.ROLE_OWNER.equals(role) && !RepoConstants.ROLE_ADMIN.equals(role)) {
                throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
            }
        }
        repoAttachmentRepository.softDelete(attachmentId);
        log.info("repo attachment deleted: id={}, spaceId={}, pageId={}, userId={}",
                attachmentId, attachment.getSpaceId(), attachment.getPageId(), userId);
    }

    private RepoAttachmentDomain doAdd(Long spaceId, Long pageId, String fileKey, String fileName, String mime, Long size) {
        if (fileKey == null || fileKey.isBlank()) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty);
        }
        if (fileName == null || fileName.isBlank()) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty);
        }
        if (fileName.length() > FILE_NAME_MAX_LENGTH) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                    "fileName too long, max " + FILE_NAME_MAX_LENGTH);
        }
        if (mime != null && mime.length() > MIME_MAX_LENGTH) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                    "mime too long, max " + MIME_MAX_LENGTH);
        }
        if (size == null || size < 0) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                    "size must be non-negative");
        }

        Long userId = RequestContext.get().getUserId();
        RepoAttachmentDomain domain = RepoAttachmentDomain.builder()
                .tenantId(RequestContext.get().getTenantId())
                .spaceId(spaceId)
                .pageId(pageId)
                .userId(userId)
                .fileKey(fileKey)
                .fileName(fileName.trim())
                .mime(mime)
                .size(size)
                .build();
        Long id = repoAttachmentRepository.insert(domain);
        domain.setId(id);
        log.info("repo attachment added: id={}, spaceId={}, pageId={}, fileKey={}, userId={}",
                id, spaceId, pageId, fileKey, userId);
        return domain;
    }
}
