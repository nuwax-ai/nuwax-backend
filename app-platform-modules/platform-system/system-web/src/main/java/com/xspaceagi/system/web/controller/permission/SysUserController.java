package com.xspaceagi.system.web.controller.permission;

import com.xspaceagi.system.application.dto.permission.*;
import com.xspaceagi.system.application.service.SysDataPermissionApplicationService;
import com.xspaceagi.system.application.service.SysGroupApplicationService;
import com.xspaceagi.system.application.service.SysRoleApplicationService;
import com.xspaceagi.system.application.service.impl.SysUserPermissionCacheServiceImpl;
import com.xspaceagi.system.infra.dao.entity.SysGroup;
import com.xspaceagi.system.infra.dao.entity.SysRole;
import com.xspaceagi.system.sdk.service.dto.UserDataPermissionDto;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.annotation.SaasAdmin;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.utils.I18nUtil;
import com.xspaceagi.system.web.controller.base.BaseController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

import static com.xspaceagi.system.spec.enums.ResourceEnum.*;

@Slf4j
@Tag(name = "权限管理-用户", description = "用户权限相关接口")
@RestController
@RequestMapping("/api/system/user")
public class SysUserController extends BaseController {

    @Resource
    private SysRoleApplicationService sysRoleApplicationService;
    @Resource
    private SysGroupApplicationService sysGroupApplicationService;
    @Resource
    private SysDataPermissionApplicationService sysDataPermissionApplicationService;
    @Resource
    private SysUserPermissionCacheServiceImpl sysUserPermissionCacheService;

    @RequireResource(USER_MANAGE_BIND_ROLE)
    @Operation(summary = "查询用户绑定的角色列表")
    @GetMapping("/list-role/{userId}")
    public ReqResult<List<SysRoleDto>> getRoleListByUserId(@PathVariable Long userId) {
        if (userId == null) {
            return ReqResult.error("参数不能为空");
        }

        List<SysRole> roleList = sysRoleApplicationService.getRoleListByUserId(userId);
        if (CollectionUtils.isEmpty(roleList)) {
            return ReqResult.success();
        }
        List<SysRoleDto> dtoList = roleList.stream().map(role -> {
            SysRoleDto roleDto = new SysRoleDto();
            BeanUtils.copyProperties(role, roleDto);
            return roleDto;
        }).collect(Collectors.toList());

        I18nUtil.replaceSystemMessage(dtoList);
        return ReqResult.success(dtoList);
    }

    @RequireResource(USER_MANAGE_BIND_ROLE)
    @Operation(summary = "用户绑定角色（全量覆盖）")
    @PostMapping("/bind-role")
    public ReqResult<Void> bindUser(@RequestBody SysUserBindRoleDto dto) {
        if (dto == null) {
            return ReqResult.error("参数不能为空");
        }
        sysRoleApplicationService.userBindRole(dto.getUserId(), dto.getRoleIds(), getUser());
        return ReqResult.success();
    }

    @RequireResource(USER_MANAGE_BIND_GROUP)
    @Operation(summary = "查询用户绑定的组列表")
    @GetMapping("/list-group/{userId}")
    public ReqResult<List<SysGroupDto>> getGroupListByUserId(@PathVariable Long userId) {
        if (userId == null) {
            return ReqResult.error("参数不能为空");
        }
        List<SysGroup> groupList = sysGroupApplicationService.getGroupListByUserId(userId);
        if (CollectionUtils.isEmpty(groupList)) {
            return ReqResult.success();
        }
        List<SysGroupDto> dtoList = groupList.stream().map(group -> {
            SysGroupDto groupDto = new SysGroupDto();
            BeanUtils.copyProperties(group, groupDto);
            return groupDto;
        }).collect(Collectors.toList());

        I18nUtil.replaceSystemMessage(dtoList);
        return ReqResult.success(dtoList);
    }

    @RequireResource(USER_MANAGE_BIND_GROUP)
    @Operation(summary = "用户绑定组（全量覆盖）")
    @PostMapping("/bind-group")
    public ReqResult<Void> bindGroup(@RequestBody SysUserBindGroupDto dto) {
        if (dto == null) {
            return ReqResult.error("参数不能为空");
        }
        sysGroupApplicationService.userBindGroup(dto.getUserId(), dto.getGroupIds(), getUser());
        return ReqResult.success();
    }

    @RequireResource(USER_MANAGE_QUERY_MENU_PERMISSION)
    @Operation(summary = "查询用户的菜单权限（树形结构）")
    @GetMapping("/list-menu/{userId}")
    public ReqResult<List<MenuNodeDto>> getMenuListByUserId(@PathVariable Long userId) {
        if (userId == null) {
            return ReqResult.error("参数不能为空");
        }
        List<MenuNodeDto> menuTree = sysUserPermissionCacheService.getUserMenuTree(userId);

        I18nUtil.replaceSystemMessage(menuTree);
        return ReqResult.success(menuTree);
    }

    @RequireResource(USER_MANAGE_QUERY_DATA_PERMISSION)
    @Operation(summary = "查询用户数据权限")
    @GetMapping("/data-permission/{userId}")
    public ReqResult<UserDataPermissionDto> getUserDataPermission(@PathVariable Long userId) {
        if (userId == null) {
            return ReqResult.error("参数不能为空");
        }
        UserDataPermissionDto dataPermission = sysDataPermissionApplicationService.getUserDataPermission(userId);
        return ReqResult.success(dataPermission);
    }

    @SaasAdmin
    @Operation(summary = "清除指定用户权限缓存")
    @GetMapping("/cache/clear-user")
    public ReqResult<Void> clearUserCache(Long tenantId, Long userId) {
        if (userId == null) {
            return ReqResult.error("参数不能为空");
        }
        sysUserPermissionCacheService.clearCacheByTenantAndUserIds(tenantId, List.of(userId));
        return ReqResult.success();
    }

    @SaasAdmin
    @Operation(summary = "清除所有用户权限缓存")
    @GetMapping("/cache/clear-all")
    public ReqResult<Void> clearAllUserCache(Long tenantId) {
        sysUserPermissionCacheService.clearCacheAllByTenant(tenantId);
        return ReqResult.success();
    }
}