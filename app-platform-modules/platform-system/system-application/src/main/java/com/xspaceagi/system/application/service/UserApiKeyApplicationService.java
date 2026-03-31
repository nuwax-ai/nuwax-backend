package com.xspaceagi.system.application.service;

import com.xspaceagi.system.application.dto.UpdateApiKeyDto;
import com.xspaceagi.system.infra.dao.entity.OpenApiDefinition;
import com.xspaceagi.system.sdk.service.dto.UserAccessKeyDto;

import java.util.Date;
import java.util.List;

public interface UserApiKeyApplicationService {

    UserAccessKeyDto createUserApiKey(Long userId, String name, Date expire);

    void deleteUserApiKey(Long userId, String apiKey);

    void updateUserApiKey(UpdateApiKeyDto updateApiKeyDto);

    List<UserAccessKeyDto> getUserApiKeys(Long userId);

    List<OpenApiDefinition> queryOpenApiDefinitions(Long userId);
}
