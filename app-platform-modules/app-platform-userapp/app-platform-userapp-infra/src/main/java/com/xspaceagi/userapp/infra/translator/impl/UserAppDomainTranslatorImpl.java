package com.xspaceagi.userapp.infra.translator.impl;

import org.springframework.stereotype.Component;

import com.xspaceagi.userapp.domain.model.UserAppDomainModel;
import com.xspaceagi.userapp.infra.dao.entity.UserAppDomain;
import com.xspaceagi.userapp.infra.translator.IUserAppDomainTranslator;

@Component
public class UserAppDomainTranslatorImpl implements IUserAppDomainTranslator {

    @Override
    public UserAppDomainModel convertToModel(UserAppDomain entity) {
        if (entity == null) {
            return null;
        }
        UserAppDomainModel model = new UserAppDomainModel();
        model.setId(entity.getId());
        model.setTenantId(entity.getTenantId());
        model.setAppId(entity.getAppId());
        model.setDomain(entity.getDomain());
        model.setDomainType(entity.getDomainType());
        model.setCreated(entity.getCreated());
        model.setModified(entity.getModified());
        return model;
    }

    @Override
    public UserAppDomain convertToEntity(UserAppDomainModel model) {
        if (model == null) {
            return null;
        }
        UserAppDomain entity = new UserAppDomain();
        entity.setId(model.getId());
        entity.setTenantId(model.getTenantId());
        entity.setAppId(model.getAppId());
        entity.setDomain(model.getDomain());
        entity.setDomainType(model.getDomainType());
        entity.setCreated(model.getCreated());
        entity.setModified(model.getModified());
        return entity;
    }
}
