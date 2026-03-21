package com.xspaceagi.im.domain.service;


import com.xspaceagi.im.infra.dao.enitity.ImSession;

public interface ImSessionDomainService {

    ImSession findSession(ImSession imSession);

    ImSession saveSession(ImSession imSession);

    boolean deleteSession(ImSession imSession);
}