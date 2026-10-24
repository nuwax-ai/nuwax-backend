package com.xspaceagi.userapp.application.service;

import java.util.List;

import com.xspaceagi.userapp.domain.model.UserAppDomainModel;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;

public interface IUserAppDomainApplicationService {

    List<UserAppDomainModel> listByAppId(Long appId);

    UserAppDomainModel getById(Long id);

    ReqResult<UserAppDomainModel> create(UserAppDomainModel model, UserContext userContext);

    ReqResult<UserAppDomainModel> update(UserAppDomainModel model, UserContext userContext);

    ReqResult<Void> delete(Long id, UserContext userContext);

    List<String> listAllDomains();
}
