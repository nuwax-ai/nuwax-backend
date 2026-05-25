package com.xspaceagi.system.sdk.permission;

import com.xspaceagi.system.sdk.service.dto.MergedGroupDataPermissionDto;
import com.xspaceagi.system.sdk.service.dto.UserDataPermissionDto;

import java.util.List;

/**
 * 用户数据权限查询接口（供内部模块 RPC 调用）
 */
public interface IUserDataPermissionRpcService {

    /**
     * 查询用户的数据权限
     */
    UserDataPermissionDto getUserDataPermission(Long userId);

    /**
     * 获取用户组数据权限
     */
    MergedGroupDataPermissionDto getMergedGroupDataPermission(List<Long> groupIds);

}
