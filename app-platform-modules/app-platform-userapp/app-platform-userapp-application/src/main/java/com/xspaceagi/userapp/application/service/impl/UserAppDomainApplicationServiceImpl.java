package com.xspaceagi.userapp.application.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;

import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.userapp.domain.model.UserAppDomainModel;
import com.xspaceagi.userapp.domain.service.IUserAppDomainDomainService;
import com.xspaceagi.userapp.application.service.IUserAppDomainApplicationService;

import jakarta.annotation.Resource;

@Service
public class UserAppDomainApplicationServiceImpl implements IUserAppDomainApplicationService {

    @Resource
    private IUserAppDomainDomainService userAppDomainDomainService;

    @Override
    public List<UserAppDomainModel> listByAppId(Long appId) {
        return userAppDomainDomainService.listByAppId(appId);
    }

    @Override
    public UserAppDomainModel getById(Long id) {
        return userAppDomainDomainService.getById(id);
    }

    @Override
    public ReqResult<UserAppDomainModel> create(UserAppDomainModel model, UserContext userContext) {
        return userAppDomainDomainService.create(model, userContext);
    }

    @Override
    public ReqResult<UserAppDomainModel> update(UserAppDomainModel model, UserContext userContext) {
        return userAppDomainDomainService.update(model, userContext);
    }

    @Override
    public ReqResult<Void> delete(Long id, UserContext userContext) {
        return userAppDomainDomainService.delete(id, userContext);
    }

    @Override
    public List<String> listAllDomains() {
        return userAppDomainDomainService.listAllDomains();
    }
}
