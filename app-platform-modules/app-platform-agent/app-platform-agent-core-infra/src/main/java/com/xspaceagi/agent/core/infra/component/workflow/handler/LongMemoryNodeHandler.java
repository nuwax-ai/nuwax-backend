package com.xspaceagi.agent.core.infra.component.workflow.handler;

import com.xspaceagi.agent.core.adapter.dto.config.AgentConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowNodeDto;
import com.xspaceagi.agent.core.infra.component.workflow.WorkflowContext;
import com.xspaceagi.memory.sdk.service.IMemoryRpcService;
import com.xspaceagi.system.application.dto.UserDto;
import org.apache.commons.lang3.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LongMemoryNodeHandler extends AbstractNodeHandler {

    @Override
    protected Object executeNode(WorkflowContext workflowContext, WorkflowNodeDto node) {
        Map<String, Object> output = new HashMap<>();
        IMemoryRpcService iMemoryRpcService = workflowContext.getWorkflowContextServiceHolder().getIMemoryRpcService();
        UserDto user = workflowContext.getAgentContext().getUser();
        AgentConfigDto agentConfig = workflowContext.getAgentContext().getAgentConfig();
        boolean filterSensitive = true;
        if (agentConfig != null) {
            filterSensitive = !user.getId().equals(agentConfig.getCreatorId());
        }
        Map<String, Object> params = extraBindValueMap(workflowContext, node, node.getNodeConfig().getInputArgs());
        String query = params.get("Query") == null ? "" : params.get("Query").toString();
        String memoriesMd = "";
        if (StringUtils.isBlank(query)) {
            memoriesMd = workflowContext.getAgentContext().getLongMemory();
        } else {
            memoriesMd = iMemoryRpcService.searchMemoriesMd(user.getTenantId(), user.getId(), agentConfig == null ? -1L : agentConfig.getId(), query, null, true, filterSensitive);
        }

        output.put("outputList", List.of(memoriesMd == null ? "" : memoriesMd));
        return output;
    }
}
