package com.xspaceagi.userapp.infra.translator.impl;

import com.xspaceagi.userapp.domain.model.UserAppTaskModel;
import com.xspaceagi.userapp.infra.dao.entity.UserAppTask;
import com.xspaceagi.userapp.infra.translator.IUserAppTaskTranslator;

import org.springframework.stereotype.Component;

@Component
public class UserAppTaskTranslatorImpl implements IUserAppTaskTranslator {

    @Override
    public UserAppTaskModel convertToModel(UserAppTask entity) {
        if (entity == null) {
            return null;
        }
        UserAppTaskModel model = new UserAppTaskModel();
        model.setId(entity.getId());
        model.setTenantId(entity.getTenantId());
        model.setAppId(entity.getAppId());
        model.setUserId(entity.getUserId());
        model.setTaskType(entity.getTaskType());
        model.setTaskId(entity.getTaskId());
        model.setStatus(entity.getStatus());
        model.setError(entity.getError());
        model.setSandboxId(entity.getSandboxId());
        model.setActiveGuard(entity.getActiveGuard());
        model.setGitCommit(entity.getGitCommit());
        model.setCreated(entity.getCreated());
        model.setModified(entity.getModified());
        return model;
    }

    @Override
    public UserAppTask convertToEntity(UserAppTaskModel model) {
        if (model == null) {
            return null;
        }
        UserAppTask entity = new UserAppTask();
        entity.setId(model.getId());
        entity.setTenantId(model.getTenantId());
        entity.setAppId(model.getAppId());
        entity.setUserId(model.getUserId());
        entity.setTaskType(model.getTaskType());
        entity.setTaskId(model.getTaskId());
        entity.setStatus(model.getStatus());
        entity.setError(model.getError());
        entity.setSandboxId(model.getSandboxId());
        entity.setActiveGuard(model.getActiveGuard());
        entity.setGitCommit(model.getGitCommit());
        entity.setCreated(model.getCreated());
        entity.setModified(model.getModified());
        return entity;
    }
}
