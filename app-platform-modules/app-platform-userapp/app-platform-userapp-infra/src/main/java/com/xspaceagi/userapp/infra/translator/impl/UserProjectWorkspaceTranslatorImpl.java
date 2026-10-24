package com.xspaceagi.userapp.infra.translator.impl;

import com.xspaceagi.userapp.domain.model.UserProjectWorkspaceModel;
import com.xspaceagi.userapp.infra.dao.entity.UserProjectWorkspace;
import com.xspaceagi.userapp.infra.translator.IUserProjectWorkspaceTranslator;

import org.springframework.stereotype.Component;

@Component
public class UserProjectWorkspaceTranslatorImpl implements IUserProjectWorkspaceTranslator {

    @Override
    public UserProjectWorkspaceModel convertToModel(UserProjectWorkspace entity) {
        if (entity == null) {
            return null;
        }
        UserProjectWorkspaceModel model = new UserProjectWorkspaceModel();
        model.setId(entity.getId());
        model.setTenantId(entity.getTenantId());
        model.setUserId(entity.getUserId());
        model.setProjectType(entity.getProjectType());
        model.setProjectId(entity.getProjectId());
        model.setFileWorkspacePath(entity.getFileWorkspacePath());
        model.setAgentWorkspacePath(entity.getAgentWorkspacePath());
        model.setSandboxId(entity.getSandboxId());
        model.setCreated(entity.getCreated());
        model.setModified(entity.getModified());
        return model;
    }

    @Override
    public UserProjectWorkspace convertToEntity(UserProjectWorkspaceModel model) {
        if (model == null) {
            return null;
        }
        UserProjectWorkspace entity = new UserProjectWorkspace();
        entity.setId(model.getId());
        entity.setTenantId(model.getTenantId());
        entity.setUserId(model.getUserId());
        entity.setProjectType(model.getProjectType());
        entity.setProjectId(model.getProjectId());
        entity.setFileWorkspacePath(model.getFileWorkspacePath());
        entity.setAgentWorkspacePath(model.getAgentWorkspacePath());
        entity.setSandboxId(model.getSandboxId());
        return entity;
    }
}
