package com.xspaceagi.agent.core.infra.component.plugin;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.agent.core.adapter.dto.PluginExecuteResultDto;
import com.xspaceagi.agent.core.infra.component.AsyncExecuteResponseHandler;
import com.xspaceagi.agent.core.infra.component.agent.AgentContext;
import com.xspaceagi.agent.core.infra.component.plugin.handler.CodePluginHandler;
import com.xspaceagi.agent.core.infra.component.plugin.handler.HttpPluginHandler;
import com.xspaceagi.agent.core.infra.rpc.MarketClientRpcService;
import com.xspaceagi.agent.core.spec.enums.ComponentTypeEnum;
import com.xspaceagi.agent.core.spec.enums.GlobalVariableEnum;
import com.xspaceagi.agent.core.spec.enums.PluginTypeEnum;
import com.xspaceagi.eco.market.sdk.reponse.ClientSecretResponse;
import com.xspaceagi.eco.market.sdk.request.ClientSecretRequest;
import com.xspaceagi.log.sdk.service.ILogRpcService;
import com.xspaceagi.log.sdk.vo.LogDocument;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
public class PluginExecutor {

    private AsyncExecuteResponseHandler asyncExecuteResponseHandler;

    private MarketClientRpcService marketClientRpcService;

    private ILogRpcService iLogRpcService;

    @Autowired
    public void setAsyncExecuteResponseHandler(AsyncExecuteResponseHandler asyncExecuteResponseHandler) {
        this.asyncExecuteResponseHandler = asyncExecuteResponseHandler;
    }

    @Autowired
    public void setMarketClientRpcService(MarketClientRpcService marketClientRpcService) {
        this.marketClientRpcService = marketClientRpcService;
    }

    @Autowired
    public void setLogRpcService(ILogRpcService iLogRpcService) {
        this.iLogRpcService = iLogRpcService;
    }

    public Mono<PluginExecuteResultDto> execute(PluginContext pluginContext) {
        Assert.notNull(pluginContext, "Plugin context cannot be blank.");
        Assert.notNull(pluginContext.getPluginConfig(), "Plugin config cannot be left blank.");
        log.info("Starting plugin, requestId {}, id {}, name {}, params {}", pluginContext.getRequestId(), pluginContext.getPluginConfig().getId()
                , pluginContext.getPluginConfig().getName(), pluginContext.getParams());
        pluginContext.setStartTime(System.currentTimeMillis());
        // Variable setup
        Map<String, Object> sysVars = new HashMap<>();
        // Iterate through GlobalVariableEnum.values() and set to SYS_VARS
        AgentContext agentContext = pluginContext.getAgentContext();
        if (agentContext != null) {
            for (GlobalVariableEnum globalVariableEnum : GlobalVariableEnum.values()) {
                Object val = agentContext.getVariableParams().get(globalVariableEnum.name());
                if (val != null) {
                    sysVars.put(globalVariableEnum.name(), val);
                }
            }
        }
        Map<String, Object> params = pluginContext.getParams();
        if (params == null) {
            params = new HashMap<>();
            pluginContext.setParams(params);
        }
        sysVars.remove(GlobalVariableEnum.CHAT_CONTEXT.name());
        if (pluginContext.getAgentContext().getUser() != null) {
            ClientSecretRequest clientSecretRequest = new ClientSecretRequest();
            clientSecretRequest.setTenantId(pluginContext.getAgentContext().getUser().getTenantId());
            ClientSecretResponse clientSecretResponse = marketClientRpcService.queryClientSecret(clientSecretRequest);
            if (clientSecretResponse != null) {
                sysVars.put("TENANT_SECRET", clientSecretResponse.getClientSecret());
            }
        }
        params.put("SYS_VARS", sysVars);

        Map<String, Object> logDocumentMap = new HashMap<>(params);
        logDocumentMap.remove("SYS_VARS");
        UserDto user = pluginContext.getAgentContext().getUser();
        LogDocument logDocument = LogDocument.builder()
                .tenantId(user.getTenantId())
                .id(UUID.randomUUID().toString().replace("-", ""))
                .requestId(pluginContext.getRequestId())
                .spaceId(pluginContext.getPluginDto().getSpaceId())
                .userId(user.getId())
                .userName(user.getNickName() == null ? user.getUserName() : user.getNickName())
                .targetType("Plugin")
                .targetName(pluginContext.getPluginDto().getName())
                .targetId(pluginContext.getPluginDto().getId().toString())
                .conversationId(pluginContext.getAgentContext().getConversationId())
                .input(JSON.toJSONString(logDocumentMap))
                .requestStartTime(System.currentTimeMillis())
                .build();
        pluginContext.getTraceContext().setLog(logDocument);
        Mono<PluginExecuteResultDto> mono0 = Mono.create(emitter -> {
            if (pluginContext.isAsyncExecute()) {
                PluginExecuteResultDto resultDto = PluginExecuteResultDto.builder()
                        .success(true)
                        .result("The tool is already executing asynchronously, please reply to the user with the following statement: " + (StringUtils.isBlank(pluginContext.getAsyncReplyContent()) ? "Processing has started, please wait patiently for the results" : pluginContext.getAsyncReplyContent()))
                        .costTime(0L)
                        .requestId(pluginContext.getRequestId())
                        .logs(pluginContext.getLogs())
                        .build();
                emitter.success(resultDto);
                Mono<PluginExecuteResultDto> mono = Mono.create(sink -> execute0(pluginContext, sink));
                mono.doOnError(e -> {
                            asyncExecuteResponseHandler.handleError(pluginContext.getAgentContext(), ComponentTypeEnum.Plugin, e);
                            logError(pluginContext, e, logDocument);
                        })
                        .subscribe(result -> {
                            asyncExecuteResponseHandler.handlePluginSuccess(pluginContext, result);
                            logSuccess(pluginContext, result, logDocument);
                        });
            } else {
                execute0(pluginContext, emitter);
            }
        });

        return mono0.doOnError(e -> logError(pluginContext, e, logDocument))
                .doOnSuccess(result -> logSuccess(pluginContext, result, logDocument));
    }


    private void logSuccess(PluginContext pluginContext, PluginExecuteResultDto result, LogDocument logDocument) {
        try {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("result", result.getResult());
            jsonObject.put("error", result.getError());
            jsonObject.put("success", result.isSuccess());
            logDocument.setOutput(jsonObject.toString());
            logDocument.setCreateTime(System.currentTimeMillis());
            logDocument.setResultCode("0000");
            logDocument.setResultMsg("Success");
            logDocument.setRequestEndTime(System.currentTimeMillis());
            iLogRpcService.pushTraceLog(pluginContext.getTraceContext());
        } catch (Exception e) {
            // Ignore
            log.error("Plugin log recording error", e);
        }
    }

    private void logError(PluginContext pluginContext, Throwable throwable, LogDocument logDocument) {
        try {
            logDocument.setCreateTime(System.currentTimeMillis());
            logDocument.setResultCode("0001");
            logDocument.setResultMsg(throwable.getMessage());
            logDocument.setRequestEndTime(System.currentTimeMillis());
            if (pluginContext.getTraceContext() != null) {
                pluginContext.getTraceContext().setErrorCode("0001");
                pluginContext.getTraceContext().setErrorMessage(throwable.getMessage());
                pluginContext.getTraceContext().setError(true);
            }
            iLogRpcService.pushTraceLog(pluginContext.getTraceContext());
        } catch (Exception e) {
            // Ignore
            log.error("Plugin log recording error", e);
        }
    }


    private static void execute0(PluginContext pluginContext, MonoSink<PluginExecuteResultDto> emitter) {
        Mono<Object> mono = null;
        if (pluginContext.getPluginConfig().getType() == PluginTypeEnum.CODE) {
            mono = new CodePluginHandler().execute(pluginContext);
        } else if (pluginContext.getPluginConfig().getType() == PluginTypeEnum.HTTP) {
            mono = new HttpPluginHandler().execute(pluginContext);
        }
        if (mono == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentPluginTypeInvalid);
        }
        mono.doOnError(throwable -> {
            log.error("Plugin execution exception, requestId {}, id {}, name {}", pluginContext.getAgentContext().getRequestId(), pluginContext.getPluginConfig().getId(),
                    pluginContext.getPluginConfig().getName(), throwable);
            pluginContext.setEndTime(System.currentTimeMillis());
            pluginContext.setExecuteSuccess(false);
            pluginContext.setError(throwable == null ? "" : throwable.getMessage());
            PluginExecuteResultDto result = PluginExecuteResultDto.builder()
                    .success(false)
                    .error(throwable == null ? "" : throwable.getMessage())
                    .costTime(pluginContext.getEndTime() - pluginContext.getStartTime())
                    .requestId(pluginContext.getRequestId())
                    .logs(pluginContext.getLogs())
                    .build();
            emitter.success(result);
        }).subscribe((result) -> {
            pluginContext.setEndTime(System.currentTimeMillis());
            pluginContext.setExecuteSuccess(true);
            log.info("Plugin execution completed, requestId {}, id {}, name {}, duration {} ms", pluginContext.getRequestId(), pluginContext.getPluginConfig().getId(),
                    pluginContext.getPluginConfig().getName(), pluginContext.getEndTime() - pluginContext.getStartTime());
            PluginExecuteResultDto resultDto = PluginExecuteResultDto.builder()
                    .success(true)
                    .result(result)
                    .costTime(pluginContext.getEndTime() - pluginContext.getStartTime())
                    .requestId(pluginContext.getRequestId())
                    .logs(pluginContext.getLogs())
                    .build();
            emitter.success(resultDto);
        });
    }
}
