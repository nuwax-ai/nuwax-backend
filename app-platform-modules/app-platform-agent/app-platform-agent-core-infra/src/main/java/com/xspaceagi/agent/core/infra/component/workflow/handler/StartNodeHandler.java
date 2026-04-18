package com.xspaceagi.agent.core.infra.component.workflow.handler;

import com.xspaceagi.agent.core.adapter.dto.config.Arg;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowNodeDto;
import com.xspaceagi.agent.core.infra.component.workflow.WorkflowContext;
import com.xspaceagi.agent.core.spec.enums.SystemArgNameEnum;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.Assert;

import java.util.*;

import static com.xspaceagi.agent.core.infra.component.ArgExtractUtil.extraParams;

public class StartNodeHandler extends AbstractNodeHandler {

    @Override
    protected Object executeNode(WorkflowContext workflowContext, WorkflowNodeDto node) {
        //解析参数，将workflowContext中params值赋给node执行结果
        Map<String, Object> startNodeOutput = new HashMap<>();
        startNodeOutput.putAll(workflowContext.getAgentContext().getVariableParams());
        List<Arg> inputArgs = node.getNodeConfig().getInputArgs();
        Map<String, Object> inputMap = new LinkedHashMap<>();
        for (Arg inputArg : inputArgs) {
            if (StringUtils.isNotBlank(workflowContext.getAgentContext().getMessage()) && inputArg.getName().equals(SystemArgNameEnum.AGENT_USER_MSG.name())) {
                startNodeOutput.put(inputArg.getName(), workflowContext.getAgentContext().getMessage());
                continue;
            }
            Object value = extraParams(inputArg, workflowContext.getParams());
            if ((value == null || value.equals("")) && inputArg.isRequire()) {
                Assert.notNull(value, "Param " + inputArg.getName() + " cannot be left blank.");
            }
            startNodeOutput.put(inputArg.getName(), value);
            inputMap.put(inputArg.getName(), value);
        }
        workflowContext.getNodeExecuteInputMap().computeIfAbsent(node.getId().toString(), k -> new ArrayList<>()).add(inputMap);
        return startNodeOutput;
    }
}
