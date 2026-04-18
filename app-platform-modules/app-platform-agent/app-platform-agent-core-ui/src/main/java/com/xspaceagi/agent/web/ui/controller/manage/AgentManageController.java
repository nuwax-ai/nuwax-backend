package com.xspaceagi.agent.web.ui.controller.manage;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xspaceagi.agent.core.adapter.application.AgentApplicationService;
import com.xspaceagi.agent.core.adapter.dto.config.AgentConfigDto;
import com.xspaceagi.agent.core.adapter.repository.PublishedRepository;
import com.xspaceagi.agent.core.adapter.repository.entity.AgentConfig;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.infra.dao.mapper.AgentConfigMapper;
import com.xspaceagi.agent.web.ui.controller.manage.dto.BaseManageItem;
import com.xspaceagi.agent.web.ui.controller.manage.dto.ManageIdsQueryRequest;
import com.xspaceagi.agent.web.ui.controller.manage.dto.ManagePageResponse;
import com.xspaceagi.agent.web.ui.controller.manage.dto.ManageQueryRequest;
import com.xspaceagi.system.application.dto.permission.BindRestrictionTargetsDto;
import com.xspaceagi.system.application.dto.permission.SubjectTargetsDto;
import com.xspaceagi.system.application.service.SysSubjectPermissionApplicationService;
import com.xspaceagi.system.sdk.server.IUserRpcService;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.enums.PermissionSubjectTypeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.xspaceagi.system.spec.enums.ResourceEnum.*;

@Tag(name = "资源管理-智能体管理")
@RestController
@RequestMapping("/api/system/resource/agent")
public class AgentManageController extends BaseManageController {

    @Resource
    private AgentApplicationService agentApplicationService;

    @Resource
    private AgentConfigMapper agentConfigMapper;

    @Resource
    private IUserRpcService userRpcService;

    @Resource
    private SysSubjectPermissionApplicationService sysSubjectPermissionApplicationService;

    @Resource
    private PublishedRepository publishedRepository;

    @RequireResource(CONTENT_AGENT_QUERY_LIST)
    @Operation(summary = "查询智能体列表")
    @PostMapping("/list")
    public ReqResult<ManagePageResponse<BaseManageItem>> list(@RequestBody ManageQueryRequest request) {
        Page<AgentConfig> page = new Page<>(request.getPageNo(), request.getPageSize());
        completeCreatorIds(request);
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentConfig> queryWrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentConfig>()
                        .like(request.getName() != null, AgentConfig::getName, request.getName())
                        .in(request.getCreatorIds() != null && !request.getCreatorIds().isEmpty(),
                                AgentConfig::getCreatorId, request.getCreatorIds())
                        .eq(request.getSpaceId() != null, AgentConfig::getSpaceId, request.getSpaceId())
                        .ne(AgentConfig::getType, "PageApp")
                        .eq(request.getAccessControl() != null, AgentConfig::getAccessControl, request.getAccessControl())
                        .orderByDesc(AgentConfig::getCreated);

        IPage<AgentConfig> resultPage = agentConfigMapper.selectPage(page, queryWrapper);

        // 批量查询用户信息
        List<Long> creatorIds = resultPage.getRecords().stream()
                .map(AgentConfig::getCreatorId)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, UserContext> userMap = userRpcService.queryUserListByIds(creatorIds).stream()
                .collect(Collectors.toMap(UserContext::getUserId, user -> user));

        // 智能体ID查询哪些全局发布
        List<Long> agentIds = resultPage.getRecords().stream().map(AgentConfig::getId).toList();
        LambdaQueryWrapper<Published> publishedQueryWrapper = new LambdaQueryWrapper<Published>()
                .eq(Published::getTargetType, Published.TargetType.Agent)
                .eq(Published::getScope, Published.PublishScope.Tenant)
                .in(!agentIds.isEmpty(), Published::getTargetId, agentIds);
        List<Published> publishedList = publishedRepository.list(publishedQueryWrapper);
        Set<Long> globalPublishedAgentIds = publishedList.stream().map(Published::getTargetId).collect(Collectors.toSet());

        List<BaseManageItem> items = resultPage.getRecords().stream()
                .map(agent -> {
                    UserContext user = userMap.get(agent.getCreatorId());
                    return BaseManageItem.builder()
                            .id(agent.getId())
                            .agentId(agent.getId())
                            .spaceId(agent.getSpaceId())
                            .name(agent.getName())
                            .description(agent.getDescription())
                            .creatorId(agent.getCreatorId())
                            .creatorName(user != null ? user.getUserName() : null)
                            .created(agent.getCreated())
                            .publishStatus(agent.getPublishStatus())
                            .publishScope(globalPublishedAgentIds.contains(agent.getId()) ? Published.PublishScope.Tenant : Published.PublishScope.Space)
                            .accessControl(agent.getAccessControl())
                            .operation("agent")
                            .build();
                })
                .collect(Collectors.toList());

        List<Long> longs = extractExistSpaceIds(items);
        items.removeIf(item -> !longs.contains(item.getSpaceId()));

        ManagePageResponse<BaseManageItem> response = ManagePageResponse.<BaseManageItem>builder()
                .total(resultPage.getTotal())
                .pageNo(request.getPageNo())
                .pageSize(request.getPageSize())
                .records(items)
                .build();

        return ReqResult.success(response);
    }

    @RequireResource(CONTENT_AGENT_DELETE)
    @Operation(summary = "删除智能体")
    @PostMapping("/delete/{id}")
    public ReqResult<Void> delete(@PathVariable Long id) {
        agentApplicationService.delete(id);
        return ReqResult.success(null);
    }

    @Operation(summary = "访问受限设置")
    @PostMapping("/access/{id}/{status}")
    public ReqResult<Void> updateAccessControlStatus(@PathVariable Long id, @PathVariable Integer status) {
        agentApplicationService.updateAccessControlStatus(id, status);
        return ReqResult.success(null);
    }

    @Operation(summary = "根据id列表查询智能体列表")
    @PostMapping(value = "/list-by-ids", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<List<AgentConfigDto>> listByIds(@RequestBody ManageIdsQueryRequest req) {
        List<AgentConfigDto> dtoList = agentApplicationService.queryListByIds(req.getAgentIds());
        return ReqResult.success(dtoList);
    }

    @RequireResource(CONTENT_AGENT_QUERY_LIST)
    @Operation(summary = "查询智能体限制访问的对象")
    @GetMapping("/restriction-targets/{agentId}")
    public ReqResult<SubjectTargetsDto> getRestrictionTargets(@PathVariable Long agentId) {
        if (agentApplicationService.queryById(agentId) == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentNotFound);
        }
        return ReqResult.success(sysSubjectPermissionApplicationService.listTargetsBySubject(
                PermissionSubjectTypeEnum.AGENT, agentId));
    }

    @RequireResource(CONTENT_AGENT_ACCESS_CONTROL)
    @Operation(summary = "绑定智能体限制访问对象（全量覆盖）")
    @PostMapping("/bind-restriction-targets")
    public ReqResult<Void> bindRestrictionTargets(@RequestBody @Valid BindRestrictionTargetsDto bindDto) {
        if (agentApplicationService.queryById(bindDto.getSubjectId()) == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentNotFound);
        }
        sysSubjectPermissionApplicationService.bindRestrictionTargets(
                PermissionSubjectTypeEnum.AGENT, bindDto.getSubjectId(), bindDto, getUser());
        return ReqResult.success();
    }
}