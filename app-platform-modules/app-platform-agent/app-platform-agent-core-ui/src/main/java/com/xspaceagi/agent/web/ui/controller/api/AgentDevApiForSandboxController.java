package com.xspaceagi.agent.web.ui.controller.api;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.agent.core.adapter.application.AgentApplicationService;
import com.xspaceagi.agent.core.adapter.application.PluginApplicationService;
import com.xspaceagi.agent.core.adapter.application.PublishApplicationService;
import com.xspaceagi.agent.core.adapter.application.WorkflowApplicationService;
import com.xspaceagi.agent.core.adapter.dto.PublishedDto;
import com.xspaceagi.agent.core.adapter.dto.PublishedPermissionDto;
import com.xspaceagi.agent.core.adapter.dto.PublishedQueryDto;
import com.xspaceagi.agent.core.adapter.dto.SkillConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.AgentComponentConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.AgentConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.Arg;
import com.xspaceagi.agent.core.adapter.dto.config.bind.KnowledgeBaseBindConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.bind.McpBindConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.PluginConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.PluginDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.NodeConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowNodeDto;
import com.xspaceagi.agent.core.adapter.repository.entity.AgentComponentConfig;
import com.xspaceagi.agent.core.adapter.repository.entity.PluginConfig;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.adapter.repository.entity.WorkflowNodeConfig;
import com.xspaceagi.agent.core.infra.component.agent.AgentContext;
import com.xspaceagi.agent.core.infra.component.knowledge.KnowledgeBaseSearcher;
import com.xspaceagi.agent.core.infra.component.knowledge.SearchContext;
import com.xspaceagi.agent.core.infra.converter.ArgConverter;
import com.xspaceagi.agent.core.sdk.IAgentRpcService;
import com.xspaceagi.agent.core.sdk.dto.PluginExecuteRequestDto;
import com.xspaceagi.agent.core.sdk.dto.WorkflowExecuteRequestDto;
import com.xspaceagi.agent.core.sdk.dto.WorkflowExecuteResultDto;
import com.xspaceagi.agent.core.sdk.enums.WfExecuteResultTypeEnum;
import com.xspaceagi.agent.web.ui.controller.api.dto.*;
import com.xspaceagi.file.sdk.IFileAccessService;
import com.xspaceagi.knowledge.sdk.request.KnowledgeConfigRequestVo;
import com.xspaceagi.knowledge.sdk.response.KnowledgeQaVo;
import com.xspaceagi.knowledge.sdk.sevice.IKnowledgeConfigRpcService;
import com.xspaceagi.mcp.sdk.dto.McpDto;
import com.xspaceagi.pricing.sdk.dto.PriceEstimate;
import com.xspaceagi.pricing.sdk.rpc.IPricingRpcService;
import com.xspaceagi.pricing.spec.enums.TargetTypeEnum;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.sdk.common.TraceContext;
import com.xspaceagi.system.sdk.permission.IUserDataPermissionRpcService;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.sdk.service.dto.UserAccessKeyDto;
import com.xspaceagi.system.sdk.service.dto.UserDataPermissionDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.page.SuperPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.*;

@Slf4j
@Tag(name = "自定义智能体开发相关接口")
@RestController
@RequestMapping("/api/v1/4sandbox/agent")
public class AgentDevApiForSandboxController {

    @Resource
    private PublishApplicationService publishApplicationService;

    @Resource
    private SpacePermissionService spacePermissionService;

    @Resource
    private IUserDataPermissionRpcService userDataPermissionRpcService;

    @Resource
    private IKnowledgeConfigRpcService knowledgeConfigRpcService;

    @Resource
    private AgentApplicationService agentApplicationService;

    @Resource
    private IAgentRpcService iAgentRpcService;

    @Resource
    private IPricingRpcService iPricingRpcService;

    @Resource
    private KnowledgeBaseSearcher knowledgeBaseSearcher;

    @Resource
    private IFileAccessService iFileAccessService;

    @Resource
    private PluginApplicationService pluginApplicationService;

    @Resource
    private WorkflowApplicationService workflowApplicationService;

    @Operation(summary = "获取当前开发的智能体基本配置信息")
    @RequestMapping(path = "/dev/config/{devAgentId}", method = RequestMethod.GET)
    public ReqResult<DevAgentConfigDTO> agentConfig(@PathVariable Long devAgentId) {
        AgentConfigDto agentConfigDto = agentApplicationService.queryConfigForTestExecute(devAgentId);
        spacePermissionService.checkSpaceUserPermission(agentConfigDto.getSpaceId());
        DevAgentConfigDTO devAgentConfigDTO = new DevAgentConfigDTO();
        devAgentConfigDTO.setSystemPrompt(agentConfigDto.getSystemPrompt());
        devAgentConfigDTO.setOpeningChatMsg(agentConfigDto.getOpeningChatMsg());
        List<ToolSearchResultItemDTO> toolSearchResultItemDTOS = new ArrayList<>();
        List<SkillResultItemDTO> skillResultItemDTOS = new ArrayList<>();
        List<McpResultDTO> mcpConfigs = new ArrayList<>();
        agentConfigDto.getAgentComponentConfigList().forEach(agentComponentConfig -> {
            if (agentComponentConfig.getType() == AgentComponentConfig.Type.Plugin) {
                PluginDto pluginDto = (PluginDto) agentComponentConfig.getTargetConfig();
                String schema = buildPluginSchema((PluginConfigDto) pluginDto.getConfig());
                toolSearchResultItemDTOS.add(ToolSearchResultItemDTO.builder()
                        .name(pluginDto.getName())
                        .description(pluginDto.getDescription())
                        .targetId(pluginDto.getId())
                        .targetType(Published.TargetType.Plugin)
                        .schema(schema)
                        .build());
            }
            if (agentComponentConfig.getType() == AgentComponentConfig.Type.Workflow) {
                WorkflowConfigDto workflowDto = (WorkflowConfigDto) agentComponentConfig.getTargetConfig();
                String schema = buildWorkflowSchema(workflowDto.getInputArgs(), workflowDto.getOutputArgs());
                toolSearchResultItemDTOS.add(ToolSearchResultItemDTO.builder()
                        .name(workflowDto.getName())
                        .description(workflowDto.getDescription())
                        .targetId(workflowDto.getId())
                        .targetType(Published.TargetType.Workflow)
                        .schema(schema)
                        .build());
            }
            if (agentComponentConfig.getType() == AgentComponentConfig.Type.Skill) {
                SkillConfigDto skillDto = (SkillConfigDto) agentComponentConfig.getTargetConfig();
                skillResultItemDTOS.add(SkillResultItemDTO.builder()
                        .name(skillDto.getName())
                        .description(skillDto.getDescription())
                        .id(skillDto.getId())
                        .downloadUrl(iFileAccessService.getFileUrlWithAk(skillDto.getZipFileUrl(), true)).build());

            }
            if (agentComponentConfig.getType() == AgentComponentConfig.Type.Mcp) {
                McpDto mcpDto = (McpDto) agentComponentConfig.getTargetConfig();
                McpBindConfigDto mcpBindConfigDto = (McpBindConfigDto) agentComponentConfig.getBindConfig();
                mcpConfigs.add(McpResultDTO.builder()
                        .name(mcpDto.getName())
                        .description(mcpDto.getDescription())
                        .serverConfig(mcpDto.getMcpConfig().getServerConfig())
                        .usedTool(mcpBindConfigDto.getToolName())
                        .build());
            }

            if (agentComponentConfig.getType() == AgentComponentConfig.Type.Knowledge) {
                toolSearchResultItemDTOS.add(ToolSearchResultItemDTO.builder()
                        .name(agentComponentConfig.getName())
                        .description(agentComponentConfig.getDescription())
                        .targetId(agentComponentConfig.getTargetId())
                        .targetType(Published.TargetType.Knowledge)
                        .schema(buildKnowledgeSchema())
                        .build());
            }

        });
        devAgentConfigDTO.setTools(toolSearchResultItemDTOS);
        devAgentConfigDTO.setSkills(skillResultItemDTOS);
        devAgentConfigDTO.setMcpConfigs(mcpConfigs);
        return ReqResult.success(devAgentConfigDTO);
    }

    @Operation(summary = "更新前智能体的基本配置信息")
    @RequestMapping(path = "/dev/config/update", method = RequestMethod.POST)
    public ReqResult<Void> updateAgentConfig(@RequestBody DevAgentConfigUpdateDTO devAgentConfigUpdateDTO) {
        Assert.notNull(devAgentConfigUpdateDTO, "devAgentConfigUpdateDTO cannot be empty");
        Assert.notNull(devAgentConfigUpdateDTO.getDevAgentId(), "devAgentId cannot be empty");
        AgentConfigDto agentConfigDto = agentApplicationService.queryConfigForTestExecute(devAgentConfigUpdateDTO.getDevAgentId());
        spacePermissionService.checkSpaceUserPermission(agentConfigDto.getSpaceId());
        if (agentConfigDto.getDevAgentConversationId() == null) {
            return ReqResult.error("Non-custom agents cannot be modified");
        }
        if (StringUtils.isNotBlank(devAgentConfigUpdateDTO.getSystemPrompt()) || StringUtils.isNotBlank(devAgentConfigUpdateDTO.getOpeningChatMsg())) {
            AgentConfigDto agentConfigUpdate = new AgentConfigDto();
            agentConfigUpdate.setSystemPrompt(StringUtils.isNotBlank(devAgentConfigUpdateDTO.getSystemPrompt()) ? devAgentConfigUpdateDTO.getSystemPrompt() : null);
            agentConfigUpdate.setOpeningChatMsg(StringUtils.isNotBlank(devAgentConfigUpdateDTO.getOpeningChatMsg()) ? devAgentConfigUpdateDTO.getOpeningChatMsg() : null);
            agentConfigUpdate.setId(devAgentConfigUpdateDTO.getDevAgentId());
            agentApplicationService.update(agentConfigUpdate);
        }
        return ReqResult.success();
    }

    @Operation(summary = "向智能体的添加工具")
    @RequestMapping(path = "/dev/config/tool/add", method = RequestMethod.POST)
    public ReqResult<Void> addAgentComponent(@RequestBody ToolAddDTO toolAddDTO) {
        AgentConfigDto agentConfigDto = agentApplicationService.queryConfigForTestExecute(toolAddDTO.getDevAgentId());
        spacePermissionService.checkSpaceUserPermission(agentConfigDto.getSpaceId());
        if (agentConfigDto.getDevAgentConversationId() == null) {
            return ReqResult.error("Non-custom agents cannot be modified");
        }
        if (toolAddDTO.getTargetType() == Published.TargetType.Plugin) {
            // Check plugin usage permission
            pluginApplicationService.checkSpacePluginPermission(agentConfigDto.getSpaceId(), toolAddDTO.getTargetId());
        }
        if (toolAddDTO.getTargetType() == Published.TargetType.Workflow) {
            // Check workflow usage permission
            workflowApplicationService.checkSpaceWorkflowPermission(agentConfigDto.getSpaceId(), toolAddDTO.getTargetId());
        }
        AgentComponentConfig.Type type = null;
        if (toolAddDTO.getTargetType() == Published.TargetType.Plugin) {
            type = AgentComponentConfig.Type.Plugin;
        }
        if (toolAddDTO.getTargetType() == Published.TargetType.Workflow) {
            type = AgentComponentConfig.Type.Workflow;
        }
        if (toolAddDTO.getTargetType() == Published.TargetType.Knowledge) {
            type = AgentComponentConfig.Type.Knowledge;
        }
        if (toolAddDTO.getTargetType() == Published.TargetType.Skill) {
            type = AgentComponentConfig.Type.Skill;
        }
        if (type == null) {
            return ReqResult.error("Error targetType");
        }
        AgentComponentConfigDto agentComponentConfigDto = AgentComponentConfigDto.builder()
                .agentId(agentConfigDto.getId())
                .type(type)
                .targetId(toolAddDTO.getTargetId())
                .agentId(agentConfigDto.getId())
                .build();
        agentApplicationService.addComponentConfig(agentComponentConfigDto);
        return ReqResult.success();
    }

    @Operation(summary = "删除智能体已添加的工具")
    @RequestMapping(path = "/dev/config/tool/delete", method = RequestMethod.POST)
    public ReqResult<Void> deleteAgentComponent(@RequestBody ToolAddDTO toolAddDTO) {
        AgentConfigDto agentConfigDto = agentApplicationService.queryConfigForTestExecute(toolAddDTO.getDevAgentId());
        spacePermissionService.checkSpaceUserPermission(agentConfigDto.getSpaceId());
        if (agentConfigDto.getDevAgentConversationId() == null) {
            return ReqResult.error("Non-custom agents cannot be modified");
        }
        if (toolAddDTO.getTargetType() == Published.TargetType.Plugin) {
            agentConfigDto.getAgentComponentConfigList().stream().filter(
                            agentComponentConfig -> agentComponentConfig.getTargetId().equals(toolAddDTO.getTargetId())
                                    && agentComponentConfig.getType() == AgentComponentConfig.Type.Plugin).findFirst()
                    .ifPresent(agentComponentConfigDto -> agentApplicationService.deleteComponentConfig(agentComponentConfigDto.getId()));
        }
        if (toolAddDTO.getTargetType() == Published.TargetType.Workflow) {
            agentConfigDto.getAgentComponentConfigList().stream().filter(
                            agentComponentConfig -> agentComponentConfig.getTargetId().equals(toolAddDTO.getTargetId())
                                    && agentComponentConfig.getType() == AgentComponentConfig.Type.Workflow).findFirst()
                    .ifPresent(agentComponentConfigDto -> agentApplicationService.deleteComponentConfig(agentComponentConfigDto.getId()));
        }
        if (toolAddDTO.getTargetType() == Published.TargetType.Knowledge) {
            agentConfigDto.getAgentComponentConfigList().stream().filter(
                            agentComponentConfig -> agentComponentConfig.getTargetId().equals(toolAddDTO.getTargetId())
                                    && agentComponentConfig.getType() == AgentComponentConfig.Type.Knowledge).findFirst()
                    .ifPresent(agentComponentConfigDto -> agentApplicationService.deleteComponentConfig(agentComponentConfigDto.getId()));
        }
        if (toolAddDTO.getTargetType() == Published.TargetType.Skill) {
            agentConfigDto.getAgentComponentConfigList().stream().filter(
                            agentComponentConfig -> agentComponentConfig.getTargetId().equals(toolAddDTO.getTargetId())
                                    && agentComponentConfig.getType() == AgentComponentConfig.Type.Skill).findFirst()
                    .ifPresent(agentComponentConfigDto -> agentApplicationService.deleteComponentConfig(agentComponentConfigDto.getId()));
        }
        return ReqResult.success();
    }

    @Operation(summary = "工具搜索相关接口")
    @RequestMapping(path = "/dev/tool/search", method = RequestMethod.POST)
    public ReqResult<List<ToolSearchResultItemDTO>> toolSearch(@RequestBody ToolSearchDTO toolSearchDTO) {
        Assert.notNull(toolSearchDTO, "toolSearchDTO must not be null");
        Assert.notNull(toolSearchDTO.getDevSpaceId(), "devSpaceId must not be null, read from env DEV_SPACE_ID");
        spacePermissionService.checkSpaceUserPermission(toolSearchDTO.getDevSpaceId());
        PublishedQueryDto publishedQueryDto = new PublishedQueryDto();
        publishedQueryDto.setOnlyTemplate(YesOrNoEnum.N.getKey());
        publishedQueryDto.setSpaceId(toolSearchDTO.getDevSpaceId());
        publishedQueryDto.setCategory(null);
        publishedQueryDto.setPage(toolSearchDTO.getPage() == null || toolSearchDTO.getPage() <= 0 ? 1 : toolSearchDTO.getPage());
        publishedQueryDto.setPageSize(toolSearchDTO.getPageSize() == null || toolSearchDTO.getPageSize() <= 0 ? 10 : toolSearchDTO.getPageSize());
        if (toolSearchDTO.getType() != null && toolSearchDTO.getType().equals("skill")) {
            publishedQueryDto.setTargetType(Published.TargetType.Skill);
            publishedQueryDto.setReturnConfig(true);
            publishedQueryDto.setKw(toolSearchDTO.getKw());
            List<ToolSearchResultItemDTO> toolSearchResultItemDTOS = new ArrayList<>();
            SuperPage<PublishedDto> page = publishApplicationService.queryPublishedList(publishedQueryDto);
            page.getRecords().forEach(publishedDto -> {
                SkillConfigDto skillConfigDto = JSON.parseObject(publishedDto.getConfig(), SkillConfigDto.class);
                toolSearchResultItemDTOS.add(ToolSearchResultItemDTO.builder()
                        .name(skillConfigDto.getName())
                        .description(skillConfigDto.getDescription())
                        .targetId(skillConfigDto.getId())
                        .targetType(Published.TargetType.Skill)
                        .schema("The skill's download url is " + iFileAccessService.getFileUrlWithAk(skillConfigDto.getZipFileUrl(), true))
                        .build());
            });
            return ReqResult.success(toolSearchResultItemDTOS);
        }

        publishedQueryDto.setTargetType(Published.TargetType.Plugin);
        publishedQueryDto.setReturnConfig(true);
        publishedQueryDto.setKw(toolSearchDTO.getKw());
        List<ToolSearchResultItemDTO> toolSearchResultItemDTOS = new ArrayList<>();
        SuperPage<PublishedDto> page = publishApplicationService.queryPublishedList(publishedQueryDto);
        page.getRecords().forEach(publishedDto -> {
            String schema = null;
            try {
                PluginConfig pluginConfig = JSON.parseObject(publishedDto.getConfig(), PluginConfig.class);
                PluginDto pluginDto = new PluginDto();
                BeanUtils.copyProperties(pluginConfig, pluginDto);
                pluginDto.setConfig(PluginDto.convertToPluginConfigDto(pluginDto, pluginConfig.getConfig()));
                PluginConfigDto pluginConfigDto = (PluginConfigDto) pluginDto.getConfig();
                schema = buildPluginSchema(pluginConfigDto);
            } catch (Exception e) {
                // Ignore
                log.warn("Failed to parse plugin config {}", publishedDto.getConfig());
            }
            toolSearchResultItemDTOS.add(ToolSearchResultItemDTO.builder()
                    .name(publishedDto.getName())
                    .description(publishedDto.getDescription())
                    .targetId(publishedDto.getTargetId())
                    .targetType(Published.TargetType.Plugin)
                    .schema(schema)
                    .build());
        });

        publishedQueryDto.setTargetType(Published.TargetType.Workflow);
        page = publishApplicationService.queryPublishedList(publishedQueryDto);
        page.getRecords().forEach(publishedDto -> {
            String schema = null;
            try {
                JSONObject jsonObject = JSON.parseObject(publishedDto.getConfig());
                List<WorkflowNodeDto> nodes = jsonObject.getJSONArray("nodes").stream().map(node -> {
                    JSONObject nodeJson = (JSONObject) node;
                    WorkflowNodeDto workflowNodeDto = nodeJson.toJavaObject(WorkflowNodeDto.class);
                    NodeConfigDto nodeConfigDto = WorkflowConfigDto.convertToNodeConfigDto(WorkflowNodeConfig.NodeType.valueOf(nodeJson.getString("type")), nodeJson.getString("nodeConfig"));
                    workflowNodeDto.setNodeConfig(nodeConfigDto);
                    return workflowNodeDto;
                }).toList();
                WorkflowNodeDto startNode = nodes.stream().filter(node -> node.getType() == WorkflowNodeConfig.NodeType.Start).findFirst().orElse(null);
                WorkflowNodeDto endNode = nodes.stream().filter(node -> node.getType() == WorkflowNodeConfig.NodeType.End).findFirst().orElse(null);
                if (startNode != null && endNode != null) {
                    schema = buildWorkflowSchema(startNode.getNodeConfig().getInputArgs(), endNode.getNodeConfig().getOutputArgs());
                }
            } catch (Exception e) {
                log.warn("Failed to parse workflow config {}", publishedDto.getConfig());
            }

            toolSearchResultItemDTOS.add(ToolSearchResultItemDTO.builder()
                    .name(publishedDto.getName())
                    .description(publishedDto.getDescription())
                    .targetId(publishedDto.getTargetId())
                    .targetType(Published.TargetType.Workflow)
                    .schema(schema)
                    .build());
        });

        UserDataPermissionDto userDataPermissionDto = userDataPermissionRpcService.getUserDataPermission(RequestContext.get().getUserId());
        List<Long> knowledgeIds = userDataPermissionDto.getKnowledgeIds();
        KnowledgeConfigRequestVo knowledgeConfigRequestVo = KnowledgeConfigRequestVo.builder()
                .kw(publishedQueryDto.getKw())
                .spaceId(publishedQueryDto.getSpaceId())
                .authSpaceIds(List.of(toolSearchDTO.getDevSpaceId()))
                .page(publishedQueryDto.getPage())
                .pageSize(publishedQueryDto.getPageSize())
                .knowledgeIds(knowledgeIds)
                .build();

        var voResponse = this.knowledgeConfigRpcService.queryListKnowledgeConfig(knowledgeConfigRequestVo);
        voResponse.getConfigPage().getRecords().forEach(knowledgeConfigVo -> {
            String schema = buildKnowledgeSchema();
            toolSearchResultItemDTOS.add(ToolSearchResultItemDTO.builder()
                    .name(knowledgeConfigVo.getName())
                    .description(knowledgeConfigVo.getDescription())
                    .targetId(knowledgeConfigVo.getId())
                    .targetType(Published.TargetType.Knowledge)
                    .schema(schema)
                    .build());
        });

        return ReqResult.success(toolSearchResultItemDTOS);
    }

    private String buildKnowledgeSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("method", "POST");
        schema.put("url", "${PLATFORM_BASE_URL}/api/v1/4sandbox/agent/kb/query");
        schema.put("authorization", "Bearer ${SANDBOX_ACCESS_KEY}");
        schema.put("requestBody", Map.of(
                "devAgentId", "该参数仅用于开发者搜索到API后调试使用，通过环境 ${DEV_AGENT_ID} 获取值，正式使用时禁止传递该参数",
                "targetType", "Knowledge",
                "targetId", "number",
                "params", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string", "description", "Search query for knowledge base retrieval")
                        ),
                        "required", List.of("query")
                )
        ));
        schema.put("response", Map.of(
                "type", "object",
                "properties", Map.of(
                        "items", Map.of("type", "array", "description", "Retrieved knowledge base items"),
                        "error", buildErrorSchema()
                )
        ));
        return JSON.toJSONString(schema);
    }

    private String buildWorkflowSchema(List<Arg> inputArgs, List<Arg> outputArgs) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("method", "POST");
        schema.put("url", "${PLATFORM_BASE_URL}/api/v1/4sandbox/agent/workflow/execute");
        schema.put("authorization", "Bearer ${SANDBOX_ACCESS_KEY}");
        schema.put("streaming", true);
        schema.put("contentType", "text/event-stream");
        schema.put("requestBody", Map.of(
                "devAgentId", "该参数仅用于开发者搜索到API后调试使用，通过环境 ${DEV_AGENT_ID} 获取值，正式使用时禁止传递该参数",
                "targetType", "Workflow",
                "targetId", "number",
                "params", inputArgs != null && !inputArgs.isEmpty()
                        ? ArgConverter.convertArgsToJsonSchema(inputArgs)
                        : Map.of("type", "object", "properties", Map.of())
        ));
        Map<String, Object> finalResultDataProperties = new LinkedHashMap<>();
        if (outputArgs != null && !outputArgs.isEmpty()) {
            Map<String, Object> outputSchema = ArgConverter.convertArgsToJsonSchema(outputArgs);
            @SuppressWarnings("unchecked")
            Map<String, Object> outputProps = (Map<String, Object>) outputSchema.get("properties");
            if (outputProps != null) {
                finalResultDataProperties.putAll(outputProps);
            }
        }
        finalResultDataProperties.put("error", buildErrorSchema());
        schema.put("sseEvents", List.of(
                Map.of("event", "Heartbeat", "description", "Keep-alive event, ignore"),
                Map.of("event", "FinalResult", "description", "Final execution result",
                        "data", Map.of("type", "object", "properties", finalResultDataProperties))
        ));
        return JSON.toJSONString(schema);
    }

    private String buildPluginSchema(PluginConfigDto pluginConfigDto) {
        if (pluginConfigDto == null) {
            return "";
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("method", "POST");
        schema.put("url", "${PLATFORM_BASE_URL}/api/v1/4sandbox/agent/plugin/execute");
        schema.put("authorization", "Bearer ${SANDBOX_ACCESS_KEY}");
        schema.put("requestBody", Map.of(
                "devAgentId", "该参数仅用于开发者搜索到API后调试使用，通过环境 ${DEV_AGENT_ID} 获取值，该参数仅用于调试，绝对禁止用到项目中",
                "targetType", "Plugin",
                "targetId", "number",
                "params", pluginConfigDto.getInputArgs() != null && !pluginConfigDto.getInputArgs().isEmpty()
                        ? ArgConverter.convertArgsToJsonSchema(pluginConfigDto.getInputArgs())
                        : Map.of("type", "object", "properties", Map.of())
        ));
        Map<String, Object> responseProperties = new LinkedHashMap<>();
        if (pluginConfigDto.getOutputArgs() != null && !pluginConfigDto.getOutputArgs().isEmpty()) {
            Map<String, Object> outputSchema = ArgConverter.convertArgsToJsonSchema(pluginConfigDto.getOutputArgs());
            @SuppressWarnings("unchecked")
            Map<String, Object> outputProps = (Map<String, Object>) outputSchema.get("properties");
            if (outputProps != null) {
                responseProperties.putAll(outputProps);
            }
        }
        responseProperties.put("error", buildErrorSchema());
        schema.put("response", Map.of("type", "object", "properties", responseProperties));
        return JSON.toJSONString(schema);
    }


    private Map<String, Object> buildErrorSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "message", Map.of("type", "string", "description", "Error message"),
                        "code", Map.of("type", "string", "description", "Error code")
                )
        );
    }

    @Operation(summary = "插件执行接口")
    @RequestMapping(path = "/plugin/execute", method = RequestMethod.POST)
    public Object executeTool(@RequestBody ToolExecuteDTO toolExecuteDTO) {
        try {
            List<AgentComponentConfigDto> agentComponentConfigList = checkConfigAndPermission(toolExecuteDTO);
            Assert.isTrue(toolExecuteDTO.getTargetType() == Published.TargetType.Plugin, "Invalid TargetType");
            AgentComponentConfigDto componentConfigDto = agentComponentConfigList.stream().filter(agentComponentConfig ->
                            agentComponentConfig.getTargetId().equals(toolExecuteDTO.getTargetId()) && agentComponentConfig.getType() == AgentComponentConfig.Type.Plugin)
                    .findFirst().orElse(null);
            if (componentConfigDto == null) {
                throw new BizException("Error plugin id");
            }
            UserAccessKeyDto userAccessKeyDto = (UserAccessKeyDto) RequestContext.get().getUserAccessKey();
            return executePlugin(userAccessKeyDto, toolExecuteDTO, componentConfigDto);
        } catch (Exception e) {
            return Map.of("error", Map.of("message", e.getMessage(), "code", "0001"));
        }
    }

    @Operation(summary = "知识库检索接口")
    @RequestMapping(path = "/kb/query", method = RequestMethod.POST)
    public KbQueryResultDTO executeKb(@RequestBody ToolExecuteDTO toolExecuteDTO) {
        try {
            List<AgentComponentConfigDto> agentComponentConfigList = checkConfigAndPermission(toolExecuteDTO);
            Assert.isTrue(toolExecuteDTO.getTargetType() == Published.TargetType.Knowledge, "Invalid TargetType");
            AgentComponentConfigDto componentConfigDto = agentComponentConfigList.stream().filter(agentComponentConfig ->
                            agentComponentConfig.getTargetId().equals(toolExecuteDTO.getTargetId()) && agentComponentConfig.getType() == AgentComponentConfig.Type.Knowledge)
                    .findFirst().orElse(null);
            if (componentConfigDto == null) {
                throw new BizException("Error kb id");
            }
            Assert.isTrue(toolExecuteDTO.getParams().containsKey("query"), "query can not be null");
            KnowledgeBaseBindConfigDto knowledgeBaseBindConfigDto = (KnowledgeBaseBindConfigDto) componentConfigDto.getBindConfig();
            SearchContext searchContext = new SearchContext();
            searchContext.setQuery(toolExecuteDTO.getParams().get("query").toString());
            searchContext.setSearchStrategy(knowledgeBaseBindConfigDto.getSearchStrategy());
            searchContext.setMaxRecallCount(knowledgeBaseBindConfigDto.getMaxRecallCount());
            searchContext.setMatchingDegree(knowledgeBaseBindConfigDto.getMatchingDegree());
            searchContext.setKnowledgeBaseIds(List.of(componentConfigDto.getTargetId()));
            AgentContext agentContext = new AgentContext();
            agentContext.setTenantConfig((TenantConfigDto) RequestContext.get().getTenantConfig());
            agentContext.setUser((UserDto) RequestContext.get().getUser());
            searchContext.setAgentContext(agentContext);
            List<KnowledgeQaVo> qaVoList = knowledgeBaseSearcher.search(searchContext).timeout(Duration.ofSeconds(60)).block();
            return new KbQueryResultDTO(qaVoList, null);
        } catch (Exception e) {
            return new KbQueryResultDTO(null, Map.of("error", Map.of("message", e.getMessage(), "code", "0001")));
        }
    }

    @Operation(summary = "工作流执行接口")
    @RequestMapping(path = "/workflow/execute", method = RequestMethod.POST, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<WorkflowExecutingDTO> executeWorkflow(@RequestBody ToolExecuteDTO toolExecuteDTO) {
        try {
            List<AgentComponentConfigDto> agentComponentConfigList = checkConfigAndPermission(toolExecuteDTO);
            Assert.isTrue(toolExecuteDTO.getTargetType() == Published.TargetType.Workflow, "Invalid TargetType");
            AgentComponentConfigDto componentConfigDto = agentComponentConfigList.stream().filter(agentComponentConfig ->
                            agentComponentConfig.getTargetId().equals(toolExecuteDTO.getTargetId()) && agentComponentConfig.getType() == AgentComponentConfig.Type.Workflow)
                    .findFirst().orElse(null);
            if (componentConfigDto == null) {
                throw new BizException("Error workflow id");
            }
            UserAccessKeyDto userAccessKeyDto = (UserAccessKeyDto) RequestContext.get().getUserAccessKey();
            return executeWorkflow(userAccessKeyDto, toolExecuteDTO, componentConfigDto).mapNotNull(workflowExecutingDTO -> {
                        if (workflowExecutingDTO.getType() == WfExecuteResultTypeEnum.EXECUTE_RESULT) {
                            return WorkflowExecutingDTO.builder().type(WorkflowExecutingDTO.EventType.FinalResult).data(workflowExecutingDTO.getData()).build();
                        }
                        return null;
                    }).filter(Objects::nonNull)
                    .mergeWith(Flux.interval(Duration.ofSeconds(20))
                            .map(i -> WorkflowExecutingDTO.builder().type(WorkflowExecutingDTO.EventType.Heartbeat).build()))
                    .takeUntil(dto -> dto != null && dto.getType() == WorkflowExecutingDTO.EventType.FinalResult);
        } catch (Exception e) {
            return Flux.just(WorkflowExecutingDTO.builder().type(WorkflowExecutingDTO.EventType.FinalResult).data(Map.of("error", Map.of("message", e.getMessage(), "code", "0001"))).build());
        }
    }

    private List<AgentComponentConfigDto> checkConfigAndPermission(ToolExecuteDTO toolExecuteDTO) {
        Assert.notNull(toolExecuteDTO, "toolExecuteDTO must not be null");
        Assert.notNull(toolExecuteDTO.getTargetId(), "targetId must not be null");
        Assert.notNull(toolExecuteDTO.getTargetType(), "targetType must not be null");
        UserAccessKeyDto userAccessKeyDto = (UserAccessKeyDto) RequestContext.get().getUserAccessKey();
        String targetId = userAccessKeyDto.getTargetId();
        Long agentId = toolExecuteDTO.getDevAgentId() != null ? toolExecuteDTO.getDevAgentId() : Long.parseLong(targetId);
        AgentConfigDto agentConfig;
        //验证用户有没有智能体的权限
        if (YesOrNoEnum.Y.getKey().equals(userAccessKeyDto.getConfig().getIsDevMode()) || toolExecuteDTO.getDevAgentId() != null) {
            agentConfig = agentApplicationService.queryConfigForTestExecute(agentId);
            if (agentConfig != null) {
                spacePermissionService.checkSpaceUserPermission(agentConfig.getSpaceId(), userAccessKeyDto.getUserId());
            }
        } else {
            agentConfig = agentApplicationService.queryPublishedConfig(agentId, true);
            PublishedPermissionDto publishedPermissionDto = publishApplicationService.hasPermission(Published.TargetType.Agent, agentId);
            if (!publishedPermissionDto.isExecute()) {
                throw new BizException("No permission");
            }
        }
        if (agentConfig == null) {
            throw new BizException("Error agent id");
        }
        return agentConfig.getAgentComponentConfigList();
    }

    private Flux<WorkflowExecuteResultDto> executeWorkflow(UserAccessKeyDto userAccessKeyDto, ToolExecuteDTO toolExecuteDTO, AgentComponentConfigDto component) {
        TraceContext traceContext = userAccessKeyDto.getConfig().getTraceContext();
        WorkflowExecuteRequestDto workflowExecuteRequest = new WorkflowExecuteRequestDto();
        workflowExecuteRequest.setWorkflowId(component.getTargetId());
        workflowExecuteRequest.setSpaceId(component.getSpaceId());
        workflowExecuteRequest.setParams(toolExecuteDTO.getParams());
        workflowExecuteRequest.setConversationId(traceContext.getConversationId());
        workflowExecuteRequest.setRequestId(traceContext.getTraceId());
        workflowExecuteRequest.setUser(RequestContext.get().getUser());
        workflowExecuteRequest.setConfig(component.getTargetConfig());
        workflowExecuteRequest.setBindConfig(component.getBindConfig());
        workflowExecuteRequest.setTraceContext(traceContext);
        TenantConfigDto tenantConfig = (TenantConfigDto) RequestContext.get().getTenantConfig();
        if (tenantConfig != null && tenantConfig.getEnableSubscription() != null && tenantConfig.getEnableSubscription() == 1) {
            List<PriceEstimate.EstimateTarget> estimateTargets = List.of(PriceEstimate.EstimateTarget.builder().targetType(TargetTypeEnum.WORKFLOW).targetId(component.getTargetId().toString()).build());
            PriceEstimate priceEstimate = iPricingRpcService.estimatePrice(traceContext.getTenantId(), traceContext.getBillUserId(), estimateTargets);
            if (priceEstimate != null && !priceEstimate.isPass()) {
                throw new BizException(priceEstimate.getMessage());
            }
        }
        return iAgentRpcService.executeWorkflow(workflowExecuteRequest);
    }

    private Object executePlugin(UserAccessKeyDto userAccessKeyDto, ToolExecuteDTO toolExecuteDTO, AgentComponentConfigDto agentComponentConfig) {
        PluginExecuteRequestDto pluginExecuteRequest = new PluginExecuteRequestDto();
        pluginExecuteRequest.setSpaceId(agentComponentConfig.getSpaceId());
        pluginExecuteRequest.setParams(toolExecuteDTO.getParams());
        pluginExecuteRequest.setConfig(agentComponentConfig.getTargetConfig());
        pluginExecuteRequest.setBindConfig(agentComponentConfig.getBindConfig());
        pluginExecuteRequest.setUser(RequestContext.get().getUser());
        pluginExecuteRequest.setTraceContext(userAccessKeyDto.getConfig().getTraceContext());
        TenantConfigDto tenantConfig = (TenantConfigDto) RequestContext.get().getTenantConfig();
        if (tenantConfig != null && tenantConfig.getEnableSubscription() != null && tenantConfig.getEnableSubscription() == 1) {
            List<PriceEstimate.EstimateTarget> estimateTargets = List.of(PriceEstimate.EstimateTarget.builder().targetType(TargetTypeEnum.PLUGIN).targetId(agentComponentConfig.getTargetId().toString()).build());
            PriceEstimate priceEstimate = iPricingRpcService.estimatePrice(userAccessKeyDto.getTenantId(), userAccessKeyDto.getConfig().getTraceContext().getBillUserId(), estimateTargets);
            if (priceEstimate != null && !priceEstimate.isPass()) {
                throw new BizException(priceEstimate.getMessage());
            }
        }
        return iAgentRpcService.executePlugin(pluginExecuteRequest).timeout(Duration.ofSeconds(180)).block();
    }
}
