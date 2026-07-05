package com.xspaceagi.modelproxy.sdk.service;

import com.xspaceagi.modelproxy.sdk.service.dto.BackendModelDto;
import com.xspaceagi.modelproxy.sdk.service.dto.FrontendModelDto;

/**
 * 模型API代理配置服务接口
 */
public interface IModelApiProxyConfigService {

    BackendModelDto getBackendModelConfig(String userApiKey, Long id, String traceId);

    FrontendModelDto generateUserFrontendModelConfig(Long tenantId, Long userId, Long agentId, BackendModelDto backendModel, String siteUrl);
}