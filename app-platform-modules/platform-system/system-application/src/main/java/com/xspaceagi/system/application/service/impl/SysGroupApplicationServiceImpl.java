package com.xspaceagi.system.application.service.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xspaceagi.subscription.sdk.dto.PlanDTO;
import com.xspaceagi.subscription.sdk.dto.UserSubscriptionDTO;
import com.xspaceagi.subscription.sdk.rpc.ISubscriptionRpcService;
import com.xspaceagi.system.application.service.SysGroupApplicationService;
import com.xspaceagi.system.application.service.SysUserPermissionCacheService;
import com.xspaceagi.system.domain.model.GroupBindMenuModel;
import com.xspaceagi.system.domain.model.MenuNode;
import com.xspaceagi.system.domain.model.SortIndex;
import com.xspaceagi.system.domain.service.SysGroupDomainService;
import com.xspaceagi.system.infra.dao.entity.SysDataPermission;
import com.xspaceagi.system.infra.dao.entity.SysGroup;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.spec.annotation.ClearUserPermissionCacheByUserIds;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.constants.RedisKeyConstants;
import com.xspaceagi.system.spec.enums.StatusEnum;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 用户组应用服务实现
 */
@Slf4j
@Service
public class SysGroupApplicationServiceImpl implements SysGroupApplicationService {

    @Resource
    private SysGroupDomainService sysGroupDomainService;
    @Resource
    private SysUserPermissionCacheService sysUserPermissionCacheService;
    @Resource
    private ISubscriptionRpcService subscriptionRpcService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addGroup(SysGroup group, UserContext userContext) {
        sysGroupDomainService.addGroup(group, userContext);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateGroup(SysGroup group, UserContext userContext) {
        boolean statusChanged = sysGroupDomainService.updateGroup(group, userContext);
        if (statusChanged) {
            sysUserPermissionCacheService.clearCacheForGroupUsers(group.getId());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bindDataPermission(Long groupId, SysDataPermission dataPermission, UserContext userContext) {
        sysGroupDomainService.bindDataPermission(groupId, dataPermission, userContext);
        sysUserPermissionCacheService.clearCacheForGroupUsers(groupId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteGroup(Long groupId, UserContext userContext) {
        sysUserPermissionCacheService.clearCacheForGroupUsers(groupId);
        sysGroupDomainService.deleteGroup(groupId, userContext);
    }

    @Override
    public SysGroup getGroupById(Long groupId) {
        return sysGroupDomainService.queryGroupById(groupId);
    }

    @Override
    public List<SysGroup> listGroupsByIds(List<Long> groupIds) {
        return sysGroupDomainService.queryGroupListByIds(groupIds);
    }

    @Override
    public SysGroup getGroupByCode(String groupCode) {
        return sysGroupDomainService.queryGroupByCode(groupCode);
    }

    @Override
    public List<SysGroup> getGroupList(SysGroup group) {
        return sysGroupDomainService.queryGroupList(group);
    }

    @Override
    public List<User> getUserListByGroupId(Long groupId) {
        return sysGroupDomainService.getUserListByGroupId(groupId);
    }

    @Override
    public IPage<User> getUserPageByGroupId(Long groupId, String userName, long pageNo, long pageSize) {
        return sysGroupDomainService.getUserPageByGroupId(groupId, userName, pageNo, pageSize);
    }

    @Override
    public List<SysGroup> getGroupListByUserId(Long userId) {
        return sysGroupDomainService.queryGroupListByUserId(userId);
    }

    @Override
    public List<SysGroup> getEffectiveGroupListByUserId(Long userId) {
        if (userId == null) {
            return List.of();
        }
        List<SysGroup> groupList = getGroupListByUserId(userId);
        groupList = CollectionUtils.isEmpty(groupList) ? new ArrayList<>() : new ArrayList<>(groupList);

        Set<Long> existingGroupIds = groupList.stream()
                .map(SysGroup::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<Long> subscriptionGroupIds = getSubscriptionPlanGroupIds(userId);
        List<Long> additionalGroupIds = subscriptionGroupIds.stream()
                .filter(id -> !existingGroupIds.contains(id))
                .toList();
        if (CollectionUtils.isNotEmpty(additionalGroupIds)) {
            List<SysGroup> subscriptionGroups = listGroupsByIds(additionalGroupIds);
            if (CollectionUtils.isNotEmpty(subscriptionGroups)) {
                groupList.addAll(subscriptionGroups);
            }
        }

        return CollectionUtils.isEmpty(groupList)
                ? List.of()
                : groupList.stream().filter(g -> StatusEnum.isEnabled(g.getStatus())).toList();
    }

    @Override
    public List<SysGroup> getSubscriptionGroupListByUserId(Long userId) {
        if (userId == null) {
            return List.of();
        }
        List<Long> subscriptionGroupIds = getSubscriptionPlanGroupIds(userId);
        if (CollectionUtils.isEmpty(subscriptionGroupIds)) {
            return List.of();
        }
        List<SysGroup> groups = listGroupsByIds(subscriptionGroupIds);
        if (CollectionUtils.isEmpty(groups)) {
            return List.of();
        }
        return groups.stream().filter(g -> StatusEnum.isEnabled(g.getStatus())).toList();
    }

    private List<Long> getSubscriptionPlanGroupIds(Long userId) {
        try {
            UserSubscriptionDTO subscription = subscriptionRpcService.getUserCurrentSystemSubscription(userId);
            if (subscription == null) {
                return List.of();
            }
            PlanDTO plan = subscription.getPlan();
            if (plan == null || CollectionUtils.isEmpty(plan.getGroupIds())) {
                return List.of();
            }
            return plan.getGroupIds().stream().filter(Objects::nonNull).distinct().toList();
        } catch (Exception e) {
            log.warn("查询用户订阅计划用户组失败, userId={}", userId, e);
            return List.of();
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void groupBindUser(Long groupId, List<Long> userIds, UserContext userContext) {
        long oldCount = sysGroupDomainService.countUsersByGroupId(groupId);
        List<Long> oldUserIds = oldCount > 0 && oldCount <= RedisKeyConstants.CLEAR_CACHE_BY_USER_IDS_THRESHOLD
                ? sysGroupDomainService.getUserIdsByGroupId(groupId)
                : null;

        sysGroupDomainService.groupBindUser(groupId, userIds, userContext);

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
    public void groupAddUser(Long groupId, Long userId, UserContext userContext) {
        sysGroupDomainService.groupAddUser(groupId, userId, userContext);
        sysUserPermissionCacheService.clearCacheByUserIds(Collections.singletonList(userId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void groupRemoveUser(Long groupId, Long userId, UserContext userContext) {
        sysGroupDomainService.groupRemoveUser(groupId, userId, userContext);
        sysUserPermissionCacheService.clearCacheByUserIds(Collections.singletonList(userId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @ClearUserPermissionCacheByUserIds(userIdParamIndexes = {0})
    public void userBindGroup(Long userId, List<Long> groupIds, UserContext userContext) {
        sysGroupDomainService.userBindGroup(userId, groupIds, userContext);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bindMenu(GroupBindMenuModel model, UserContext userContext) {
        sysGroupDomainService.bindMenu(model, userContext);
        if (model != null && model.getGroupId() != null) {
            sysUserPermissionCacheService.clearCacheForGroupUsers(model.getGroupId());
        }
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchUpdateGroupSort(List<SortIndex> sortIndexList, UserContext userContext) {
        sysGroupDomainService.batchUpdateGroupSort(sortIndexList, userContext);
    }

    @Override
    public List<MenuNode> getMenuTreeByGroupId(Long groupId) {
        if (groupId == null) {
            throw new IllegalArgumentException("Group ID cannot be empty");
        }

        // domain层已经构建好的完整菜单树（包含root节点和资源详情，以及绑定类型打标）
        return sysGroupDomainService.getMenuTreeByGroupId(groupId);
    }
}
