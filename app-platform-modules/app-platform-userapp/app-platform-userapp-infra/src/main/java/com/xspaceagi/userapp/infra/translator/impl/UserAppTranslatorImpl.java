package com.xspaceagi.userapp.infra.translator.impl;

import com.xspaceagi.userapp.domain.model.UserAppModel;
import com.xspaceagi.userapp.infra.dao.entity.UserApp;
import com.xspaceagi.userapp.infra.translator.IUserAppTranslator;

import org.springframework.stereotype.Component;

@Component
public class UserAppTranslatorImpl implements IUserAppTranslator {

    @Override
    public UserAppModel convertToModel(UserApp entity) {
        if (entity == null) {
            return null;
        }
        UserAppModel model = new UserAppModel();
        model.setId(entity.getId());
        model.setTenantId(entity.getTenantId());
        model.setSpaceId(entity.getSpaceId());
        model.setCreatorId(entity.getCreatorId());
        model.setName(entity.getName());
        model.setDescription(entity.getDescription());
        model.setIcon(entity.getIcon());
        model.setCoverImg(entity.getCoverImg());
        model.setSandboxId(entity.getSandboxId());
        model.setSandboxType(entity.getSandboxType());
        model.setDeployType(entity.getDeployType());
        model.setDeployServerId(entity.getDeployServerId());
        model.setRepoSlugId(entity.getRepoSlugId());
        model.setDevAgentId(entity.getDevAgentId());
        model.setProdDeployed(Boolean.TRUE.equals(entity.getProdDeployed()));
        model.setProdRunning(Boolean.TRUE.equals(entity.getProdRunning()));
        model.setProdReleaseId(entity.getProdReleaseId());
        model.setPlanSlugId(entity.getPlanSlugId());
        model.setPublishStatus(entity.getPublishStatus());
        model.setBuildVersions(entity.getBuildVersions());
        model.setDevDbUsername(entity.getDevDbUsername());
        model.setDevDbPassword(entity.getDevDbPassword());
        model.setProdDbUsername(entity.getProdDbUsername());
        model.setProdDbPassword(entity.getProdDbPassword());
        model.setPreDeleteTime(entity.getPreDeleteTime());
        model.setModified(entity.getModified());
        model.setCreated(entity.getCreated());
        return model;
    }

    @Override
    public UserApp convertToEntity(UserAppModel model) {
        if (model == null) {
            return null;
        }
        UserApp entity = new UserApp();
        entity.setId(model.getId());
        entity.setTenantId(model.getTenantId());
        entity.setSpaceId(model.getSpaceId());
        entity.setCreatorId(model.getCreatorId());
        entity.setName(model.getName());
        entity.setDescription(model.getDescription());
        entity.setIcon(model.getIcon());
        entity.setCoverImg(model.getCoverImg());
        entity.setSandboxId(model.getSandboxId());
        entity.setSandboxType(model.getSandboxType());
        entity.setDeployType(model.getDeployType());
        entity.setDeployServerId(model.getDeployServerId());
        entity.setRepoSlugId(model.getRepoSlugId());
        entity.setPlanSlugId(model.getPlanSlugId());
        entity.setDevAgentId(model.getDevAgentId());
        entity.setProdDeployed(model.getProdDeployed());
        entity.setProdRunning(model.getProdRunning());
        entity.setProdReleaseId(model.getProdReleaseId());
        entity.setPublishStatus(model.getPublishStatus());
        entity.setBuildVersions(model.getBuildVersions());
        entity.setDevDbUsername(model.getDevDbUsername());
        entity.setDevDbPassword(model.getDevDbPassword());
        entity.setProdDbUsername(model.getProdDbUsername());
        entity.setProdDbPassword(model.getProdDbPassword());
        entity.setPreDeleteTime(model.getPreDeleteTime());
        entity.setModified(model.getModified());
        entity.setCreated(model.getCreated());
        return entity;
    }
}
