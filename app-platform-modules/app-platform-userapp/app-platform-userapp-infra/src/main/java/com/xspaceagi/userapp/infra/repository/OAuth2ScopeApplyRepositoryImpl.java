package com.xspaceagi.userapp.infra.repository;

import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xspaceagi.system.spec.page.SuperPage;
import com.xspaceagi.userapp.domain.model.OAuth2ScopeApplyModel;
import com.xspaceagi.userapp.domain.repository.IOAuth2ScopeApplyRepository;
import com.xspaceagi.userapp.infra.dao.entity.OAuth2ScopeApply;
import com.xspaceagi.userapp.infra.dao.service.IOAuth2ScopeApplyService;
import com.xspaceagi.userapp.infra.translator.IOAuth2ScopeApplyTranslator;
import com.xspaceagi.userapp.sdk.dto.OAuth2ScopeApplyStatus;

import jakarta.annotation.Resource;

@Repository
public class OAuth2ScopeApplyRepositoryImpl implements IOAuth2ScopeApplyRepository {

    @Resource
    private IOAuth2ScopeApplyService oauth2ScopeApplyService;

    @Resource
    private IOAuth2ScopeApplyTranslator oauth2ScopeApplyTranslator;

    @Override
    public OAuth2ScopeApplyModel getById(Long id) {
        if (id == null) {
            return null;
        }
        return oauth2ScopeApplyTranslator.convertToModel(oauth2ScopeApplyService.getById(id));
    }

    @Override
    public OAuth2ScopeApplyModel getPendingByProjectId(Long projectId) {
        return getLatestByStatus(projectId, OAuth2ScopeApplyStatus.Pending);
    }

    @Override
    public OAuth2ScopeApplyModel getLatestByProjectId(Long projectId) {
        OAuth2ScopeApply latest = oauth2ScopeApplyService.getOne(Wrappers.<OAuth2ScopeApply>lambdaQuery()
                .eq(OAuth2ScopeApply::getProjectId, projectId)
                .orderByDesc(OAuth2ScopeApply::getId)
                .last("limit 1"));
        return oauth2ScopeApplyTranslator.convertToModel(latest);
    }

    @Override
    public SuperPage<OAuth2ScopeApplyModel> pageQuery(OAuth2ScopeApplyStatus status, Long projectId, Long current,
            Long pageSize) {
        Page<OAuth2ScopeApply> page = new Page<>(current == null ? 1 : current, pageSize == null ? 10 : pageSize);
        Page<OAuth2ScopeApply> iPage = oauth2ScopeApplyService.page(page,
                Wrappers.<OAuth2ScopeApply>lambdaQuery()
                        .eq(status != null, OAuth2ScopeApply::getStatus, status)
                        .eq(projectId != null, OAuth2ScopeApply::getProjectId, projectId)
                        .orderByDesc(OAuth2ScopeApply::getId));
        List<OAuth2ScopeApplyModel> records = iPage.getRecords().stream()
                .map(oauth2ScopeApplyTranslator::convertToModel).toList();
        return SuperPage.build(iPage, records);
    }

    @Override
    public Long add(OAuth2ScopeApplyModel model) {
        OAuth2ScopeApply entity = oauth2ScopeApplyTranslator.convertToEntity(model);
        oauth2ScopeApplyService.save(entity);
        return entity.getId();
    }

    @Override
    public void updateById(OAuth2ScopeApplyModel model) {
        OAuth2ScopeApply entity = oauth2ScopeApplyTranslator.convertToEntity(model);
        oauth2ScopeApplyService.update(entity, Wrappers.<OAuth2ScopeApply>lambdaUpdate()
                .eq(OAuth2ScopeApply::getId, model.getId()));
    }

    @Override
    public boolean updatePendingContent(Long projectId, Long applyUserId, String oldScopes, String scopes) {
        // 状态守卫的条件更新：只覆盖仍是 Pending 的行
        return oauth2ScopeApplyService.update(Wrappers.<OAuth2ScopeApply>lambdaUpdate()
                .set(OAuth2ScopeApply::getApplyUserId, applyUserId)
                .set(OAuth2ScopeApply::getOldScopes, oldScopes)
                .set(OAuth2ScopeApply::getScopes, scopes)
                .eq(OAuth2ScopeApply::getProjectId, projectId)
                .eq(OAuth2ScopeApply::getStatus, OAuth2ScopeApplyStatus.Pending));
    }

    @Override
    public boolean reviewIfPending(Long id, OAuth2ScopeApplyStatus newStatus, Long reviewerId, String rejectReason,
            Date reviewedAt) {
        // 原子流转：WHERE 带 Pending 守卫，并发审核/审核期间被重新提交时后到者更新 0 行
        return oauth2ScopeApplyService.update(Wrappers.<OAuth2ScopeApply>lambdaUpdate()
                .set(OAuth2ScopeApply::getStatus, newStatus)
                .set(OAuth2ScopeApply::getReviewerId, reviewerId)
                .set(OAuth2ScopeApply::getRejectReason, rejectReason)
                .set(OAuth2ScopeApply::getReviewedAt, reviewedAt)
                .eq(OAuth2ScopeApply::getId, id)
                .eq(OAuth2ScopeApply::getStatus, OAuth2ScopeApplyStatus.Pending));
    }

    @Override
    public void deleteByProjectId(Long projectId) {
        if (projectId == null) {
            return;
        }
        oauth2ScopeApplyService.remove(Wrappers.<OAuth2ScopeApply>lambdaQuery()
                .eq(OAuth2ScopeApply::getProjectId, projectId));
    }

    private OAuth2ScopeApplyModel getLatestByStatus(Long projectId, OAuth2ScopeApplyStatus status) {
        OAuth2ScopeApply latest = oauth2ScopeApplyService.getOne(Wrappers.<OAuth2ScopeApply>lambdaQuery()
                .eq(OAuth2ScopeApply::getProjectId, projectId)
                .eq(OAuth2ScopeApply::getStatus, status)
                .orderByDesc(OAuth2ScopeApply::getId)
                .last("limit 1"));
        return oauth2ScopeApplyTranslator.convertToModel(latest);
    }
}
