package com.xspaceagi.system.application.service;

import com.xspaceagi.system.infra.dao.entity.SysDataPermission;
import com.xspaceagi.system.sdk.service.dto.MergedGroupDataPermissionDto;
import com.xspaceagi.system.sdk.service.dto.UserDataPermissionDto;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.enums.PermissionTargetTypeEnum;

import java.util.List;

/**
 * 数据权限应用服务
 */
public interface SysDataPermissionApplicationService {

    void add(SysDataPermission dataPermission, UserContext userContext);

    void update(Long id, SysDataPermission dataPermission, UserContext userContext);

    SysDataPermission getByTarget(PermissionTargetTypeEnum targetType, Long targetId);

    List<SysDataPermission> getByTargetList(PermissionTargetTypeEnum targetType, List<Long> targetIds);

    void deleteByTaret(PermissionTargetTypeEnum permissionTargetTypeEnum, Long roleId, UserContext userContext);

    /**
     * 查询用户聚合后的数据权限（由用户绑定的角色和用户组的数据权限合并而成）
     */
    UserDataPermissionDto getUserDataPermission(Long userId);

    /**
     * 构建用户数据权限（不含缓存）
     */
    UserDataPermissionDto buildUserDataPermission(Long userId);

    MergedGroupDataPermissionDto getMergedGroupDataPermission(List<Long> groupIds);
}