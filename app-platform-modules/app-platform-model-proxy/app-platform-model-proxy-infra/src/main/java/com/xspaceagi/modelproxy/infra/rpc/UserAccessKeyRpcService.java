package com.xspaceagi.modelproxy.infra.rpc;

import com.xspaceagi.system.sdk.service.UserAccessKeyApiService;
import com.xspaceagi.system.sdk.service.dto.UserAccessKeyDto;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserAccessKeyRpcService {

    @Resource
    private UserAccessKeyApiService userAccessKeyApiService;

    public UserAccessKeyDto newAccessKey(Long tenantId, Long userId, UserAccessKeyDto.AKTargetType targetType, String targetId, UserAccessKeyDto.UserAccessKeyConfig userAccessKeyConfig) {
        return TenantFunctions.callWithIgnoreCheck(() -> userAccessKeyApiService.newAccessKey(tenantId, userId, targetType, targetId, userAccessKeyConfig));
    }

    public UserAccessKeyDto queryAgentModelAccessKey(Long userId, String agentId, Long modelId) {
        return TenantFunctions.callWithIgnoreCheck(() -> {
            List<UserAccessKeyDto> userAccessKeys = userAccessKeyApiService.queryAccessKeyList(userId, UserAccessKeyDto.AKTargetType.AgentModel, agentId);
            return userAccessKeys.stream().filter(userAccessKeyDto -> userAccessKeyDto.getConfig().getModelId().equals(modelId)).findFirst().orElse(null);
        });
    }

    public UserAccessKeyDto queryAccessKey(String apiKey) {
        return TenantFunctions.callWithIgnoreCheck(() -> userAccessKeyApiService.queryAccessKey(apiKey));
    }

    public void updateAccessKey(Long id, UserAccessKeyDto.UserAccessKeyConfig config) {
        TenantFunctions.runWithIgnoreCheck(() -> userAccessKeyApiService.updateUserAccessKeyConfig(id, config));
    }
}
