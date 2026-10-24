package com.xspaceagi.userapp.infra.translator;

import com.xspaceagi.userapp.domain.model.OAuth2ScopeApplyModel;
import com.xspaceagi.userapp.infra.dao.entity.OAuth2ScopeApply;

public interface IOAuth2ScopeApplyTranslator {

    OAuth2ScopeApplyModel convertToModel(OAuth2ScopeApply entity);

    OAuth2ScopeApply convertToEntity(OAuth2ScopeApplyModel model);
}
