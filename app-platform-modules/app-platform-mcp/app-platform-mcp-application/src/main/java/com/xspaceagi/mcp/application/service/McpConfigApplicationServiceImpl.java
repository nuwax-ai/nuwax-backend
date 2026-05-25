package com.xspaceagi.mcp.application.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xspaceagi.agent.core.adapter.application.ModelApplicationService;
import com.xspaceagi.agent.core.sdk.IAgentRpcService;
import com.xspaceagi.agent.core.sdk.dto.*;
import com.xspaceagi.agent.core.spec.enums.DataTypeEnum;
import com.xspaceagi.compose.sdk.request.DorisTableDefineRequest;
import com.xspaceagi.compose.sdk.service.IComposeDbTableRpcService;
import com.xspaceagi.compose.sdk.vo.define.TableDefineVo;
import com.xspaceagi.compose.sdk.vo.define.TableFieldDefineVo;
import com.xspaceagi.knowledge.sdk.response.KnowledgeConfigVo;
import com.xspaceagi.knowledge.sdk.sevice.IKnowledgeConfigRpcService;
import com.xspaceagi.mcp.adapter.application.McpConfigApplicationService;
import com.xspaceagi.mcp.adapter.application.McpDeployTaskService;
import com.xspaceagi.mcp.adapter.domain.McpConfigDomainService;
import com.xspaceagi.mcp.adapter.dto.McpPageQueryDto;
import com.xspaceagi.mcp.adapter.repository.entity.McpConfig;
import com.xspaceagi.mcp.application.dto.EnNameDto;
import com.xspaceagi.mcp.sdk.dto.*;
import com.xspaceagi.mcp.sdk.enums.DeployStatusEnum;
import com.xspaceagi.mcp.sdk.enums.InstallTypeEnum;
import com.xspaceagi.mcp.sdk.enums.McpComponentTypeEnum;
import com.xspaceagi.mcp.sdk.enums.McpDataTypeEnum;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.application.util.DefaultIconUrlUtil;
import com.xspaceagi.system.sdk.service.UserAccessKeyApiService;
import com.xspaceagi.system.sdk.service.dto.UserAccessKeyDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.enums.YnEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class McpConfigApplicationServiceImpl implements McpConfigApplicationService {

    @Resource
    private McpConfigDomainService mcpConfigDomainService;

    @Resource
    private UserApplicationService userApplicationService;

    @Resource
    private IAgentRpcService iAgentRpcService;

    @Resource
    private IComposeDbTableRpcService iComposeDbTableRpcService;

    @Resource
    private IKnowledgeConfigRpcService iKnowledgeConfigRpcService;

    @Resource
    private UserAccessKeyApiService userAccessKeyApiService;

    @Resource
    private McpDeployTaskService mcpDeployTaskService;

    @Resource
    private ModelApplicationService modelApplicationService;

    @Override
    public void addMcp(McpDto mcpDto) {
        mcpDto.setId(null);
        McpConfig mcpConfig = new McpConfig();
        BeanUtils.copyProperties(mcpDto, mcpConfig);
        completeComponentMcpToolNames(mcpDto);
        mcpConfig.setConfig(JSON.toJSONString(mcpDto.getMcpConfig()));
        if (mcpDto.getDeployedConfig() != null) {
            mcpConfig.setDeployedConfig(JSON.toJSONString(mcpDto.getDeployedConfig()));
        }
        mcpConfigDomainService.add(mcpConfig);
        mcpDto.setId(mcpConfig.getId());
    }

    private void completeComponentMcpToolNames(McpDto mcpDto) {
        if (mcpDto.getInstallType() != InstallTypeEnum.COMPONENT) {
            return;
        }
        Map<String, McpComponentDto> componentMap = new HashMap<>();
        List<String> toolNames = new ArrayList<>();
        if (mcpDto.getId() != null) {
            McpConfig mcpConfig = mcpConfigDomainService.getById(mcpDto.getId());
            McpConfigDto mcpConfigDto = JSON.parseObject(mcpConfig.getConfig(), McpConfigDto.class);
            if (mcpConfigDto.getComponents() != null) {
                //type + targetId作为key
                componentMap = mcpConfigDto.getComponents().stream().collect(Collectors.toMap(componentDto -> componentDto.getType().name() + "_" + componentDto.getTargetId(), McpComponentDto -> McpComponentDto, (a, b) -> a));
                toolNames = mcpConfigDto.getComponents().stream().map(McpComponentDto::getToolName).collect(Collectors.toList());
            }
        }
        if (mcpDto.getMcpConfig() != null && mcpDto.getMcpConfig().getComponents() != null) {
            for (McpComponentDto componentDto : mcpDto.getMcpConfig().getComponents()) {
                String key = componentDto.getType().name() + "_" + componentDto.getTargetId();
                McpComponentDto component = componentMap.get(key);
                //存在则使用
                if (component != null && StringUtils.isNotBlank(component.getToolName())) {
                    componentDto.setToolName(component.getToolName());
                } else {
                    EnNameDto enNameDto = null;
                    try {
                        String prompt = componentDto.getName();
                        if (StringUtils.isNotBlank(componentDto.getDescription())) {
                            prompt += "(" + componentDto.getDescription() + ")";
                        }
                        enNameDto = modelApplicationService.call(prompt, new ParameterizedTypeReference<EnNameDto>() {
                        });
                    } catch (Exception e) {
                        log.warn("call model name error", e);
                        //忽略
                    }
                    if (enNameDto != null && StringUtils.isNotBlank(enNameDto.getEnName())) {
                        String toolName = enNameDto.getEnName().replace(" ", "");
                        if (toolNames.contains(toolName)) {
                            toolName = toolName + "_" + componentDto.getTargetId();
                            toolNames.add(toolName);
                        }
                        componentDto.setToolName(toolName);
                    }
                }
            }
        }
    }

    @Override
    public void updateMcp(McpDto mcpDto) {
        Assert.notNull(mcpDto, "mcpDto must be non-null");
        Assert.notNull(mcpDto.getId(), "id must be non-null");
        McpDto mcp = getMcp(mcpDto.getId());
        if (mcp == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.mcpNotFound);
        }
        mcpDto.setInstallType(mcp.getInstallType());
        completeComponentMcpToolNames(mcpDto);
        McpConfig mcpConfig = new McpConfig();
        BeanUtils.copyProperties(mcpDto, mcpConfig);
        if (mcpDto.getMcpConfig() != null) {
            mcpConfig.setConfig(JSON.toJSONString(mcpDto.getMcpConfig()));
        }
        if (mcpDto.getDeployStatus() == DeployStatusEnum.Stopped) {
            mcpConfig.setDeployedConfig(null);
        }
        if (mcpDto.getIcon() != null && mcpDto.getIcon().contains("api/logo/")) {
            mcpConfig.setIcon(null);
        }
        mcpConfigDomainService.update(mcpConfig);
    }

    @Override
    public void deleteMcp(Long id) {
        mcpConfigDomainService.delete(id);
    }

    @Override
    public McpDto getMcp(Long id) {
        return getMcp0(id, false);
    }

    private McpDto getMcp0(Long id, boolean deployed) {
        McpConfig mcpConfig = mcpConfigDomainService.getById(id);
        if (mcpConfig == null) {
            return null;
        }
        if (deployed && (mcpConfig.getDeployStatus() == DeployStatusEnum.Initialization || mcpConfig.getDeployStatus() == DeployStatusEnum.Stopped || mcpConfig.getDeployedConfig() == null)) {
            return null;
        }
        McpDto mcpDto = new McpDto();
        BeanUtils.copyProperties(mcpConfig, mcpDto);
        mcpDto.setMcpConfig(convertToMcpConfig(mcpConfig.getConfig()));
        mcpDto.setDeployedConfig(convertToMcpConfig(mcpConfig.getDeployedConfig()));
        if (mcpDto.getInstallType() == InstallTypeEnum.COMPONENT) {
            completeComponent(mcpDto.getDeployedConfig(), mcpConfig.getSpaceId());
            if (mcpDto.getMcpConfig() != null && mcpDto.getDeployStatus() == DeployStatusEnum.Deployed) {
                mcpDto.getMcpConfig().setTools(mcpDto.getDeployedConfig().getTools());
            }
        }
        UserDto userDto = userApplicationService.queryById(mcpConfig.getCreatorId());
        CreatorDto creatorDto = convertUserToCreator(userDto);
        mcpDto.setCreator(creatorDto);
        mcpDto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(mcpDto.getIcon(), mcpDto.getName(), "mcp"));
        return mcpDto;
    }

    private McpConfigDto convertToMcpConfig(String config) {
        if (!JSON.isValid(config)) {
            return null;
        }
        McpConfigDto mcpConfigDto = JSON.parseObject(config, McpConfigDto.class);
        if (mcpConfigDto == null) {
            return null;
        }
        if (CollectionUtils.isNotEmpty(mcpConfigDto.getTools())) {
            mcpConfigDto.getTools().forEach(mcpToolDto -> {
                completeMcpArgDtos(mcpToolDto.getInputArgs());
                completeMcpArgDtos(mcpToolDto.getOutputArgs());
            });
        }
        return mcpConfigDto;
    }

    private void completeMcpArgDtos(List<McpArgDto> inputArgs) {
        if (CollectionUtils.isNotEmpty(inputArgs)) {
            inputArgs.forEach(mcpArgDto -> {
                if (mcpArgDto.getDataType() == null) {
                    mcpArgDto.setDataType(McpDataTypeEnum.String);
                }
                completeMcpArgDtos(mcpArgDto.getSubArgs());
            });
        }
    }

    @Override
    public McpDto getDeployedMcp(Long id) {
        return getMcp0(id, true);
    }

    private void completeComponent(McpConfigDto mcpConfig, Long spaceId) {
        if (mcpConfig != null && CollectionUtils.isNotEmpty(mcpConfig.getComponents())) {
            mcpConfig.setResources(new ArrayList<>());
            mcpConfig.setTools(new ArrayList<>());
            mcpConfig.setPrompts(new ArrayList<>());
            List<McpComponentDto> components = mcpConfig.getComponents();
            Iterator<McpComponentDto> ite = components.iterator();
            while (ite.hasNext()) {
                McpComponentDto component = ite.next();
                if (component.getType() == McpComponentTypeEnum.Agent) {
                    ReqResult<AgentInfoDto> agentInfoDtoReqResult = iAgentRpcService.queryPublishedAgentInfo(component.getTargetId());
                    if (!agentInfoDtoReqResult.isSuccess()) {
                        ite.remove();
                        continue;
                    }
                    AgentInfoDto agentInfoDto = agentInfoDtoReqResult.getData();
                    component.setName(agentInfoDto.getName());
                    component.setDescription(agentInfoDto.getDescription());
                    component.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(agentInfoDto.getIcon(), agentInfoDto.getName(), McpComponentTypeEnum.Agent.name()));
                    McpToolDto mcpToolDto = new McpToolDto();
                    mcpToolDto.setName("agent_execute_" + agentInfoDto.getId());
                    if (StringUtils.isNotBlank(component.getToolName())) {
                        mcpToolDto.setName(component.getToolName());
                    }
                    mcpToolDto.setDescription(agentInfoDto.getDescription());
                    List<McpArgDto> inputArgs = new ArrayList<>();
                    mcpToolDto.setInputArgs(inputArgs);
                    mcpToolDto.getInputArgs().add(McpArgDto.builder().key("message").name("message").description("用户提示词消息内容，如果有文档、图片等附件内容可以将相关的URL地址一并放置在用户提示词消息中")
                            .require(true)
                            .dataType(McpDataTypeEnum.String)
                            .build());
                    if (CollectionUtils.isNotEmpty(agentInfoDto.getVariables())) {
                        McpArgDto mcpArgDto = McpArgDto.builder().key("variables").name("variables").require(false)
                                .dataType(McpDataTypeEnum.Object).description("变量参数，格式为JSON对象")
                                .subArgs(convertToMcpArgDtos(agentInfoDto.getVariables())).build();
                        mcpToolDto.getInputArgs().add(mcpArgDto);
                    }
                    mcpToolDto.setJsonSchema(JSON.toJSONString(buildSchema(mcpToolDto.getInputArgs())));
                    mcpConfig.getTools().add(mcpToolDto);
                    component.setToolName(mcpToolDto.getName());
                }
                if (component.getType() == McpComponentTypeEnum.Table) {
                    DorisTableDefineRequest dorisTableDefineRequest = new DorisTableDefineRequest();
                    dorisTableDefineRequest.setTableId(component.getTargetId());
                    TableDefineVo tableDefineVo = iComposeDbTableRpcService.queryTableDefinition(dorisTableDefineRequest);
                    if (tableDefineVo == null) {
                        ite.remove();
                        continue;
                    }
                    component.setName(tableDefineVo.getTableName());
                    component.setDescription(tableDefineVo.getTableDescription());
                    component.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(tableDefineVo.getIcon(), tableDefineVo.getTableName(), McpComponentTypeEnum.Table.name()));
                    McpToolDto mcpToolDto = new McpToolDto();
                    mcpToolDto.setName("sql_execute_" + tableDefineVo.getId());
                    if (StringUtils.isNotBlank(component.getToolName())) {
                        mcpToolDto.setName(component.getToolName());
                    }
                    mcpToolDto.setDescription(tableDefineVo.getTableDescription());
                    mcpToolDto.setInputArgs(Arrays.asList(
                            McpArgDto.builder().key("sql").name("sql").description("SQL语句，可以直接执行且符合业务诉求的SQL语句，符合MySQL语法规范。表结构：" + convertArgsToSimpleTableStructure(tableDefineVo.getFieldList()))
                                    .require(true)
                                    .dataType(McpDataTypeEnum.String)
                                    .build())
                    );
                    mcpToolDto.setJsonSchema(JSON.toJSONString(buildSchema(mcpToolDto.getInputArgs())));
                    mcpConfig.getTools().add(mcpToolDto);
                    component.setToolName(mcpToolDto.getName());
                }
                if (component.getType() == McpComponentTypeEnum.Knowledge) {
                    KnowledgeConfigVo knowledgeConfigVo = iKnowledgeConfigRpcService.queryKnowledgeConfigById(component.getTargetId());
                    if (knowledgeConfigVo == null) {
                        ite.remove();
                        continue;
                    }
                    component.setName(knowledgeConfigVo.getName());
                    component.setDescription(knowledgeConfigVo.getDescription());
                    component.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(knowledgeConfigVo.getIcon(), knowledgeConfigVo.getName(), McpComponentTypeEnum.Knowledge.name()));
                    McpToolDto mcpToolDto = new McpToolDto();
                    mcpToolDto.setName("knowledge_query_" + knowledgeConfigVo.getId());
                    if (StringUtils.isNotBlank(component.getToolName())) {
                        mcpToolDto.setName(component.getToolName());
                    }
                    mcpToolDto.setDescription(knowledgeConfigVo.getDescription());
                    mcpToolDto.setInputArgs(List.of(
                                    McpArgDto.builder().key("query").name("query").description("知识库搜索关键词")
                                            .require(true)
                                            .dataType(McpDataTypeEnum.String)
                                            .build(),
                                    McpArgDto.builder().key("topK").name("topK").description("返回topK条数，默认5")
                                            .require(false)
                                            .dataType(McpDataTypeEnum.Integer)
                                            .build(),
                                    McpArgDto.builder().key("rawText").name("rawText").description("是否返回原始段落")
                                            .require(false)
                                            .dataType(McpDataTypeEnum.Boolean)
                                            .build()
                            )
                    );
                    mcpToolDto.setJsonSchema(JSON.toJSONString(buildSchema(mcpToolDto.getInputArgs())));
                    mcpConfig.getTools().add(mcpToolDto);
                    component.setToolName(mcpToolDto.getName());
                }
                if (component.getType() == McpComponentTypeEnum.Plugin) {
                    ReqResult<PluginInfoDto> publishedPluginInfo = iAgentRpcService.getPublishedPluginInfo(component.getTargetId(), spaceId);
                    if (!publishedPluginInfo.isSuccess() || publishedPluginInfo.getData() == null) {
                        ite.remove();
                        continue;
                    }
                    PluginInfoDto pluginInfoDto = publishedPluginInfo.getData();
                    component.setName(pluginInfoDto.getName());
                    component.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(pluginInfoDto.getIcon(), pluginInfoDto.getName(), McpComponentTypeEnum.Plugin.name()));
                    component.setDescription(pluginInfoDto.getDescription());
                    McpToolDto mcpToolDto = new McpToolDto();
                    mcpToolDto.setName("tool_plugin_" + pluginInfoDto.getId());
                    if (StringUtils.isNotBlank(component.getToolName())) {
                        mcpToolDto.setName(component.getToolName());
                    }
                    mcpToolDto.setDescription(pluginInfoDto.getDescription());
                    if (pluginInfoDto.getInputArgs() != null) {
                        List<ArgDto> bindArgs = null;
                        if (StringUtils.isNotBlank(component.getTargetBindConfig())) {
                            bindArgs = iAgentRpcService.parseAgentPluginBindArgs(component.getTargetBindConfig());
                        }
                        //移除未启用的参数
                        removeDisabledArgs(pluginInfoDto.getInputArgs(), bindArgs);
                        List<McpArgDto> argDtos = convertToMcpArgDtos(pluginInfoDto.getInputArgs());
                        mcpToolDto.setInputArgs(argDtos);
                    }
                    mcpToolDto.setJsonSchema(JSON.toJSONString(buildSchema(mcpToolDto.getInputArgs())));
                    mcpConfig.getTools().add(mcpToolDto);
                    component.setToolName(mcpToolDto.getName());
                    component.setTargetConfig(pluginInfoDto.getConfig());
                }
                if (component.getType() == McpComponentTypeEnum.Workflow) {
                    ReqResult<WorkflowInfoDto> publishedWorkflowInfo = iAgentRpcService.getPublishedWorkflowInfo(component.getTargetId(), spaceId);
                    if (!publishedWorkflowInfo.isSuccess() || publishedWorkflowInfo.getData() == null) {
                        ite.remove();
                        continue;
                    }
                    WorkflowInfoDto workflowInfoDto = publishedWorkflowInfo.getData();
                    component.setName(workflowInfoDto.getName());
                    component.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(workflowInfoDto.getIcon(), workflowInfoDto.getName(), McpComponentTypeEnum.Workflow.name()));
                    component.setDescription(workflowInfoDto.getDescription());
                    McpToolDto mcpToolDto = new McpToolDto();
                    mcpToolDto.setName("tool_workflow_" + workflowInfoDto.getId());
                    if (StringUtils.isNotBlank(component.getToolName())) {
                        mcpToolDto.setName(component.getToolName());
                    }
                    if (workflowInfoDto.getInputArgs() != null) {
                        List<ArgDto> bindArgs = null;
                        if (StringUtils.isNotBlank(component.getTargetBindConfig())) {
                            bindArgs = iAgentRpcService.parseWorkflowPluginBindArgs(component.getTargetBindConfig());
                        }
                        //移除未启用的参数
                        removeDisabledArgs(workflowInfoDto.getInputArgs(), bindArgs);
                        List<McpArgDto> argDtos = convertToMcpArgDtos(workflowInfoDto.getInputArgs());
                        mcpToolDto.setInputArgs(argDtos);
                    }
                    mcpToolDto.setDescription(workflowInfoDto.getDescription());
                    mcpToolDto.setJsonSchema(JSON.toJSONString(buildSchema(mcpToolDto.getInputArgs())));
                    mcpConfig.getTools().add(mcpToolDto);
                    component.setToolName(mcpToolDto.getName());
                    component.setTargetConfig(workflowInfoDto.getConfig());
                }
            }
        }
    }

    private void removeDisabledArgs(List<ArgDto> inputArgs, List<ArgDto> bindArgs) {
        Map<String, ArgDto> bindArgMap = null;
        if (bindArgs != null) {
            bindArgMap = bindArgs.stream().collect(Collectors.toMap(ArgDto::getName, a -> a, (a, b) -> a));
        }
        Map<String, ArgDto> finalBindArgMap = bindArgMap;
        inputArgs.removeIf(arg -> {
            if (arg.getEnable() != null && !arg.getEnable()) {
                return true;
            }
            if (finalBindArgMap != null) {
                ArgDto bindArg = finalBindArgMap.get(arg.getName());
                return bindArg != null && bindArg.getEnable() != null && !bindArg.getEnable();
            }
            return false;
        });
        inputArgs.forEach(arg -> {
            if (arg.getSubArgs() != null && !arg.getSubArgs().isEmpty()) {
                ArgDto bindArg = finalBindArgMap == null ? null : finalBindArgMap.get(arg.getName());
                removeDisabledArgs(arg.getSubArgs(), bindArg == null ? null : bindArg.getSubArgs());
            }
        });
    }

    private List<McpArgDto> convertToMcpArgDtos(List<ArgDto> inputArgs) {
        if (inputArgs == null) {
            return new ArrayList<>();
        }
        return inputArgs.stream().map(arg -> {
            McpArgDto mcpArgDto = new McpArgDto();
            mcpArgDto.setName(arg.getName());
            mcpArgDto.setDescription(arg.getDescription());
            try {
                if (arg.getDataType().name().startsWith("File")) {
                    mcpArgDto.setDataType(McpDataTypeEnum.String);
                } else if (arg.getDataType().name().startsWith("Array_File")) {
                    mcpArgDto.setDataType(McpDataTypeEnum.Array_String);
                } else {
                    mcpArgDto.setDataType(McpDataTypeEnum.valueOf(arg.getDataType().name()));
                }
            } catch (Exception e) {
                mcpArgDto.setDataType(McpDataTypeEnum.String);
            }
            mcpArgDto.setRequire(arg.isRequire());
            mcpArgDto.setKey(arg.getKey());
            mcpArgDto.setSubArgs(convertToMcpArgDtos(arg.getSubArgs()));
            return mcpArgDto;
        }).collect(Collectors.toList());
    }

    private CreatorDto convertUserToCreator(UserDto userDto) {
        if (userDto == null) {
            return null;
        }
        return CreatorDto.builder()
                .userId(userDto.getId())
                .userName(userDto.getUserName())
                .nickName(userDto.getNickName())
                .avatar(userDto.getAvatar())
                .build();
    }

    @Override
    public List<McpDto> queryMcpListBySpaceId(Long spaceId) {
        List<McpDto> mcpDtos = mcpConfigDomainService.queryListBySpaceId(spaceId).stream().map(mcpConfig -> {
            boolean isPlatformMcp = mcpConfig.getConfig() != null && mcpConfig.getConfig().contains("TENANT_SECRET");
            mcpConfig.setDeployedConfig(null);
            mcpConfig.setConfig(null);
            McpDto mcpDto = new McpDto();
            BeanUtils.copyProperties(mcpConfig, mcpDto);
            mcpDto.setPlatformMcp(isPlatformMcp);
            mcpDto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(mcpDto.getIcon(), mcpDto.getName(), "mcp"));
            return mcpDto;
        }).collect(Collectors.toList());
        completeCreator(mcpDtos);
        return mcpDtos;
    }

    private void completeCreator(List<McpDto> mcpDtos) {
        List<UserDto> userDtos = userApplicationService.queryUserListByIds(mcpDtos.stream().map(McpDto::getCreatorId).collect(Collectors.toList()));
        Map<Long, UserDto> userMap = userDtos.stream().collect(Collectors.toMap(UserDto::getId, userDto -> userDto));
        TenantConfigDto tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
        mcpDtos.forEach(mcpDto -> {
            UserDto userDto = userMap.get(mcpDto.getCreatorId());
            if (userDto == null) {
                userDto = new UserDto();
                userDto.setId(-1L);
                userDto.setUserName("");
            }
            if (StringUtils.isBlank(userDto.getNickName())) {
                userDto.setNickName(userDto.getUserName());
            }
            mcpDto.setCreator(CreatorDto.builder()
                    .userId(userDto.getId())
                    .userName(userDto.getUserName())
                    .nickName(StringUtils.isNotBlank(tenantConfigDto.getOfficialUserName()) && mcpDto.getSpaceId() != null && mcpDto.getSpaceId() == -1L ? tenantConfigDto.getOfficialUserName() : userDto == null ? "" : userDto.getNickName())
                    .avatar(userDto.getAvatar())
                    .build());
        });
    }

    @Override
    public IPage<McpDto> queryDeployedMcpList(McpPageQueryDto mcpPageQueryDto) {
        IPage<McpDto> mcpPage = mcpConfigDomainService.queryDeployedMcpList(mcpPageQueryDto).convert(mcpConfig -> convertToMcpDto(mcpConfig));
        List<McpDto> mcpDtos = mcpPage.getRecords();
        completeCreator(mcpDtos);
        return mcpPage;
    }

    @Override
    public IPage<McpDto> queryDeployedMcpListForManage(McpPageQueryDto mcpPageQueryDto) {
        IPage<McpDto> mcpPage = mcpConfigDomainService.queryDeployedMcpListForManage(mcpPageQueryDto).convert(mcpConfig -> convertToMcpDto(mcpConfig));
        List<McpDto> mcpDtos = mcpPage.getRecords();
        completeCreator(mcpDtos);
        return mcpPage;
    }

    private McpDto convertToMcpDto(McpConfig mcpConfig) {
        McpDto mcpDto = new McpDto();
        BeanUtils.copyProperties(mcpConfig, mcpDto);
        mcpDto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(mcpDto.getIcon(), mcpDto.getName(), "mcp"));
        mcpDto.setDeployedConfig(JSON.parseObject(mcpConfig.getDeployedConfig(), McpConfigDto.class));
        if (mcpDto.getDeployedConfig() != null) {
            mcpDto.getDeployedConfig().setServerConfig(null);
            mcpDto.getDeployedConfig().setResources(null);
            mcpDto.getDeployedConfig().setPrompts(null);
            if (mcpDto.getDeployedConfig().getTools() != null) {
                mcpDto.getDeployedConfig().getTools().forEach(tool -> {
                    tool.setInputArgs(null);
                    tool.setJsonSchema(null);
                });
            }
        }
        if (mcpDto.getInstallType() == InstallTypeEnum.COMPONENT) {
            completeComponent(mcpDto.getDeployedConfig(), mcpConfig.getSpaceId());
            mcpDto.getDeployedConfig().setComponents(null);
        }
        return mcpDto;
    }


    @Override
    public String getExportMcpServerConfig(Long userId, Long mcpId, UserAccessKeyDto.UserAccessKeyConfig userAccessKeyConfig) {
        List<UserAccessKeyDto> userAccessKeyDtos = userAccessKeyApiService.queryAccessKeyList(userId, UserAccessKeyDto.AKTargetType.Mcp, mcpId.toString());
        UserAccessKeyDto userAccessKeyDto;
        if (CollectionUtils.isEmpty(userAccessKeyDtos)) {
            userAccessKeyDto = userAccessKeyApiService.newAccessKey(userId, UserAccessKeyDto.AKTargetType.Mcp, mcpId.toString());
        } else {
            userAccessKeyDto = userAccessKeyDtos.get(0);
        }
        if (userAccessKeyConfig != null) {
            userAccessKeyApiService.updateUserAccessKeyConfig(userAccessKeyDto.getId(), userAccessKeyConfig);
        }
        McpDto mcpDto = getMcp(mcpId);
        TenantConfigDto tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
        String siteUrl = tenantConfigDto.getSiteUrl().trim().endsWith("/") ? tenantConfigDto.getSiteUrl() : tenantConfigDto.getSiteUrl() + "/";
        String sseUrl = siteUrl + "api/mcp/sse?ak=" + userAccessKeyDto.getAccessKey();
        JSONObject jsonObject = new JSONObject();
        JSONObject mcpServers = new JSONObject();
        JSONObject mcpServer = new JSONObject();
        jsonObject.put("mcpServers", mcpServers);
        if (StringUtils.isBlank(mcpDto.getServerName())) {
            mcpServers.put("mcp-server-" + mcpId, mcpServer);
        } else {
            mcpServers.put(mcpDto.getServerName(), mcpServer);
        }
        mcpServer.put("type", "sse");
        mcpServer.put("url", sseUrl);
        return jsonObject.toJSONString();
    }

    @Override
    public String refreshExportMcpServerConfig(Long userId, Long mcpId) {
        List<UserAccessKeyDto> userAccessKeyDtos = userAccessKeyApiService.queryAccessKeyList(userId, UserAccessKeyDto.AKTargetType.Mcp, mcpId.toString());
        if (CollectionUtils.isNotEmpty(userAccessKeyDtos)) {
            userAccessKeyApiService.refreshAccessKey(userAccessKeyDtos.get(0).getId());
        }
        return getExportMcpServerConfig(userId, mcpId, null);
    }

    public static String convertArgsToSimpleTableStructure(List<TableFieldDefineVo> fieldDefineVos) {
        StringBuilder sb = new StringBuilder();
        if (fieldDefineVos == null) {
            return sb.toString();
        }
        sb.append("CREATE TABLE IF NOT EXISTS `custom_table` (");
        fieldDefineVos.forEach(arg -> {
            sb.append(arg.getFieldName()).append(" ").append(getMysqlType(getDataType(arg.getFieldType()))).append(" ");
            Boolean require = YnEnum.Y.getKey().equals(arg.getNullableFlag()) ? false : true;
            if (require) {
                sb.append("NOT NULL ");
            }
            sb.append("COMMENT '").append(arg.getFieldDescription().replace("'", "\\'")).append("'");
            sb.append(",");
        });
        return sb.substring(0, sb.length() - 1) + ")";
    }

    private static DataTypeEnum getDataType(Integer fieldType) {
        //1:String;2:Integer;3:Number;4:Boolean;5:Date
        switch (fieldType) {
            case 2:
                return DataTypeEnum.Integer;
            case 3:
                return DataTypeEnum.Number;
            case 4:
                return DataTypeEnum.Boolean;
            default:
                return DataTypeEnum.String;
        }
    }

    private static String getMysqlType(DataTypeEnum dataType) {
        switch (dataType) {
            case String:
                return "varchar(255)";
            case Integer:
                return "int(10)";
            case Number:
                return "bigDecimal";
            case Boolean:
                return "tinyint(4)";
        }
        return "datetime";
    }

    private static Map<String, Object> buildSchema(List<McpArgDto> inputArgs) {
        if (inputArgs == null) {
            return new HashMap<>();
        }
        List<String> required = new ArrayList<>();
        Map<String, Object> properties = new HashMap<>();
        Map<String, Object> params = new HashMap<>();
        params.putAll(Map.of("type", "object", "properties", properties, "required", required));
        if (inputArgs != null && inputArgs.size() > 0) {
            for (McpArgDto inputArg : inputArgs) {
                if (inputArg.isRequire()) {
                    required.add(inputArg.getName());
                }
                McpDataTypeEnum dataType = inputArg.getDataType();
                if (dataType == McpDataTypeEnum.Object) {
                    properties.put(inputArg.getName(), buildSchema(inputArg.getSubArgs()));
                    continue;
                }
                if (dataType == McpDataTypeEnum.Array_Object) {
                    properties.put(inputArg.getName(), Map.of("type", "array",
                            "items", buildSchema(inputArg.getSubArgs())));
                    continue;
                }
                if (dataType.name().startsWith("Array_String") || dataType.name().startsWith("Array_Number")
                        || dataType.name().startsWith("Array_Boolean") || dataType.name().startsWith("Array_Integer")) {
                    properties.put(inputArg.getName(), Map.of("type", "array",
                            "items", Map.of("type", inputArg.getDataType().name().split("_")[1].toLowerCase())));
                    continue;
                }
                if (inputArg.getDataType().name().startsWith("Array")) {
                    properties.put(inputArg.getName(), Map.of("type", "array", "items", Map.of("type", "string")));
                    continue;
                }

                String dataTypeStr = McpDataTypeEnum.String.name().toLowerCase();
                if (inputArg.getDataType() == McpDataTypeEnum.Number) {
                    dataTypeStr = DataTypeEnum.Number.name().toLowerCase();
                } else if (inputArg.getDataType() == McpDataTypeEnum.Boolean) {
                    dataTypeStr = DataTypeEnum.Boolean.name().toLowerCase();
                } else if (inputArg.getDataType() == McpDataTypeEnum.Integer) {
                    dataTypeStr = DataTypeEnum.Integer.name().toLowerCase();
                }
                properties.put(inputArg.getName(), Map.of("type", dataTypeStr, "description", inputArg.getDescription() == null ? inputArg.getName() : inputArg.getDescription()));
            }
        }
        return params;
    }

    @Override
    public Long deployOfficialMcp(McpDto mcpDto) {
        Assert.notNull(mcpDto.getUid(), "uid must be non-null");
        mcpDto.setSpaceId(-1L);
        McpConfig mcpConfig = mcpConfigDomainService.getBySpaceIdAndUid(-1L, mcpDto.getUid());
        Long mcpId;
        if (mcpConfig != null) {
            mcpDto.setId(mcpConfig.getId());
            updateMcp(mcpDto);
            mcpDto.setId(mcpConfig.getId());
            mcpId = mcpConfig.getId();
        } else {
            addMcp(mcpDto);
            mcpId = mcpDto.getId();
        }
        mcpDeployTaskService.addDeployTask(mcpDto);
        return mcpId;
    }

    @Override
    public void stopOfficialMcp(Long id) {
        McpDto update = new McpDto();
        update.setId(id);
        update.setDeployStatus(DeployStatusEnum.Stopped);
        updateMcp(update);
    }

    @Override
    public Long deployProxyMcp(McpDto mcpDto) {
        Long id = mcpDto.getId();
        if (mcpDto.getId() == null) {
            addMcp(mcpDto);
            id = mcpDto.getId();
        } else {
            updateMcp(mcpDto);
        }
        mcpDeployTaskService.addDeployTask(mcpDto, false);
        return id;
    }

    @Override
    public Long countTotalMcps() {
        return mcpConfigDomainService.countTotalMcps();
    }
}
