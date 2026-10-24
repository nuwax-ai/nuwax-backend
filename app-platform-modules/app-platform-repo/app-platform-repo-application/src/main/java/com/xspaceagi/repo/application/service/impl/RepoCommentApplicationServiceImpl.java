package com.xspaceagi.repo.application.service.impl;

import cn.hutool.json.JSONUtil;
import com.xspaceagi.repo.application.service.RepoAccessService;
import com.xspaceagi.repo.application.service.RepoCommentApplicationService;
import com.xspaceagi.repo.application.service.dto.RepoAccessResult;
import com.xspaceagi.repo.application.service.dto.RepoCommentThread;
import com.xspaceagi.repo.domain.model.RepoCommentDomain;
import com.xspaceagi.repo.domain.model.RepoPageDomain;
import com.xspaceagi.repo.domain.repository.RepoCommentRepository;
import com.xspaceagi.repo.domain.repository.RepoPageRepository;
import com.xspaceagi.repo.spec.constants.RepoConstants;
import com.xspaceagi.system.application.dto.SendNotifyMessageDto;
import com.xspaceagi.system.application.service.NotifyMessageApplicationService;
import com.xspaceagi.system.infra.dao.entity.NotifyMessage;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.SpacePermissionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 文档评论应用服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepoCommentApplicationServiceImpl implements RepoCommentApplicationService {

    private final RepoCommentRepository repoCommentRepository;

    private final RepoPageRepository repoPageRepository;

    private final SpacePermissionService spacePermissionService;

    private final RepoAccessService repoAccessService;

    private final NotifyMessageApplicationService notifyMessageApplicationService;

    @Override
    public List<RepoCommentThread> listCommentThreads(Long pageId, String shareToken) {
        mustGetViewablePage(pageId, shareToken);
        List<RepoCommentDomain> all = repoCommentRepository.listByPageId(pageId);
        Map<Long, RepoCommentThread> threadById = new LinkedHashMap<>();
        for (RepoCommentDomain comment : all) {
            if (comment.getParentId() == null) {
                threadById.put(comment.getId(), RepoCommentThread.builder()
                        .comment(comment)
                        .replies(new ArrayList<>())
                        .build());
            }
        }
        for (RepoCommentDomain comment : all) {
            if (comment.getParentId() != null) {
                RepoCommentThread thread = threadById.get(comment.getParentId());
                if (thread != null) {
                    thread.getReplies().add(comment);
                }
                // 父评论已删除的孤儿回复直接忽略
            }
        }
        return new ArrayList<>(threadById.values());
    }

    @Override
    public RepoCommentDomain addComment(Long pageId, Long parentId, String content, List<Long> mentionUserIds, String anchor, String shareToken) {
        // 发评论：可编辑者必然可评论；否则看分享评论权限（需求5）
        RepoPageDomain page = mustGetCommentablePage(pageId, shareToken);
        if (content == null || content.isBlank()) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty);
        }
        if (parentId != null) {
            RepoCommentDomain parent = repoCommentRepository.findById(parentId);
            if (parent == null || !Objects.equals(parent.getPageId(), pageId)) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.resourceDataNotFound);
            }
            if (parent.getParentId() != null) {
                // 仅支持一层回复
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                        "nested reply beyond one level is not allowed");
            }
        }

        Long userId = RequestContext.get().getUserId();
        Long tenantId = RequestContext.get().getTenantId();
        // 提及列表过滤：仅保留同空间成员，去重、去自己
        List<Long> mentions = filterMentions(page.getSpaceId(), mentionUserIds, userId);

        RepoCommentDomain domain = RepoCommentDomain.builder()
                .tenantId(tenantId)
                .pageId(pageId)
                .userId(userId)
                .parentId(parentId)
                .content(content)
                .mentions(JSONUtil.toJsonStr(mentions))
                .anchor(anchor)
                .build();
        Long id = repoCommentRepository.insert(domain);
        domain.setId(id);
        log.info("repo comment added: id={}, pageId={}, parentId={}, userId={}, mentions={}, anchor={}",
                id, pageId, parentId, userId, mentions, anchor);

        sendMentionNotifications(tenantId, userId, page, mentions);
        return domain;
    }

    @Override
    public RepoCommentDomain resolveComment(Long commentId, String shareToken) {
        RepoCommentDomain comment = mustGetComment(commentId);
        RepoPageDomain page = mustGetViewablePage(comment.getPageId(), shareToken);
        checkAuthorOrAdmin(comment, page);
        if (!repoCommentRepository.updateResolved(commentId, RepoConstants.COMMENT_RESOLVED)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.resourceDataNotFound);
        }
        comment.setResolved(RepoConstants.COMMENT_RESOLVED);
        return comment;
    }

    @Override
    public void deleteComment(Long commentId, String shareToken) {
        RepoCommentDomain comment = mustGetComment(commentId);
        RepoPageDomain page = mustGetViewablePage(comment.getPageId(), shareToken);
        checkAuthorOrAdmin(comment, page);

        List<Long> ids = new ArrayList<>();
        ids.add(commentId);
        // 顶层评论删除时连同回复一并软删
        if (comment.getParentId() == null) {
            for (RepoCommentDomain other : repoCommentRepository.listByPageId(comment.getPageId())) {
                if (Objects.equals(other.getParentId(), commentId)) {
                    ids.add(other.getId());
                }
            }
        }
        repoCommentRepository.softDeleteByIds(ids);
        log.info("repo comment deleted: id={}, pageId={}, affected={}", commentId, comment.getPageId(), ids.size());
    }

    /**
     * 提及过滤：同空间成员、去重、排除评论者本人。
     */
    private List<Long> filterMentions(Long spaceId, List<Long> mentionUserIds, Long selfId) {
        List<Long> result = new ArrayList<>();
        if (mentionUserIds == null || mentionUserIds.isEmpty()) {
            return result;
        }
        for (Long mentionId : mentionUserIds) {
            if (mentionId == null || Objects.equals(mentionId, selfId) || result.contains(mentionId)) {
                continue;
            }
            if (spacePermissionService.getSpaceUserRole(spaceId, mentionId) != null) {
                result.add(mentionId);
            }
        }
        return result;
    }

    /**
     * 向被提及用户发送 Private 站内通知；失败仅告警，不影响评论写入。
     */
    private void sendMentionNotifications(Long tenantId, Long senderId, RepoPageDomain page, List<Long> mentions) {
        if (mentions.isEmpty()) {
            return;
        }
        try {
            SendNotifyMessageDto dto = SendNotifyMessageDto.builder()
                    .tenantId(tenantId)
                    .senderId(senderId)
                    .content("在文档「" + page.getTitle() + "」中提到了你")
                    .scope(NotifyMessage.MessageScope.Private)
                    .userIds(mentions)
                    .build();
            notifyMessageApplicationService.sendNotifyMessage(dto);
        } catch (Exception e) {
            log.warn("repo comment mention notify failed, pageId={}, mentions={}", page.getId(), mentions, e);
        }
    }

    /**
     * 评论作者或空间管理员/属主可操作。
     */
    private void checkAuthorOrAdmin(RepoCommentDomain comment, RepoPageDomain page) {
        Long userId = RequestContext.get().getUserId();
        if (Objects.equals(comment.getUserId(), userId)) {
            return;
        }
        String role = spacePermissionService.getSpaceUserRole(page.getSpaceId(), userId);
        if (RepoConstants.ROLE_OWNER.equals(role) || RepoConstants.ROLE_ADMIN.equals(role)) {
            return;
        }
        throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
    }

    private RepoCommentDomain mustGetComment(Long commentId) {
        RepoCommentDomain comment = repoCommentRepository.findById(commentId);
        if (comment == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.resourceDataNotFound);
        }
        return comment;
    }

    /**
     * 取页面并校验可查看（需求5 补口）：带分享链接 token 时经链接裁决补查看权限，
     * 登录用户经分享链接进入但非空间成员时也能读评论。
     */
    private RepoPageDomain mustGetViewablePage(Long pageId, String shareToken) {
        RepoPageDomain page = repoPageRepository.findById(pageId);
        if (page == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.resourceDataNotFound);
        }
        Long userId = RequestContext.get() == null ? null : RequestContext.get().getUserId();
        RepoAccessResult access = repoAccessService.resolveAccess(page, userId, shareToken);
        if (!access.isViewable()) {
            throw new SpacePermissionException();
        }
        return page;
    }

    /**
     * 取页面并校验可评论（需求5）：严格按 commentable（评论权限位 / 分享链接
     * allow_comment=1——后者须带 token 裁决）裁决，与可编辑解耦——仅授予编辑位、
     * 未勾选「可评论」的分享/按人权限用户不可评论。
     */
    private RepoPageDomain mustGetCommentablePage(Long pageId, String shareToken) {
        RepoPageDomain page = repoPageRepository.findById(pageId);
        if (page == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.resourceDataNotFound);
        }
        Long userId = RequestContext.get() == null ? null : RequestContext.get().getUserId();
        RepoAccessResult access = repoAccessService.resolveAccess(page, userId, shareToken);
        if (!access.isCommentable()) {
            throw new SpacePermissionException();
        }
        return page;
    }
}
