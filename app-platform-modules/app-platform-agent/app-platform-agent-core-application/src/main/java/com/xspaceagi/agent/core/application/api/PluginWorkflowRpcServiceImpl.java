package com.xspaceagi.agent.core.application.api;

import com.xspaceagi.agent.core.adapter.application.PluginApplicationService;
import com.xspaceagi.agent.core.adapter.application.WorkflowApplicationService;
import com.xspaceagi.agent.core.adapter.dto.PluginExecuteRequestDto;
import com.xspaceagi.agent.core.adapter.dto.PluginExecuteResultDto;
import com.xspaceagi.agent.core.adapter.dto.WorkflowExecuteRequestDto;
import com.xspaceagi.agent.core.adapter.dto.WorkflowExecutingDto;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.PluginConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.PluginDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowConfigDto;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.sdk.IPluginWorkflowRpcService;
import com.xspaceagi.system.application.dto.SpaceDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.application.service.TenantConfigApplicationService;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.exception.BizException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 插件/工作流绑定执行 SPI 实现。
 *
 * <p>连接器工具绑定插件/工作流时，由本实现完成：已发布校验 → 空间权限校验 →
 * 复用 agent 既有 {@link PluginApplicationService}/{@link WorkflowApplicationService} 执行。
 * 执行前补齐 RequestContext 的 user/tenantConfig（参照 IM 机器人调用 agent 的上下文构造模式），
 * 不覆盖调用方已填充的字段。</p>
 */
@Slf4j
@Service
public class PluginWorkflowRpcServiceImpl implements IPluginWorkflowRpcService {

    private static final String BIND_TYPE_PLUGIN = "PLUGIN";

    @Resource
    private PluginApplicationService pluginApplicationService;
    @Resource
    private WorkflowApplicationService workflowApplicationService;
    @Resource
    private SpaceApplicationService spaceApplicationService;
    @Resource
    private UserApplicationService userApplicationService;
    @Resource
    private TenantConfigApplicationService tenantConfigApplicationService;

    @Override
    public Object execute(String bindType, Long refId, Map<String, Object> params, Long userId, Long tenantId) {
        ensureContext(userId, tenantId);
        if (BIND_TYPE_PLUGIN.equalsIgnoreCase(bindType)) {
            return executePlugin(refId, params, userId);
        }
        return executeWorkflow(refId, params, userId);
    }

    @Override
    public List<Map<String, Object>> listBindable(String bindType, Long spaceId, Long userId) {
        Long targetSpace = spaceId;
        if (targetSpace == null) {
            List<SpaceDto> spaces = spaceApplicationService.queryListByUserId(userId);
            if (CollectionUtils.isNotEmpty(spaces)) {
                targetSpace = spaces.get(0).getId();
            }
        }
        if (targetSpace == null) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> items = new ArrayList<>();
        if (BIND_TYPE_PLUGIN.equalsIgnoreCase(bindType)) {
            for (PluginDto dto : pluginApplicationService.queryListBySpaceId(targetSpace)) {
                if (dto.getPublishStatus() == Published.PublishStatus.Published) {
                    items.add(pluginItem(dto));
                }
            }
        } else {
            for (WorkflowConfigDto dto : workflowApplicationService.queryListBySpaceId(targetSpace)) {
                if (dto.getPublishStatus() == Published.PublishStatus.Published) {
                    items.add(workflowItem(dto));
                }
            }
        }
        return items;
    }

    private Object executePlugin(Long refId, Map<String, Object> params, Long userId) {
        PluginDto pluginDto = pluginApplicationService.queryPublishedPluginConfig(refId, null);
        if (pluginDto == null) {
            throw new BizException("插件不存在或未发布: " + refId);
        }
        checkPermission(pluginDto.getPublishedSpaceIds(), pluginDto.getScope(), userId);
        PluginExecuteRequestDto req = new PluginExecuteRequestDto();
        req.setPluginId(refId);
        req.setParams(params);
        req.setAgentId(-1L);
        req.setRequestId(UUID.randomUUID().toString().replace("-", ""));
        req.setTest(false);
        PluginExecuteResultDto result = pluginApplicationService.execute(req, pluginDto);
        if (result == null || !result.isSuccess()) {
            throw new BizException(result == null ? "插件执行失败" : result.getError());
        }
        return result.getResult();
    }

    private Object executeWorkflow(Long refId, Map<String, Object> params, Long userId) {
        WorkflowConfigDto workflowConfigDto = workflowApplicationService.queryPublishedWorkflowConfig(refId, null, true);
        if (workflowConfigDto == null) {
            throw new BizException("工作流不存在或未发布: " + refId);
        }
        checkPermission(workflowConfigDto.getPublishedSpaceIds(), workflowConfigDto.getScope(), userId);
        WorkflowExecuteRequestDto req = new WorkflowExecuteRequestDto();
        req.setWorkflowId(refId);
        req.setParams(params);
        req.setAgentId(-1L);
        req.setRequestId(UUID.randomUUID().toString().replace("-", ""));
        req.setTest(false);
        WorkflowExecutingDto result = workflowApplicationService.executeWorkflow(req, workflowConfigDto).blockLast();
        if (result == null || !result.isSuccess()) {
            throw new BizException(result == null ? "工作流执行失败" : result.getMessage());
        }
        return result.getData();
    }

    private Map<String, Object> pluginItem(PluginDto dto) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", dto.getId());
        item.put("name", dto.getName());
        item.put("description", dto.getDescription());
        item.put("icon", dto.getIcon());
        Object config = dto.getConfig();
        if (config instanceof PluginConfigDto pc) {
            item.put("inputArgs", pc.getInputArgs());
            item.put("outputArgs", pc.getOutputArgs());
        }
        return item;
    }

    private Map<String, Object> workflowItem(WorkflowConfigDto dto) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", dto.getId());
        item.put("name", dto.getName());
        item.put("description", dto.getDescription());
        item.put("icon", dto.getIcon());
        item.put("inputArgs", dto.getInputArgs());
        item.put("outputArgs", dto.getOutputArgs());
        return item;
    }

    /** 发布空间权限校验：Tenant/Global 放行；Space 需当前用户空间与发布空间有交集（参考开放 API checkPermission）。 */
    private void checkPermission(List<Long> publishedSpaceIds, Published.PublishScope scope, Long userId) {
        if (scope == Published.PublishScope.Tenant || scope == Published.PublishScope.Global) {
            return;
        }
        List<Long> userSpaceIds = spaceApplicationService.queryListByUserId(userId)
                .stream().map(SpaceDto::getId).collect(Collectors.toList());
        if (CollectionUtils.isNotEmpty(publishedSpaceIds)) {
            boolean anyMatch = publishedSpaceIds.stream().anyMatch(spaceId -> spaceId != null && userSpaceIds.contains(spaceId));
            if (!anyMatch) {
                throw new BizException("无权执行该插件/工作流");
            }
        }
    }

    /** 补齐 RequestContext 的 tenantConfig/user（参照 IM 机器人调 agent 模式），不覆盖已有字段。 */
    private void ensureContext(Long userId, Long tenantId) {
        RequestContext<Object> ctx = RequestContext.get();
        if (ctx == null) {
            ctx = new RequestContext<>();
            ctx.setTenantId(tenantId);
            if (tenantId != null) {
                ctx.setTenantConfig(tenantConfigApplicationService.getTenantConfig(tenantId));
            }
            RequestContext.set(ctx);
        } else {
            if (ctx.getTenantId() == null && tenantId != null) {
                ctx.setTenantId(tenantId);
            }
            if (ctx.getTenantConfig() == null && ctx.getTenantId() != null) {
                ctx.setTenantConfig(tenantConfigApplicationService.getTenantConfig(ctx.getTenantId()));
            }
        }
        if (ctx.getUser() == null && userId != null) {
            UserDto user = userApplicationService.queryById(userId);
            if (user != null) {
                ctx.setUser(user);
                ctx.setUserId(userId);
            }
        }
    }
}
