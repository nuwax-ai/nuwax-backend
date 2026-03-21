package com.xspaceagi.im.web.util;

import com.xspaceagi.agent.core.adapter.dto.config.AgentConfigDto;
import com.xspaceagi.im.application.dto.ImChannelConfigResponse;
import com.xspaceagi.im.infra.dao.enitity.ImChannelConfig;
import com.xspaceagi.im.web.dto.ImChannelConfigSaveRequest;
import com.xspaceagi.system.application.util.DefaultIconUrlUtil;

public class ImDtoConvertor {

    public static ImChannelConfigResponse toResponse(ImChannelConfig config, AgentConfigDto agentConfig) {
        if (config == null) {
            return null;
        }
        ImChannelConfigResponse response = new ImChannelConfigResponse();
        response.setId(config.getId());
        response.setChannel(config.getChannel());
        response.setTargetType(config.getTargetType());
        response.setTargetId(config.getTargetId());
        response.setAgentId(config.getAgentId());
        response.setEnabled(config.getEnabled());
        response.setConfigData(config.getConfigData());
        response.setOutputMode(config.getOutputMode());
        response.setName(config.getName());
        response.setSpaceId(config.getSpaceId());
        response.setCreated(config.getCreated());
        response.setCreatorId(config.getCreatorId());
        response.setCreatorName(config.getCreatorName());
        response.setModified(config.getModified());
        response.setModifiedId(config.getModifiedId());
        response.setModifiedName(config.getModifiedName());
        if (agentConfig != null) {
            response.setAgentName(agentConfig.getName());
            response.setAgentIcon(DefaultIconUrlUtil.setDefaultIconUrl(agentConfig.getIcon(), agentConfig.getName()));
            response.setAgentDescription(agentConfig.getDescription());
        }
        return response;
    }

    public static ImChannelConfig toEntity(ImChannelConfigSaveRequest request) {
        ImChannelConfig config = new ImChannelConfig();
        config.setId(request.getId());
        config.setChannel(request.getChannel());
        config.setTargetType(request.getTargetType());
        config.setAgentId(request.getAgentId());
        config.setEnabled(request.getEnabled());
        config.setConfigData(request.getConfigData());
        config.setOutputMode(request.getOutputMode());
        config.setSpaceId(request.getSpaceId());
        return config;
    }
}
