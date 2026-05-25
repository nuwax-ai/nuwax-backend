package com.xspaceagi.mcp.ui.api.service;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.agent.core.sdk.IAgentRpcService;
import com.xspaceagi.agent.core.sdk.dto.AgentExecuteRequestDto;
import com.xspaceagi.agent.core.sdk.dto.KnowledgeSearchRequest;
import com.xspaceagi.agent.core.sdk.dto.PluginExecuteRequestDto;
import com.xspaceagi.agent.core.sdk.dto.WorkflowExecuteRequestDto;
import com.xspaceagi.agent.core.sdk.enums.WfExecuteResultTypeEnum;
import com.xspaceagi.agent.core.spec.enums.SearchStrategyEnum;
import com.xspaceagi.compose.sdk.request.DorisTableDataRequest;
import com.xspaceagi.compose.sdk.response.DorisTableDataResponse;
import com.xspaceagi.compose.sdk.service.IComposeDbTableRpcService;
import com.xspaceagi.log.sdk.service.ILogRpcService;
import com.xspaceagi.log.sdk.vo.LogDocument;
import com.xspaceagi.mcp.adapter.application.McpConfigApplicationService;
import com.xspaceagi.mcp.infra.client.McpAsyncClientWrapper;
import com.xspaceagi.mcp.infra.rpc.McpDeployRpcService;
import com.xspaceagi.mcp.sdk.IMcpApiService;
import com.xspaceagi.mcp.sdk.dto.*;
import com.xspaceagi.mcp.sdk.enums.InstallTypeEnum;
import com.xspaceagi.mcp.sdk.enums.McpComponentTypeEnum;
import com.xspaceagi.mcp.sdk.enums.McpContentTypeEnum;
import com.xspaceagi.mcp.sdk.enums.McpDataTypeEnum;
import com.xspaceagi.pricing.sdk.dto.PriceEstimate;
import com.xspaceagi.pricing.sdk.rpc.IPricingRpcService;
import com.xspaceagi.pricing.spec.enums.TargetTypeEnum;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.sdk.service.dto.UserAccessKeyDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class McpApiServiceImpl implements IMcpApiService {

    @Resource
    private McpConfigApplicationService mcpConfigApplicationService;

    @Resource
    private IAgentRpcService iAgentRpcService;

    @Resource
    private IComposeDbTableRpcService iComposeDbTableRpcService;

    @Resource
    private McpDeployRpcService mcpDeployRpcService;

    @Resource
    private ILogRpcService iLogRpcService;

    @Resource
    private IPricingRpcService iPricingRpcService;

    @Override
    public McpDto getDeployedMcp(Long id, Long spaceId) {
        McpDto deployedMcp = mcpConfigApplicationService.getDeployedMcp(id);
        if (deployedMcp == null) {
            return null;
        }
        if (spaceId != null && deployedMcp.getSpaceId() != -1 && !deployedMcp.getSpaceId().equals(spaceId)) {
            return null;
        }
        deployedMcp.setMcpConfig(deployedMcp.getDeployedConfig());
        return deployedMcp;
    }

    @Override
    public Flux<McpExecuteOutput> execute(McpExecuteRequest mcpExecuteRequestDto) {
        try {
            UserDto userDto = (UserDto) mcpExecuteRequestDto.getUser();
            RequestContext.setThreadTenantId(userDto.getTenantId());
            RequestContext.get().setUser(userDto);
            try {
                LogDocument logDocument = LogDocument.builder()
                        .tenantId(userDto.getTenantId())
                        .id(UUID.randomUUID().toString().replace("-", ""))
                        .requestId(mcpExecuteRequestDto.getRequestId())
                        .spaceId(mcpExecuteRequestDto.getMcpDto().getSpaceId())
                        .userId(userDto.getId())
                        .userName(userDto.getNickName() == null ? userDto.getUserName() : userDto.getNickName())
                        .targetType("Mcp")
                        .targetName(mcpExecuteRequestDto.getName())
                        .targetId(mcpExecuteRequestDto.getMcpDto().getId().toString())
                        .conversationId(mcpExecuteRequestDto.getSessionId())
                        .input(JSON.toJSONString(mcpExecuteRequestDto.getParams()))
                        .requestStartTime(System.currentTimeMillis())
                        .build();
                Flux<McpExecuteOutput> mcpExecuteOutputFlux = execute0(mcpExecuteRequestDto);
                if (mcpExecuteOutputFlux == null) {
                    return Flux.empty();
                }
                return mcpExecuteOutputFlux.doOnError(throwable -> {
                    try {
                        logDocument.setCreateTime(System.currentTimeMillis());
                        logDocument.setResultCode("0001");
                        logDocument.setResultMsg(throwable.getMessage());
                        logDocument.setRequestEndTime(System.currentTimeMillis());
                        iLogRpcService.bulkIndex(List.of(logDocument));
                    } catch (Exception e) {
                        // 忽略
                        log.error("MCP log recording error", e);
                    }
                }).doOnNext(result -> {
                    try {
                        logDocument.setOutput(JSON.toJSONString(result));
                        logDocument.setCreateTime(System.currentTimeMillis());
                        logDocument.setResultCode("0000");
                        logDocument.setResultMsg("成功");
                        logDocument.setRequestEndTime(System.currentTimeMillis());
                        iLogRpcService.bulkIndex(List.of(logDocument));
                    } catch (Exception e) {
                        // 忽略
                        log.error("MCP log recording error", e);
                    }
                });
            } catch (Exception e) {
                log.error("mcp execute error", e);
                return Flux.error(e);
            }
        } finally {
            RequestContext.remove();
        }
    }

    private Flux<McpExecuteOutput> execute0(McpExecuteRequest mcpExecuteRequestDto) {
        McpDto mcpDto = mcpExecuteRequestDto.getMcpDto();
        if (CollectionUtils.isNotEmpty(mcpDto.getDeployedConfig().getTools()) && mcpExecuteRequestDto.getExecuteType() == McpExecuteRequest.ExecuteTypeEnum.TOOL) {
            try {
                McpToolDto mcpToolDto = mcpDto.getDeployedConfig().getTools().stream().filter(tool -> tool.getName().equals(mcpExecuteRequestDto.getName())).findFirst().orElse(null);
                if (mcpToolDto == null) {
                    return Flux.just(McpExecuteOutput.builder().success(false).message("未找到工具").build());
                }
                checkAndUpdateParams(mcpToolDto.getInputArgs(), mcpExecuteRequestDto.getParams());
            } catch (Exception e) {
                return Flux.just(new McpExecuteOutput(false, e.getMessage(), null));
            }
        }
        if (CollectionUtils.isNotEmpty(mcpDto.getDeployedConfig().getPrompts()) && mcpExecuteRequestDto.getExecuteType() == McpExecuteRequest.ExecuteTypeEnum.PROMPT) {
            try {
                McpPromptDto mcpPromptDto = mcpDto.getDeployedConfig().getPrompts().stream().filter(tool -> tool.getName().equals(mcpExecuteRequestDto.getName())).findFirst().orElse(null);
                checkAndUpdateParams(mcpPromptDto.getInputArgs(), mcpExecuteRequestDto.getParams());
            } catch (Exception e) {
                return Flux.just(new McpExecuteOutput(false, e.getMessage(), null));
            }
        }
        if (mcpDto.getInstallType() == InstallTypeEnum.COMPONENT) {
            if (mcpDto.getMcpConfig() == null || mcpDto.getMcpConfig().getComponents() == null) {
                return Flux.just(new McpExecuteOutput(false, "MCP配置不完整", null));
            }
            McpConfigDto mcpConfig = mcpDto.getDeployedConfig();
            //mcpConfig.getComponents()根据toolName转map
            Map<String, McpComponentDto> componentMap = mcpConfig.getComponents().stream().collect(Collectors.toMap(McpComponentDto::getToolName, Function.identity(), (a, b) -> a));
            McpComponentDto component = componentMap.get(mcpExecuteRequestDto.getName());
            if (component == null) {
                return Flux.just(new McpExecuteOutput(false, "MCP组件不存在", null));
            }
            Map<String, Object> params = mcpExecuteRequestDto.getParams();
            if (component.getType() == McpComponentTypeEnum.Plugin) {
                return executePlugin(mcpDto, component, mcpExecuteRequestDto);
            }
            if (component.getType() == McpComponentTypeEnum.Workflow) {
                return executeWorkflow(mcpDto, component, mcpExecuteRequestDto);
            }
            if (component.getType() == McpComponentTypeEnum.Table) {
                return executeTable(component, mcpExecuteRequestDto);
            }
            if (component.getType() == McpComponentTypeEnum.Knowledge) {
                return searchKnowledge(mcpExecuteRequestDto, component, params);
            }
            if (component.getType() == McpComponentTypeEnum.Agent) {
                return executeAgent(component, mcpExecuteRequestDto);
            }
        } else {
            if (mcpDto.getDeployedConfig() == null || mcpDto.getDeployedConfig().getServerConfig() == null) {
                return Flux.just(new McpExecuteOutput(false, "MCP未部署或已停用", null));
            }
            AtomicReference<Disposable> disposableAtomicReference = new AtomicReference<>();
            Sinks.Many<McpExecuteOutput> sink = Sinks.many().multicast().onBackpressureBuffer();

            boolean isSSE = mcpDto.getInstallType() == InstallTypeEnum.SSE;
            String conversationId = mcpExecuteRequestDto.getSessionId();
            McpConfigDto mcpConfig = mcpDto.getMcpConfig();
            McpAsyncClientWrapper mcpAsyncClientWrapper;
            if (isSSE) {
                try {
                    UserDto userDto = (UserDto) mcpExecuteRequestDto.getUser();
                    RequestContext.setThreadTenantId(userDto.getTenantId());
                    mcpAsyncClientWrapper = mcpDeployRpcService.getMcpAsyncClientForSSE(mcpDto.getId().toString(), conversationId, mcpConfig.getServerConfig()).block();
                } finally {
                    RequestContext.remove();
                }
            } else {
                mcpAsyncClientWrapper = mcpDeployRpcService.getMcpAsyncClient(mcpDto.getId().toString(), conversationId, mcpConfig.getServerConfig()).block();
            }
            Disposable disposable = null;
            if (mcpExecuteRequestDto.getExecuteType() == McpExecuteRequest.ExecuteTypeEnum.TOOL) {
                disposable = callTool(sink, mcpAsyncClientWrapper, mcpExecuteRequestDto);
            } else if (mcpExecuteRequestDto.getExecuteType() == McpExecuteRequest.ExecuteTypeEnum.RESOURCE) {
                disposable = readResource(sink, mcpAsyncClientWrapper, mcpExecuteRequestDto);
            } else if (mcpExecuteRequestDto.getExecuteType() == McpExecuteRequest.ExecuteTypeEnum.PROMPT) {
                disposable = getPrompt(sink, mcpAsyncClientWrapper, mcpExecuteRequestDto);
            }
            disposableAtomicReference.set(disposable);
            return sink.asFlux().doOnCancel(() -> {
                if (disposableAtomicReference.get() != null) {
                    disposableAtomicReference.get().dispose();
                }
            });
        }
        return null;
    }

    private void checkAndUpdateParams(List<McpArgDto> inputArgs, Map<String, Object> params) {
        if (inputArgs == null || inputArgs.size() == 0) {
            return;
        }
        if (params == null) {
            params = new HashMap<>();
        }
        for (McpArgDto arg : inputArgs) {
            Object val = params.get(arg.getName());
            if (val == null) {
                val = StringUtils.isNotBlank(arg.getBindValue()) ? arg.getBindValue() : null;
                if (val == null && arg.isRequire()) {
                    throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, arg.getName());
                }
            }
            if (arg.getDataType() == McpDataTypeEnum.Object) {
                if (val == null) {
                    params.put(arg.getName(), new HashMap<>());
                }
                checkAndUpdateParams(arg.getSubArgs(), (Map<String, Object>) params.get(arg.getName()));
            }
            if (arg.getDataType() == McpDataTypeEnum.Array_Object) {
                if (val == null) {
                    params.put(arg.getName(), new ArrayList<>());
                }
                checkAndUpdateListParams(arg, val);
            }
            if (arg.getDataType() == McpDataTypeEnum.Array_Boolean) {
                List<Boolean> newVals = new ArrayList<>();
                if (val != null) {
                    if (val instanceof List) {
                        for (Object v : (List<?>) val) {
                            newVals.add(Boolean.parseBoolean(v.toString()));
                        }
                    }
                }
                params.put(arg.getName(), newVals);
            }
            if (arg.getDataType() == McpDataTypeEnum.Array_Integer) {
                List<Integer> newVals = new ArrayList<>();
                if (val != null) {
                    if (val instanceof List) {
                        for (Object v : (List<?>) val) {
                            newVals.add(Integer.parseInt(v.toString()));
                        }
                    }
                }
                params.put(arg.getName(), newVals);
            }
            if (arg.getDataType() == McpDataTypeEnum.Array_Number) {
                List<Number> newVals = new ArrayList<>();
                if (val != null) {
                    if (val instanceof List) {
                        for (Object v : (List<?>) val) {
                            newVals.add(Double.parseDouble(v.toString()));
                        }
                    }
                }
                params.put(arg.getName(), newVals);
            }
            if (arg.getDataType() == McpDataTypeEnum.Boolean) {
                if (val != null) {
                    params.put(arg.getName(), Boolean.parseBoolean(val.toString()));
                }
            }
            if (arg.getDataType() == McpDataTypeEnum.Integer) {
                if (val != null) {
                    params.put(arg.getName(), Integer.parseInt(val.toString()));
                }
            }
            if (arg.getDataType() == McpDataTypeEnum.Number) {
                if (val != null) {
                    params.put(arg.getName(), Double.parseDouble(val.toString()));
                }
            }
            if (arg.getDataType() == McpDataTypeEnum.String) {
                if (val != null) {
                    params.put(arg.getName(), val.toString());
                }
            }
        }
    }

    private void checkAndUpdateListParams(McpArgDto arg, Object val) {
        if (!(val instanceof List)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.paramArgTypeInvalid, arg.getName());
        }
        for (Object obj : (List<?>) val) {
            if (obj instanceof Map) {
                checkAndUpdateParams(arg.getSubArgs(), (Map<String, Object>) obj);
            } else {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.paramArgTypeInvalid, arg.getName());
            }
        }
    }

    private Disposable getPrompt(Sinks.Many<McpExecuteOutput> sink, McpAsyncClientWrapper
            mcpAsyncClientWrapper, McpExecuteRequest mcpExecuteRequestDto) {
        McpSchema.GetPromptRequest getPromptRequest = new McpSchema.GetPromptRequest(mcpExecuteRequestDto.getName(), mcpExecuteRequestDto.getParams());
        return mcpAsyncClientWrapper.getClient().getPrompt(getPromptRequest).doOnSuccess(getPromptResult -> {
            try {
                List<McpContent> mcpContents = getPromptResult.messages().stream().map(content -> {
                    McpPromptContent mcpPromptContent = new McpPromptContent();
                    mcpPromptContent.setRole(content.role().name());
                    mcpPromptContent.setContent(convertToMcpContent(content.content()));
                    return mcpPromptContent;
                }).collect(Collectors.toList());
                sink.tryEmitNext(McpExecuteOutput.builder()
                        .success(true).message(null)
                        .result(mcpContents).build());
                sink.tryEmitComplete();
            } finally {
                returnMcpClient(mcpExecuteRequestDto, mcpAsyncClientWrapper);
            }
        }).doOnError(throwable -> {
            log.error("callTool error", throwable);
            sink.tryEmitError(throwable);
            mcpDeployRpcService.closeMcpClient(mcpExecuteRequestDto.getMcpDto().getId().toString(), mcpExecuteRequestDto.getSessionId(), mcpAsyncClientWrapper);
        }).subscribe();
    }

    private Disposable readResource(Sinks.Many<McpExecuteOutput> sink, McpAsyncClientWrapper
            mcpAsyncClientWrapper, McpExecuteRequest mcpExecuteRequestDto) {
        McpSchema.ReadResourceRequest readResourceRequest = new McpSchema.ReadResourceRequest(mcpExecuteRequestDto.getParams().get("uri").toString());
        return mcpAsyncClientWrapper.getClient().readResource(readResourceRequest).doOnSuccess((resourceResult) -> {
            try {
                List<McpContent> mcpContents = resourceResult.contents().stream().map(content -> {
                    McpResourceContent mcpResourceContent = new McpResourceContent();
                    mcpResourceContent.setUri(content.uri());
                    mcpResourceContent.setMimeType(content.mimeType());
                    if (content instanceof McpSchema.TextResourceContents) {
                        mcpResourceContent.setData(((McpSchema.TextResourceContents) content).text());
                    }
                    if (content instanceof McpSchema.BlobResourceContents) {
                        mcpResourceContent.setBlob(((McpSchema.BlobResourceContents) content).blob());
                    }
                    return mcpResourceContent;
                }).collect(Collectors.toList());
                sink.tryEmitNext(McpExecuteOutput.builder()
                        .success(true).message(null)
                        .result(mcpContents).build());
                sink.tryEmitComplete();
            } finally {
                returnMcpClient(mcpExecuteRequestDto, mcpAsyncClientWrapper);
            }
        }).doOnError(throwable -> {
            log.error("callTool error", throwable);
            sink.tryEmitError(throwable);
            mcpDeployRpcService.closeMcpClient(mcpExecuteRequestDto.getMcpDto().getId().toString(), mcpExecuteRequestDto.getSessionId(), mcpAsyncClientWrapper);
        }).subscribe();
    }

    private Disposable callTool(Sinks.Many<McpExecuteOutput> sink, McpAsyncClientWrapper
            mcpAsyncClientWrapper, McpExecuteRequest mcpExecuteRequestDto) {
        log.debug("callTool, getMcpDto {}", mcpExecuteRequestDto.getMcpDto());
        McpSchema.CallToolRequest callToolRequest = new McpSchema.CallToolRequest(mcpExecuteRequestDto.getName(), JSON.toJSONString(mcpExecuteRequestDto.getParams()));
        return mcpAsyncClientWrapper.getClient().callTool(callToolRequest).onErrorResume(throwable -> {
            log.error("callTool error", throwable);
            return Mono.error(throwable);
        }).doOnSuccess(callToolResult -> {
            try {
                List<McpContent> mcpContents = callToolResult.content().stream().map((ct) -> convertToMcpContent(ct)).collect(Collectors.toList());
                boolean isSuccess = callToolResult.isError() == null || !callToolResult.isError();
                String message = null;
                if (!isSuccess) {
                    message = JSON.toJSONString(mcpContents);
                }
                sink.tryEmitNext(McpExecuteOutput.builder()
                        .success(isSuccess)
                        .message(message)
                        .result(mcpContents).build());
                sink.tryEmitComplete();
            } finally {
                returnMcpClient(mcpExecuteRequestDto, mcpAsyncClientWrapper);
            }
        }).doOnError(throwable -> {
            log.error("callTool error", throwable);
            sink.tryEmitError(throwable);
            mcpDeployRpcService.closeMcpClient(mcpExecuteRequestDto.getMcpDto().getId().toString(), mcpExecuteRequestDto.getSessionId(), mcpAsyncClientWrapper);
        }).subscribe();
    }

    private void returnMcpClient(McpExecuteRequest mcpExecuteRequestDto, McpAsyncClientWrapper
            mcpAsyncClientWrapper) {
        if (mcpExecuteRequestDto.isKeepAlive()) {
            mcpDeployRpcService.returnMcpClient(mcpExecuteRequestDto.getMcpDto().getId().toString(), mcpExecuteRequestDto.getSessionId(), mcpAsyncClientWrapper);
        } else {
            mcpDeployRpcService.closeMcpClient(mcpExecuteRequestDto.getMcpDto().getId().toString(), mcpExecuteRequestDto.getSessionId(), mcpAsyncClientWrapper);
        }
    }

    private McpContent convertToMcpContent(McpSchema.Content ct) {
        if (ct.type().equals("text")) {
            McpSchema.TextContent textContent = (McpSchema.TextContent) ct;
            McpTextContent mcpTextContent = new McpTextContent();
            mcpTextContent.setData(textContent.text());
            mcpTextContent.setType(McpContentTypeEnum.TEXT);
            return mcpTextContent;
        }
        if (ct.type().equals("image")) {
            McpSchema.ImageContent imageContent = (McpSchema.ImageContent) ct;
            McpImageContent mcpImageContent = new McpImageContent();
            mcpImageContent.setAudience(imageContent.audience().stream().map(role -> role.name()).collect(Collectors.toList()));
            mcpImageContent.setData(imageContent.data());
            mcpImageContent.setMimeType(imageContent.mimeType());
            mcpImageContent.setPriority(imageContent.priority());
            return mcpImageContent;
        }
        if (ct.type().equals("resource")) {
            McpSchema.EmbeddedResource embeddedResource = (McpSchema.EmbeddedResource) ct;
            McpResourceContent mcpResourceContent = new McpResourceContent();
            mcpResourceContent.setUri(embeddedResource.resource().uri());
            mcpResourceContent.setMimeType(embeddedResource.resource().mimeType());
            if (embeddedResource.resource() instanceof McpSchema.TextResourceContents) {
                mcpResourceContent.setData(((McpSchema.TextResourceContents) embeddedResource.resource()).text());
            }
            if (embeddedResource.resource() instanceof McpSchema.BlobResourceContents) {
                mcpResourceContent.setBlob(((McpSchema.BlobResourceContents) embeddedResource.resource()).blob());
            }
            mcpResourceContent.setMimeType(embeddedResource.resource().mimeType());
            return mcpResourceContent;
        }
        return new McpContent();
    }

    private Flux<McpExecuteOutput> searchKnowledge(McpExecuteRequest mcpExecuteRequestDto, McpComponentDto component, Map<String, Object> params) {
        if (params.get("query") == null || params.get("query").equals("")) {
            return Flux.just(new McpExecuteOutput(false, "查询条件缺失", null));
        }
        KnowledgeSearchRequest request = new KnowledgeSearchRequest();
        request.setQuery(params.get("query").toString());
        if (params.get("topK") != null) {
            try {
                request.setMaxRecallCount(Integer.parseInt(params.get("topK").toString()));
            } catch (NumberFormatException e) {
                // 忽略
                request.setMaxRecallCount(5);
            }
        } else {
            request.setMaxRecallCount(5);
        }
        request.setKnowledgeBaseIds(List.of(component.getTargetId()));
        request.setUser(mcpExecuteRequestDto.getUser());
        request.setSearchStrategy(SearchStrategyEnum.MIXED);
        request.setMatchingDegree(0.5);
        request.setRequestId(mcpExecuteRequestDto.getRequestId());

        return Flux.create(emitter -> iAgentRpcService.searchKnowledge(request).subscribe(
                knowledgeQaDtos -> {
                    List<McpContent> mcpContents = knowledgeQaDtos.stream().map(qa -> {
                        McpTextContent mcpTextContent = new McpTextContent();
                        mcpTextContent.setData(JSON.toJSONString(qa));
                        mcpTextContent.setType(McpContentTypeEnum.TEXT);
                        return mcpTextContent;
                    }).collect(Collectors.toList());
                    McpExecuteOutput mcpExecuteOutput = McpExecuteOutput.builder()
                            .success(true)
                            .result(mcpContents)
                            .build();
                    emitter.next(mcpExecuteOutput);
                    emitter.complete();
                },
                throwable -> {
                    McpExecuteOutput mcpExecuteOutput = McpExecuteOutput.builder()
                            .success(false)
                            .message(throwable.getMessage())
                            .build();
                    emitter.next(mcpExecuteOutput);
                    emitter.complete();
                }
        ));
    }

    private Flux<McpExecuteOutput> executeTable(McpComponentDto component, McpExecuteRequest mcpExecuteRequestDto) {
        Object sql = mcpExecuteRequestDto.getParams().get("sql");
        if (sql == null || StringUtils.isBlank(sql.toString())) {
            return Flux.just(new McpExecuteOutput(false, "sql参数缺失", null));
        }
        DorisTableDataRequest request = new DorisTableDataRequest(component.getTargetId(), sql.toString(), new HashMap<>(), new HashMap<>());
        DorisTableDataResponse dorisTableDataResponse = iComposeDbTableRpcService.queryTableData(request);
        Map<String, Object> data = new HashMap<>();
        data.put("id", dorisTableDataResponse.getRowId());
        data.put("outputList", dorisTableDataResponse.getData());
        data.put("rowNum", dorisTableDataResponse.getRowNum() == null ? 0 : dorisTableDataResponse.getRowNum().intValue());
        if (dorisTableDataResponse.getRowId() != null) {
            UserDto userDto = (UserDto) mcpExecuteRequestDto.getUser();
            if (userDto != null) {
                StringBuilder updateSqlBuilder = new StringBuilder();
                updateSqlBuilder.append("UPDATE ").append(dorisTableDataResponse.getTableDefineVo().getTableName()).append(" SET ");
                if (userDto.getUserName() != null) {
                    updateSqlBuilder.append("user_name = '").append(userDto.getUserName()).append("',");
                }
                if (userDto.getNickName() != null) {
                    updateSqlBuilder.append("nick_name = '").append(userDto.getNickName()).append("',");
                }
                updateSqlBuilder.append("uid = '").append(userDto.getUid()).append("'");
                updateSqlBuilder.append(" WHERE id = ").append(dorisTableDataResponse.getRowId());
                DorisTableDataRequest updateRequest = new DorisTableDataRequest(component.getTargetId(), updateSqlBuilder.toString(), new HashMap<>(), new HashMap<>());
                iComposeDbTableRpcService.queryTableData(updateRequest);
            }
        }
        McpTextContent mcpTextContent = new McpTextContent();
        mcpTextContent.setData(JSON.toJSONString(data));
        mcpTextContent.setType(McpContentTypeEnum.TEXT);
        McpExecuteOutput mcpExecuteOutput = McpExecuteOutput.builder()
                .success(true)
                .result(List.of(mcpTextContent))
                .build();
        return Flux.just(mcpExecuteOutput);
    }

    private Flux<McpExecuteOutput> executeWorkflow(McpDto mcpDto, McpComponentDto component, McpExecuteRequest
            mcpExecuteRequestDto) {
        WorkflowExecuteRequestDto workflowExecuteRequest = new WorkflowExecuteRequestDto();
        workflowExecuteRequest.setWorkflowId(component.getTargetId());
        workflowExecuteRequest.setSpaceId(mcpDto.getSpaceId());
        workflowExecuteRequest.setParams(mcpExecuteRequestDto.getParams());
        workflowExecuteRequest.setConversationId(mcpExecuteRequestDto.getSessionId());
        workflowExecuteRequest.setRequestId(mcpExecuteRequestDto.getRequestId());
        workflowExecuteRequest.setUser(mcpExecuteRequestDto.getUser());
        workflowExecuteRequest.setConfig(component.getTargetConfig());
        workflowExecuteRequest.setBindConfig(component.getTargetBindConfig());
        workflowExecuteRequest.setTraceContext(mcpExecuteRequestDto.getTraceContext());
        TenantConfigDto tenantConfig = (TenantConfigDto) RequestContext.get().getTenantConfig();
        if (tenantConfig != null && tenantConfig.getEnableSubscription() != null && tenantConfig.getEnableSubscription() == 1) {
            List<PriceEstimate.EstimateTarget> estimateTargets = List.of(PriceEstimate.EstimateTarget.builder().targetType(TargetTypeEnum.WORKFLOW).targetId(component.getTargetId().toString()).build());
            PriceEstimate priceEstimate = iPricingRpcService.estimatePrice(mcpExecuteRequestDto.getTraceContext().getTenantId(), mcpExecuteRequestDto.getTraceContext().getBillUserId(), estimateTargets);
            if (priceEstimate != null && !priceEstimate.isPass()) {
                return Flux.just(McpExecuteOutput.builder()
                        .success(false)
                        .message(priceEstimate.getMessage())
                        .build());
            }
        }
        return iAgentRpcService.executeWorkflow(workflowExecuteRequest)
                .<McpExecuteOutput>map(result -> {
                    if (result.getType() == WfExecuteResultTypeEnum.EXECUTE_RESULT) {
                        String content;
                        if (StringUtils.isNotBlank(result.getOutputContent())) {
                            content = result.getOutputContent();
                        } else {
                            content = JSON.toJSONString(result.getData());
                        }
                        McpTextContent mcpTextContent = new McpTextContent();
                        mcpTextContent.setData(content);
                        mcpTextContent.setType(McpContentTypeEnum.TEXT);
                        return McpExecuteOutput.builder()
                                .success(true)
                                .result(List.of(mcpTextContent))
                                .build();
                    } else if (result.getType() == WfExecuteResultTypeEnum.EXECUTING_LOG) {
                        McpLogContent mcpLogContent = new McpLogContent();
                        mcpLogContent.setData(JSON.toJSONString(result.getData()));
                        mcpLogContent.setType(McpContentTypeEnum.TEXT);
                        return McpExecuteOutput.builder()
                                .success(true)
                                .result(List.of(mcpLogContent))
                                .build();
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .onErrorResume(throwable -> Flux.just(McpExecuteOutput.builder()
                        .success(false)
                        .message(throwable.getMessage()).build()));
    }

    private Flux<McpExecuteOutput> executeAgent(McpComponentDto component, McpExecuteRequest
            mcpExecuteRequestDto) {
        if (mcpExecuteRequestDto.getParams() == null || mcpExecuteRequestDto.getParams().get("message") == null) {
            return Flux.just(McpExecuteOutput.builder()
                    .success(false)
                    .message("请输入要执行的内容")
                    .build());
        }
        AgentExecuteRequestDto agentExecuteRequestDto = new AgentExecuteRequestDto();
        if (mcpExecuteRequestDto.getParams().get("variables") != null && mcpExecuteRequestDto.getParams().get("variables") instanceof Map<?, ?>) {
            agentExecuteRequestDto.setVariables((Map<String, Object>) mcpExecuteRequestDto.getParams().get("variables"));
        }
        agentExecuteRequestDto.setAgentId(component.getTargetId());
        agentExecuteRequestDto.setMessage(mcpExecuteRequestDto.getParams().get("message").toString());
        agentExecuteRequestDto.setUser(mcpExecuteRequestDto.getUser());
        agentExecuteRequestDto.setSessionId(mcpExecuteRequestDto.getSessionId());
        agentExecuteRequestDto.setTraceContext(mcpExecuteRequestDto.getTraceContext());
        return Flux.create(sink -> iAgentRpcService.executeAgent(agentExecuteRequestDto)
                .onErrorResume(throwable -> Mono.error(throwable))
                .doOnNext(result -> {
                    if ("FINAL_RESULT".equals(result.getEventType())) {
                        String content;
                        if (StringUtils.isNotBlank(result.getData())) {
                            content = result.getData();
                        } else {
                            content = JSON.toJSONString(result.getData());
                        }
                        McpTextContent mcpTextContent = new McpTextContent();
                        mcpTextContent.setData(content);
                        mcpTextContent.setType(McpContentTypeEnum.TEXT);
                        McpExecuteOutput mcpExecuteOutput = McpExecuteOutput.builder()
                                .success(!result.isError())
                                .result(List.of(mcpTextContent))
                                .build();
                        sink.next(mcpExecuteOutput);
                    } else if ("MESSAGE".equals(result.getEventType())) {
                        McpLogContent mcpLogContent = new McpLogContent();
                        mcpLogContent.setData(JSON.toJSONString(result.getData()));
                        mcpLogContent.setType(McpContentTypeEnum.TEXT);
                        McpExecuteOutput mcpExecuteOutput = McpExecuteOutput.builder()
                                .success(true)
                                .result(List.of(mcpLogContent))
                                .build();
                        sink.next(mcpExecuteOutput);
                    }
                }).doOnError(throwable -> {
                    McpExecuteOutput mcpExecuteOutput = McpExecuteOutput.builder()
                            .success(false)
                            .message(throwable.getMessage()).build();
                    sink.next(mcpExecuteOutput);
                    sink.complete();
                }).doOnComplete(sink::complete).subscribe());
    }

    private Flux<McpExecuteOutput> executePlugin(McpDto mcpDto, McpComponentDto component, McpExecuteRequest mcpExecuteRequestDt) {
        PluginExecuteRequestDto pluginExecuteRequest = new PluginExecuteRequestDto();
        pluginExecuteRequest.setSpaceId(mcpDto.getSpaceId());
        pluginExecuteRequest.setParams(mcpExecuteRequestDt.getParams());
        pluginExecuteRequest.setConfig(component.getTargetConfig());
        pluginExecuteRequest.setBindConfig(component.getTargetBindConfig());
        pluginExecuteRequest.setUser(mcpExecuteRequestDt.getUser());
        pluginExecuteRequest.setTraceContext(mcpExecuteRequestDt.getTraceContext());
        TenantConfigDto tenantConfig = (TenantConfigDto) RequestContext.get().getTenantConfig();
        if (tenantConfig != null && tenantConfig.getEnableSubscription() != null && tenantConfig.getEnableSubscription() == 1) {
            List<PriceEstimate.EstimateTarget> estimateTargets = List.of(PriceEstimate.EstimateTarget.builder().targetType(TargetTypeEnum.PLUGIN).targetId(component.getTargetId().toString()).build());
            PriceEstimate priceEstimate = iPricingRpcService.estimatePrice(mcpExecuteRequestDt.getTraceContext().getTenantId(), mcpExecuteRequestDt.getTraceContext().getBillUserId(), estimateTargets);
            if (priceEstimate != null && !priceEstimate.isPass()) {
                return Flux.just(McpExecuteOutput.builder()
                        .success(false)
                        .message(priceEstimate.getMessage())
                        .build());
            }
        }
        return iAgentRpcService.executePlugin(pluginExecuteRequest)
                .timeout(Duration.ofSeconds(180))
                .onErrorResume(throwable -> {
                    log.warn("executePlugin error", throwable);
                    if (throwable instanceof TimeoutException) {
                        return Mono.error(new TimeoutException("executePlugin timeout"));
                    }
                    return Mono.error(throwable);
                })
                .<McpExecuteOutput>map(pluginExecuteResult -> {
                    McpTextContent mcpTextContent = new McpTextContent();
                    mcpTextContent.setData(JSON.toJSONString(pluginExecuteResult));
                    mcpTextContent.setType(McpContentTypeEnum.TEXT);
                    return McpExecuteOutput.builder()
                            .success(true)
                            .result(List.of(mcpTextContent))
                            .build();
                })
                .onErrorResume(error -> {
                    log.warn("executePlugin failed", error);
                    return Mono.just(new McpExecuteOutput(false, error.getMessage(), null));
                })
                .flux();
    }

    @Override
    public Long addAndDeployMcp(Long userId, Long spaceId, McpDto mcpDto) {
        mcpDto.setCreatorId(userId);
        mcpDto.setSpaceId(spaceId);
        Date now = new Date();
        mcpDto.setModified(now);
        mcpDto.setDeployed(now);
        mcpConfigApplicationService.addMcp(mcpDto);
        return mcpDto.getId();
    }

    @Override
    public Long deployOfficialMcp(McpDto mcpDto) {
        return mcpConfigApplicationService.deployOfficialMcp(mcpDto);
    }

    @Override
    public void stopOfficialMcp(Long id) {
        mcpConfigApplicationService.stopOfficialMcp(id);
    }

    @Override
    public Long deployProxyMcp(McpDto mcpDto) {
        return mcpConfigApplicationService.deployProxyMcp(mcpDto);
    }

    @Override
    public String getExportMcpServerConfig(Long userId, Long mcpId, UserAccessKeyDto.UserAccessKeyConfig userAccessKeyConfig) {
        return mcpConfigApplicationService.getExportMcpServerConfig(userId, mcpId, userAccessKeyConfig);
    }

    @Override
    public Long countTotalMcps() {
        return mcpConfigApplicationService.countTotalMcps();
    }

    @Override
    public com.baomidou.mybatisplus.core.metadata.IPage<McpDto> queryListForManage(Integer pageNo, Integer pageSize, String name, java.util.List<Long> creatorIds, Long spaceId) {
        com.xspaceagi.mcp.adapter.dto.McpPageQueryDto queryDto = new com.xspaceagi.mcp.adapter.dto.McpPageQueryDto();
        queryDto.setPage(pageNo);
        queryDto.setPageSize(pageSize);
        queryDto.setSpaceId(spaceId);
        queryDto.setKw(name);
        queryDto.setCreatorIds(creatorIds);
        return mcpConfigApplicationService.queryDeployedMcpListForManage(queryDto);
    }

    @Override
    public void deleteForManage(Long id) {
        mcpConfigApplicationService.deleteMcp(id);
    }
}
