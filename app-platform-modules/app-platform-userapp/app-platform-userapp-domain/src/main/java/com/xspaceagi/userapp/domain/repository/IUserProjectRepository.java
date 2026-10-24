package com.xspaceagi.userapp.domain.repository;

import java.util.List;
import com.xspaceagi.system.spec.enums.ProjectTypeEnum;

import com.xspaceagi.system.spec.page.SuperPage;
import com.xspaceagi.userapp.domain.model.UserProjectModel;

public interface IUserProjectRepository {

    UserProjectModel getById(Long id);

    UserProjectModel getByRef(ProjectTypeEnum projectType, Long refId);

    /** 按 OAuth2 Client ID 查接入应用（uk_oauth_client_id 唯一索引） */
    UserProjectModel getByOAuthClientId(String oauthClientId);

    List<UserProjectModel> list(UserProjectModel query);

    SuperPage<UserProjectModel> pageQuery(UserProjectModel query, Long current, Long pageSize);

    Long add(UserProjectModel model);

    void updateById(UserProjectModel model);

    void deleteById(Long id);

    void deleteByRef(ProjectTypeEnum projectType, Long refId);
}
