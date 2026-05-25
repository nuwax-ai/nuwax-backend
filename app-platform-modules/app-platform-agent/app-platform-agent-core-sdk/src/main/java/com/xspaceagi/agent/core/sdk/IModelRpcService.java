package com.xspaceagi.agent.core.sdk;

import com.xspaceagi.agent.core.sdk.dto.ModelInfoDto;
import org.springframework.core.ParameterizedTypeReference;

import java.util.List;

public interface IModelRpcService {

    <T> T call(String sysPrompt, String userPrompt, ParameterizedTypeReference<T> type);

    ModelInfoDto getModelInfo(Long modelId);

    List<ModelInfoDto> getModelInfoList(List<Long> modelIds);

    List<ModelInfoDto> getUserModels(Long tenantId, Long userId);
}
