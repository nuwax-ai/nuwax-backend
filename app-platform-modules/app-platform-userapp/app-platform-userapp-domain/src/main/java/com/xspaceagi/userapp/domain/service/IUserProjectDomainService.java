package com.xspaceagi.userapp.domain.service;

import com.xspaceagi.system.spec.page.SuperPage;
import com.xspaceagi.system.spec.enums.ProjectTypeEnum;
import com.xspaceagi.userapp.domain.model.UserProjectModel;

import java.util.List;

public interface IUserProjectDomainService {

    UserProjectModel register(ProjectTypeEnum projectType, Long refId, String name, String description,
            String icon, Long spaceId, Long creatorId, Long sandboxId, Long devAgentId, Boolean nameDefined);

    void syncBasicInfo(ProjectTypeEnum projectType, Long refId, String name, String description, String icon);

    void unregister(ProjectTypeEnum projectType, Long refId);

    UserProjectModel create(UserProjectModel model);

    UserProjectModel update(UserProjectModel model);

    void delete(Long id);

    UserProjectModel getById(Long id);

    UserProjectModel getByRef(ProjectTypeEnum projectType, Long refId);

    /** 按 OAuth2 Client ID 查接入应用（uk_oauth_client_id 唯一） */
    UserProjectModel getByClientId(String oauthClientId);

    /** 条件查询（查询专用字段参与过滤） */
    List<UserProjectModel> list(UserProjectModel query);

    SuperPage<UserProjectModel> pageQuery(UserProjectModel query, Long current, Long pageSize);
}
