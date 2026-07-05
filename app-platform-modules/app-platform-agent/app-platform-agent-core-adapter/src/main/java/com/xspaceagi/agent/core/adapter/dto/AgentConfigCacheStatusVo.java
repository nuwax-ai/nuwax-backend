package com.xspaceagi.agent.core.adapter.dto;

import com.xspaceagi.agent.core.adapter.dto.config.AgentConfigDto;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import lombok.Data;

import java.util.List;

@Data
public class AgentConfigCacheStatusVo {

    private AgentConfigDto agentConfig;
    private List<AgentComponentCacheStatusVo> components;

    @Data
    public static class AgentComponentCacheStatusVo {
        private Published.TargetType targetType;
        private Long targetId;
        private Long lastUpdated;
    }
}
