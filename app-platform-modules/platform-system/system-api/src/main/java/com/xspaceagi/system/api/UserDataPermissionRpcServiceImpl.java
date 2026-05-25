package com.xspaceagi.system.api;

import com.xspaceagi.system.application.service.SysDataPermissionApplicationService;
import com.xspaceagi.system.sdk.permission.IUserDataPermissionRpcService;
import com.xspaceagi.system.sdk.service.dto.MergedGroupDataPermissionDto;
import com.xspaceagi.system.sdk.service.dto.UserDataPermissionDto;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 用户数据权限查询接口实现（供内部模块 RPC 调用）
 */
@Slf4j
@Service
public class UserDataPermissionRpcServiceImpl implements IUserDataPermissionRpcService {

    @Resource
    private SysDataPermissionApplicationService sysDataPermissionApplicationService;

    @Override
    public UserDataPermissionDto getUserDataPermission(Long userId) {
        return sysDataPermissionApplicationService.getUserDataPermission(userId);
    }

    @Override
    public MergedGroupDataPermissionDto getMergedGroupDataPermission(List<Long> groupIds) {
        return sysDataPermissionApplicationService.getMergedGroupDataPermission(groupIds);
    }
}
