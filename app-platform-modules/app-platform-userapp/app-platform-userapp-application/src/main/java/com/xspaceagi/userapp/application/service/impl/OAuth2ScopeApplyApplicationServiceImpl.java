package com.xspaceagi.userapp.application.service.impl;

import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.xspaceagi.system.sdk.service.INotificationRpcService;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.page.SuperPage;
import com.xspaceagi.userapp.application.oauth2.OAuth2ScopeRegistry;
import com.xspaceagi.userapp.application.service.IOAuth2ScopeApplyApplicationService;
import com.xspaceagi.userapp.domain.model.OAuth2ScopeApplyModel;
import com.xspaceagi.userapp.domain.model.UserProjectModel;
import com.xspaceagi.userapp.domain.service.IOAuth2ScopeApplyDomainService;
import com.xspaceagi.userapp.domain.service.IUserProjectDomainService;
import com.xspaceagi.userapp.sdk.dto.OAuth2ScopeApplyStatus;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * OAuth2 scope 范围变更流程实现
 */
@Slf4j
@Service
public class OAuth2ScopeApplyApplicationServiceImpl implements IOAuth2ScopeApplyApplicationService {

    @Resource
    private IOAuth2ScopeApplyDomainService oauth2ScopeApplyDomainService;

    @Resource
    private IUserProjectDomainService userProjectDomainService;

    @Resource
    private INotificationRpcService notificationRpcService;

    @Override
    public OAuth2ScopeApplyModel submit(UserProjectModel project, Long applyUserId, List<String> scopes) {
        String target = OAuth2ScopeRegistry.normalizeToStored(scopes);
        // 先条件覆盖已有 Pending（以最新申请为准），无 Pending 再新建；
        // 并发提交的第二条 insert 会撞 pending_slot 唯一键，重试一次条件覆盖即收敛到「至多一条 Pending」
        boolean claimed = oauth2ScopeApplyDomainService.updatePendingContent(project.getId(), applyUserId,
                project.getOauthScope(), target);
        if (!claimed) {
            OAuth2ScopeApplyModel apply = new OAuth2ScopeApplyModel();
            apply.setProjectId(project.getId());
            apply.setProjectType(project.getProjectType());
            apply.setApplyUserId(applyUserId);
            // 待审核期间项目仍按原 scope 生效；oldScopes 记录原始登记值（空=平台默认）供审核页对比
            apply.setOldScopes(project.getOauthScope());
            apply.setScopes(target);
            apply.setStatus(OAuth2ScopeApplyStatus.Pending);
            try {
                oauth2ScopeApplyDomainService.create(apply);
            } catch (DuplicateKeyException e) {
                claimed = oauth2ScopeApplyDomainService.updatePendingContent(project.getId(), applyUserId,
                        project.getOauthScope(), target);
                if (!claimed) {
                    throw new IllegalStateException("concurrent scope apply submit failed, projectId="
                            + project.getId());
                }
            }
        }
        OAuth2ScopeApplyModel saved = oauth2ScopeApplyDomainService.getPendingByProjectId(project.getId());
        log.info("OAuth2 scope apply submitted, applyId={}, projectId={}, {} -> {}, applyUserId={}", saved.getId(),
                project.getId(), saved.getOldScopes(), target, applyUserId);
        return saved;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OAuth2ScopeApplyModel approve(Long applyId, Long reviewerId) {
        OAuth2ScopeApplyModel apply = requirePending(applyId);
        UserProjectModel project = userProjectDomainService.getById(apply.getProjectId());
        if (project == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.oauth2ClientNotFound);
        }
        // 原子流转（status='Pending' 守卫）：审核期间申请人重新提交过内容时此处更新 0 行并报错，
        // 避免把管理员没见过的旧内容写生效；与写回 user_project 在同一事务，失败一并回滚
        boolean transitioned = oauth2ScopeApplyDomainService.reviewIfPending(applyId, OAuth2ScopeApplyStatus.Approved,
                reviewerId, null, new Date());
        if (!transitioned) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.oauth2ScopeApplyNotFound);
        }
        // 写回生效：授权请求与令牌校验实时读取登记 scope（令牌校验还会与当前登记值取交集），写库即生效
        UserProjectModel update = new UserProjectModel();
        update.setId(project.getId());
        update.setOauthScope(apply.getScopes());
        userProjectDomainService.update(update);
        log.info("OAuth2 scope apply approved, applyId={}, projectId={}, scopes={}, reviewerId={}", applyId,
                apply.getProjectId(), apply.getScopes(), reviewerId);
        notifyReviewed(apply, true, null);
        return oauth2ScopeApplyDomainService.getById(applyId);
    }

    @Override
    public OAuth2ScopeApplyModel reject(Long applyId, Long reviewerId, String reason) {
        OAuth2ScopeApplyModel apply = requirePending(applyId);
        String trimmedReason = StringUtils.trimToNull(reason);
        boolean transitioned = oauth2ScopeApplyDomainService.reviewIfPending(applyId, OAuth2ScopeApplyStatus.Rejected,
                reviewerId, trimmedReason, new Date());
        if (!transitioned) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.oauth2ScopeApplyNotFound);
        }
        log.info("OAuth2 scope apply rejected, applyId={}, projectId={}, reviewerId={}", applyId, apply.getProjectId(),
                reviewerId);
        notifyReviewed(apply, false, trimmedReason);
        return oauth2ScopeApplyDomainService.getById(applyId);
    }

    @Override
    public SuperPage<OAuth2ScopeApplyModel> pageQuery(OAuth2ScopeApplyStatus status, Long projectId, Long current,
            Long pageSize) {
        return oauth2ScopeApplyDomainService.pageQuery(status, projectId, current, pageSize);
    }

    @Override
    public OAuth2ScopeApplyModel getLatestByProjectId(Long projectId) {
        return oauth2ScopeApplyDomainService.getLatestByProjectId(projectId);
    }

    /**
     * 审核结果通知申请人（通知失败只记日志，不影响审核结果）。
     */
    private void notifyReviewed(OAuth2ScopeApplyModel apply, boolean approved, String reason) {
        if (apply.getApplyUserId() == null) {
            return;
        }
        String message = approved ? "您提交的 OAuth2 权限范围变更申请已通过审核并生效"
                : "您提交的 OAuth2 权限范围变更申请未通过审核"
                        + (reason == null ? "" : "：" + reason);
        try {
            notificationRpcService.sendNotifyMessage(apply.getApplyUserId(), message);
        } catch (Exception e) {
            log.warn("OAuth2 scope apply review notify failed, applyId={}, applyUserId={}", apply.getId(),
                    apply.getApplyUserId(), e);
        }
    }

    private OAuth2ScopeApplyModel requirePending(Long applyId) {
        OAuth2ScopeApplyModel apply = applyId == null ? null : oauth2ScopeApplyDomainService.getById(applyId);
        if (apply == null || apply.getStatus() != OAuth2ScopeApplyStatus.Pending) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.oauth2ScopeApplyNotFound);
        }
        return apply;
    }
}
