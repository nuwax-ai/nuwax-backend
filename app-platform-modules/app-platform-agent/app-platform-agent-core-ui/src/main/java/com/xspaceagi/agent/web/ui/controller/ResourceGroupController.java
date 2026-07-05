package com.xspaceagi.agent.web.ui.controller;

import com.xspaceagi.agent.core.adapter.application.ResourceGroupApplicationService;
import com.xspaceagi.agent.core.adapter.dto.ResourceGroupDto;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.adapter.repository.entity.ResourceGroupRelation;
import com.xspaceagi.agent.web.ui.controller.dto.ResourceGroupQueryDto;
import com.xspaceagi.agent.web.ui.controller.dto.ResourceTargetDto;
import com.xspaceagi.system.application.util.DefaultIconUrlUtil;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "资源分组相关接口")
@RestController
@RequestMapping("/api/resource/group")
@Slf4j
public class ResourceGroupController {

    @Resource
    private ResourceGroupApplicationService resourceGroupApplicationService;

    @Resource
    private SpacePermissionService spacePermissionService;

    @Operation(summary = "查询资源分组列表")
    @RequestMapping(path = "/list", method = RequestMethod.POST)
    public ReqResult<List<ResourceGroupDto>> list(@RequestBody ResourceGroupQueryDto resourceGroupQueryDto) {
        Assert.notNull(resourceGroupQueryDto, "resourceGroupQueryDto is null");
        Assert.notNull(resourceGroupQueryDto.getSpaceId(), "spaceId is null");
        spacePermissionService.checkSpaceUserPermission(resourceGroupQueryDto.getSpaceId());
        List<ResourceGroupDto> resourceGroups = resourceGroupApplicationService.queryList(resourceGroupQueryDto.getName(), resourceGroupQueryDto.getTypes().stream().map(Published.TargetType::name).toList(), resourceGroupQueryDto.getSpaceId());
        resourceGroups.forEach(resourceGroup -> DefaultIconUrlUtil.setDefaultIconUrl(resourceGroup.getIcon(), resourceGroup.getName()));
        return ReqResult.success(resourceGroups);
    }

    @Operation(summary = "添加资源分组")
    @RequestMapping(path = "/add", method = RequestMethod.POST)
    public ReqResult<Long> add(@RequestBody ResourceGroupDto dto) {
        if (dto.getSpaceId() == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "spaceId");
        }
        spacePermissionService.checkSpaceUserPermission(dto.getSpaceId());
        Long id = resourceGroupApplicationService.add(dto);
        return ReqResult.success(id);
    }

    @Operation(summary = "更新资源分组")
    @RequestMapping(path = "/update/{id}", method = RequestMethod.POST)
    public ReqResult<Void> update(@PathVariable Long id, @RequestBody ResourceGroupDto dto) {
        ResourceGroupDto exist = resourceGroupApplicationService.queryById(id);
        if (exist == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.resourceDataNotFound);
        }
        spacePermissionService.checkSpaceUserPermission(exist.getSpaceId());
        dto.setId(id);
        resourceGroupApplicationService.update(dto);
        return ReqResult.success();
    }

    @Operation(summary = "删除资源分组")
    @RequestMapping(path = "/delete/{id}", method = RequestMethod.POST)
    public ReqResult<Void> delete(@PathVariable Long id) {
        ResourceGroupDto exist = resourceGroupApplicationService.queryById(id);
        if (exist == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.resourceDataNotFound);
        }
        spacePermissionService.checkSpaceUserPermission(exist.getSpaceId());
        resourceGroupApplicationService.delete(id);
        return ReqResult.success();
    }

    @Operation(summary = "查询资源分组详情")
    @RequestMapping(path = "/{id}", method = RequestMethod.GET)
    public ReqResult<ResourceGroupDto> detail(@PathVariable Long id) {
        ResourceGroupDto dto = resourceGroupApplicationService.queryById(id);
        if (dto == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.resourceDataNotFound);
        }
        spacePermissionService.checkSpaceUserPermission(dto.getSpaceId());
        return ReqResult.success(dto);
    }

    @Operation(summary = "添加资源到分组")
    @RequestMapping(path = "/{id}/resource/add", method = RequestMethod.POST)
    public ReqResult<Void> addResource(@PathVariable Long id, @RequestBody ResourceTargetDto resourceTargetDto) {
        ResourceGroupDto exist = resourceGroupApplicationService.queryById(id);
        if (exist == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.resourceDataNotFound);
        }
        checkPermissionAndRequired(exist.getSpaceId(), resourceTargetDto.getTargetType(), resourceTargetDto.getTargetId());
        resourceGroupApplicationService.addResourceToGroup(id, resourceTargetDto.getTargetType().name(), resourceTargetDto.getTargetId());
        return ReqResult.success();
    }

    private void checkPermissionAndRequired(Long spaceId, Published.TargetType targetType, Long targetId) {
        spacePermissionService.checkSpaceUserPermission(spaceId);
        if (targetType == null || targetId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "targetType/targetId");
        }
    }

    @Operation(summary = "从分组移除资源")
    @RequestMapping(path = "/{id}/resource/remove", method = RequestMethod.POST)
    public ReqResult<Void> removeResource(@PathVariable Long id, @RequestBody ResourceTargetDto resourceTargetDto) {
        ResourceGroupDto exist = resourceGroupApplicationService.queryById(id);
        if (exist == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.resourceDataNotFound);
        }
        checkPermissionAndRequired(exist.getSpaceId(), resourceTargetDto.getTargetType(), resourceTargetDto.getTargetId());
        resourceGroupApplicationService.removeResourceFromGroup(id, resourceTargetDto.getTargetType().name(), resourceTargetDto.getTargetId());
        return ReqResult.success();
    }

    @Operation(summary = "查询分组下的资源列表")
    @RequestMapping(path = "/{id}/resources", method = RequestMethod.GET)
    public ReqResult<List<ResourceGroupRelation>> listResources(@PathVariable Long id) {
        ResourceGroupDto exist = resourceGroupApplicationService.queryById(id);
        if (exist == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.resourceDataNotFound);
        }
        spacePermissionService.checkSpaceUserPermission(exist.getSpaceId());
        List<ResourceGroupRelation> relations = resourceGroupApplicationService.queryGroupRelations(id);
        return ReqResult.success(relations);
    }
}
