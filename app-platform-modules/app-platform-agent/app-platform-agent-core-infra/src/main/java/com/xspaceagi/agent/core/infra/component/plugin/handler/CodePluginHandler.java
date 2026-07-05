package com.xspaceagi.agent.core.infra.component.plugin.handler;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.agent.core.adapter.dto.config.Arg;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.CodePluginConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.PluginConfigDto;
import com.xspaceagi.agent.core.infra.code.CodeArgDto;
import com.xspaceagi.agent.core.infra.code.CodeExecuteResultDto;
import com.xspaceagi.agent.core.infra.code.CodeExecuteService;
import com.xspaceagi.agent.core.infra.component.ArgExtractUtil;
import com.xspaceagi.agent.core.infra.component.plugin.PluginContext;
import com.xspaceagi.agent.core.spec.enums.DataTypeEnum;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.xspaceagi.agent.core.infra.component.ArgExtractUtil.extraParams;

@Component
public class CodePluginHandler extends AbstractPluginHandler {

    private static CodeExecuteService codeExecuteService;

    @Autowired
    public void setCodeExecuteService(CodeExecuteService codeExecuteService) {
        CodePluginHandler.codeExecuteService = codeExecuteService;
    }

    protected Object execute0(PluginContext pluginContext) {
        CodePluginConfigDto codePluginConfigDto = (CodePluginConfigDto) pluginContext.getPluginConfig();
        CodeArgDto codeArgDto = new CodeArgDto();
        codeArgDto.setCode(codePluginConfigDto.getCode());
        codeArgDto.setEngineType(codePluginConfigDto.getCodeLang().getName());
        //用于python虚拟环境隔离,可以去插件id,没有取用户id
        var envId = Optional.ofNullable(pluginContext.getPluginConfig())
                .map(PluginConfigDto::getId)
                .map(String::valueOf)
                .orElse(String.valueOf(pluginContext.getUserId()));
        codeArgDto.setUserId(envId);
        Map<String, Object> params = new HashMap<>();
        // 提取参数
        for (Arg inputArg : codePluginConfigDto.getInputArgs()) {
            Object value = extraParams(inputArg, pluginContext.getParams());
            if ((value == null || value.equals("")) && inputArg.isRequire()) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                        ArgExtractUtil.requireArgMsg(inputArg));
            }
            params.put(inputArg.getName(), value);
        }
        params.put("SYS_VARS", pluginContext.getParams().get("SYS_VARS"));
        codeArgDto.setParams(params);
        CodeExecuteResultDto codeExecuteResultDto = codeExecuteService.execute(codeArgDto);
        pluginContext.setLogs(codeExecuteResultDto.getLogs());
        pluginContext.setError(codeExecuteResultDto.getError());
        if (Objects.isNull(codeExecuteResultDto.getSuccess()) || !codeExecuteResultDto.getSuccess()) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                    codeExecuteResultDto.getError() == null ? "" : codeExecuteResultDto.getError());
        }
        Map<String, Object> resultMap;
        Map<String, Object> outputMap = new HashMap<>();
        if (!(codeExecuteResultDto.getResult() instanceof Map<?, ?>)) {
            if (codeExecuteResultDto.getResult() != null && JSON.isValid(codeExecuteResultDto.getResult().toString())) {
                resultMap = JSON.parseObject(codeExecuteResultDto.getResult().toString());
            } else {
                return outputMap;
            }
        } else {
            resultMap = (Map<String, Object>) codeExecuteResultDto.getResult();
        }
        //null时为解析参数时传递
        if (codePluginConfigDto.getOutputArgs() == null && resultMap != null) {
            outputMap.putAll(resultMap);
            codePluginConfigDto.setOutputArgs(new ArrayList<>());
        }
        if (resultMap != null && resultMap.containsKey("usage")) {
            outputMap.put("usage", resultMap.get("usage"));
        }
        resetRequireToFalse(codePluginConfigDto.getOutputArgs());
        for (Arg outputArg : codePluginConfigDto.getOutputArgs()) {
            if ((outputArg.getDataType() == DataTypeEnum.Object || outputArg.getDataType().name().startsWith("Array")) && CollectionUtils.isEmpty(outputArg.getSubArgs())) {
                outputMap.put(outputArg.getName(), resultMap.get(outputArg.getName()));
                continue;
            }
            Object value = extraParams(outputArg, resultMap);
            if (value != null) {
                outputMap.put(outputArg.getName(), value);
            }
        }
        return outputMap;
    }
}
