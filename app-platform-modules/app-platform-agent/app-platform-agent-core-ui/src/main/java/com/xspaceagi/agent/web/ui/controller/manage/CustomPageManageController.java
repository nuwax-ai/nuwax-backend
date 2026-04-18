package com.xspaceagi.agent.web.ui.controller.manage;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xspaceagi.agent.core.adapter.application.AgentApplicationService;
import com.xspaceagi.agent.core.adapter.dto.config.AgentConfigDto;
import com.xspaceagi.agent.core.adapter.repository.AgentConfigRepository;
import com.xspaceagi.agent.core.adapter.repository.PublishedRepository;
import com.xspaceagi.agent.core.adapter.repository.entity.AgentConfig;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.web.ui.controller.manage.dto.BaseManageItem;
import com.xspaceagi.agent.web.ui.controller.manage.dto.ManageIdsQueryRequest;
import com.xspaceagi.agent.web.ui.controller.manage.dto.ManagePageResponse;
import com.xspaceagi.agent.web.ui.controller.manage.dto.ManageQueryRequest;
import com.xspaceagi.custompage.sdk.ICustomPageRpcService;
import com.xspaceagi.custompage.sdk.dto.CustomPageDto;
import com.xspaceagi.system.application.dto.permission.BindRestrictionTargetsDto;
import com.xspaceagi.system.application.dto.permission.SubjectTargetsDto;
import com.xspaceagi.system.application.service.SysSubjectPermissionApplicationService;
import com.xspaceagi.system.spec.annotation.RequireResource;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.xspaceagi.system.spec.enums.ResourceEnum.*;

@Tag(name = "资源管理-网页应用管理")
@RestController
@RequestMapping("/api/system/resource/page")
public class CustomPageManageController extends BaseManageController {

    @Resource
    private ICustomPageRpcService iCustomPageRpcService;

    @Resource
    private AgentApplicationService agentApplicationService;

    @Resource
    private SysSubjectPermissionApplicationService sysSubjectPermissionApplicationService;

    @Resource
    private AgentConfigRepository agentConfigRepository;

    @Resource
    private PublishedRepository publishedRepository;

    @RequireResource(CONTENT_PAGE_APP_QUERY_LIST)
    @Operation(summary = "查询网页应用列表")
    @PostMapping("/list")
    public ReqResult<ManagePageResponse<BaseManageItem>> list(@RequestBody ManageQueryRequest request) {
        completeCreatorIds(request);
        List<Long> devAgentIds = new ArrayList<>();
        if (request.getAccessControl() != null) {
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentConfig> queryWrapper =
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentConfig>()
                            .select(AgentConfig::getId)
                            .like(request.getName() != null, AgentConfig::getName, request.getName())
                            .in(request.getCreatorIds() != null && !request.getCreatorIds().isEmpty(),
                                    AgentConfig::getCreatorId, request.getCreatorIds())
                            .eq(request.getSpaceId() != null, AgentConfig::getSpaceId, request.getSpaceId())
                            .eq(AgentConfig::getType, "PageApp")
                            .eq(request.getAccessControl() != null, AgentConfig::getAccessControl, request.getAccessControl());
            devAgentIds = agentConfigRepository.list(queryWrapper).stream().map(AgentConfig::getId).toList();
        }
        IPage<CustomPageDto> resultPage = iCustomPageRpcService.queryListForManage(
                request.getPageNo(),
                request.getPageSize(),
                request.getName(),
                request.getCreatorIds(),
                request.getSpaceId(),
                devAgentIds
        );

        //获取agentId列表
        List<Long> agentIds = resultPage.getRecords().stream().map(CustomPageDto::getDevAgentId).distinct().collect(Collectors.toList());
        List<AgentConfigDto> agentConfigDtos = agentApplicationService.queryListByIds(agentIds);
        //转map
        Map<Long, AgentConfigDto> agentConfigMap = agentConfigDtos.stream().collect(Collectors.toMap(AgentConfigDto::getId, v -> v));

        // 智能体ID查询哪些全局发布
        LambdaQueryWrapper<Published> publishedQueryWrapper = new LambdaQueryWrapper<Published>()
                .eq(Published::getTargetType, Published.TargetType.Agent)
                .eq(Published::getScope, Published.PublishScope.Tenant)
                .in(!agentIds.isEmpty(), Published::getTargetId, agentIds);
        List<Published> publishedList = publishedRepository.list(publishedQueryWrapper);
        Set<Long> globalPublishedAgentIds = publishedList.stream().map(Published::getTargetId).collect(Collectors.toSet());

        List<BaseManageItem> items = resultPage.getRecords().stream()
                .map(page -> {
                    AgentConfigDto agentConfigDto = agentConfigMap.get(page.getDevAgentId());
                    return BaseManageItem.builder()
                            .id(page.getProjectId())
                            .agentId(agentConfigDto != null ? agentConfigDto.getId() : null)
                            .pageAgentId(page.getDevAgentId())
                            .accessControl(agentConfigDto != null ? agentConfigDto.getAccessControl() : null)
                            .publishStatus(page.getPublishType() != null ? Published.PublishStatus.Published : Published.PublishStatus.Developing)
                            .publishScope(globalPublishedAgentIds.contains(page.getDevAgentId()) ? Published.PublishScope.Tenant : Published.PublishScope.Space)
                            .publishType(page.getPublishType())
                            .spaceId(page.getSpaceId())
                            .name(page.getName())
                            .description(page.getDescription())
                            .creatorId(page.getCreatorId())
                            .creatorName(page.getCreatorName())
                            .created(page.getCreated())
                            .operation("page")
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

    @RequireResource(CONTENT_PAGE_APP_DELETE)
    @Operation(summary = "删除网页应用")
    @PostMapping("/delete/{id}")
    public ReqResult<Void> delete(@PathVariable Long id) {
        iCustomPageRpcService.deleteForManage(id);
        return ReqResult.success(null);
    }

    @Operation(summary = "根据id列表查询应用列表")
    @PostMapping(value = "/list-by-ids", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<List<CustomPageDto>> listByIds(@RequestBody ManageIdsQueryRequest req) {
        List<CustomPageDto> dtoList = iCustomPageRpcService.listByIds(req.getPageIds(), req.getAgentIds());
        return ReqResult.success(dtoList);
    }

    @RequireResource(CONTENT_PAGE_APP_QUERY_LIST)
    @Operation(summary = "查询网页应用限制访问的对象")
    @GetMapping("/restriction-targets/{pageAgentId}")
    public ReqResult<SubjectTargetsDto> getRestrictionTargets(@PathVariable Long pageAgentId) {
        return ReqResult.success(sysSubjectPermissionApplicationService.listTargetsBySubject(
                PermissionSubjectTypeEnum.PAGE, pageAgentId));
    }

    @RequireResource(CONTENT_PAGE_APP_ACCESS_CONTROL)
    @Operation(summary = "绑定网页应用限制访问对象（全量覆盖）")
    @PostMapping("/bind-restriction-targets")
    public ReqResult<Void> bindRestrictionTargets(@RequestBody @Valid BindRestrictionTargetsDto bindDto) {
        if (iCustomPageRpcService.queryDetailByAgentId(bindDto.getSubjectId()) == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentWebAppNotFound);
        }
        sysSubjectPermissionApplicationService.bindRestrictionTargets(
                PermissionSubjectTypeEnum.PAGE, bindDto.getSubjectId(), bindDto, getUser());
        return ReqResult.success();
    }
}