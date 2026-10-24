package com.xspaceagi.userapp.infra.translator.impl;

import org.springframework.stereotype.Component;

import com.xspaceagi.userapp.domain.model.OAuth2ScopeApplyModel;
import com.xspaceagi.userapp.infra.dao.entity.OAuth2ScopeApply;
import com.xspaceagi.userapp.infra.translator.IOAuth2ScopeApplyTranslator;

@Component
public class OAuth2ScopeApplyTranslatorImpl implements IOAuth2ScopeApplyTranslator {

    @Override
    public OAuth2ScopeApplyModel convertToModel(OAuth2ScopeApply entity) {
        if (entity == null) {
            return null;
        }
        OAuth2ScopeApplyModel model = new OAuth2ScopeApplyModel();
        model.setId(entity.getId());
        model.setTenantId(entity.getTenantId());
        model.setProjectId(entity.getProjectId());
        model.setProjectType(entity.getProjectType());
        model.setApplyUserId(entity.getApplyUserId());
        model.setOldScopes(entity.getOldScopes());
        model.setScopes(entity.getScopes());
        model.setStatus(entity.getStatus());
        model.setReviewerId(entity.getReviewerId());
        model.setRejectReason(entity.getRejectReason());
        model.setReviewedAt(entity.getReviewedAt());
        model.setModified(entity.getModified());
        model.setCreated(entity.getCreated());
        return model;
    }

    @Override
    public OAuth2ScopeApply convertToEntity(OAuth2ScopeApplyModel model) {
        if (model == null) {
            return null;
        }
        OAuth2ScopeApply entity = new OAuth2ScopeApply();
        entity.setId(model.getId());
        entity.setTenantId(model.getTenantId());
        entity.setProjectId(model.getProjectId());
        entity.setProjectType(model.getProjectType());
        entity.setApplyUserId(model.getApplyUserId());
        entity.setOldScopes(model.getOldScopes());
        entity.setScopes(model.getScopes());
        entity.setStatus(model.getStatus());
        entity.setReviewerId(model.getReviewerId());
        entity.setRejectReason(model.getRejectReason());
        entity.setReviewedAt(model.getReviewedAt());
        return entity;
    }
}
