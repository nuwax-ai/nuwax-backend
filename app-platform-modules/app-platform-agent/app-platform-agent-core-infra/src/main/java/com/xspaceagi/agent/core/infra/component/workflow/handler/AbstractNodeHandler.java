package com.xspaceagi.agent.core.infra.component.workflow.handler;

import com.xspaceagi.agent.core.adapter.dto.config.Arg;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowNodeDto;
import com.xspaceagi.agent.core.infra.component.ArgExtractUtil;
import com.xspaceagi.agent.core.infra.component.BaseComponent;
import com.xspaceagi.agent.core.infra.component.workflow.WorkflowContext;
import com.xspaceagi.agent.core.infra.component.workflow.dto.LoopNodeExecutingDto;
import com.xspaceagi.agent.core.infra.component.workflow.dto.NodeExecuteResult;
import com.xspaceagi.agent.core.spec.enums.DataTypeEnum;
import com.xspaceagi.agent.core.spec.enums.SystemArgNameEnum;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public abstract class AbstractNodeHandler extends BaseComponent implements NodeHandler {
    public Mono<Object> execute(WorkflowContext workflowContext, WorkflowNodeDto node) {
        return Mono.create(emitter -> executorService.submit(() -> {
            try {
                Object object = executeNode(workflowContext, node);
                emitter.success(object);
            } catch (Exception e) {
                emitter.error(e);
            }
        }));
    }

    protected Object executeNode(WorkflowContext workflowContext, WorkflowNodeDto node) {
        return Map.of();
    }

    protected Map<String, Object> extraBindValueMap(WorkflowContext workflowContext, WorkflowNodeDto node, List<?> args) {
        if (args == null) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> bindValueMap = new LinkedHashMap<>();
        Map<String, Object> logValueMap = new LinkedHashMap<>();
        for (Object arg : args) {
            if (arg instanceof Arg) {
                Arg arg1 = (Arg) arg;
                Object value = extraSubBindValue(workflowContext, node, arg1);
                if (value != null) {
                    bindValueMap.put(arg1.getName(), value);
                    if (arg1.getEnable()) {
                        logValueMap.put(arg1.getName(), value);
                    }
                }
            }
        }
        workflowContext.getNodeExecuteInputMap().computeIfAbsent(node.getId().toString(), k -> new ArrayList<>()).add(logValueMap);
        return bindValueMap;
    }

    private Object extraSubBindValue(WorkflowContext workflowContext, WorkflowNodeDto node, Arg arg) {
        if ((arg.getDataType() == DataTypeEnum.Object || arg.getDataType() == DataTypeEnum.Array_Object) && (arg.getBindValueType() == Arg.BindValueType.Input || arg.getBindValueType() == null)) {
            if (arg.getDataType() == DataTypeEnum.Object) {
                Map<String, Object> valueMap = new LinkedHashMap<>();
                if (arg.getSubArgs() != null) {
                    for (Arg arg1 : arg.getSubArgs()) {
                        Object value = extraSubBindValue(workflowContext, node, arg1);
                        if (value != null) {
                            valueMap.put(arg1.getName(), value);
                        }
                    }
                }
                return valueMap;
            }
            if (arg.getDataType() == DataTypeEnum.Array_Object) {
                List<Object> valueList = new ArrayList<>();
                if (arg.getSubArgs() != null) {
                    for (Arg arg1 : arg.getSubArgs()) {
                        Object value = extraSubBindValue(workflowContext, node, arg1);
                        if (value != null) {
                            valueList.add(value);
                        }
                    }
                }
                return valueList;
            }
            return null;
        } else {
            return extraBindValue(workflowContext, node, arg);
        }
    }

    protected Object extraBindValue(WorkflowContext workflowContext, WorkflowNodeDto node, Arg arg) {
        if (workflowContext.getTestParams() != null) {
            Object res = ArgExtractUtil.getTypeValue(arg.getDataType(), workflowContext.getTestParams().get(arg.getName()));
            if (res == null && (arg.getBindValueType() == Arg.BindValueType.Input || arg.getBindValueType() == null)) {
                return ArgExtractUtil.getTypeValue(arg.getDataType(), arg.getBindValue());
            }
            return res;
        }
        if (arg == null) {
            return null;
        }
        if (arg.getBindValueType() == Arg.BindValueType.Input || arg.getBindValueType() == null) {
            return ArgExtractUtil.getTypeValue(arg.getDataType(), arg.getBindValue());
        }
        String bindNodeId;
        try {
            bindNodeId = extractLeadingNumber(arg.getBindValue()).toString();
        } catch (Exception e) {
            log.warn("Error parsing binding value", e);
            return null;
        }
        // Variable
        if (arg.getBindValue() != null && arg.getBindValue().startsWith(bindNodeId + "-var")) {
            Map<String, Map<String, Object>> variableValueMap = workflowContext.getNodeVariableValueMap();
            return ArgExtractUtil.extraBindValue(variableValueMap, arg.getBindValue().replaceFirst(bindNodeId + "-var", bindNodeId));
        }
        // Input, only loop has -input
        if (arg.getBindValue() != null && arg.getBindValue().startsWith(bindNodeId + "-input")) {
            LoopNodeExecutingDto loopNodeDto = workflowContext.getExecutingLoopNodeMap().get(bindNodeId);
            if (loopNodeDto != null) {
                String[] keys = arg.getBindValue().split("\\.");
                // Referenced element
                Map<String, Object> currentLoopItemValueMap = loopNodeDto.getCurrentLoopItemValueMap();
                if (SystemArgNameEnum.INDEX.name().equals(keys[1])) {
                    return loopNodeDto.getIndex();
                }
                if (currentLoopItemValueMap != null) {
                    return ArgExtractUtil.extraBindValue(Map.of(bindNodeId + "-input", currentLoopItemValueMap), arg.getBindValue());
                }
            }
            return null;
        }
        // Output
        if (node.getLoopNodeId() != null) {
            LoopNodeExecutingDto loopNodeDto = workflowContext.getExecutingLoopNodeMap().get(node.getLoopNodeId().toString());
            if (loopNodeDto != null) {
                NodeExecuteResult nodeExecuteResult = loopNodeDto.getNodeExecuteResultMap().get(bindNodeId);
                if (nodeExecuteResult != null) {
                    return ArgExtractUtil.extraBindValue(Map.of(bindNodeId, nodeExecuteResult.getData()), arg.getBindValue());
                }
            }
        }
        Map<String, NodeExecuteResult> nodeExecuteInfoDtoMap = workflowContext.getNodeExecuteResultMap();
        NodeExecuteResult nodeExecuteInfoDto = nodeExecuteInfoDtoMap.get(bindNodeId);
        if (nodeExecuteInfoDto != null) {
            return ArgExtractUtil.extraBindValue(Map.of(bindNodeId, nodeExecuteInfoDto.getData()), arg.getBindValue());
        }
        return null;
    }

    protected Object extractLoopNodeValue(Map<String, NodeExecuteResult> nodeExecuteInfoMap, Arg arg) {
        return extractLoopNodeValue(nodeExecuteInfoMap, arg, null);
    }

    protected Object extractLoopNodeValue(Map<String, NodeExecuteResult> nodeExecuteInfoMap, Arg arg, AtomicBoolean virtualExecuteStatus) {
        if (arg.getBindValueType() == Arg.BindValueType.Input) {
            return arg.getBindValue();
        }
        if (arg.getBindValue() == null) {
            return null;
        }
        String[] keys = arg.getBindValue().split("\\.");
        if (keys.length == 1) {
            return null;
        }
        try {
            Long.parseLong(keys[0]);
        } catch (NumberFormatException e) {
            return null;
        }
        NodeExecuteResult nodeExecuteResult = nodeExecuteInfoMap.get(keys[0]);
        if (nodeExecuteResult == null) {
            return null;
        }
        if (nodeExecuteResult.isVirtualExecute() && virtualExecuteStatus != null) {
            virtualExecuteStatus.set(true);
        }
        return ArgExtractUtil.extraBindValue(Map.of(keys[0], nodeExecuteResult.getData()), arg.getBindValue());
    }

    private Long extractLeadingNumber(String key) {
        Pattern pattern = Pattern.compile("^\\d+");
        Matcher matcher = pattern.matcher(key);
        if (matcher.find()) {
            return Long.parseLong(matcher.group());
        }
        return null;
    }

    @PreDestroy
    public void shutdown() {
        executorService.shutdown();
    }
}
