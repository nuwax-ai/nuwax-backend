package com.xspaceagi.userapp.infra.translator.impl;

import com.xspaceagi.userapp.domain.model.UserProjectModel;
import com.xspaceagi.userapp.infra.dao.entity.UserProject;
import com.xspaceagi.userapp.infra.translator.IUserProjectTranslator;

import org.springframework.stereotype.Component;

@Component
public class UserProjectTranslatorImpl implements IUserProjectTranslator {

    @Override
    public UserProjectModel convertToModel(UserProject entity) {
        if (entity == null) {
            return null;
        }
        UserProjectModel model = new UserProjectModel();
        model.setId(entity.getId());
        model.setTenantId(entity.getTenantId());
        model.setSpaceId(entity.getSpaceId());
        model.setCreatorId(entity.getCreatorId());
        model.setName(entity.getName());
        model.setDescription(entity.getDescription());
        model.setIcon(entity.getIcon());
        model.setProjectType(entity.getProjectType());
        model.setRefId(entity.getRefId());
        model.setSandboxId(entity.getSandboxId());
        model.setSandboxType(entity.getSandboxType());
        model.setRepoSlugId(entity.getRepoSlugId());
        model.setPlanSlugId(entity.getPlanSlugId());
        model.setDevAgentId(entity.getDevAgentId());
        model.setOauthClientId(entity.getOauthClientId());
        model.setOauthClientSecret(entity.getOauthClientSecret());
        model.setHomepageUrl(entity.getHomepageUrl());
        model.setRedirectUri(entity.getRedirectUri());
        model.setRedirectUris(entity.getRedirectUris());
        model.setOauthScope(entity.getOauthScope());
        model.setOauthEnabled(entity.getOauthEnabled());
        model.setPublishStatus(entity.getPublishStatus());
        model.setPinned(Boolean.TRUE.equals(entity.getPinned()));
        model.setArchived(Boolean.TRUE.equals(entity.getArchived()));
        model.setNameDefined(Boolean.TRUE.equals(entity.getNameDefined()));
        model.setModified(entity.getModified());
        model.setCreated(entity.getCreated());
        return model;
    }

    @Override
    public UserProject convertToEntity(UserProjectModel model) {
        if (model == null) {
            return null;
        }
        UserProject entity = new UserProject();
        entity.setId(model.getId());
        entity.setTenantId(model.getTenantId());
        entity.setSpaceId(model.getSpaceId());
        entity.setCreatorId(model.getCreatorId());
        entity.setName(model.getName());
        entity.setDescription(model.getDescription());
        entity.setIcon(model.getIcon());
        entity.setProjectType(model.getProjectType());
        entity.setRefId(model.getRefId());
        entity.setSandboxId(model.getSandboxId());
        entity.setSandboxType(model.getSandboxType());
        entity.setRepoSlugId(model.getRepoSlugId());
        entity.setPlanSlugId(model.getPlanSlugId());
        entity.setDevAgentId(model.getDevAgentId());
        entity.setOauthClientId(model.getOauthClientId());
        entity.setOauthClientSecret(model.getOauthClientSecret());
        entity.setHomepageUrl(model.getHomepageUrl());
        entity.setRedirectUri(model.getRedirectUri());
        entity.setRedirectUris(model.getRedirectUris());
        entity.setOauthScope(model.getOauthScope());
        entity.setOauthEnabled(model.getOauthEnabled());
        entity.setPublishStatus(model.getPublishStatus());
        entity.setPinned(model.getPinned());
        entity.setArchived(model.getArchived());
        entity.setNameDefined(model.getNameDefined());
        entity.setModified(model.getModified());
        entity.setCreated(model.getCreated());
        return entity;
    }
}
