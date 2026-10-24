package com.xspaceagi.agent.core.infra.component.workflow.handler;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.VariableNodeConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowNodeDto;
import com.xspaceagi.agent.core.infra.component.workflow.WorkflowContext;
import com.xspaceagi.agent.core.infra.component.workflow.dto.LoopNodeExecutingDto;
import com.xspaceagi.agent.core.spec.enums.GlobalVariableEnum;
import org.apache.commons.collections4.CollectionUtils;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class VariableNodeHandler extends AbstractNodeHandler {

    @Override
    public Mono<Object> execute(WorkflowContext workflowContext, WorkflowNodeDto node) {
        VariableNodeConfigDto variableNodeConfigDto = (VariableNodeConfigDto) node.getNodeConfig();
        //设置变量
        if (variableNodeConfigDto.getConfigType() == VariableNodeConfigDto.ConfigTypeEnum.SET_VARIABLE) {
            if (CollectionUtils.isNotEmpty(variableNodeConfigDto.getInputArgs())) {
                Map<String, Object> params = extraBindValueMap(workflowContext, node, variableNodeConfigDto.getInputArgs());
                if (node.getLoopNodeId() != null && node.getLoopNodeId() > 0) {
                    LoopNodeExecutingDto loopNodeDto = workflowContext.getExecutingLoopNodeMap().get(node.getLoopNodeId().toString());
                    if (loopNodeDto != null) {
                        variableNodeConfigDto.getInputArgs().forEach(arg -> {
                            //如果是系统变量，禁止设置
                            if (GlobalVariableEnum.isSystemVariable(arg.getName())) {
                                return;
                            }
                            Object value = extractLoopNodeValue(loopNodeDto.getNodeExecuteResultMap(), arg);
                            if (value != null) {
                                if (value instanceof List<?>) {
                                    value = ((List<?>) value).stream().map(m -> JSON.parseObject(JSON.toJSONString(m))).collect(Collectors.toList());
                                }
                                params.put(arg.getName(), value);
                            }
                        });
                    }
                }
                if (!params.isEmpty()) {
                    params.forEach((k, value) -> {
                        if (value instanceof List<?>) {
                            value = ((List<?>) value).stream().map(m -> JSON.parseObject(JSON.toJSONString(m))).collect(Collectors.toList());
                            params.put(k, value);
                        }
                    });
                    workflowContext.getAgentContext().getVariableParams().putAll(params);

                    //循环内部变量
                    if (node.getLoopNodeId() != null && node.getLoopNodeId() > 0) {
                        Map<String, Object> nodeVariableValueMap = workflowContext.getNodeVariableValueMap().get(node.getLoopNodeId().toString());
                        if (nodeVariableValueMap != null) {
                            nodeVariableValueMap.putAll(params);
                        }
                    }
                }
            }
            return Mono.just(Map.of("isSuccess", true));
        } else {
            if (CollectionUtils.isNotEmpty(variableNodeConfigDto.getOutputArgs())) {
                Map<String, Object> outputMap = new HashMap<>();
                variableNodeConfigDto.getOutputArgs().forEach(outputArg -> {
                    //优先读取循环内部变量
                    if (node.getLoopNodeId() != null && node.getLoopNodeId() > 0) {
                        Map<String, Object> var = workflowContext.getNodeVariableValueMap().get(node.getLoopNodeId().toString());
                        if (var != null) {
                            Object value = var.get(outputArg.getBindValue());
                            if (value != null) {
                                outputMap.put(outputArg.getName(), value);
                                return;
                            }
                        }
                    }
                    Object value = workflowContext.getAgentContext().getVariableParams().get(outputArg.getBindValue());
                    if (value != null) {
                        outputMap.put(outputArg.getName(), value);
                    }
                });
                return Mono.just(outputMap);
            }
        }
        return Mono.just(Map.of());
    }
}
