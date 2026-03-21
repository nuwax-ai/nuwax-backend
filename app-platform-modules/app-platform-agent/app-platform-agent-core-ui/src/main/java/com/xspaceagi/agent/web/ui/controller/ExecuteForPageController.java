package com.xspaceagi.agent.web.ui.controller;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.agent.core.adapter.application.AgentApplicationService;
import com.xspaceagi.agent.core.adapter.application.PluginApplicationService;
import com.xspaceagi.agent.core.adapter.application.PublishApplicationService;
import com.xspaceagi.agent.core.adapter.application.WorkflowApplicationService;
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
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.sdk.server.IUserDataPermissionRpcService;
import com.xspaceagi.system.sdk.service.dto.UserDataPermissionDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.exception.BizException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.http.MediaType;
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
            return Flux.create(emitter -> sendError(emitter, requestId, "请求的数据源不存在"));
        }

        WorkflowConfigDto workflowConfigDto = workflowApplicationService.queryPublishedWorkflowConfig(first.get().getId(), null, true);
        if (workflowConfigDto == null) {
            return Flux.create(emitter -> sendError(emitter, requestId, "工作流Id错误或未发布"));
        }
        //验证页面所在空间是否有接口权限
        List<Long> publishedSpaceIds = workflowConfigDto.getPublishedSpaceIds();
        if (CollectionUtils.isNotEmpty(publishedSpaceIds)) {
            boolean anyMatch = publishedSpaceIds.stream().anyMatch(spaceId -> spaceId != null && spaceId.equals(pageDto.getSpaceId()));
            if (!anyMatch) {
                return Flux.create(emitter -> sendError(emitter, requestId, "无工作流执行权限"));
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

    @Operation(summary = "运行已发布的工作流")
    @RequestMapping(path = "/api/page/workflow/{key}/execute", method = RequestMethod.POST)
    public ReqResult<Object> executeWorkflow(@PathVariable String key, @RequestBody @Valid Map<String, Object> params, HttpServletRequest request, HttpServletResponse response) {
        String requestId = UUID.randomUUID().toString().replace("-", "");
        ReferUtil.RefererParseVo refererParseVo = ReferUtil.parseRefer(request);
        PageDto pageDto = parseFromReferer(refererParseVo);
        Optional<DataSourceDto> first = pageDto.getDataSources().stream().filter(dataSourceDto -> dataSourceDto.getKey() != null && dataSourceDto.getKey().equals(key)).findFirst();
        if (!first.isPresent()) {
            return ReqResult.error("请求的数据源不存在");
        }

        WorkflowConfigDto workflowConfigDto = workflowApplicationService.queryPublishedWorkflowConfig(first.get().getId(), null, true);
        if (workflowConfigDto == null) {
            return ReqResult.error("工作流Id错误或未发布");
        }
        //验证页面所在空间是否有接口权限
        List<Long> publishedSpaceIds = workflowConfigDto.getPublishedSpaceIds();
        if (CollectionUtils.isNotEmpty(publishedSpaceIds)) {
            boolean anyMatch = publishedSpaceIds.stream().anyMatch(spaceId -> spaceId != null && spaceId.equals(pageDto.getSpaceId()));
            if (!anyMatch) {
                return ReqResult.error("无工作流执行权限");
            }
        }

        WorkflowExecuteRequestDto workflowExecuteRequestDto = new WorkflowExecuteRequestDto();
        workflowExecuteRequestDto.setWorkflowId(first.get().getId());
        workflowExecuteRequestDto.setParams(params);
        workflowExecuteRequestDto.setRequestId(requestId);
        workflowExecuteRequestDto.setAgentId(refererParseVo.getAgentId());
        response.setCharacterEncoding("utf-8");
        WorkflowExecutingDto workflowExecutingDto = workflowApplicationService.executeWorkflow(workflowExecuteRequestDto, workflowConfigDto).blockLast();
        return ReqResult.success(workflowExecutingDto.getData());
    }

    private PageDto parseFromReferer(ReferUtil.RefererParseVo refererParseVo) {
        if (refererParseVo == null || refererParseVo.getPageId() == null) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "页面Id错误");
        }
        if (refererParseVo == null || refererParseVo.getAgentId() == null) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "智能体Id错误");
        }
        if (refererParseVo == null || refererParseVo.getEnv() == null) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "环境错误");
        }
        PageDto pageDto = customPageRpcService.queryPageDto(refererParseVo.getPageId(), false);
        if (pageDto == null) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "页面Id错误");
        }
        if (pageDto.getDataSources() == null || pageDto.getDataSources().isEmpty()) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "页面数据源错误");
        }

        if (pageDto.getNeedLogin()) {
            if (!RequestContext.get().isLogin()) {
                throw new BizException(ErrorCodeEnum.UNAUTHORIZED.getCode(), "未登录或登录超时");
            }
        } else {
            UserDto userDto = userApplicationService.queryById(pageDto.getCreatorId());
            if (userDto == null) {
                throw new BizException(ErrorCodeEnum.UNAUTHORIZED.getCode(), "未登录或登录超时");
            }
            RequestContext.get().setUser(userDto);
            RequestContext.get().setUserId(userDto.getId());
        }

        AgentConfigDto agentConfigDto = agentApplicationService.queryPublishedConfigForExecute(refererParseVo.getAgentId());
        if (agentConfigDto != null && agentConfigDto.getAccessControl() != null && agentConfigDto.getAccessControl().equals(YesOrNoEnum.Y.getKey())) {
            // 用户数据权限
            UserDataPermissionDto userDataPermission = userDataPermissionRpcService.getUserDataPermission(RequestContext.get().getUserId());
            if (userDataPermission.getAgentIds() == null || !userDataPermission.getAgentIds().contains(refererParseVo.getAgentId())) {
                throw new BizException(ErrorCodeEnum.PERMISSION_DENIED.getCode(), "无应用访问权限");
            }
        }
        //dev环境或依赖的智能体没有发布时，验证用户是否有页面所在空间的权限
        if ("dev".equals(refererParseVo.getEnv()) || agentConfigDto == null) {
            try {
                spacePermissionService.checkSpaceUserPermission(pageDto.getSpaceId());
            } catch (Exception e) {
                throw new BizException(ErrorCodeEnum.PERMISSION_DENIED.getCode(), "用户无页面所在空间权限");
            }
        } else {
            // 验证用户是否有智能体的权限
            PublishedPermissionDto publishedPermissionDto = publishApplicationService.hasPermission(Published.TargetType.Agent, refererParseVo.getAgentId());
            if (!publishedPermissionDto.isExecute()) {
                throw new BizException(ErrorCodeEnum.PERMISSION_DENIED.getCode(), "用户无智能体权限");
            }
            AgentComponentConfigDto agentComponentConfigDto = agentConfigDto.getAgentComponentConfigList().stream().filter(agentComponentConfig -> agentComponentConfig.getType() == AgentComponentConfig.Type.Page).findFirst().orElse(null);
            if (agentComponentConfigDto == null) {
                agentComponentConfigDto = agentApplicationService.queryComponentConfigList(refererParseVo.getAgentId()).stream().filter(agentComponentConfig -> agentComponentConfig.getType() == AgentComponentConfig.Type.Page).findFirst().orElse(null);
                if (agentComponentConfigDto == null) {
                    throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "调用的智能体未绑定对应页面");
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


    @Operation(summary = "插件运行接口")
    @RequestMapping(path = "/api/page/plugin/{key}/execute", method = RequestMethod.POST)
    public ReqResult<Object> execute(@PathVariable String key, @RequestBody Map<String, Object> params, HttpServletRequest request) {
        String requestId = UUID.randomUUID().toString().replace("-", "");
        ReferUtil.RefererParseVo refererParseVo = ReferUtil.parseRefer(request);
        PageDto pageDto;
        pageDto = parseFromReferer(refererParseVo);
        Optional<DataSourceDto> first = pageDto.getDataSources().stream().filter(dataSourceDto -> dataSourceDto.getKey() != null && dataSourceDto.getKey().equals(key)).findFirst();
        if (!first.isPresent()) {
            throw new BizException("请求的数据源不存在");
        }
        PluginDto pluginDto = pluginApplicationService.queryPublishedPluginConfig(first.get().getId(), null);
        if (pluginDto == null) {
            throw new BizException("插件ID错误或未发布");
        }
        List<Long> publishedSpaceIds = pluginDto.getPublishedSpaceIds();
        if (CollectionUtils.isNotEmpty(publishedSpaceIds)) {
            boolean anyMatch = publishedSpaceIds.stream().anyMatch(spaceId -> spaceId != null && spaceId.equals(pageDto.getSpaceId()));
            if (!anyMatch) {
                throw new BizException("无插件执行权限");
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
        return ReqResult.success(pluginExecuteResultDto.getResult());
    }

    @Operation(summary = "获取插件、工作流接口文档schema")
    @RequestMapping(path = "/api/page/target/schema", method = RequestMethod.GET)
    public ReqResult<Object> schema(TargetTypeEnum type, Long id, Long projectId) {
        String data = iAgentRpcService.queryApiSchema(type, id, projectId).getData();
        return ReqResult.success(data != null ? JSON.parse(data) : null);
    }
}
