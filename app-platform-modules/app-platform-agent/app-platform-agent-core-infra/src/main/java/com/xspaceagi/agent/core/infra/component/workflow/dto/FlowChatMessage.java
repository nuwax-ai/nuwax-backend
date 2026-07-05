package com.xspaceagi.agent.core.infra.component.workflow.dto;

import com.xspaceagi.agent.core.infra.component.agent.AgentContext;
import com.xspaceagi.agent.core.infra.component.model.dto.CallMessage;
import lombok.Data;

@Data
public class FlowChatMessage extends CallMessage {
    private AgentContext agentContext;
}
