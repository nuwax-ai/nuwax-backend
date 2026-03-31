package com.xspaceagi.system.infra.dao.service;

import com.xspaceagi.system.infra.dao.entity.OpenApiDefinition;

import java.util.List;

public interface OpenApiDefinitionService {

    List<OpenApiDefinition> queryAll();
}
