package com.xspaceagi.agent.web.ui.controller;

import com.xspaceagi.agent.core.adapter.application.ComponentApplicationService;
import com.xspaceagi.agent.core.adapter.dto.ComponentDto;
import com.xspaceagi.agent.core.spec.enums.ComponentTypeEnum;
import com.xspaceagi.agent.web.ui.controller.util.SpaceObjectPermissionUtil;
import com.xspaceagi.agent.web.ui.dto.ComponentQueryDto;
import com.xspaceagi.system.application.dto.SpaceUserDto;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.application.util.DefaultIconUrlUtil;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

import static com.xspaceagi.system.spec.enums.ResourceEnum.COMPONENT_LIB_QUERY_LIST;

@Tag(name = "组件库接口")
@RestController
@RequestMapping("/api/component")
@Slf4j
public class ComponentController {

    @Resource
    private ComponentApplicationService componentApplicationService;

    @Resource
    private SpacePermissionService spacePermissionService;

    @Resource
    private SpaceApplicationService spaceApplicationService;

    @RequireResource(COMPONENT_LIB_QUERY_LIST)
    @Operation(summary = "查询组件列表接口")
    @RequestMapping(path = "/list/{spaceId}", method = RequestMethod.GET)
    public ReqResult<List<ComponentDto>> list(@PathVariable Long spaceId) {
        spacePermissionService.checkSpaceUserPermission(spaceId);
        List<ComponentDto> componentDtos = componentApplicationService.getComponentListBySpaceId(spaceId, null, null);
        completePermissions(componentDtos, spaceId);
        return ReqResult.success(componentDtos);
    }

    @RequireResource(COMPONENT_LIB_QUERY_LIST)
    @Operation(summary = "条件查询组件列表接口")
    @RequestMapping(path = "/list", method = RequestMethod.POST)
    public ReqResult<List<ComponentDto>> queryList(@RequestBody ComponentQueryDto queryDto) {
        Assert.notNull(queryDto, "queryDto is null");
        Assert.notNull(queryDto.getSpaceId(), "spaceId is null");
        spacePermissionService.checkSpaceUserPermission(queryDto.getSpaceId());
        List<ComponentDto> componentDtos = componentApplicationService.getComponentListBySpaceId(queryDto.getSpaceId(), queryDto.getGroupId(), queryDto.getTypes());
        completePermissions(componentDtos, queryDto.getSpaceId());
        return ReqResult.success(componentDtos);
    }

    private void completePermissions(List<ComponentDto> componentDtos, Long spaceId) {
        SpaceUserDto spaceUserDto = spaceApplicationService.querySpaceUser(spaceId, RequestContext.get().getUserId());
        componentDtos.forEach(componentDto -> {
            List<String> collect = SpaceObjectPermissionUtil.generatePermissionList(spaceUserDto, componentDto.getCreatorId()).stream().map(Enum::name).collect(Collectors.toList());
            componentDto.setPermissions(collect);
            if (componentDto.getType() != ComponentTypeEnum.Model) {
                componentDto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(componentDto.getIcon(), componentDto.getName(), componentDto.getType().name()));
            }
        });
    }

}
