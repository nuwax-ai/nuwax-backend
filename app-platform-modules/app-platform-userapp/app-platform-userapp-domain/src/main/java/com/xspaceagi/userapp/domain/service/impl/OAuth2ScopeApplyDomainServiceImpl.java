package com.xspaceagi.userapp.domain.service.impl;

import java.util.Date;

import org.springframework.stereotype.Service;

import com.xspaceagi.system.spec.page.SuperPage;
import com.xspaceagi.userapp.domain.model.OAuth2ScopeApplyModel;
import com.xspaceagi.userapp.domain.repository.IOAuth2ScopeApplyRepository;
import com.xspaceagi.userapp.domain.service.IOAuth2ScopeApplyDomainService;
import com.xspaceagi.userapp.sdk.dto.OAuth2ScopeApplyStatus;

import jakarta.annotation.Resource;

@Service
public class OAuth2ScopeApplyDomainServiceImpl implements IOAuth2ScopeApplyDomainService {

    @Resource
    private IOAuth2ScopeApplyRepository oauth2ScopeApplyRepository;

    @Override
    public OAuth2ScopeApplyModel getById(Long id) {
        return oauth2ScopeApplyRepository.getById(id);
    }

    @Override
    public OAuth2ScopeApplyModel getPendingByProjectId(Long projectId) {
        return oauth2ScopeApplyRepository.getPendingByProjectId(projectId);
    }

    @Override
    public OAuth2ScopeApplyModel getLatestByProjectId(Long projectId) {
        return oauth2ScopeApplyRepository.getLatestByProjectId(projectId);
    }

    @Override
    public SuperPage<OAuth2ScopeApplyModel> pageQuery(OAuth2ScopeApplyStatus status, Long projectId, Long current,
            Long pageSize) {
        return oauth2ScopeApplyRepository.pageQuery(status, projectId, current, pageSize);
    }

    @Override
    public OAuth2ScopeApplyModel create(OAuth2ScopeApplyModel model) {
        Long id = oauth2ScopeApplyRepository.add(model);
        model.setId(id);
        return model;
    }

    @Override
    public OAuth2ScopeApplyModel update(OAuth2ScopeApplyModel model) {
        oauth2ScopeApplyRepository.updateById(model);
        return oauth2ScopeApplyRepository.getById(model.getId());
    }

    @Override
    public boolean updatePendingContent(Long projectId, Long applyUserId, String oldScopes, String scopes) {
        return oauth2ScopeApplyRepository.updatePendingContent(projectId, applyUserId, oldScopes, scopes);
    }

    @Override
    public boolean reviewIfPending(Long id, OAuth2ScopeApplyStatus newStatus, Long reviewerId, String rejectReason,
            Date reviewedAt) {
        return oauth2ScopeApplyRepository.reviewIfPending(id, newStatus, reviewerId, rejectReason, reviewedAt);
    }

    @Override
    public void deleteByProjectId(Long projectId) {
        oauth2ScopeApplyRepository.deleteByProjectId(projectId);
    }
}
