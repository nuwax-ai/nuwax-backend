package com.xspaceagi.im.web.controller;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.agent.core.adapter.application.AgentApplicationService;
import com.xspaceagi.agent.core.adapter.dto.config.AgentConfigDto;
import com.xspaceagi.im.application.ImChannelConfigApplicationService;
import com.xspaceagi.im.application.dto.ImChannelConfigResponse;
import com.xspaceagi.im.application.dto.ImChannelStatisticsResponse;
import com.xspaceagi.im.infra.dao.enitity.ImChannelConfig;
import com.xspaceagi.im.web.dto.*;
import com.xspaceagi.im.web.service.ImChannelTestService;
import com.xspaceagi.im.web.util.ImDtoConvertor;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.exception.BizException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.xspaceagi.system.spec.enums.ResourceEnum.*;

@RestController
@RequestMapping("/api/im-config/channel")
@Slf4j
@Tag(name = "IM 渠道配置")
public class ImChannelController {

    @Resource
    private SpacePermissionService spacePermissionService;
    @Resource
    private AgentApplicationService agentApplicationService;
    @Resource
    private ImChannelConfigApplicationService imChannelConfigApplicationService;
    @Resource
    private ImChannelTestService imChannelTestService;

    @RequireResource(IM_CONFIG_QUERY_LIST)
    @PostMapping("/statistics")
    @Operation(summary = "统计IM渠道配置")
    public ReqResult<List<ImChannelStatisticsResponse>> statistics(@RequestBody ImChannelStatisticsRequest request) {
        if (request.getSpaceId() == null) {
            throw new BizException("空间ID不能为空");
        }
        spacePermissionService.checkSpaceUserPermission(request.getSpaceId());

        List<ImChannelStatisticsResponse> response = imChannelConfigApplicationService.statistics(request.getSpaceId());
        return ReqResult.success(response);
    }

    @RequireResource(IM_CONFIG_QUERY_LIST)
    @PostMapping("/list")
    @Operation(summary = "查询IM渠道配置列表")
    public ReqResult<List<ImChannelConfigResponse>> list(@RequestBody ImChannelConfigQueryRequest request) {
        if (request.getSpaceId() == null) {
            throw new BizException("空间ID不能为空");
        }
        spacePermissionService.checkSpaceUserPermission(request.getSpaceId());

        // 将请求对象转换为查询条件
        ImChannelConfig query = new ImChannelConfig();
        query.setChannel(request.getChannel());
        query.setTargetType(request.getTargetType());
        query.setAgentId(request.getAgentId());
        query.setEnabled(request.getEnabled());
        query.setTargetId(request.getTargetId());
        query.setSpaceId(request.getSpaceId());

        List<ImChannelConfig> configs = imChannelConfigApplicationService.list(query);
        if (CollectionUtils.isEmpty(configs)) {
            return ReqResult.success(null);
        }

        // 收集所有智能体ID
        List<Long> agentIds = configs.stream()
                .map(ImChannelConfig::getAgentId)
                .filter(id -> id != null)
                .toList();

        // 批量查询智能体信息
        Map<Long, AgentConfigDto> agentMap = Map.of();
        if (!agentIds.isEmpty()) {
            try {
                List<AgentConfigDto> agents = agentApplicationService.queryListByIds(agentIds);
                if (agents != null) {
                    agentMap = agents.stream().collect(Collectors.toMap(AgentConfigDto::getId, Function.identity()));
                }
            } catch (Exception e) {
                log.warn("批量查询智能体信息失败: agentIds={}", agentIds, e);
            }
        }
        final Map<Long, AgentConfigDto> finalAgentMap = agentMap;
        // 构建响应并填充智能体名称
        List<ImChannelConfigResponse> list = configs.stream()
                .map(config -> ImDtoConvertor.toResponse(config, finalAgentMap.get(config.getAgentId()))).toList();
        return ReqResult.success(list);
    }

    @RequireResource(IM_CONFIG_QUERY_DETAIL)
    @GetMapping("/detail/{id}")
    @Operation(summary = "根据ID查询IM渠道配置")
    public ReqResult<ImChannelConfigResponse> getById(@PathVariable Long id) {
        ImChannelConfig config = imChannelConfigApplicationService.getById(id);
        if (config == null) {
            throw new BizException("配置不存在");
        }
        spacePermissionService.checkSpaceUserPermission(config.getSpaceId());

        AgentConfigDto agentConfigDto = agentApplicationService.queryById(config.getAgentId());
        ImChannelConfigResponse response = ImDtoConvertor.toResponse(config, agentConfigDto);
        return ReqResult.success(response);
    }

    @RequireResource(IM_CONFIG_ADD)
    @PostMapping("/add")
    @Operation(summary = "添加IM渠道配置")
    public ReqResult<Void> add(@Valid @RequestBody ImChannelConfigSaveRequest request) {
        // 校验空间权限
        if (request.getSpaceId() == null) {
            throw new BizException("空间ID不能为空");
        }
        spacePermissionService.checkSpaceUserPermission(request.getSpaceId());

        // 将请求对象转换为实体
        ImChannelConfig config = ImDtoConvertor.toEntity(request);

        imChannelConfigApplicationService.add(config);
        return ReqResult.success(null);
    }

    @RequireResource(IM_CONFIG_MODIFY)
    @PostMapping("/update")
    @Operation(summary = "修改IM渠道配置")
    public ReqResult<Void> update(@Valid @RequestBody ImChannelConfigSaveRequest request) {
        if (request.getId() == null) {
            throw new BizException("配置ID不能为空");
        }
        ImChannelConfig exist = imChannelConfigApplicationService.getById(request.getId());
        if (exist == null) {
            throw new IllegalArgumentException("配置不存在");
        }
        spacePermissionService.checkSpaceUserPermission(exist.getSpaceId());

        // 将请求对象转换为实体
        ImChannelConfig newConfig = ImDtoConvertor.toEntity(request);

        imChannelConfigApplicationService.update(newConfig, exist);
        return ReqResult.success(null);
    }

//    @RequireResource(IM_CONFIG_ENABLE)
//    @PostMapping("/updateEnabled")
//    @Operation(summary = "启用/禁用IM渠道配置")
//    public ReqResult<Void> updateEnabled(@Valid @RequestBody ImChannelConfigEnabledRequest request) {
//        if (request.getId() == null) {
//            throw new BizException("配置ID不能为空");
//        }
//        if (request.getEnabled() == null) {
//            throw new BizException("启用状态不能为空");
//        }
//        ImChannelConfig exist = imChannelConfigApplicationService.getById(request.getId());
//        if (exist == null) {
//            throw new IllegalArgumentException("配置不存在");
//        }
//        spacePermissionService.checkSpaceUserPermission(exist.getSpaceId());
//
//        exist.setEnabled(request.getEnabled());
//
//        boolean success = imChannelConfigApplicationService.updateEnabled(exist);
//        if (!success) {
//            return ReqResult.error("操作失败");
//        }
//        return ReqResult.success(null);
//    }

    @RequireResource(IM_CONFIG_DELETE)
    @PostMapping("/delete/{id}")
    @Operation(summary = "删除IM渠道配置")
    public ReqResult<Void> delete(@PathVariable Long id) {
        ImChannelConfig exist = imChannelConfigApplicationService.getById(id);
        if (exist == null) {
            throw new IllegalArgumentException("配置不存在");
        }
        spacePermissionService.checkSpaceUserPermission(exist.getSpaceId());

        boolean success = imChannelConfigApplicationService.delete(id);
        if (!success) {
            return ReqResult.error("删除失败");
        }
        return ReqResult.success(null);
    }

    @PostMapping("/testConnection")
    @Operation(summary = "测试IM渠道配置连通性")
    public ReqResult<ImChannelConfigTestResponse> testConnection(@Valid @RequestBody ImChannelConfigTestRequest request) {
        // 执行连通性测试
        ImChannelConfigTestResponse response = imChannelTestService.testConnection(
                request.getChannel(),
                request.getTargetType(),
                request.getConfigData()
        );
        log.info("IM渠道连通性结果: {}", JSON.toJSONString(response));
        if (response.getSuccess()) {
            return ReqResult.success();
        }
        return ReqResult.error(response.getMessage());
    }

}
