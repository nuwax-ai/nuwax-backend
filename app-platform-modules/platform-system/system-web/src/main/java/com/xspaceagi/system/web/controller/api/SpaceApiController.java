package com.xspaceagi.system.web.controller.api;

import com.xspaceagi.system.application.dto.SpaceDto;
import com.xspaceagi.system.application.dto.SpaceUserDto;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.infra.dao.entity.Space;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.sdk.permission.IUserDataPermissionRpcService;
import com.xspaceagi.system.sdk.service.dto.UserDataPermissionDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.web.dto.SpaceAddDto;
import com.xspaceagi.system.web.dto.SpaceUpdateDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "开放API-工作空间相关接口")
@RestController
@RequestMapping("/api/v1/space")
@Slf4j
public class SpaceApiController {

    @Resource
    private SpaceApplicationService spaceApplicationService;

    @Resource
    private SpacePermissionService spacePermissionService;

    @Resource
    private IUserDataPermissionRpcService userDataPermissionRpcService;

    @Operation(summary = "创建团队空间接口")
    @RequestMapping(path = "/add", method = RequestMethod.POST)
    public ReqResult<Long> add(@RequestBody @Valid SpaceAddDto spaceAddDto) {
        // 创建团队空间数量限制
        UserDataPermissionDto userDataPermission = userDataPermissionRpcService.getUserDataPermission(RequestContext.get().getUserId());
        userDataPermission.checkMaxSpaceCount(spaceApplicationService.countUserCreatedTeamSpaces(RequestContext.get().getUserId()).intValue());
        SpaceDto spaceDto = SpaceDto.builder()
                .name(spaceAddDto.getName())
                .description(spaceAddDto.getDescription())
                .icon(spaceAddDto.getIcon())
                .creatorId(RequestContext.get().getUserId())
                .type(Space.Type.Team)
                .build();
        return ReqResult.success(spaceApplicationService.add(spaceDto));
    }

    @Operation(summary = "更新团队空间接口")
    @RequestMapping(path = "/{id}/update", method = RequestMethod.POST)
    public ReqResult<Void> update(@PathVariable Long id, @RequestBody @Valid SpaceUpdateDto spaceUpdateDto) {
        spaceUpdateDto.setId(id);
        spacePermissionService.checkSpaceAdminPermission(spaceUpdateDto.getId());
        spaceApplicationService.update(SpaceDto.builder().id(spaceUpdateDto.getId()).icon(spaceUpdateDto.getIcon())
                .name(spaceUpdateDto.getName()).description(spaceUpdateDto.getDescription())
                .receivePublish(spaceUpdateDto.getReceivePublish()).allowDevelop(spaceUpdateDto.getAllowDevelop()).build());
        return ReqResult.success();
    }

    @Operation(summary = "删除空间接口")
    @RequestMapping(path = "/{id}/delete", method = RequestMethod.POST)
    public ReqResult<Void> delete(@PathVariable Long id) {
        SpaceDto spaceDto = spaceApplicationService.queryById(id);
        if (spaceDto != null && spaceDto.getType() == Space.Type.Personal) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemPersonalSpaceDeleteForbidden);
        }
        spacePermissionService.checkSpaceOwnerPermission(id);
        spaceApplicationService.delete(id);
        return ReqResult.success();
    }

    @Operation(summary = "查询用户空间列表")
    @RequestMapping(path = "/list", method = RequestMethod.GET)
    public ReqResult<List<SpaceDto>> list() {
        List<SpaceDto> spaceDtoList = spaceApplicationService.queryListByUserId(RequestContext.get().getUserId());
        spaceDtoList.forEach(spaceDto -> {
            SpaceUserDto spaceUserDto = spaceApplicationService.querySpaceUser(spaceDto.getId(), RequestContext.get().getUserId());
            if (spaceUserDto != null) {
                spaceDto.setCurrentUserRole(spaceUserDto.getRole());
            }
        });
        return ReqResult.success(spaceDtoList);
    }
}
