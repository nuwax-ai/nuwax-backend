package com.xspaceagi.agent.web.ui.controller.api;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.agent.core.adapter.application.PluginApplicationService;
import com.xspaceagi.agent.core.adapter.application.WorkflowApplicationService;
import com.xspaceagi.agent.core.adapter.dto.PluginExecuteRequestDto;
import com.xspaceagi.agent.core.adapter.dto.PluginExecuteResultDto;
import com.xspaceagi.agent.core.adapter.dto.WorkflowExecuteRequestDto;
import com.xspaceagi.agent.core.adapter.dto.WorkflowExecutingDto;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.PluginDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowConfigDto;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.system.application.dto.SpaceDto;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.sdk.service.dto.UserAccessKeyDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
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
import java.util.UUID;
import java.util.stream.Collectors;

@Tag(name = "开放API-插件执行接口")
@RestController
public class PluginAndWorkflowApiController {

    @Resource
    private WorkflowApplicationService workflowApplicationService;

    @Resource
    private PluginApplicationService pluginApplicationService;
    @Resource
    private SpaceApplicationService spaceApplicationService;

    @Operation(summary = "工作流流式执行接口")
    @RequestMapping(path = "/api/v1/workflow/{id}/sse/execute", method = RequestMethod.POST, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamExecuteWorkflow(@PathVariable Long id, @RequestBody @Valid Map<String, Object> params, HttpServletRequest request, HttpServletResponse response) {
        String requestId0 = request.getHeader("Trace-Id");
        if (requestId0 == null) {
            requestId0 = UUID.randomUUID().toString().replace("-", "");
        }
        final String requestId = requestId0;
        WorkflowConfigDto workflowConfigDto = workflowApplicationService.queryPublishedWorkflowConfig(id, null, true);
        if (workflowConfigDto == null) {
            return Flux.create(emitter -> sendError(emitter, requestId, "Error workflow id or not published"));
        }
        //验证页面所在空间是否有接口权限
        try {
            checkPermission(workflowConfigDto.getPublishedSpaceIds(), workflowConfigDto.getScope());
        } catch (Exception e) {
            return Flux.create(emitter -> sendError(emitter, requestId, e.getMessage()));
        }

        WorkflowExecuteRequestDto workflowExecuteRequestDto = new WorkflowExecuteRequestDto();
        workflowExecuteRequestDto.setWorkflowId(id);
        workflowExecuteRequestDto.setParams(params);
        workflowExecuteRequestDto.setRequestId(requestId);
        workflowExecuteRequestDto.setAgentId(-1L);
        response.setCharacterEncoding("utf-8");
        return workflowApplicationService.executeWorkflow(workflowExecuteRequestDto, workflowConfigDto)
                .map(workflowExecutingDto -> " " + JSON.toJSONString(workflowExecutingDto));
    }

    @Operation(summary = "工作流执行接口")
    @RequestMapping(path = "/api/v1/workflow/{id}/execute", method = RequestMethod.POST)
    public ReqResult<Object> executeWorkflow(@PathVariable Long id, @RequestBody @Valid Map<String, Object> params, HttpServletRequest request) {
        UserAccessKeyDto userAccessKey = (UserAccessKeyDto) RequestContext.get().getUserAccessKey();
        String requestId = request.getHeader("Trace-Id");
        if (requestId == null) {
            requestId = UUID.randomUUID().toString().replace("-", "");
        }
        WorkflowConfigDto workflowConfigDto = workflowApplicationService.queryPublishedWorkflowConfig(id, null, true);
        if (workflowConfigDto == null) {
            return ReqResult.error("Error workflow id or not published");
        }
        //验证页面所在空间是否有接口权限
        checkPermission(workflowConfigDto.getPublishedSpaceIds(), workflowConfigDto.getScope());

        WorkflowExecuteRequestDto workflowExecuteRequestDto = new WorkflowExecuteRequestDto();
        workflowExecuteRequestDto.setWorkflowId(id);
        workflowExecuteRequestDto.setParams(params);
        workflowExecuteRequestDto.setRequestId(requestId);
        workflowExecuteRequestDto.setAgentId(-1L);
        workflowExecuteRequestDto.setApiKey(userAccessKey.getAccessKey());
        WorkflowExecutingDto workflowExecutingDto = workflowApplicationService.executeWorkflow(workflowExecuteRequestDto, workflowConfigDto).blockLast();
        assert workflowExecutingDto != null;
        return ReqResult.success(workflowExecutingDto.getData());
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
    @RequestMapping(path = "/api/v1/plugin/{id}/execute", method = RequestMethod.POST)
    public ReqResult<Object> execute(@PathVariable Long id, @RequestBody Map<String, Object> params, HttpServletRequest request) {
        UserAccessKeyDto userAccessKey = (UserAccessKeyDto) RequestContext.get().getUserAccessKey();
        String requestId = request.getHeader("Trace-Id");
        if (requestId == null) {
            requestId = UUID.randomUUID().toString().replace("-", "");
        }
        PluginDto pluginDto = pluginApplicationService.queryPublishedPluginConfig(id, null);
        if (pluginDto == null) {
            throw new BizException("Error plugin id or not published");
        }
        checkPermission(pluginDto.getPublishedSpaceIds(), pluginDto.getScope());
        PluginExecuteRequestDto pluginExecuteRequestDto = new PluginExecuteRequestDto();
        pluginExecuteRequestDto.setParams(params);
        pluginExecuteRequestDto.setPluginId(id);
        pluginExecuteRequestDto.setRequestId(requestId);
        pluginExecuteRequestDto.setAgentId(-1L);
        pluginExecuteRequestDto.setTest(false);
        pluginExecuteRequestDto.setApiKey(userAccessKey.getAccessKey());
        PluginExecuteResultDto pluginExecuteResultDto = pluginApplicationService.execute(pluginExecuteRequestDto, pluginDto);
        return ReqResult.success(pluginExecuteResultDto.getResult());
    }


    @Operation(summary = "工具执行接口")
    @RequestMapping(path = "/api/v1/tool/{id}", method = RequestMethod.POST)
    public Object executeTool(@PathVariable String id, @RequestBody Map<String, Object> params, HttpServletRequest request) {
        try {
            if (id.startsWith("p")) {
                return execute(Long.parseLong(id.substring(1)), params, request).getData();
            }
            return executeWorkflow(Long.parseLong(id.substring(1)), params, request).getData();
        } catch (Exception e) {
            return Map.of("error", Map.of("message", e.getMessage(), "code", "0001"));
        }
    }

    private void checkPermission(List<Long> publishedSpaceIds, Published.PublishScope scope) {
        if (scope == Published.PublishScope.Tenant) {
            return;
        }
        List<SpaceDto> spaceDtos = spaceApplicationService.queryListByUserId(RequestContext.get().getUserId());
        List<Long> userSpaceIds = spaceDtos.stream().map(SpaceDto::getId).collect(Collectors.toList());
        if (CollectionUtils.isNotEmpty(publishedSpaceIds)) {
            boolean anyMatch = publishedSpaceIds.stream().anyMatch(spaceId -> spaceId != null && userSpaceIds.contains(spaceId));
            if (!anyMatch) {
                throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
            }
        }
    }
}
