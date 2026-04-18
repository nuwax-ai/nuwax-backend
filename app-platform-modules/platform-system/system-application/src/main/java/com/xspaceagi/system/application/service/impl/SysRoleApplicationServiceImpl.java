package com.xspaceagi.system.application.service.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xspaceagi.system.application.service.SysRoleApplicationService;
import com.xspaceagi.system.application.service.SysUserPermissionCacheService;
import com.xspaceagi.system.domain.model.MenuNode;
import com.xspaceagi.system.domain.model.RoleBindMenuModel;
import com.xspaceagi.system.domain.model.SortIndex;
import com.xspaceagi.system.domain.service.SysRoleDomainService;
import com.xspaceagi.system.infra.dao.entity.SysDataPermission;
import com.xspaceagi.system.infra.dao.entity.SysRole;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.spec.annotation.ClearUserPermissionCacheByUserIds;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.constants.RedisKeyConstants;

import jakarta.annotation.Resource;

/**
 * 系统角色应用服务实现
 */
@Service
public class SysRoleApplicationServiceImpl implements SysRoleApplicationService {

    @Resource
    private SysRoleDomainService sysRoleDomainService;
    @Resource
    private SysUserPermissionCacheService sysUserPermissionCacheService;
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addRole(SysRole role, UserContext userContext) {
        sysRoleDomainService.addRole(role, userContext);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateRole(SysRole role, UserContext userContext) {
        boolean statusChanged = sysRoleDomainService.updateRole(role, userContext);
        if (statusChanged) {
            sysUserPermissionCacheService.clearCacheForRoleUsers(role.getId());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bindDataPermission(Long roleId, SysDataPermission dataPermission, UserContext userContext) {
        sysRoleDomainService.bindDataPermission(roleId, dataPermission, userContext);
        sysUserPermissionCacheService.clearCacheForRoleUsers(roleId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRole(Long roleId, UserContext userContext) {
        sysUserPermissionCacheService.clearCacheForRoleUsers(roleId);
        sysRoleDomainService.deleteRole(roleId, userContext);
    }

    @Override
    public SysRole getRoleById(Long roleId) {
        return sysRoleDomainService.queryRoleById(roleId);
    }

    @Override
    public List<SysRole> listRolesByIds(List<Long> roleIds) {
        return sysRoleDomainService.queryRoleListByIds(roleIds);
    }

    @Override
    public SysRole getRoleByCode(String roleCode) {
        return sysRoleDomainService.queryRoleByCode(roleCode);
    }

    @Override
    public List<SysRole> getRoleList(SysRole role) {
        return sysRoleDomainService.queryRoleList(role);
    }

    @Override
    public List<User> getUserListByRoleId(Long roleId) {
        return sysRoleDomainService.getUserListByRoleId(roleId);
    }

    @Override
    public IPage<User> getUserPageByRoleId(Long roleId, String userName, long pageNo, long pageSize) {
        return sysRoleDomainService.getUserPageByRoleId(roleId, userName, pageNo, pageSize);
    }

    @Override
    public List<SysRole> getRoleListByUserId(Long userId) {
        return sysRoleDomainService.queryRoleListByUserId(userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void roleBindUser(Long roleId, List<Long> userIds, UserContext userContext) {
        long oldCount = sysRoleDomainService.countUsersByRoleId(roleId);
        List<Long> oldUserIds = oldCount > 0 && oldCount <= RedisKeyConstants.CLEAR_CACHE_BY_USER_IDS_THRESHOLD
                ? sysRoleDomainService.getUserIdsByRoleId(roleId)
                : null;

        sysRoleDomainService.roleBindUser(roleId, userIds, userContext);

        if (oldCount > RedisKeyConstants.CLEAR_CACHE_BY_USER_IDS_THRESHOLD) {
            sysUserPermissionCacheService.clearCacheAll();
        } else {
            Set<Long> allUserIds = new HashSet<>();
            if (CollectionUtils.isNotEmpty(oldUserIds)) {
                allUserIds.addAll(oldUserIds);
            }
            if (CollectionUtils.isNotEmpty(userIds)) {
                allUserIds.addAll(userIds);
            }
            if (!allUserIds.isEmpty()) {
                sysUserPermissionCacheService.clearCacheByUserIds(new ArrayList<>(allUserIds));
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void roleAddUser(Long roleId, Long userId, UserContext userContext) {
        sysRoleDomainService.roleAddUser(roleId, userId, userContext);
        sysUserPermissionCacheService.clearCacheByUserIds(Collections.singletonList(userId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void roleRemoveUser(Long roleId, Long userId, UserContext userContext) {
        sysRoleDomainService.roleRemoveUser(roleId, userId, userContext);
        sysUserPermissionCacheService.clearCacheByUserIds(Collections.singletonList(userId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @ClearUserPermissionCacheByUserIds(userIdParamIndexes = {0})
    public void userBindRole(Long userId, List<Long> roleIds, UserContext userContext) {
        sysRoleDomainService.userBindRole(userId, roleIds, userContext);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bindMenu(RoleBindMenuModel model, UserContext userContext) {
        sysRoleDomainService.bindMenu(model, userContext);
        if (model != null && model.getRoleId() != null) {
            sysUserPermissionCacheService.clearCacheForRoleUsers(model.getRoleId());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchUpdateRoleSort(List<SortIndex> sortIndexList, UserContext userContext) {
        sysRoleDomainService.batchUpdateRoleSort(sortIndexList, userContext);
    }

    @Override
    public List<MenuNode> getMenuTreeByRoleId(Long roleId) {
        if (roleId == null) {
            throw new IllegalArgumentException("Role ID cannot be empty");
        }

        // domain层已经构建好的完整菜单树（包含root节点和资源详情，以及绑定类型打标）
        return sysRoleDomainService.getMenuTreeByRoleId(roleId);
    }
}
