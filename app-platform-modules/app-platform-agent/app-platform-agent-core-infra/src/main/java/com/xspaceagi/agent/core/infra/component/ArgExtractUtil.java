package com.xspaceagi.agent.core.infra.component;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.agent.core.adapter.dto.config.Arg;
import com.xspaceagi.agent.core.infra.component.agent.AgentContext;
import com.xspaceagi.agent.core.spec.enums.DataTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public class ArgExtractUtil {

    public static Object extraBindValue(Object currentValue, String bindKey) {
        String[] keys = bindKey.split("\\.");
        for (String key : keys) {
            if (StringUtils.isBlank(key)) {
                continue;
            }
            if (currentValue instanceof Map<?, ?>) {
                currentValue = ((Map<String, Object>) currentValue).get(key);
                continue;
            }
            if (currentValue instanceof List<?>) {
                List<Object> list = (List<Object>) currentValue;
                List<Object> result = new ArrayList<>();
                for (Object item : list) {
                    Object value = extraBindValue(item, key);
                    if (value != null) {
                        result.add(value);
                    }
                }
                currentValue = result;
                continue;
            }
            return null;
        }
        return currentValue;
    }

    // Extract and set parameters
    public static Object extraParams(Arg arg, Map<String, Object> params) {
        // If parameter is not enabled, directly return default value
        if (!arg.getEnable() && StringUtils.isNotBlank(arg.getBindValue())) {
            return getTypeValue(arg.getDataType(), arg.getBindValue());
        }
        Object paramValue = params.get(arg.getName());
        if (arg.getDataType() == DataTypeEnum.Object) {
            if (CollectionUtils.isEmpty(arg.getSubArgs())) {
                if (paramValue instanceof Map<?, ?>) {
                    return paramValue;
                }
                return Map.of();
            }
            Map<String, Object> subParams = new HashMap<>();
            for (Arg subArg : arg.getSubArgs()) {
                if (!(paramValue instanceof Map<?, ?>)) {
                    if (subArg.isRequire()) {
                        Assert.notNull(null, requireArgMsg(subArg));
                    }
                    continue;
                }
                Object subParamValue = extraParams(subArg, (Map<String, Object>) paramValue);
                if (subArg.isRequire() && (subParamValue == null || subParamValue.toString().equals(""))) {
                    Assert.notNull(null, requireArgMsg(subArg));
                }
                if (subParamValue != null) {
                    subParams.put(subArg.getName(), getTypeValue(subArg.getDataType(), subParamValue));
                }
            }
            return subParams;
        }
        if (arg.getDataType().name().startsWith("Array")) {
            if (CollectionUtils.isEmpty(arg.getSubArgs())) {
                if (paramValue instanceof List<?>) {
                    return paramValue;
                }
                arg.setSubArgs(new ArrayList<>());
            }
            List<Object> subParams = new ArrayList<>();
            if (!(paramValue instanceof List<?>) || CollectionUtils.isEmpty((List<?>) paramValue)) {
                return subParams;
            }
            // Array type parameter, if parameter value is array, process recursively
            for (Object param : (List<?>) paramValue) {
                // Validate required parameters
                List<Arg> requireSubArgs = arg.getSubArgs().stream().filter(Arg::isRequire).collect(Collectors.toList());
                if (param instanceof Map<?, ?>) {
                    Map<String, Object> subParamsMap = new HashMap<>();
                    for (Arg subArg : arg.getSubArgs()) {
                        Object subParamValue = extraParams(subArg, (Map<String, Object>) param);
                        if (subParamValue != null && !subParamValue.toString().equals("")) {
                            subParamsMap.put(subArg.getName(), getTypeValue(subArg.getDataType(), subParamValue));
                            requireSubArgs.remove(subArg);
                        }
                    }
                    subParams.add(subParamsMap);
                } else {
                    subParams.add(getTypeValue(arg.getDataType(), param));
                    if (!arg.getSubArgs().isEmpty()) {
                        requireSubArgs.remove(arg.getSubArgs().get(0));
                    }
                }
                if (!requireSubArgs.isEmpty()) {
                    Assert.notNull(null, requireArgMsg(requireSubArgs.get(0)));
                }
            }
            return subParams;
        }
        return getTypeValue(arg.getDataType(), paramValue);
    }

    public static Object getTypeValue(DataTypeEnum dataType, Object subParamValue) {
        if (subParamValue == null) {
            return null;
        }
        switch (dataType) {
            case String:
                return subParamValue.toString();
            case Array_String:
                if (subParamValue instanceof List<?>) {
                    ((List<?>) subParamValue).removeIf(o -> o == null);
                    return ((List<?>) subParamValue).stream().map(Object::toString).collect(Collectors.toList());
                }
                return subParamValue;
            case Integer:
                try {
                    return Integer.parseInt(subParamValue.toString());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Data [" + subParamValue + "] does not match configured type");
                }
            case Array_Integer:
                if (subParamValue instanceof List<?>) {
                    ((List<?>) subParamValue).removeIf(o -> o == null);
                    return ((List<?>) subParamValue).stream().map(Object::toString).map(Integer::parseInt).collect(Collectors.toList());
                }
                return subParamValue;
            case Number:
                try {
                    return Double.parseDouble(subParamValue.toString());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Data [" + subParamValue + "] does not match configured type");
                }
            case Array_Number:
                if (subParamValue instanceof List<?>) {
                    ((List<?>) subParamValue).removeIf(o -> o == null);
                    return ((List<?>) subParamValue).stream().map(Object::toString).map(Double::parseDouble).collect(Collectors.toList());
                }
                return subParamValue;
            case Boolean:
                return Boolean.parseBoolean(subParamValue.toString());
        }
        return subParamValue;
    }

    public static void setArgDefaultValue(AgentContext agentContext, List<Arg> argBindConfigDtos, Object inputArgs, Map<String, Object> params, List<String> errorList) {
        if (org.apache.commons.collections4.CollectionUtils.isEmpty(argBindConfigDtos) || inputArgs == null) {
            return;
        }
        List<Object> inputs = inputArgs instanceof List ? (List<Object>) inputArgs : Collections.singletonList(inputArgs);
        inputs.forEach(input -> argBindConfigDtos.forEach(argBindConfigDto -> {
            if (argBindConfigDto.getDataType() == DataTypeEnum.Object) {
                if (input instanceof Map<?, ?>) {
                    Map<String, Object> inputMap = (Map<String, Object>) input;
                    if (!inputMap.containsKey(argBindConfigDto.getName())) {
                        inputMap.put(argBindConfigDto.getName(), new HashMap<>());
                    }
                    setArgDefaultValue(agentContext, argBindConfigDto.getSubArgs(), inputMap.get(argBindConfigDto.getName()), params, errorList);
                } else if (argBindConfigDto.isRequire()) {
                    errorList.add(requireArgMsg(argBindConfigDto));
                }
            } else if (argBindConfigDto.getDataType() == DataTypeEnum.Array_Object) {
                if (input instanceof Map<?, ?>) {
                    Map<String, Object> inputMap = (Map<String, Object>) input;
                    if (!inputMap.containsKey(argBindConfigDto.getName())) {
                        inputMap.put(argBindConfigDto.getName(), new ArrayList<>());
                    }
                    List<Object> inputList = (List<Object>) inputMap.get(argBindConfigDto.getName());
                    if (org.apache.commons.collections4.CollectionUtils.isEmpty(inputList)) {
                        inputList.add(new ArrayList<>());
                    }
                    if (CollectionUtils.isEmpty(argBindConfigDto.getSubArgs())) {
                        if (argBindConfigDto.getBindValueType() == Arg.BindValueType.Reference && agentContext != null) {
                            Object bindVariableValue = agentContext.getVariableParams().get(argBindConfigDto.getBindValue());
                            if (bindVariableValue != null) {
                                inputMap.put(argBindConfigDto.getName(), bindVariableValue);
                                argBindConfigDto.setBindValue(JSON.toJSONString(bindVariableValue));
                            }
                        }
                    } else {
                        setArgDefaultValue(agentContext, argBindConfigDto.getSubArgs(), inputList, params, errorList);
                    }
                } else if (argBindConfigDto.isRequire()) {
                    errorList.add(requireArgMsg(argBindConfigDto));
                }
            } else if (input instanceof Map<?, ?>) {
                Map<String, Object> inputMap = (Map<String, Object>) input;
                if (!inputMap.containsKey(argBindConfigDto.getName()) || !argBindConfigDto.getEnable()) {
                    if (argBindConfigDto.getBindValueType() == Arg.BindValueType.Reference && agentContext != null) {
                        Object bindVariableValue = agentContext.getVariableParams().get(argBindConfigDto.getBindValue());
                        if (params != null) {
                            Object value = params.get(argBindConfigDto.getBindValue());
                            if (value != null) {
                                bindVariableValue = value;
                            }
                        }
                        if (bindVariableValue != null) {
                            argBindConfigDto.setBindValueType(Arg.BindValueType.Input);
                            inputMap.put(argBindConfigDto.getName(), bindVariableValue);
                            // Determine if bindVariableValue is a basic type, otherwise convert to JSON
                            if (bindVariableValue instanceof String) {
                                argBindConfigDto.setBindValue((String) bindVariableValue);
                            } else if (bindVariableValue instanceof Number) {
                                argBindConfigDto.setBindValue(String.valueOf(bindVariableValue));
                            } else if (bindVariableValue instanceof Double) {
                                argBindConfigDto.setBindValue(String.valueOf(bindVariableValue));
                            } else if (bindVariableValue instanceof Boolean) {
                                argBindConfigDto.setBindValue(String.valueOf(bindVariableValue));
                            } else {
                                argBindConfigDto.setBindValue(JSON.toJSONString(bindVariableValue));
                            }
                        }
                    } else if (StringUtils.isNotBlank(argBindConfigDto.getBindValue())) {
                        inputMap.put(argBindConfigDto.getName(), argBindConfigDto.getBindValue());
                    }
                }
                if (argBindConfigDto.isRequire() && isArgBlankValue(argBindConfigDto, inputMap)) {
                    errorList.add(requireArgMsg(argBindConfigDto));
                }
            }
        }));
    }

    public static String requireArgMsg(Arg arg) {
        if (StringUtils.isBlank(arg.getDescription())) {
            return "Required parameter[" + arg.getName() + "] is missing";
        }
        return "Required parameter[" + arg.getName() + "(" + arg.getDescription() + ")" + "] is missing";
    }

    public static boolean isArgBlankValue(Arg arg, Map<?, ?> params) {
        if (params == null) {
            return true;
        }
        Object value = params.get(arg.getName());
        if (value == null) {
            return true;
        }
        if (value.toString().equals("")) {
            return true;
        }
        return false;
    }
}
