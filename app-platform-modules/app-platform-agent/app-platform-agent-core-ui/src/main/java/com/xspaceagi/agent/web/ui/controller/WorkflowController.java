package com.xspaceagi.agent.web.ui.controller;

import com.alibaba.fastjson2.JSONObject;
import com.google.common.base.Joiner;
import com.xspaceagi.agent.core.adapter.application.*;
import com.xspaceagi.agent.core.adapter.dto.*;
import com.xspaceagi.agent.core.adapter.dto.config.Arg;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.*;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.adapter.repository.entity.WorkflowNodeConfig;
import com.xspaceagi.agent.core.infra.component.agent.AgentContext;
import com.xspaceagi.agent.core.infra.component.workflow.WorkflowContext;
import com.xspaceagi.agent.core.infra.component.workflow.WorkflowExecutor;
import com.xspaceagi.agent.core.infra.rpc.McpRpcService;
import com.xspaceagi.agent.web.ui.controller.util.SpaceObjectPermissionUtil;
import com.xspaceagi.agent.web.ui.dto.WorkflowPublishApplyDto;
import com.xspaceagi.compose.sdk.request.DorisTableDefineRequest;
import com.xspaceagi.compose.sdk.service.IComposeDbTableRpcService;
import com.xspaceagi.compose.sdk.vo.define.TableDefineVo;
import com.xspaceagi.knowledge.core.application.service.impl.KnowledgeConfigApplicationService;
import com.xspaceagi.knowledge.domain.model.KnowledgeConfigModel;
import com.xspaceagi.mcp.sdk.dto.McpComponentDto;
import com.xspaceagi.mcp.sdk.dto.McpDto;
import com.xspaceagi.mcp.sdk.enums.McpComponentTypeEnum;
import com.xspaceagi.system.application.dto.SpaceUserDto;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.application.util.DefaultIconUrlUtil;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.jackson.JsonSerializeUtil;
import com.xspaceagi.system.spec.utils.I18nUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.xspaceagi.system.spec.enums.ResourceEnum.*;

@Tag(name = "工作流相关接口")
@RestController
@RequestMapping("/api/workflow")
@Slf4j
public class WorkflowController {

    @Resource
    private WorkflowApplicationService workflowApplicationService;

    @Resource
    private SpacePermissionService spacePermissionService;

    @Resource
    private SpaceApplicationService spaceApplicationService;

    @Resource
    private PublishApplicationService publishApplicationService;

    @Resource
    private ConfigHistoryApplicationService configHistoryApplicationService;

    @Resource
    private PluginApplicationService pluginApplicationService;

    @Resource
    private ModelApplicationService modelApplicationService;

    @Resource
    private KnowledgeConfigApplicationService knowledgeConfigApplicationService;

    @Resource
    private IComposeDbTableRpcService iComposeDbTableRpcService;

    @Resource
    private McpRpcService mcpRpcService;

    @Resource
    private WorkflowExecutor workflowExecutor;

    @RequireResource(COMPONENT_LIB_CREATE)
    @Operation(summary = "新增工作流接口")
    @RequestMapping(path = "/add", method = RequestMethod.POST)
    public ReqResult<Long> add(@RequestBody @Valid WorkflowAddDto workflowAddDto) {
        spacePermissionService.checkSpaceUserPermission(workflowAddDto.getSpaceId());
        WorkflowConfigDto workflowConfigDto = new WorkflowConfigDto();
        BeanUtils.copyProperties(workflowAddDto, workflowConfigDto);
        workflowConfigDto.setCreatorId(RequestContext.get().getUserId());
        Long workflowId = workflowApplicationService.add(workflowConfigDto);
        return ReqResult.success(workflowId);
    }

    @RequireResource(COMPONENT_LIB_MODIFY)
    @Operation(summary = "工作流整体保存接口")
    @RequestMapping(path = "/save", method = RequestMethod.POST)
    public ReqResult<Long> save(@RequestBody @Valid WorkflowSaveDto workflowSaveDto) {
        JSONObject jsonObject = workflowSaveDto.getWorkflowConfig();
        if (jsonObject == null) {
            return ReqResult.error("Workflow configuration JSON is invalid");
        }
        Long workflowId = jsonObject.getLong("id");
        if (workflowId == null) {
            return ReqResult.error("Workflow ID cannot be empty");
        }
        WorkflowConfigDto workflowConfigDto = workflowApplicationService.queryById(workflowId);
        if (workflowConfigDto == null) {
            return ReqResult.error("Invalid workflow ID");
        }
        spacePermissionService.checkSpaceUserPermission(workflowConfigDto.getSpaceId());
        Boolean forceCommit = workflowSaveDto.getWorkflowConfig().getBoolean("forceCommit");
        if (forceCommit == null || !forceCommit) {
            Long version = workflowApplicationService.workflowEditVersion(workflowId, false);
            Long editVersion = workflowSaveDto.getWorkflowConfig().getLong("editVersion");
            if (editVersion == null || !editVersion.equals(version)) {
                return ReqResult.error("1011", I18nUtil.systemMessage("Backend.Workflow.ConcurrentModification"));
            }
        }

        workflowApplicationService.save(jsonObject, workflowConfigDto);
        return ReqResult.success(workflowApplicationService.workflowEditVersion(workflowId, true));
    }

    @Operation(summary = "创建副本接口")
    @RequestMapping(path = "/copy/{workflowId}", method = RequestMethod.POST)
    public ReqResult<Long> copy(@PathVariable Long workflowId) {
        WorkflowConfigDto workflowConfigDto = workflowApplicationService.queryById(workflowId);
        if (workflowConfigDto == null) {
            throw new IllegalArgumentException("Invalid workflowId");
        }
        // 检查权限
        spacePermissionService.checkSpaceUserPermission(workflowConfigDto.getSpaceId());
        Long id = workflowApplicationService.copyWorkflow(RequestContext.get().getUserId(), workflowId);
        return ReqResult.success(id);
    }

    @RequireResource(COMPONENT_LIB_MODIFY)
    @Operation(summary = "工作流还原到某一个版本接口")
    @RequestMapping(path = "/restore/{historyRecordId}", method = RequestMethod.POST)
    public ReqResult<Void> restore(@PathVariable Long historyRecordId) {
        ConfigHistoryDto configHistoryDto = configHistoryApplicationService.queryConfigHistory(historyRecordId);
        if (configHistoryDto == null) {
            throw new IllegalArgumentException("Invalid recordId parameter");
        }
        JSONObject config = JSONObject.parseObject(configHistoryDto.getConfig().toString());
        if (config.containsKey("workflowConfig")) {
            WorkflowConfigDto workflowConfigDto = config.getJSONObject("workflowConfig").toJavaObject(WorkflowConfigDto.class);
            spacePermissionService.checkSpaceUserPermission(workflowConfigDto.getSpaceId());
            workflowApplicationService.restoreWorkflow(configHistoryDto.getConfig().toString());
            return ReqResult.success();
        }
        // Compatible with old data
        WorkflowConfigDto workflowConfigDto = WorkflowConfigDto.convertToWorkflowConfigDto(configHistoryDto.getConfig().toString());
        spacePermissionService.checkSpaceUserPermission(workflowConfigDto.getSpaceId());
        workflowApplicationService.restoreWorkflow(workflowConfigDto);
        return ReqResult.success();
    }

    @RequireResource(COMPONENT_LIB_COPY_TO_SPACE)
    @Operation(summary = "复制工作流到空间")
    @RequestMapping(path = "/copy/{workflowId}/{targetSpaceId}", method = RequestMethod.POST)
    public ReqResult<Long> copyToSpace(@PathVariable Long workflowId, @PathVariable Long targetSpaceId) {
        WorkflowConfigDto workflowConfigDto = workflowApplicationService.queryById(workflowId);
        if (workflowConfigDto == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentWorkflowIdInvalid);
        }
        if (targetSpaceId.equals(workflowConfigDto.getSpaceId())) {
            // Copy to this space, only need to check regular user permission
            spacePermissionService.checkSpaceUserPermission(workflowConfigDto.getSpaceId());
        } else {
            // Copy to this space, only admin can copy
            spacePermissionService.checkSpaceAdminPermission(workflowConfigDto.getSpaceId());
            // Copy to other space, check target space permission
            spacePermissionService.checkSpaceUserPermission(targetSpaceId);
        }
        Long id = workflowApplicationService.copyWorkflow(RequestContext.get().getUserId(), workflowConfigDto, targetSpaceId);
        return ReqResult.success(id);
    }

    @RequireResource(COMPONENT_LIB_QUERY_DETAIL)
    @Operation(summary = "查询工作流信息")
    @RequestMapping(path = "/{workflowId}", method = RequestMethod.GET)
    public ReqResult<WorkflowConfigDto> getWorkflowConfig(@PathVariable Long workflowId) {
        WorkflowConfigDto workflowConfigDto = workflowApplicationService.queryById(workflowId);
        if (workflowConfigDto == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentWorkflowIdInvalid);
        }
        spacePermissionService.checkSpaceUserPermission(workflowConfigDto.getSpaceId());
        workflowConfigDto.setNodes(workflowApplicationService.organizeNodeHierarchicalRelationship(workflowConfigDto.getNodes()));
        SpaceUserDto spaceUserDto = spaceApplicationService.querySpaceUser(workflowConfigDto.getSpaceId(), RequestContext.get().getUserId());
        workflowConfigDto.setPermissions(SpaceObjectPermissionUtil.generatePermissionList(spaceUserDto, workflowConfigDto.getCreatorId()).stream().map(permission -> permission.name()).collect(Collectors.toList()));
        workflowConfigDto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(workflowConfigDto.getIcon(), workflowConfigDto.getName(), "workflow"));
        workflowConfigDto.setEditVersion(workflowApplicationService.workflowEditVersion(workflowId, false));
        return ReqResult.success(workflowConfigDto);
    }

    @RequireResource(COMPONENT_LIB_QUERY_DETAIL)
    @Operation(summary = "验证工作流配置完整性")
    @RequestMapping(path = "/valid/{workflowId}", method = RequestMethod.GET)
    public ReqResult<List<WorkflowNodeCheckDto>> validWorkflow(@PathVariable Long workflowId) {
        WorkflowConfigDto workflowConfigDto = workflowApplicationService.queryById(workflowId);
        if (workflowConfigDto == null) {
            throw new IllegalArgumentException("Invalid workflowId");
        }
        spacePermissionService.checkSpaceUserPermission(workflowConfigDto.getSpaceId());
        List<WorkflowNodeCheckDto> workflowNodeCheckDtos = workflowApplicationService.validWorkflow(workflowId);
        return ReqResult.success(workflowNodeCheckDtos);
    }

    @RequireResource(COMPONENT_LIB_QUERY_DETAIL)
    @Operation(summary = "试运行工作流（勿用）")
    @RequestMapping(path = "/test/execute", method = RequestMethod.GET, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<WorkflowExecutingDto> testExecuteWorkflowGET(WorkflowExecuteRequestDto workflowExecuteRequestDto, HttpServletRequest request, HttpServletResponse response) {
        // Get parameters from request and convert to Map<String, String>
        Map<String, Object> param = request.getParameterMap().entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue()[0]));
        workflowExecuteRequestDto.setParams(param);
        return testExecuteWorkflow(workflowExecuteRequestDto, response);
    }

    @RequireResource(COMPONENT_LIB_QUERY_DETAIL)
    @Operation(summary = "试运行工作流")
    @RequestMapping(path = "/test/execute", method = RequestMethod.POST, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<WorkflowExecutingDto> testExecuteWorkflow(@RequestBody @Valid WorkflowExecuteRequestDto workflowExecuteRequestDto, HttpServletResponse response) {
        WorkflowConfigDto workflowConfigDto = workflowApplicationService.queryById(workflowExecuteRequestDto.getWorkflowId());

        if (workflowConfigDto == null) {
            return Flux.create(emitter -> sendError(emitter, workflowExecuteRequestDto.getRequestId(), "Invalid workflowId"));
        }

        try {
            spacePermissionService.checkSpaceUserPermission(workflowConfigDto.getSpaceId());
        } catch (Exception e) {
            log.warn("Permission check failed", e);
            return Flux.create(emitter -> sendError(emitter, workflowExecuteRequestDto.getRequestId(), e.getMessage()));
        }

        workflowConfigDto.setNodes(workflowApplicationService.queryWorkflowNodeListForTestExecute(workflowExecuteRequestDto.getWorkflowId()));

        return executeWorkflow(workflowExecuteRequestDto, workflowConfigDto, response);
    }

    public Flux<WorkflowExecutingDto> executeWorkflow(WorkflowExecuteRequestDto workflowExecuteRequestDto, WorkflowConfigDto workflowConfigDto, HttpServletResponse response) {
        response.setCharacterEncoding("utf-8");
        return workflowApplicationService.executeWorkflow(workflowExecuteRequestDto, workflowConfigDto);
    }

    private void sendError(FluxSink<WorkflowExecutingDto> emitter, String requestId, String message) {
        WorkflowExecutingDto workflowExecutingDto = new WorkflowExecutingDto();
        workflowExecutingDto.setSuccess(false);
        workflowExecutingDto.setRequestId(requestId);
        workflowExecutingDto.setMessage(message);
        workflowExecutingDto.setComplete(true);
        emitter.next(workflowExecutingDto);
        emitter.complete();
    }

    @RequireResource(COMPONENT_LIB_QUERY_DETAIL)
    @Operation(summary = "试运行工作流节点")
    @RequestMapping(path = "/test/node/execute", method = RequestMethod.POST, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<WorkflowExecutingDto> testExecuteWorkflowNode(@RequestBody WorkflowNodeExecuteRequestDto workflowNodeExecuteRequestDto) {
        return Flux.create(emitter -> {
            WorkflowNodeDto workflowNodeDto = workflowApplicationService.queryWorkflowNode(workflowNodeExecuteRequestDto.getNodeId());
            if (workflowNodeDto == null) {
                sendError(emitter, workflowNodeExecuteRequestDto.getRequestId(), "Invalid nodeId");
                return;
            }
            try {
                checkNodePermission(workflowNodeDto.getId());
            } catch (Exception e) {
                log.warn("Permission check failed", e);
                sendError(emitter, workflowNodeExecuteRequestDto.getRequestId(), e.getMessage());
                return;
            }
            WorkflowConfigDto workflowConfigDto = workflowApplicationService.queryById(workflowNodeDto.getWorkflowId());
            workflowConfigDto.setNodes(workflowApplicationService.queryWorkflowNodeListForTestExecute(workflowNodeDto.getWorkflowId()));
            WorkflowContext workflowContext1 = new WorkflowContext();
            AgentContext agentContext = new AgentContext();
            UserDto userDto = (UserDto) RequestContext.get().getUser();
            agentContext.setUser((UserDto) RequestContext.get().getUser());
            agentContext.setUserId(RequestContext.get().getUserId());
            agentContext.setUid(((UserDto) RequestContext.get().getUser()).getUid());
            agentContext.setUserName(userDto.getNickName() != null ? userDto.getNickName() : userDto.getUserName());
            workflowContext1.setAgentContext(agentContext);
            workflowContext1.setRequestId(workflowNodeExecuteRequestDto.getRequestId());
            workflowContext1.setWorkflowConfig(workflowConfigDto);
            workflowContext1.setTestParams(workflowNodeExecuteRequestDto.getParams());
            WorkflowNodeDto nodeDto = workflowConfigDto.getNodes().stream().filter(node -> node.getId().equals(workflowNodeDto.getId())).findFirst().get();
            // Check data type
            WorkflowNodeCheckDto workflowNodeCheckDto = workflowApplicationService.validWorkflowNode(nodeDto);
            if (CollectionUtils.isNotEmpty(workflowNodeCheckDto.getMessages())) {
                WorkflowExecutingDto workflowExecutingDto = new WorkflowExecutingDto();
                workflowExecutingDto.setSuccess(false);
                workflowExecutingDto.setRequestId(workflowNodeExecuteRequestDto.getRequestId());
                workflowExecutingDto.setMessage(Joiner.on(",").join(workflowNodeCheckDto.getMessages()));
                workflowExecutingDto.setComplete(true);
                emitter.next(workflowExecutingDto);
                emitter.complete();
                return;
            }
            long startTimestamp = System.currentTimeMillis();
            workflowExecutor.testExecuteNode(workflowContext1, nodeDto).doOnError(e -> {
                log.warn("Workflow node execution failed {}", nodeDto.getName(), e);
                WorkflowExecutingDto workflowExecutingDto = new WorkflowExecutingDto();
                workflowExecutingDto.setSuccess(false);
                workflowExecutingDto.setRequestId(workflowNodeExecuteRequestDto.getRequestId());
                workflowExecutingDto.setMessage(e.getMessage());
                workflowExecutingDto.setComplete(true);
                emitter.next(workflowExecutingDto);
                emitter.complete();
            }).subscribe((result) -> {
                log.info("Workflow node executed successfully {}", nodeDto.getName());
                WorkflowExecutingDto workflowExecutingDto = new WorkflowExecutingDto();
                workflowExecutingDto.setData(result);
                workflowExecutingDto.setSuccess(true);
                workflowExecutingDto.setComplete(true);
                workflowExecutingDto.setRequestId(workflowNodeExecuteRequestDto.getRequestId());
                workflowExecutingDto.setCostTime(System.currentTimeMillis() - startTimestamp);
                emitter.next(workflowExecutingDto);
                emitter.complete();
            });
        });
    }

    @RequireResource(COMPONENT_LIB_QUERY_DETAIL)
    @Operation(summary = "查询工作流历史配置信息接口")
    @RequestMapping(path = "/config/history/list/{workflowId}", method = RequestMethod.GET)
    public ReqResult<List<ConfigHistoryDto>> historyList(@PathVariable Long workflowId) {
        checkWorkflowPermission(workflowId);
        List<ConfigHistoryDto> historyList = configHistoryApplicationService.queryConfigHistoryList(Published.TargetType.Workflow, workflowId);
        return ReqResult.success(historyList);
    }

    @RequireResource(COMPONENT_LIB_DELETE)
    @Operation(summary = "删除工作流接口")
    @RequestMapping(path = "/delete/{workflowId}", method = RequestMethod.POST)
    public ReqResult<Void> delete(@PathVariable Long workflowId) {
        checkWorkflowPermission(workflowId);
        workflowApplicationService.delete(workflowId);
        return ReqResult.success();
    }

    @RequireResource(COMPONENT_LIB_MODIFY)
    @Operation(summary = "更新工作流基础信息")
    @RequestMapping(path = "/update", method = RequestMethod.POST)
    public ReqResult<Void> updateWorkflow(@RequestBody WorkflowUpdateDto workflowUpdateDto) {
        checkWorkflowPermission(workflowUpdateDto.getId());
        WorkflowConfigDto workflowConfigDto = new WorkflowConfigDto();
        BeanUtils.copyProperties(workflowUpdateDto, workflowConfigDto);
        workflowApplicationService.update(workflowConfigDto);
        return ReqResult.success();
    }

    @RequireResource(COMPONENT_LIB_PUBLISH)
    @Operation(summary = "工作流发布")
    @RequestMapping(path = "/publish", method = RequestMethod.POST)
    public ReqResult<String> publishApply(@RequestBody WorkflowPublishApplyDto workflowPublishApplyDto) {
        checkWorkflowPermission(workflowPublishApplyDto.getWorkflowId());
        WorkflowConfigDto workflowConfigDto = workflowApplicationService.queryById(workflowPublishApplyDto.getWorkflowId());
        if (workflowConfigDto == null) {
            throw new IllegalArgumentException("Invalid workflowId");
        }
        // Validate
        List<WorkflowNodeCheckDto> workflowNodeCheckDtos = workflowApplicationService.validWorkflow(workflowPublishApplyDto.getWorkflowId());
        // If any node check result is not success, disallow publish
        List<WorkflowNodeCheckDto> collect = workflowNodeCheckDtos.stream().filter(workflowNodeCheckDto -> !workflowNodeCheckDto.isSuccess()).collect(Collectors.toList());
        if (!collect.isEmpty()) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentWorkflowHasInvalidNodesForPublish);
        }
        PublishApplyDto publishApplyDto = new PublishApplyDto();
        publishApplyDto.setApplyUser((UserDto) RequestContext.get().getUser());
        publishApplyDto.setTargetType(Published.TargetType.Workflow);
        publishApplyDto.setTargetId(workflowPublishApplyDto.getWorkflowId());
        // If publish scope is not selected, it means already published items should be unpublished
        publishApplyDto.setChannels(workflowPublishApplyDto.getScope() == null ? new ArrayList<>() : List.of(Published.PublishChannel.System));
        publishApplyDto.setScope(workflowPublishApplyDto.getScope());
        publishApplyDto.setName(workflowConfigDto.getName());
        publishApplyDto.setRemark(workflowPublishApplyDto.getRemark());
        publishApplyDto.setIcon(workflowConfigDto.getIcon());
        publishApplyDto.setDescription(workflowConfigDto.getDescription());
        publishApplyDto.setTargetConfig(workflowConfigDto);
        publishApplyDto.setSpaceId(workflowConfigDto.getSpaceId());
        Long applyId = publishApplicationService.publishApply(publishApplyDto);
        if (workflowPublishApplyDto.getScope() == Published.PublishScope.Space) {
            return ReqResult.create(ReqResult.SUCCESS, "Published successfully", "Published successfully");
        }
        TenantConfigDto tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
        if (tenantConfigDto.getWorkflowPublishAudit() == 0) {
            publishApplicationService.publish(applyId);
            return ReqResult.create(ReqResult.SUCCESS, "Published successfully", "Published successfully");
        }
        return ReqResult.create(ReqResult.SUCCESS, "Publish application submitted, awaiting review", "Publish application submitted, awaiting review");
    }

    @RequireResource(COMPONENT_LIB_QUERY_DETAIL)
    @Operation(summary = "查询工作流节点列表")
    @RequestMapping(path = "/node/list/{workflowId}", method = RequestMethod.GET)
    public ReqResult<List<WorkflowNodeDto>> getWorkflowNodeConfigList(@PathVariable Long workflowId) {
        checkWorkflowPermission(workflowId);
        List<WorkflowNodeDto> workflowNodeDtos = workflowApplicationService.queryWorkflowNodeList(workflowId);
        workflowNodeDtos.forEach(this::replaceNodeArgsSystemMessage);
        return ReqResult.success(workflowApplicationService.organizeNodeHierarchicalRelationship(workflowNodeDtos));
    }

    @RequireResource(COMPONENT_LIB_MODIFY)
    @Operation(summary = "新增工作流节点")
    @RequestMapping(path = "/node/add", method = RequestMethod.POST)
    public ReqResult<WorkflowNodeDto> addWorkflowNode(@RequestBody WorkflowNodeAddDto workflowNodeAddDto) {
        // Permission check
        WorkflowConfigDto workflowConfigDto = checkWorkflowPermission(workflowNodeAddDto.getWorkflowId());
        if (workflowNodeAddDto.getType() == WorkflowNodeConfig.NodeType.Workflow) {
            workflowApplicationService.checkSpaceWorkflowPermission(workflowConfigDto.getSpaceId(), workflowNodeAddDto.getTypeId());
            workflowLoopCheck(workflowNodeAddDto.getWorkflowId(), workflowNodeAddDto.getTypeId());
        }
        if (workflowNodeAddDto.getType() == WorkflowNodeConfig.NodeType.Plugin) {
            pluginApplicationService.checkSpacePluginPermission(workflowConfigDto.getSpaceId(), workflowNodeAddDto.getTypeId());
        }
        if (workflowNodeAddDto.getType() == WorkflowNodeConfig.NodeType.Mcp) {
            McpDto deployedMcp = mcpRpcService.queryMcp(workflowNodeAddDto.getTypeId(), workflowConfigDto.getSpaceId());
            mcpRpcService.checkMcpPermission(deployedMcp, workflowNodeAddDto.getNodeConfigDto().getToolName());
            workflowMcpLoopCheck(workflowNodeAddDto.getWorkflowId(), deployedMcp, workflowNodeAddDto.getNodeConfigDto().getToolName());
        }
        if (workflowNodeAddDto.getType() == WorkflowNodeConfig.NodeType.Knowledge && workflowNodeAddDto.getTypeId() != null) {
            KnowledgeConfigModel knowledgeConfigModel = knowledgeConfigApplicationService.queryOneInfoById(workflowNodeAddDto.getTypeId());
            if (knowledgeConfigModel == null) {
                throw new IllegalArgumentException("Invalid knowledge base ID");
            }
            spacePermissionService.checkSpaceUserPermission(knowledgeConfigModel.getSpaceId());
        }
        if (workflowNodeAddDto.getType() != null && workflowNodeAddDto.getType().name().startsWith("Table")) {
            checkTablePermission(workflowNodeAddDto.getTypeId());
        }
        Long nodeId = workflowApplicationService.addWorkflowNode(workflowNodeAddDto);
        WorkflowNodeDto workflowNodeDto = workflowApplicationService.queryWorkflowNode(nodeId);
        if (workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.Loop) {
            workflowNodeDto.setInnerNodes(List.of(workflowNodeDto.getStartNode(), workflowNodeDto.getEndNode()));
        }
        I18nUtil.replaceSystemMessage(workflowNodeDto);
        replaceNodeArgsSystemMessage(workflowNodeDto);
        return ReqResult.success(workflowNodeDto);
    }

    /**
     * Workflow cyclic dependency check
     *
     * @param workflowId
     * @param addWorkflowId
     */
    private void workflowLoopCheck(Long workflowId, Long addWorkflowId) {
        if (addWorkflowId == null || addWorkflowId.equals(workflowId)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentWorkflowCyclicDependency);
        }
        WorkflowConfigDto workflowConfigDto = workflowApplicationService.queryPublishedWorkflowConfig(addWorkflowId, null, true);
        workflowLoopCheck(workflowId, workflowConfigDto);
    }

    private void workflowLoopCheck(Long workflowId, WorkflowConfigDto workflowConfigDto) {
        List<WorkflowNodeDto> workflowNodeDtos = workflowConfigDto.getNodes().stream().filter(node -> node.getType() == WorkflowNodeConfig.NodeType.Workflow).collect(Collectors.toList());
        workflowNodeDtos.forEach(node -> {
            WorkflowAsNodeConfigDto workflowAsNodeConfigDto = (WorkflowAsNodeConfigDto) node.getNodeConfig();
            if (workflowAsNodeConfigDto.getWorkflowId().equals(workflowId)) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentWorkflowCyclicDependency);
            }
            workflowLoopCheck(workflowId, workflowAsNodeConfigDto.getWorkflowConfig());
        });
        List<WorkflowNodeDto> mcpNodeDtos = workflowConfigDto.getNodes().stream().filter(node -> node.getType() == WorkflowNodeConfig.NodeType.Mcp).collect(Collectors.toList());
        mcpNodeDtos.forEach(node -> {
            McpNodeConfigDto mcpNodeConfigDto = (McpNodeConfigDto) node.getNodeConfig();
            workflowMcpLoopCheck(workflowId, (McpDto) mcpNodeConfigDto.getMcp(), mcpNodeConfigDto.getToolName());
        });
    }

    private void workflowMcpLoopCheck(Long workflowId, McpDto mcpDto, String toolName) {
        if (mcpDto.getDeployedConfig() == null || mcpDto.getDeployedConfig().getComponents() == null) {
            return;
        }
        McpComponentDto mcpComponentDto = mcpDto.getDeployedConfig().getComponents().stream().filter(component -> component.getType() == McpComponentTypeEnum.Workflow && toolName.equals(component.getToolName())).findFirst().orElse(null);
        if (mcpComponentDto == null) {
            return;
        }
        WorkflowConfigDto workflowConfigDto = (WorkflowConfigDto) JsonSerializeUtil.parseObjectGeneric(mcpComponentDto.getTargetConfig());
        if (workflowId == null || workflowId.equals(workflowConfigDto.getId())) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentWorkflowCyclicDependency);
        }
        workflowLoopCheck(workflowId, workflowConfigDto);
    }

    private void checkTablePermission(Long typeId) {
        DorisTableDefineRequest dorisTableDefineRequest = new DorisTableDefineRequest();
        dorisTableDefineRequest.setTableId(typeId);
        TableDefineVo tableDefineVo = iComposeDbTableRpcService.queryTableDefinition(dorisTableDefineRequest);
        if (tableDefineVo == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentTableIdInvalid);
        }
        spacePermissionService.checkSpaceUserPermission(tableDefineVo.getSpaceId());
    }

    @RequireResource(COMPONENT_LIB_MODIFY)
    @Operation(summary = "更新工作流节点（通用）", description = "只包含输入输出的节点可以直接用，比如 开始节点、长期记忆节点、文档提取节点")
    @RequestMapping(path = "/node/update", method = RequestMethod.POST)
    public ReqResult<Void> updateWorkflowNode(@RequestBody WorkflowNodeUpdateDto<NodeConfigDto> workflowNodeUpdateDto) {
        // Permission check
        checkNodePermission(workflowNodeUpdateDto.getNodeId());
        workflowApplicationService.updateWorkflowNodeConfig(workflowNodeUpdateDto);
        return ReqResult.success();
    }

    @RequireResource(COMPONENT_LIB_MODIFY)
    @Operation(summary = "更新结束节点")
    @RequestMapping(path = "/node/end/update", method = RequestMethod.POST)
    public ReqResult<Void> updateWorkflowEndNode(@RequestBody WorkflowNodeUpdateDto<EndNodeConfigDto> workflowNodeUpdateDto) {
        // Permission check
        checkNodePermission(workflowNodeUpdateDto.getNodeId());
        workflowApplicationService.updateWorkflowNodeConfig(workflowNodeUpdateDto);
        return ReqResult.success();
    }

    @RequireResource(COMPONENT_LIB_MODIFY)
    @Operation(summary = "更新大模型节点")
    @RequestMapping(path = "/node/llm/update", method = RequestMethod.POST)
    public ReqResult<Void> updateWorkflowLLMNode(@RequestBody WorkflowNodeUpdateDto<LLMNodeConfigDto> workflowNodeUpdateDto) {
        // Permission check
        WorkflowNodeDto workflowNodeDto = checkNodePermission(workflowNodeUpdateDto.getNodeId());
        WorkflowConfigDto workflowConfigDto = workflowApplicationService.queryByIdWithoutNodes(workflowNodeDto.getWorkflowId());
        if (workflowNodeUpdateDto.getNodeConfig() != null && workflowNodeUpdateDto.getNodeConfig().getSkillComponentConfigs() != null) {
            workflowNodeUpdateDto.getNodeConfig().getSkillComponentConfigs().forEach(skillComponentConfigDto -> {
                if (skillComponentConfigDto.getType() == LLMNodeConfigDto.SkillComponentConfigDto.Type.Plugin) {
                    pluginApplicationService.checkSpacePluginPermission(workflowConfigDto.getSpaceId(), skillComponentConfigDto.getTypeId());
                }
                if (skillComponentConfigDto.getType() == LLMNodeConfigDto.SkillComponentConfigDto.Type.Workflow) {
                    workflowApplicationService.checkSpaceWorkflowPermission(workflowConfigDto.getSpaceId(), skillComponentConfigDto.getTypeId());
                }
                if (skillComponentConfigDto.getType() == LLMNodeConfigDto.SkillComponentConfigDto.Type.Knowledge) {
                    KnowledgeConfigModel knowledgeConfigModel = knowledgeConfigApplicationService.queryOneInfoById(skillComponentConfigDto.getTypeId());
                    if (knowledgeConfigModel == null) {
                        throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentKnowledgeBaseRemovedOrMissing,
                                skillComponentConfigDto.getName());
                    }
                    spacePermissionService.checkSpaceUserPermission(knowledgeConfigModel.getSpaceId());
                }
            });
        }
        modelApplicationService.checkUserModelPermission(RequestContext.get().getUserId(), workflowNodeUpdateDto.getNodeConfig().getModelId());
        workflowApplicationService.updateWorkflowNodeConfig(workflowNodeUpdateDto);
        return ReqResult.success();
    }

    @RequireResource(COMPONENT_LIB_MODIFY)
    @Operation(summary = "更新插件节点")
    @RequestMapping(path = "/node/plugin/update", method = RequestMethod.POST)
    public ReqResult<Void> updateWorkflowPluginNode(@RequestBody WorkflowNodeUpdateDto<PluginNodeConfigDto> workflowNodeUpdateDto) {
        // Permission check
        WorkflowNodeDto workflowNodeDto = checkNodePermission(workflowNodeUpdateDto.getNodeId());
        WorkflowConfigDto workflowConfigDto = workflowApplicationService.queryByIdWithoutNodes(workflowNodeDto.getWorkflowId());
        pluginApplicationService.checkSpacePluginPermission(workflowConfigDto.getSpaceId(), workflowNodeUpdateDto.getNodeConfig().getPluginId());
        workflowApplicationService.updateWorkflowNodeConfig(workflowNodeUpdateDto);
        return ReqResult.success();
    }

    @RequireResource(COMPONENT_LIB_MODIFY)
    @Operation(summary = "更新MCP节点")
    @RequestMapping(path = "/node/mcp/update", method = RequestMethod.POST)
    public ReqResult<Void> updateWorkflowMcpNode(@RequestBody WorkflowNodeUpdateDto<McpNodeConfigDto> workflowNodeUpdateDto) {
        // Permission check
        WorkflowNodeDto workflowNodeDto = checkNodePermission(workflowNodeUpdateDto.getNodeId());
        WorkflowConfigDto workflowConfigDto = workflowApplicationService.queryByIdWithoutNodes(workflowNodeDto.getWorkflowId());
        McpDto deployedMcp = mcpRpcService.queryMcp(workflowNodeUpdateDto.getNodeConfig().getMcpId(), workflowConfigDto.getSpaceId());
        mcpRpcService.checkMcpPermission(deployedMcp, workflowNodeUpdateDto.getNodeConfig().getToolName());
        workflowApplicationService.updateWorkflowNodeConfig(workflowNodeUpdateDto);
        return ReqResult.success();
    }

    @RequireResource(COMPONENT_LIB_MODIFY)
    @Operation(summary = "更新\"工作流\"节点")
    @RequestMapping(path = "/node/workflow/update", method = RequestMethod.POST)
    public ReqResult<Void> updateWorkflowWorkflowNode(@RequestBody WorkflowNodeUpdateDto<WorkflowAsNodeConfigDto> workflowNodeUpdateDto) {
        // Permission check
        WorkflowNodeDto workflowNodeDto = checkNodePermission(workflowNodeUpdateDto.getNodeId());
        WorkflowConfigDto workflowConfigDto = workflowApplicationService.queryByIdWithoutNodes(workflowNodeDto.getWorkflowId());
        workflowApplicationService.checkSpaceWorkflowPermission(workflowConfigDto.getSpaceId(), workflowNodeUpdateDto.getNodeConfig().getWorkflowId());
        workflowApplicationService.updateWorkflowNodeConfig(workflowNodeUpdateDto);
        return ReqResult.success();
    }

    @RequireResource(COMPONENT_LIB_MODIFY)
    @Operation(summary = "更新过程输出节点")
    @RequestMapping(path = "/node/output/update", method = RequestMethod.POST)
    public ReqResult<Void> updateWorkflowOutputNode(@RequestBody WorkflowNodeUpdateDto<ProcessOutputNodeConfigDto> workflowNodeUpdateDto) {
        // Permission check
        checkNodePermission(workflowNodeUpdateDto.getNodeId());
        workflowApplicationService.updateWorkflowNodeConfig(workflowNodeUpdateDto);
        return ReqResult.success();
    }

    @RequireResource(COMPONENT_LIB_MODIFY)
    @Operation(summary = "更新代码节点")
    @RequestMapping(path = "/node/code/update", method = RequestMethod.POST)
    public ReqResult<Void> updateWorkflowCodeNode(@RequestBody WorkflowNodeUpdateDto<CodeNodeConfigDto> workflowNodeUpdateDto) {
        // Permission check
        checkNodePermission(workflowNodeUpdateDto.getNodeId());
        workflowApplicationService.updateWorkflowNodeConfig(workflowNodeUpdateDto);
        return ReqResult.success();
    }

    @RequireResource(COMPONENT_LIB_MODIFY)
    @Operation(summary = "更新条件分支节点")
    @RequestMapping(path = "/node/condition/update", method = RequestMethod.POST)
    public ReqResult<Void> updateWorkflowConditionNode(@RequestBody WorkflowNodeUpdateDto<ConditionNodeConfigDto> workflowNodeUpdateDto) {
        // Permission check
        checkNodePermission(workflowNodeUpdateDto.getNodeId());
        workflowApplicationService.updateWorkflowNodeConfig(workflowNodeUpdateDto);
        return ReqResult.success();
    }

    @RequireResource(COMPONENT_LIB_MODIFY)
    @Operation(summary = "更新意图识别节点")
    @RequestMapping(path = "/node/intent/update", method = RequestMethod.POST)
    public ReqResult<Void> updateWorkflowIntentNode(@RequestBody WorkflowNodeUpdateDto<IntentRecognitionNodeConfigDto> workflowNodeUpdateDto) {
        // Permission check
        checkNodePermission(workflowNodeUpdateDto.getNodeId());
        if (workflowNodeUpdateDto.getNodeConfig() != null && workflowNodeUpdateDto.getNodeConfig().getIntentConfigs() != null) {
            workflowNodeUpdateDto.getNodeConfig().getIntentConfigs().forEach(intentConfigDto -> {
                if (StringUtils.isBlank(intentConfigDto.getUuid())) {
                    intentConfigDto.setUuid(UUID.randomUUID().toString().replace("-", ""));
                }
            });
        }
        workflowApplicationService.updateWorkflowNodeConfig(workflowNodeUpdateDto);
        return ReqResult.success();
    }

    @RequireResource(COMPONENT_LIB_MODIFY)
    @Operation(summary = "更新循环节点")
    @RequestMapping(path = "/node/loop/update", method = RequestMethod.POST)
    public ReqResult<Void> updateWorkflowLoopNode(@RequestBody WorkflowNodeUpdateDto<LoopNodeConfigDto> workflowNodeUpdateDto) {
        // Permission check
        checkNodePermission(workflowNodeUpdateDto.getNodeId());
        workflowNodeUpdateDto.setInnerStartNodeId(null);
        workflowNodeUpdateDto.setInnerEndNodeId(null);
        workflowApplicationService.updateWorkflowNodeConfig(workflowNodeUpdateDto);
        if (CollectionUtils.isNotEmpty(workflowNodeUpdateDto.getInnerNodes())) {
            workflowApplicationService.updateLoopInnerNodes(workflowNodeUpdateDto.getNodeId(), workflowNodeUpdateDto.getInnerNodes());
        }
        return ReqResult.success();
    }

    @RequireResource(COMPONENT_LIB_MODIFY)
    @Operation(summary = "更新知识库节点")
    @RequestMapping(path = "/node/knowledge/update", method = RequestMethod.POST)
    public ReqResult<Void> updateWorkflowKnowledgeNode(@RequestBody WorkflowNodeUpdateDto<KnowledgeNodeConfigDto> workflowNodeUpdateDto) {
        // Permission check
        checkNodePermission(workflowNodeUpdateDto.getNodeId());
        List<KnowledgeNodeConfigDto.KnowledgeBaseConfigDto> knowledgeBaseConfigs = workflowNodeUpdateDto.getNodeConfig().getKnowledgeBaseConfigs();
        if (knowledgeBaseConfigs != null) {
            knowledgeBaseConfigs.forEach(knowledgeBaseConfigDto -> {
                KnowledgeConfigModel knowledgeConfigModel = knowledgeConfigApplicationService.queryOneInfoById(knowledgeBaseConfigDto.getKnowledgeBaseId());
                if (knowledgeConfigModel == null) {
                    throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentKnowledgeBaseRemovedOrMissing,
                            knowledgeBaseConfigDto.getName());
                }
                spacePermissionService.checkSpaceUserPermission(knowledgeConfigModel.getSpaceId(), RequestContext.get().getUserId());
            });
        }
        workflowApplicationService.updateWorkflowNodeConfig(workflowNodeUpdateDto);
        return ReqResult.success();
    }

    @RequireResource(COMPONENT_LIB_MODIFY)
    @Operation(summary = "更新变量节点")
    @RequestMapping(path = "/node/variable/update", method = RequestMethod.POST)
    public ReqResult<Void> updateWorkflowVariableNode(@RequestBody WorkflowNodeUpdateDto<VariableNodeConfigDto> workflowNodeUpdateDto) {
        // Permission check
        checkNodePermission(workflowNodeUpdateDto.getNodeId());
        workflowApplicationService.updateWorkflowNodeConfig(workflowNodeUpdateDto);
        return ReqResult.success();
    }

    @RequireResource(COMPONENT_LIB_MODIFY)
    @Operation(summary = "更新变量聚合节点")
    @RequestMapping(path = "/node/variableAggregation/update", method = RequestMethod.POST)
    public ReqResult<Void> updateWorkflowVariableAggregation(@RequestBody WorkflowNodeUpdateDto<VariableAggregationNodeConfigDto> workflowNodeUpdateDto) {
        // Permission check
        checkNodePermission(workflowNodeUpdateDto.getNodeId());
        workflowApplicationService.updateWorkflowNodeConfig(workflowNodeUpdateDto);
        return ReqResult.success();
    }

    @RequireResource(COMPONENT_LIB_MODIFY)
    @Operation(summary = "更新问答节点")
    @RequestMapping(path = "/node/qa/update", method = RequestMethod.POST)
    public ReqResult<Void> updateWorkflowQaNode(@RequestBody WorkflowNodeUpdateDto<QaNodeConfigDto> workflowNodeUpdateDto) {
        // Permission check
        WorkflowNodeDto workflowNodeDto = checkNodePermission(workflowNodeUpdateDto.getNodeId());
        workflowNodeUpdateDto.setLastWorkflowNodeDto(workflowNodeDto);
        workflowApplicationService.updateWorkflowNodeConfig(workflowNodeUpdateDto);
        return ReqResult.success();
    }

    @RequireResource(COMPONENT_LIB_MODIFY)
    @Operation(summary = "更新文本处理节点")
    @RequestMapping(path = "/node/text/update", method = RequestMethod.POST)
    public ReqResult<Void> updateWorkflowTextNode(@RequestBody WorkflowNodeUpdateDto<TextProcessingNodeConfigDto> workflowNodeUpdateDto) {
        // Permission check
        checkNodePermission(workflowNodeUpdateDto.getNodeId());
        workflowApplicationService.updateWorkflowNodeConfig(workflowNodeUpdateDto);
        return ReqResult.success();
    }

    @RequireResource(COMPONENT_LIB_MODIFY)
    @Operation(summary = "更新HTTP节点")
    @RequestMapping(path = "/node/http/update", method = RequestMethod.POST)
    public ReqResult<Void> updateWorkflowHttpNode(@RequestBody WorkflowNodeUpdateDto<HttpNodeConfigDto> workflowNodeUpdateDto) {
        // Permission check
        checkNodePermission(workflowNodeUpdateDto.getNodeId());
        workflowApplicationService.updateWorkflowNodeConfig(workflowNodeUpdateDto);
        return ReqResult.success();
    }

    @RequireResource(COMPONENT_LIB_MODIFY)
    @Operation(summary = "更新<数据表数据查询>节点")
    @RequestMapping(path = "/node/tableDataQuery/update", method = RequestMethod.POST)
    public ReqResult<Void> updateWorkflowTableDataQueryNode(@RequestBody WorkflowNodeUpdateDto<TableDataQueryNodeConfigDto> workflowNodeUpdateDto) {
        // Permission check
        checkNodePermission(workflowNodeUpdateDto.getNodeId());
        workflowApplicationService.updateWorkflowNodeConfig(workflowNodeUpdateDto);
        return ReqResult.success();
    }

    @RequireResource(COMPONENT_LIB_MODIFY)
    @Operation(summary = "更新<数据表数据新增>节点")
    @RequestMapping(path = "/node/tableDataAdd/update", method = RequestMethod.POST)
    public ReqResult<Void> updateWorkflowTableDataAddNode(@RequestBody WorkflowNodeUpdateDto<TableNodeConfigDto> workflowNodeUpdateDto) {
        // Permission check
        checkNodePermission(workflowNodeUpdateDto.getNodeId());
        workflowApplicationService.updateWorkflowNodeConfig(workflowNodeUpdateDto);
        return ReqResult.success();
    }

    @RequireResource(COMPONENT_LIB_MODIFY)
    @Operation(summary = "更新<数据表数据删除>节点")
    @RequestMapping(path = "/node/tableDataDelete/update", method = RequestMethod.POST)
    public ReqResult<Void> updateWorkflowTableDataDeleteNode(@RequestBody WorkflowNodeUpdateDto<TableDataDeleteNodeConfigDto> workflowNodeUpdateDto) {
        // Permission check
        checkNodePermission(workflowNodeUpdateDto.getNodeId());
        workflowApplicationService.updateWorkflowNodeConfig(workflowNodeUpdateDto);
        return ReqResult.success();
    }

    @RequireResource(COMPONENT_LIB_MODIFY)
    @Operation(summary = "更新<数据表数据更新>节点")
    @RequestMapping(path = "/node/tableDataUpdate/update", method = RequestMethod.POST)
    public ReqResult<Void> updateWorkflowTableDataUpdateNode(@RequestBody WorkflowNodeUpdateDto<TableDataUpdateNodeConfigDto> workflowNodeUpdateDto) {
        // Permission check
        checkNodePermission(workflowNodeUpdateDto.getNodeId());
        workflowApplicationService.updateWorkflowNodeConfig(workflowNodeUpdateDto);
        return ReqResult.success();
    }

    @RequireResource(COMPONENT_LIB_MODIFY)
    @Operation(summary = "更新<数据表SQL自定义>节点")
    @RequestMapping(path = "/node/tableCustomSql/update", method = RequestMethod.POST)
    public ReqResult<Void> updateWorkflowTableCustomSqlNode(@RequestBody WorkflowNodeUpdateDto<TableCustomSqlNodeConfigDto> workflowNodeUpdateDto) {
        // Permission check
        checkNodePermission(workflowNodeUpdateDto.getNodeId());
        workflowApplicationService.updateWorkflowNodeConfig(workflowNodeUpdateDto);
        return ReqResult.success();
    }

    @RequireResource(COMPONENT_LIB_MODIFY)
    @Operation(summary = "删除工作流节点")
    @RequestMapping(path = "/node/delete/{id}", method = RequestMethod.POST)
    public ReqResult<Void> deleteWorkflowNode(@PathVariable Long id) {
        // Permission check
        checkNodePermission(id);
        workflowApplicationService.deleteWorkflowNode(id);
        return ReqResult.success();
    }

    @RequireResource(COMPONENT_LIB_MODIFY)
    @Operation(summary = "更新节点连线（下级节点）")
    @RequestMapping(path = "/node/{nodeId}/nextIds/update", method = RequestMethod.POST)
    public ReqResult<WorkflowNodeDto> updateNextIds(@PathVariable Long nodeId, @RequestBody List<Long> nextIds) {
        // Permission check
        WorkflowNodeDto workflowNodeDto = checkNodePermission(nodeId);
        if (nextIds == null) {
            return ReqResult.error("nextIds cannot be empty");
        }
        if (workflowNodeDto.getLoopNodeId() != null) {
            nextIds.remove(workflowNodeDto.getLoopNodeId());
        }
        List<Long> newNextIds = new ArrayList<>();
        nextIds.forEach(nextId -> {
            try {
                checkNodePermission(nextId);
                newNextIds.add(nextId);
            } catch (Exception e) {
                if (e instanceof BizException) {
                    if (ErrorCodeEnum.INVALID_PARAM.getCode().equals(((BizException) e).getCode())) {
                        return;
                    }
                }
                throw e;
            }
        });
        if (workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.QA) {
            QaNodeConfigDto qaNodeConfigDto = (QaNodeConfigDto) workflowNodeDto.getNodeConfig();
            if (qaNodeConfigDto.getAnswerType() == QaNodeConfigDto.AnswerTypeEnum.SELECT) {
                return ReqResult.success(workflowApplicationService.queryWorkflowNode(nodeId));
            }
        }
        if (workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.Condition || workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.IntentRecognition) {
            return ReqResult.success(workflowApplicationService.queryWorkflowNode(nodeId));
        }
        workflowApplicationService.updateNextIds(nodeId, newNextIds);
        WorkflowNodeDto workflowNodeDto1 = workflowApplicationService.queryWorkflowNode(nodeId);
        replaceNodeArgsSystemMessage(workflowNodeDto1);
        return ReqResult.success(workflowNodeDto1);
    }

    @RequireResource(COMPONENT_LIB_MODIFY)
    @Operation(summary = "复制工作流节点")
    @RequestMapping(path = "/node/copy/{id}", method = RequestMethod.POST)
    public ReqResult<WorkflowNodeDto> copyWorkflowNode(@PathVariable Long id) {
        // Permission check
        checkNodePermission(id);
        Long nodeId = workflowApplicationService.copyWorkflowNode(id);
        WorkflowNodeDto workflowNodeDto = workflowApplicationService.queryWorkflowNode(nodeId);
        if (workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.Loop) {
            List<WorkflowNodeDto> workflowNodeDtos = workflowApplicationService.queryWorkflowNodeList(workflowNodeDto.getWorkflowId());
            workflowNodeDto.setInnerNodes(workflowNodeDtos.stream().filter(node -> node.getLoopNodeId() != null && node.getLoopNodeId().equals(workflowNodeDto.getId())).collect(Collectors.toList()));
        }
        replaceNodeArgsSystemMessage(workflowNodeDto);
        return ReqResult.success(workflowNodeDto);
    }

    @RequireResource(COMPONENT_LIB_QUERY_DETAIL)
    @Operation(summary = "查询工作流节点详情")
    @RequestMapping(path = "/node/{id}", method = RequestMethod.GET)
    public ReqResult<WorkflowNodeDto> queryWorkflowNode(@PathVariable Long id) {
        // Permission check
        checkNodePermission(id);
        WorkflowNodeDto workflowNodeDto = workflowApplicationService.queryWorkflowNode(id);
        replaceNodeArgsSystemMessage(workflowNodeDto);
        return ReqResult.success(workflowNodeDto);
    }

    private void replaceNodeArgsSystemMessage(WorkflowNodeDto workflowNodeDto) {
        try {
            List<Arg> inputArgs = workflowNodeDto.getNodeConfig().getInputArgs();
            I18nUtil.replaceSystemMessage("WorkflowNodeInput", inputArgs);
            List<Arg> outputArgs = workflowNodeDto.getNodeConfig().getOutputArgs();
            I18nUtil.replaceSystemMessage("WorkflowNodeOutput", outputArgs);
        } catch (Exception ignored) {
        }
    }

    @RequireResource(COMPONENT_LIB_QUERY_DETAIL)
    @Operation(summary = "查询指定节点的前置节点信息")
    @RequestMapping(path = "/node/previous/{id}", method = RequestMethod.GET)
    public ReqResult<PreviousDto> queryPreviousNodes(@PathVariable Long id) {
        // Permission check
        try {
            checkNodePermission(id);
        } catch (Exception e) {
            if (e instanceof BizException) {
                if (ErrorCodeEnum.INVALID_PARAM.getCode().equals(((BizException) e).getCode())) {
                    return ReqResult.success(PreviousDto.builder().previousNodes(List.of()).innerPreviousNodes(List.of())
                            .argMap(Map.of()).build());
                }
            }
            throw e;
        }
        return ReqResult.success(workflowApplicationService.queryPreviousNodes(id));
    }

    private WorkflowNodeDto checkNodePermission(Long id) {
        WorkflowNodeDto workflowNodeDto = workflowApplicationService.queryWorkflowNode(id);
        if (workflowNodeDto == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentWorkflowNodeIdInvalid);
        }

        checkWorkflowPermission(workflowNodeDto.getWorkflowId());
        return workflowNodeDto;
    }


    private WorkflowConfigDto checkWorkflowPermission(Long workflowId) {
        WorkflowConfigDto workflowConfigDto = workflowApplicationService.queryByIdWithoutNodes(workflowId);
        if (workflowConfigDto == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentWorkflowNotFound);
        }
        spacePermissionService.checkSpaceUserPermission(workflowConfigDto.getSpaceId());
        return workflowConfigDto;
    }
}
