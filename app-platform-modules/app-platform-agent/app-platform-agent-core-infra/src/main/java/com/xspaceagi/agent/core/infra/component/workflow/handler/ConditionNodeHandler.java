package com.xspaceagi.agent.core.infra.component.workflow.handler;

import com.xspaceagi.agent.core.adapter.dto.config.workflow.ConditionNodeConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowNodeDto;
import com.xspaceagi.agent.core.infra.component.workflow.WorkflowContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import org.apache.commons.collections4.CollectionUtils;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.hutool.core.text.CharSequenceUtil.contains;

public class ConditionNodeHandler extends AbstractNodeHandler {

    @Override
    public Mono<Object> execute(WorkflowContext workflowContext, WorkflowNodeDto node) {
        ConditionNodeConfigDto conditionNodeConfig = (ConditionNodeConfigDto) node.getNodeConfig();
        if (CollectionUtils.isEmpty(conditionNodeConfig.getConditionBranchConfigs())) {
            return Mono.error(BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentWorkflowConditionBranchesEmpty));
        }
        Set<Long> reachableNextNodeIds = new HashSet<>();
        Set<Long> unReachableNextNodeIds = new HashSet<>();
        Boolean evalResult = null;
        for (ConditionNodeConfigDto.ConditionBranchConfigDto conditionBranchConfig : conditionNodeConfig.getConditionBranchConfigs()) {
            if (evalResult != null && evalResult && CollectionUtils.isNotEmpty(conditionBranchConfig.getNextNodeIds())) {
                unReachableNextNodeIds.addAll(conditionBranchConfig.getNextNodeIds());
                continue;
            }
            if (conditionBranchConfig.getConditionArgs() == null) {
                conditionBranchConfig.setConditionArgs(List.of());
            }
            evalResult = null;
            if (CollectionUtils.isNotEmpty(conditionBranchConfig.getNextNodeIds())) {
                for (ConditionNodeConfigDto.ConditionArgDto conditionArgDto : conditionBranchConfig.getConditionArgs()) {
                    Object firstValue = extraBindValue(workflowContext, node, conditionArgDto.getFirstArg());
                    Object secondValue = extraBindValue(workflowContext, node, conditionArgDto.getSecondArg());
                    boolean res = eval(firstValue, secondValue, conditionArgDto.getCompareType());
                    if (conditionBranchConfig.getConditionType() == ConditionNodeConfigDto.ConditionTypeEnum.OR) {
                        evalResult = evalResult == null ? res : evalResult || res;
                    } else {
                        evalResult = evalResult == null ? res : evalResult && res;
                    }
                }
                if (evalResult != null && evalResult) {
                    reachableNextNodeIds.addAll(conditionBranchConfig.getNextNodeIds());
                } else {
                    if (conditionBranchConfig.getBranchType() == ConditionNodeConfigDto.BranchTypeEnum.ELSE) {
                        reachableNextNodeIds.addAll(conditionBranchConfig.getNextNodeIds());
                    } else {
                        unReachableNextNodeIds.addAll(conditionBranchConfig.getNextNodeIds());
                    }
                }
            }
        }
        unReachableNextNodeIds.removeAll(reachableNextNodeIds);
        node.setUnreachableNextNodeIds(unReachableNextNodeIds);
        return Mono.just(Map.of());
    }

    private boolean eval(Object firstValue, Object secondValue, ConditionNodeConfigDto.CompareTypeEnum compareType) {
        String firstValueStr = firstValue == null ? null : firstValue.toString();
        if (firstValue != null && (firstValue instanceof List<?>) && ((List<?>) firstValue).size() > 0) {
            Object val = ((List<?>) firstValue).get(0);
            firstValueStr = val == null ? null : val.toString();
        }
        switch (compareType) {
            case EQUAL -> {
                if (firstValue == null) {
                    return secondValue == null;
                }
                if (secondValue == null) {
                    secondValue = "";
                }
                return firstValueStr.equals(secondValue.toString());
            }
            case NOT_EQUAL -> {
                if (firstValue == null) {
                    return secondValue != null;
                }
                if (secondValue == null) {
                    secondValue = "";
                }
                return !firstValueStr.equals(secondValue.toString());
            }
            case GREATER_THAN -> {
                return compareValues(firstValueStr, secondValue) > 0;
            }
            case GREATER_THAN_OR_EQUAL -> {
                return compareValues(firstValueStr, secondValue) >= 0;
            }
            case LESS_THAN -> {
                return compareValues(firstValueStr, secondValue) < 0;
            }
            case LESS_THAN_OR_EQUAL -> {
                return compareValues(firstValueStr, secondValue) <= 0;
            }
            case LENGTH_GREATER_THAN_OR_EQUAL -> {
                return compareLength(firstValue, secondValue) >= 0;
            }
            case LENGTH_GREATER_THAN -> {
                return compareLength(firstValue, secondValue) > 0;
            }
            case LENGTH_LESS_THAN -> {
                return compareLength(firstValue, secondValue) < 0;
            }
            case LENGTH_LESS_THAN_OR_EQUAL -> {
                return compareLength(firstValue, secondValue) <= 0;
            }
            case CONTAINS -> {
                return contains(firstValueStr, secondValue.toString());
            }
            case NOT_CONTAINS -> {
                return !contains(firstValueStr, secondValue.toString());
            }
            case MATCH_REGEX -> {
                if (firstValue == null || secondValue == null) {
                    return false;
                }
                return firstValueStr.matches(secondValue.toString());
            }
            case IS_NULL -> {
                return firstValueStr == null || firstValueStr.equals("");
            }
            case NOT_NULL -> {
                return firstValueStr != null && !firstValueStr.equals("");
            }
        }
        return false;
    }

    private int compareValues(Object firstValue, Object secondValue) {
        if (firstValue == null || secondValue == null) {
            return -1;
        }
        try {
            return new BigDecimal(firstValue.toString()).compareTo(new BigDecimal(secondValue.toString()));
        } catch (Exception e) {
            return -1;
        }
    }

    private int compareLength(Object firstValue, Object secondValue) {
        if (firstValue == null || secondValue == null) {
            return -1;
        }
        try {
            if (firstValue instanceof List<?> && secondValue instanceof String) {
                try {
                    int val = Integer.parseInt(secondValue.toString());
                    return ((List<?>) firstValue).size() - val;
                } catch (Exception e) {
                    // ignore
                }
            }
            if (firstValue instanceof List<?> && secondValue instanceof List<?>) {
                return ((List<?>) firstValue).size() - ((List<?>) secondValue).size();
            }
            return firstValue.toString().length() - secondValue.toString().length();
        } catch (Exception e) {
            return -1;
        }
    }
}
