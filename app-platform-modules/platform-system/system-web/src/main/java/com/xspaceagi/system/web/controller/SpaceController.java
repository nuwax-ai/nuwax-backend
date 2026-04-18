package com.xspaceagi.system.web.controller;

import com.xspaceagi.system.application.dto.SpaceDto;
import com.xspaceagi.system.application.dto.SpaceUserDto;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.infra.dao.entity.Space;
import com.xspaceagi.system.infra.dao.entity.SpaceUser;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.sdk.server.IUserDataPermissionRpcService;
import com.xspaceagi.system.sdk.service.dto.UserDataPermissionDto;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.utils.I18nUtil;
import com.xspaceagi.system.web.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

import static com.xspaceagi.system.spec.enums.ResourceEnum.*;

@Tag(name = "工作空间相关接口")
@RestController
@RequestMapping("/api/space")
@Slf4j
public class SpaceController {

    @Resource
    private SpaceApplicationService spaceApplicationService;

    @Resource
    private SpacePermissionService spacePermissionService;

    @Resource
    private IUserDataPermissionRpcService userDataPermissionRpcService;

    @Resource
    private UserApplicationService userApplicationService;

    @RequireResource(SPACE_CREATE)
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

    @RequireResource(SPACE_MODIFY)
    @Operation(summary = "更新团队空间接口")
    @RequestMapping(path = "/update", method = RequestMethod.POST)
    public ReqResult<Void> update(@RequestBody @Valid SpaceUpdateDto spaceUpdateDto) {
        spacePermissionService.checkSpaceAdminPermission(spaceUpdateDto.getId());
        spaceApplicationService.update(SpaceDto.builder().id(spaceUpdateDto.getId()).icon(spaceUpdateDto.getIcon())
                .name(spaceUpdateDto.getName()).description(spaceUpdateDto.getDescription())
                .receivePublish(spaceUpdateDto.getReceivePublish()).allowDevelop(spaceUpdateDto.getAllowDevelop()).build());
        return ReqResult.success();
    }

    @RequireResource(SPACE_DELETE)
    @Operation(summary = "删除空间接口")
    @RequestMapping(path = "/delete/{spaceId}", method = RequestMethod.POST)
    public ReqResult<Void> delete(@PathVariable Long spaceId) {
        SpaceDto spaceDto = spaceApplicationService.queryById(spaceId);
        if (spaceDto != null && spaceDto.getType() == Space.Type.Personal) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemPersonalSpaceDeleteForbidden);
        }
        spacePermissionService.checkSpaceOwnerPermission(spaceId);
        spaceApplicationService.delete(spaceId);
        return ReqResult.success();
    }

    //@RequireResource(SPACE_QUERY_DETAIL)
    @Operation(summary = "查询指定空间信息")
    @RequestMapping(path = "/get/{spaceId}", method = RequestMethod.GET)
    public ReqResult<SpaceDto> getOne(@PathVariable Long spaceId) {
        spacePermissionService.checkSpaceUserPermission(spaceId);
        SpaceDto spaceDto = spaceApplicationService.queryById(spaceId);
        SpaceUserDto spaceUserDto = spaceApplicationService.querySpaceUser(spaceId, RequestContext.get().getUserId());
        if (spaceUserDto != null) {
            spaceDto.setCurrentUserRole(spaceUserDto.getRole());
        }
        UserDto userDto = userApplicationService.queryById(spaceDto.getCreatorId());
        if (userDto != null) {
            spaceDto.setCreatorName(userDto.getUserName());
        }
        I18nUtil.replaceSystemMessage(spaceDto);
        return ReqResult.success(spaceDto);
    }

    @Operation(summary = "跳转到用户空间页面")
    @RequestMapping(path = "/redirect", method = RequestMethod.GET)
    public void redirect(HttpServletResponse response) throws IOException {
        SpaceDto spaceDto = spaceApplicationService.queryListByUserId(RequestContext.get().getUserId()).stream().filter(spaceDto1 -> spaceDto1.getType() == Space.Type.Personal).findFirst().orElse(null);
        TenantConfigDto tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
        if (spaceDto == null) {
            response.sendRedirect(tenantConfigDto.getCasClientHostUrl());
        }
        String redirectUrl = tenantConfigDto.getCasClientHostUrl().endsWith("/") ? tenantConfigDto.getCasClientHostUrl() + "space/" + spaceDto.getId() + "/develop" :
                tenantConfigDto.getCasClientHostUrl() + "/space/" + spaceDto.getId() + "/develop";
        response.sendRedirect(redirectUrl);
    }

    //@RequireResource(SPACE_QUERY_LIST)
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
        I18nUtil.replaceSystemMessage(spaceDtoList);
        return ReqResult.success(spaceDtoList);
    }

    @RequireResource(SPACE_ADD_USER)
    @Operation(summary = "增加团队成员接口")
    @RequestMapping(path = "/user/add", method = RequestMethod.POST)
    public ReqResult<Void> addSpaceUser(@RequestBody @Valid List<SpaceUserAddDto> spaceUserAddDtos) {
        if (CollectionUtils.isEmpty(spaceUserAddDtos)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemParamRequired);
        }
        spaceUserAddDtos.forEach(spaceUserAddDto -> {
            if (spaceUserAddDto.getRole() == SpaceUser.Role.Owner) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemCannotAddOwner);
            }
            spacePermissionService.checkSpaceAdminPermission(spaceUserAddDto.getSpaceId());
        });
        spaceUserAddDtos.forEach(spaceUserAddDto -> {
            SpaceDto spaceDto = spaceApplicationService.queryById(spaceUserAddDto.getSpaceId());
            // 团队空间才允许添加成员
            if (spaceDto != null && spaceDto.getType() != Space.Type.Personal) {
                SpaceUserDto spaceUserDto = new SpaceUserDto();
                spaceUserDto.setUserId(spaceUserAddDto.getUserId());
                spaceUserDto.setRole(spaceUserAddDto.getRole());
                spaceUserDto.setSpaceId(spaceUserAddDto.getSpaceId());
                spaceApplicationService.addSpaceUser(spaceUserDto);
            }
        });
        return ReqResult.success();
    }

    @Operation(summary = "更新团队成员角色接口")
    @RequestMapping(path = "/user/role/update", method = RequestMethod.POST)
    public ReqResult<Void> updateSpaceUserRole(@RequestBody @Valid SpaceUserRoleUpdateDto spaceUserRoleUpdateDto) {
        if (spaceUserRoleUpdateDto.getRole() == SpaceUser.Role.Owner) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemCannotUpdateToOwner);
        }
        spacePermissionService.checkSpaceAdminPermission(spaceUserRoleUpdateDto.getSpaceId());
        spaceApplicationService.updateSpaceUserRole(spaceUserRoleUpdateDto.getSpaceId(), spaceUserRoleUpdateDto.getUserId(), spaceUserRoleUpdateDto.getRole());
        return ReqResult.success();
    }

    @RequireResource(SPACE_DELETE_USER)
    @Operation(summary = "删除团队成员接口")
    @RequestMapping(path = "/user/delete", method = RequestMethod.POST)
    public ReqResult<Void> deleteUser(@RequestBody @Valid SpaceUserDeleteDto spaceUserDeleteDto) {
        spacePermissionService.checkSpaceAdminPermission(spaceUserDeleteDto.getSpaceId());
        if (spaceUserDeleteDto.getUserId().equals(RequestContext.get().getUserId())) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemCannotDeleteSelf);
        }
        SpaceDto spaceDto = spaceApplicationService.queryById(spaceUserDeleteDto.getSpaceId());
        if (spaceDto == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemSpaceNotFound);
        }
        if (spaceDto.getCreatorId().equals(spaceUserDeleteDto.getUserId())) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemCannotDeleteSpaceCreator);
        }
        spaceApplicationService.deleteSpaceUser(spaceUserDeleteDto.getSpaceId(), spaceUserDeleteDto.getUserId());
        return ReqResult.success();
    }

    @RequireResource(SPACE_QUERY_USER_LIST)
    @Operation(summary = "查询团队成员列表接口")
    @RequestMapping(path = "/user/list", method = RequestMethod.POST)
    public ReqResult<List<SpaceUserDto>> getTeamUserList(@RequestBody @Valid SpaceUserQueryDto spaceUserQueryDto) {
        spacePermissionService.checkSpaceUserPermission(spaceUserQueryDto.getSpaceId());
        List<SpaceUserDto> teamUserDtoList = spaceApplicationService.querySpaceUserList(spaceUserQueryDto.getSpaceId());
        teamUserDtoList = teamUserDtoList.stream().filter(spaceUserDto -> {
            boolean flag = true;
            if (StringUtils.isNotBlank(spaceUserQueryDto.getKw())) {
                if (!spaceUserDto.getUserName().contains(spaceUserQueryDto.getKw()) && !spaceUserDto.getNickName().contains(spaceUserQueryDto.getKw())) {
                    flag = false;
                }
            }
            if (spaceUserQueryDto.getRole() != null && spaceUserDto.getRole() != spaceUserQueryDto.getRole()) {
                flag = false;
            }
            return flag;
        }).toList();
        return ReqResult.success(teamUserDtoList);
    }

    @RequireResource(SPACE_TRANSFER)
    @Operation(summary = "空间转让接口")
    @RequestMapping(path = "/transfer", method = RequestMethod.POST)
    public ReqResult<Void> transfer(@RequestBody @Valid SpaceTransferDto spaceTransferDto) {
        spacePermissionService.checkSpaceOwnerPermission(spaceTransferDto.getSpaceId());
        spaceApplicationService.transfer(spaceTransferDto.getSpaceId(), spaceTransferDto.getTargetUserId());
        return ReqResult.success();
    }
}
