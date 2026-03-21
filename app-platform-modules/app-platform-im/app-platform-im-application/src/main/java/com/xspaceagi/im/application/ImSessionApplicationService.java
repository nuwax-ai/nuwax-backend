package com.xspaceagi.im.application;

import com.xspaceagi.im.infra.dao.enitity.ImSession;

public interface ImSessionApplicationService {

    Long getConversationId(ImSession imSession);

}