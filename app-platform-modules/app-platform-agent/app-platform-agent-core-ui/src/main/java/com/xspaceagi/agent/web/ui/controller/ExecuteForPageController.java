package com.xspaceagi.agent.web.ui.controller;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.agent.core.adapter.application.*;
import com.xspaceagi.agent.core.adapter.dto.*;
import com.xspaceagi.agent.core.adapter.dto.config.AgentComponentConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.AgentConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.PluginDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowConfigDto;
import com.xspaceagi.agent.core.adapter.repository.entity.AgentComponentConfig;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.infra.rpc.CustomPageRpcService;
import com.xspaceagi.agent.core.infra.rpc.dto.PageDto;
import com.xspaceagi.agent.core.sdk.IAgentRpcService;
import com.xspaceagi.agent.core.sdk.enums.TargetTypeEnum;
import com.xspaceagi.agent.web.ui.controller.util.ReferUtil;
import com.xspaceagi.custompage.sdk.dto.DataSourceDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.sdk.permission.IUserDataPermissionRpcService;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.sdk.service.dto.UserDataPermissionDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Tag(name = "应用页面组件调用相关接口")
@RestController
public class ExecuteForPageController {

    @Resource
    private SpacePermissionService spacePermissionService;

    @Resource
    private WorkflowApplicationService workflowApplicationService;

    @Resource
    private PluginApplicationService pluginApplicationService;

    @Resource
    private IAgentRpcService iAgentRpcService;

    @Resource
    private CustomPageRpcService customPageRpcService;

    @Resource
    private AgentApplicationService agentApplicationService;

    @Resource
    private PublishApplicationService publishApplicationService;

    @Resource
    private UserApplicationService userApplicationService;

    @Resource
    private IUserDataPermissionRpcService userDataPermissionRpcService;

    @Resource
    private TableWorkflowForPageApplicationService tableWorkflowForPageApplicationService;

    @Operation(summary = "运行已发布的工作流")
    @RequestMapping(path = "/api/page/workflow/{key}/streamExecute", method = RequestMethod.POST, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamExecuteWorkflow(@PathVariable String key, @RequestBody @Valid Map<String, Object> params, HttpServletRequest request, HttpServletResponse response) {
        String requestId = UUID.randomUUID().toString().replace("-", "");
        ReferUtil.RefererParseVo refererParseVo = ReferUtil.parseRefer(request);
        PageDto pageDto;
        try {
            pageDto = parseFromReferer(refererParseVo);
        } catch (Exception e) {
            return Flux.create(emitter -> sendError(emitter, requestId, e.getMessage()));
        }

        Optional<DataSourceDto> first = pageDto.getDataSources().stream().filter(dataSourceDto -> dataSourceDto.getKey() != null && dataSourceDto.getKey().equals(key)).findFirst();
        if (first.isEmpty()) {
            return Flux.create(emitter -> sendError(emitter, requestId, "The requested data source does not exist"));
        }

        WorkflowConfigDto workflowConfigDto = workflowApplicationService.queryPublishedWorkflowConfig(first.get().getId(), null, true);
        if (workflowConfigDto == null) {
            return Flux.create(emitter -> sendError(emitter, requestId, "Invalid workflow ID or not published"));
        }
        // Verify if the page's space has workflow execution permission
        List<Long> publishedSpaceIds = workflowConfigDto.getPublishedSpaceIds();
        if (CollectionUtils.isNotEmpty(publishedSpaceIds)) {
            boolean anyMatch = publishedSpaceIds.stream().anyMatch(spaceId -> spaceId != null && spaceId.equals(pageDto.getSpaceId()));
            if (!anyMatch) {
                return Flux.create(emitter -> sendError(emitter, requestId, "No workflow execution permission"));
            }
        }

        WorkflowExecuteRequestDto workflowExecuteRequestDto = new WorkflowExecuteRequestDto();
        workflowExecuteRequestDto.setWorkflowId(first.get().getId());
        workflowExecuteRequestDto.setParams(params);
        workflowExecuteRequestDto.setRequestId(requestId);
        workflowExecuteRequestDto.setAgentId(refererParseVo.getAgentId());
        response.setCharacterEncoding("utf-8");
        return workflowApplicationService.executeWorkflow(workflowExecuteRequestDto, workflowConfigDto)
                .map(workflowExecutingDto -> " " + JSON.toJSONString(workflowExecutingDto));
    }


    @Operation(summary = "运行已发布的工作流（new）")
    @RequestMapping(path = "/api/page/w/{key}", method = RequestMethod.POST)
    public ReqResult<Object> executeW(@PathVariable String key, @RequestBody @Valid Map<String, Object> params, HttpServletRequest request, HttpServletResponse response) {
        String requestId = UUID.randomUUID().toString().replace("-", "");
        ReferUtil.RefererParseVo refererParseVo = ReferUtil.parseRefer(request);
        PageDto pageDto = parseFromReferer(refererParseVo);
        Optional<DataSourceDto> first = pageDto.getDataSources().stream().filter(dataSourceDto -> dataSourceDto.getKey() != null && dataSourceDto.getKey().equals(key)).findFirst();
        if (first.isEmpty()) {
            return ReqResult.error("The requested data source does not exist");
        }

        WorkflowConfigDto workflowConfigDto = workflowApplicationService.queryPublishedWorkflowConfig(first.get().getId(), null, true);
        if (workflowConfigDto == null) {
            return ReqResult.error("Invalid workflow ID or not published");
        }
        // Verify if the page's space has workflow execution permission
        List<Long> publishedSpaceIds = workflowConfigDto.getPublishedSpaceIds();
        if (CollectionUtils.isNotEmpty(publishedSpaceIds)) {
            boolean anyMatch = publishedSpaceIds.stream().anyMatch(spaceId -> spaceId != null && spaceId.equals(pageDto.getSpaceId()));
            if (!anyMatch) {
                return ReqResult.error("No workflow execution permission");
            }
        }

        WorkflowExecuteRequestDto workflowExecuteRequestDto = new WorkflowExecuteRequestDto();
        workflowExecuteRequestDto.setWorkflowId(first.get().getId());
        workflowExecuteRequestDto.setParams(params);
        workflowExecuteRequestDto.setRequestId(requestId);
        workflowExecuteRequestDto.setAgentId(refererParseVo.getAgentId());
        response.setCharacterEncoding("utf-8");
        WorkflowExecutingDto workflowExecutingDto = workflowApplicationService.executeWorkflow(workflowExecuteRequestDto, workflowConfigDto).blockLast();
        return workflowExecutingDto != null && workflowExecutingDto.isSuccess() ? ReqResult.success(workflowExecutingDto.getData()) : ReqResult.error(workflowExecutingDto == null ? "" : workflowExecutingDto.getMessage());
    }

    @Operation(summary = "运行已发布的工作流")
    @RequestMapping(path = "/api/page/workflow/{key}/execute", method = RequestMethod.POST)
    public ReqResult<Object> executeWorkflow(@PathVariable String key, @RequestBody @Valid Map<String, Object> params, HttpServletRequest request, HttpServletResponse response) {
        return executeW(key, params, request, response);
    }

    private PageDto parseFromReferer(ReferUtil.RefererParseVo refererParseVo) {
        if (refererParseVo == null || refererParseVo.getPageId() == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentPageIdError);
        }
        if (refererParseVo.getAgentId() == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentPageAgentIdError);
        }
        if (refererParseVo.getEnv() == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentPageEnvInvalid);
        }
        PageDto pageDto = customPageRpcService.queryPageDto(refererParseVo.getPageId(), false);
        if (pageDto == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentPageIdError);
        }
        if (pageDto.getDataSources() == null || pageDto.getDataSources().isEmpty()) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentPageDatasourceInvalid);
        }

        if (pageDto.getNeedLogin()) {
            if (!RequestContext.get().isLogin()) {
                throw BizException.of(ErrorCodeEnum.UNAUTHORIZED, BizExceptionCodeEnum.systemUnauthorizedOrSessionExpired);
            }
        } else {
            UserDto userDto = userApplicationService.queryById(pageDto.getCreatorId());
            if (userDto == null) {
                throw BizException.of(ErrorCodeEnum.UNAUTHORIZED, BizExceptionCodeEnum.systemUnauthorizedOrSessionExpired);
            }
            RequestContext.get().setUser(userDto);
            RequestContext.get().setUserId(userDto.getId());
        }

        AgentConfigDto agentConfigDto = agentApplicationService.queryPublishedConfigForExecute(refererParseVo.getAgentId());
        if (agentConfigDto != null && agentConfigDto.getAccessControl() != null && agentConfigDto.getAccessControl().equals(YesOrNoEnum.Y.getKey())) {
            // User data permission
            UserDataPermissionDto userDataPermission = userDataPermissionRpcService.getUserDataPermission(RequestContext.get().getUserId());
            if (userDataPermission.getAgentIds() == null || !userDataPermission.getAgentIds().contains(refererParseVo.getAgentId())) {
                throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
            }
        }
        // For dev environment or when the dependent agent is not published, verify if the user has permission for the page's space
        if ("dev".equals(refererParseVo.getEnv()) || agentConfigDto == null) {
            try {
                spacePermissionService.checkSpaceUserPermission(pageDto.getSpaceId());
            } catch (Exception e) {
                throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
            }
        } else {
            // Verify if the user has permission for the agent
            PublishedPermissionDto publishedPermissionDto = publishApplicationService.hasPermission(Published.TargetType.Agent, refererParseVo.getAgentId());
            if (!publishedPermissionDto.isExecute()) {
                throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
            }
            AgentComponentConfigDto agentComponentConfigDto = agentConfigDto.getAgentComponentConfigList().stream().filter(agentComponentConfig -> agentComponentConfig.getType() == AgentComponentConfig.Type.Page).findFirst().orElse(null);
            if (agentComponentConfigDto == null) {
                agentComponentConfigDto = agentApplicationService.queryComponentConfigList(refererParseVo.getAgentId()).stream().filter(agentComponentConfig -> agentComponentConfig.getType() == AgentComponentConfig.Type.Page).findFirst().orElse(null);
                if (agentComponentConfigDto == null) {
                    throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentPageNotBoundToAgent);
                }
            }
        }
        return pageDto;
    }

    private void sendError(FluxSink<String> emitter, String requestId, String message) {
        WorkflowExecutingDto workflowExecutingDto = new WorkflowExecutingDto();
        workflowExecutingDto.setSuccess(false);
        workflowExecutingDto.setRequestId(requestId);
        workflowExecutingDto.setMessage(message);
        workflowExecutingDto.setComplete(true);
        emitter.next(" " + JSON.toJSONString(workflowExecutingDto));
        emitter.complete();
    }

    @Operation(summary = "插件运行接口（new）")
    @RequestMapping(path = "/api/page/p/{key}", method = RequestMethod.POST)
    public ReqResult<Object> executeP(@PathVariable String key, @RequestBody Map<String, Object> params, HttpServletRequest request) {
        String requestId = UUID.randomUUID().toString().replace("-", "");
        ReferUtil.RefererParseVo refererParseVo = ReferUtil.parseRefer(request);
        PageDto pageDto;
        pageDto = parseFromReferer(refererParseVo);
        Optional<DataSourceDto> first = pageDto.getDataSources().stream().filter(dataSourceDto -> dataSourceDto.getKey() != null && dataSourceDto.getKey().equals(key)).findFirst();
        if (first.isEmpty()) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentPageDatasourceNotFound);
        }
        PluginDto pluginDto = pluginApplicationService.queryPublishedPluginConfig(first.get().getId(), null);
        if (pluginDto == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentPluginNotPublished);
        }
        List<Long> publishedSpaceIds = pluginDto.getPublishedSpaceIds();
        if (CollectionUtils.isNotEmpty(publishedSpaceIds)) {
            boolean anyMatch = publishedSpaceIds.stream().anyMatch(spaceId -> spaceId != null && spaceId.equals(pageDto.getSpaceId()));
            if (!anyMatch) {
                throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
            }
        }
        PluginExecuteRequestDto pluginExecuteRequestDto = new PluginExecuteRequestDto();
        pluginExecuteRequestDto.setParams(params);
        pluginExecuteRequestDto.setPluginId(first.get().getId());
        pluginExecuteRequestDto.setRequestId(requestId);
        pluginExecuteRequestDto.setRequestId(UUID.randomUUID().toString());
        pluginExecuteRequestDto.setAgentId(refererParseVo.getAgentId());
        pluginExecuteRequestDto.setTest(false);
        PluginExecuteResultDto pluginExecuteResultDto = pluginApplicationService.execute(pluginExecuteRequestDto, pluginDto);
        return pluginExecuteResultDto.isSuccess() ? ReqResult.success(pluginExecuteResultDto.getResult()) : ReqResult.error(pluginExecuteResultDto.getError());
    }

    @Operation(summary = "插件运行接口")
    @RequestMapping(path = "/api/page/plugin/{key}/execute", method = RequestMethod.POST)
    public ReqResult<Object> execute(@PathVariable String key, @RequestBody Map<String, Object> params, HttpServletRequest request) {
        return executeP(key, params, request);
    }

    @Operation(summary = "获取插件、工作流接口文档schema")
    @RequestMapping(path = "/api/page/target/schema", method = RequestMethod.GET)
    public ReqResult<Object> schema(TargetTypeEnum type, Long id, Long projectId) {
        String data = iAgentRpcService.queryApiSchema(type, id, projectId).getData();
        return ReqResult.success(data != null ? JSON.parse(data) : null);
    }

    @Operation(summary = "创建数据表操作API(SQL)")
    @RequestMapping(path = "/api/table/sql/new", method = RequestMethod.POST)
    public ReqResult<PageSqlResultDTO> tableNewSql(@RequestBody PageAppNewSqlDTO pageAppNewSqlDTO) {
        PageDto pageDto = customPageRpcService.queryPageDto(Long.parseLong(pageAppNewSqlDTO.getProjectId()), false);
        if (pageDto == null) {
            throw new IllegalArgumentException("Invalid projectId");
        }
        if (StringUtils.isBlank(pageAppNewSqlDTO.getGroupName())) {
            pageAppNewSqlDTO.setGroupName(pageDto.getId().toString());//id不会变
            pageAppNewSqlDTO.setGroupDescription(pageDto.getName());
        }
        pageAppNewSqlDTO.setSpaceId(pageDto.getSpaceId());
        spacePermissionService.checkSpaceUserPermission(pageAppNewSqlDTO.getSpaceId());
        PageSqlResultDTO resultDTO = tableWorkflowForPageApplicationService.tableNewSql(pageAppNewSqlDTO);
        return ReqResult.success(resultDTO);
    }

    @Operation(summary = "更新数据表操作API(SQL)")
    @RequestMapping(path = "/api/table/sql/update", method = RequestMethod.POST)
    public ReqResult<PageSqlResultDTO> tableUpdateSql(@RequestBody PageAppUpdateSqlDTO pageAppUpdateSqlDTO) {
        WorkflowConfigDto workflowConfigDto = workflowApplicationService.queryById(pageAppUpdateSqlDTO.getApiId());
        if (workflowConfigDto == null) {
            throw new IllegalArgumentException("Invalid apiId");
        }
        PageDto pageDto = customPageRpcService.queryPageDto(Long.parseLong(pageAppUpdateSqlDTO.getProjectId()), false);
        if (pageDto == null) {
            throw new IllegalArgumentException("Invalid projectId");
        }
        Assert.isTrue(workflowConfigDto.getSpaceId().equals(pageDto.getSpaceId()), "Invalid spaceId");
        spacePermissionService.checkSpaceUserPermission(workflowConfigDto.getSpaceId());
        PageSqlResultDTO resultDTO = tableWorkflowForPageApplicationService.tableUpdateSql(pageAppUpdateSqlDTO);
        return ReqResult.success(resultDTO);
    }
}
