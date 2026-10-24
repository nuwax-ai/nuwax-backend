package com.xspaceagi.userapp.infra.translator;

import com.xspaceagi.userapp.domain.model.UserProjectWorkspaceModel;
import com.xspaceagi.userapp.infra.dao.entity.UserProjectWorkspace;

public interface IUserProjectWorkspaceTranslator {

    UserProjectWorkspaceModel convertToModel(UserProjectWorkspace entity);

    UserProjectWorkspace convertToEntity(UserProjectWorkspaceModel model);
}
