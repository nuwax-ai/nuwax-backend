package com.xspaceagi.agent.core.infra.component.workflow.handler;

import com.xspaceagi.agent.core.adapter.dto.config.workflow.TableDataDeleteNodeConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.TableNodeConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowNodeDto;
import com.xspaceagi.agent.core.infra.component.table.dto.TableExecutorContext;
import com.xspaceagi.agent.core.infra.component.workflow.WorkflowContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class AbstractTableNodeHandler extends AbstractNodeHandler {

    protected Mono<Object> executeSQL(WorkflowContext workflowContext, TableNodeConfigDto tableNodeConfigDto, String sql, Map<String, Object> args) {
        TableExecutorContext tableExecutorContext = new TableExecutorContext();
        tableExecutorContext.setAgentContext(workflowContext.getAgentContext());
        tableExecutorContext.setTableId(tableNodeConfigDto.getTableId());
        tableExecutorContext.setSql(sql);
        tableExecutorContext.setArgs(args);
        tableExecutorContext.setExtArgs(new HashMap<>());
        return Mono.create(sink -> workflowContext.getWorkflowContextServiceHolder().getTableExecutor().execute(tableExecutorContext).timeout(Duration.ofMinutes(1))
                .onErrorResume(throwable -> {
                    log.warn("executeSQL error", throwable);
                    if (throwable instanceof TimeoutException) {
                        return Mono.error(new TimeoutException("SQL execution timeout"));
                    }
                    return Mono.error(throwable);
                })
                .doOnError(e -> sink.error(e))
                .subscribe(response -> {
                    Map<String, Object> tableResponseDto = new HashMap<>();
                    if (response.getOutputList() != null) {
                        tableResponseDto.put("outputList", response.getOutputList());
                    }
                    if (response.getId() != null) {
                        tableResponseDto.put("id", response.getId());
                    }
                    tableResponseDto.put("rowNum", response.getRomNum());
                    tableResponseDto.put("success", response.isSuccess());
                    sink.success(tableResponseDto);
                }));
    }

    protected Map<String, Object> extractConditionArgs(WorkflowContext workflowContext, WorkflowNodeDto node, List<TableNodeConfigDto.ConditionArgDto> conditionArgs) {
        Map<String, Object> params = new HashMap<>();
        if (CollectionUtils.isEmpty(conditionArgs)) {
            return params;
        }
        Iterator<TableDataDeleteNodeConfigDto.ConditionArgDto> iterator = conditionArgs.iterator();
        AtomicInteger index = new AtomicInteger(0);
        while (iterator.hasNext()) {
            TableDataDeleteNodeConfigDto.ConditionArgDto conditionArgDto = iterator.next();
            if (conditionArgDto.getFirstArg() == null) {
                throw new IllegalArgumentException("Parameter cannot be empty; check configuration");
            }
            Object value = extraBindValue(workflowContext, node, conditionArgDto.getSecondArg());
            String name = conditionArgDto.getFirstArg().getName() == null ? conditionArgDto.getFirstArg().getBindValue() : conditionArgDto.getFirstArg().getName();
            if (conditionArgDto.getCompareType() == TableNodeConfigDto.CompareTypeEnum.IN || conditionArgDto.getCompareType() == TableNodeConfigDto.CompareTypeEnum.NOT_IN) {
                if (value instanceof List) {
                    List<Object> list = (List<Object>) value;
                    if (CollectionUtils.isEmpty(list)) {
                        throw new IllegalArgumentException("IN/NOT_IN condition value cannot be empty");
                    }
                } else if (value == null) {
                    throw new IllegalArgumentException("IN/NOT_IN condition value cannot be empty");
                } else {
                    value = List.of(value);
                }
            } else {
                if (value != null && (value instanceof List)) {
                    value = value.toString();
                }
            }
            value = value == null ? "" : value;
            if (params.containsKey(name)) {
                params.put(name + "_" + index.incrementAndGet(), value);
            } else {
                params.put(name, value);
            }
        }
        return params;
    }

}
