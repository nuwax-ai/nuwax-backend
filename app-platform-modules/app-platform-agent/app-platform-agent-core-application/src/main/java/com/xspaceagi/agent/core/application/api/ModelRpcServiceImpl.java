package com.xspaceagi.agent.core.application.api;

import com.xspaceagi.agent.core.adapter.application.ModelApplicationService;
import com.xspaceagi.agent.core.adapter.dto.config.ModelConfigDto;
import com.xspaceagi.agent.core.adapter.repository.entity.ModelConfig;
import com.xspaceagi.agent.core.sdk.IModelRpcService;
import com.xspaceagi.agent.core.sdk.dto.ModelInfoDto;
import com.xspaceagi.system.spec.common.RequestContext;
import jakarta.annotation.Resource;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ModelRpcServiceImpl implements IModelRpcService {

    @Resource
    private ModelApplicationService modelApplicationService;

    @Override
    public <T> T call(String sysPrompt, String userPrompt, ParameterizedTypeReference<T> type) {
        return modelApplicationService.call(sysPrompt, userPrompt, type);
    }

    @Override
    public ModelInfoDto getModelInfo(Long modelId) {
        ModelConfigDto modelConfigDto = modelApplicationService.queryModelConfigById(modelId);
        if (modelConfigDto == null) {
            return null;
        }
        return ModelInfoDto.builder()
                .id(modelConfigDto.getId())
                .creatorId(modelConfigDto.getCreatorId())
                .isTenantModel(modelConfigDto.getScope() == ModelConfig.ModelScopeEnum.Tenant)
                .name(modelConfigDto.getName())
                .description(modelConfigDto.getDescription())
                .spaceId(modelConfigDto.getSpaceId())
                .build();
    }

    @Override
    public List<ModelInfoDto> getModelInfoList(List<Long> modelIds) {
        return modelApplicationService.queryModelConfigListByIds(modelIds).stream().map(modelConfigDto -> ModelInfoDto.builder().id(modelConfigDto.getId())
                .name(modelConfigDto.getName())
                .creatorId(modelConfigDto.getCreatorId())
                .isTenantModel(modelConfigDto.getScope() == ModelConfig.ModelScopeEnum.Tenant)
                .description(modelConfigDto.getDescription())
                .spaceId(modelConfigDto.getSpaceId())
                .build()).collect(Collectors.toList());
    }

    @Override
    public List<ModelInfoDto> getUserModels(Long tenantId, Long userId) {
        List<ModelConfigDto> models;
        if (RequestContext.get() == null) {
            try {
                RequestContext.setThreadTenantId(tenantId);
                models = queryUserModels(userId);
            } finally {
                RequestContext.remove();
            }
        } else {
            models = queryUserModels(userId);
        }
        return models.stream().map(modelConfigDto -> ModelInfoDto.builder()
                .isTenantModel(modelConfigDto.getScope() == ModelConfig.ModelScopeEnum.Tenant)
                .description(modelConfigDto.getDescription())
                .spaceId(modelConfigDto.getSpaceId())
                .id(modelConfigDto.getId())
                .name(modelConfigDto.getName())
                .creatorId(modelConfigDto.getCreatorId())
                .provider(modelConfigDto.getProviderName())
                .build()
        ).collect(Collectors.toList());
    }

    private List<ModelConfigDto> queryUserModels(Long userId) {
        List<ModelConfigDto> models = modelApplicationService.getMySystemModels(userId, "system");
        List<ModelConfigDto> spaceModels = modelApplicationService.getMySystemModels(userId, "space");
        models.addAll(spaceModels);
        return models;
    }
}
