package com.xspaceagi.system.domain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xspaceagi.system.domain.service.UserDomainService;
import com.xspaceagi.system.infra.dao.entity.Tenant;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.infra.dao.service.TenantService;
import com.xspaceagi.system.infra.dao.service.UserService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.TenantStatus;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import com.xspaceagi.system.spec.utils.MD5;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class UserDomainServiceImpl implements UserDomainService {
    @Resource
    private UserService userService;

    @Resource
    private TenantService tenantService;

    @Override
    public void add(User user) {
        user.setPassword(MD5.StrongMD5Encode(user.getPassword()));
        userService.save(user);
    }

    @Override
    public void update(User user) {
        if (StringUtils.isNotBlank(user.getPassword())) {
            user.setPassword(MD5.StrongMD5Encode(user.getPassword()));
        }
        TenantFunctions.runWithIgnoreCheck(() -> userService.updateById(user));
    }

    @Override
    public void delete(Long userId) {
        userService.removeById(userId);
    }

    @Override
    public User queryById(Long userId) {
        return TenantFunctions.callWithIgnoreCheck(() -> userService.getById(userId));
    }

    @Override
    public User queryByEmail(String email) {
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getEmail, email);
        return userService.getOne(queryWrapper, false);
    }

    @Override
    public User queryByPhone(String phone) {
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getPhone, phone);
        return userService.getOne(queryWrapper, false);
    }

    @Override
    public User queryByPhoneAndPassword(String phone, String password) {
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getPhone, phone);
        queryWrapper.eq(User::getPassword, MD5.StrongMD5Encode(password));
        return userService.getOne(queryWrapper, false);
    }

    @Override
    public User queryByUserName(String userName) {
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getUserName, userName);
        return userService.getOne(queryWrapper, false);
    }

    @Override
    public User queryUserByUid(String uid) {
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getUid, uid);
        return userService.getOne(queryWrapper, false);
    }

    @Override
    public List<User> queryUserListByIds(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return new ArrayList<>();
        }
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(User::getId, userIds);
        RequestContext.addTenantIgnoreEntity(User.class);
        try {
            return userService.list(queryWrapper);
        } finally {
            RequestContext.removeTenantIgnoreEntity(User.class);
        }
    }

    @Override
    public List<User> searchUsers(String kw, int limit) {
        if (StringUtils.isBlank(kw) || limit <= 0) {
            return new ArrayList<>();
        }
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.and(w -> w.like(User::getUserName, kw.trim())
                .or().like(User::getNickName, kw.trim()));
        // 只返回自然人：选人场景（仓库分享/沙箱仓库）不应出现智能体或群代理等虚拟主体
        queryWrapper.eq(User::getAccountType, User.ACCOUNT_TYPE_USER);
        queryWrapper.orderByAsc(User::getId);
        queryWrapper.last("limit " + limit);
        return userService.list(queryWrapper);
    }

    @Override
    public User queryByPrincipal(Long tenantId, String accountType, String principalRefId) {
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getTenantId, tenantId);
        queryWrapper.eq(User::getAccountType, accountType);
        queryWrapper.eq(User::getPrincipalRefId, principalRefId);
        return userService.getOne(queryWrapper, false);
    }

    @Override
    public List<Long> queryUserIdList(Long lastId, Integer size) {
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.lt(User::getId, lastId);
        // 只返回自然人：本方法用于全量广播遍历，会按返回的 id 逐个写通知行并推送事件，
        // 虚拟身份（智能体/群代理）不接收站内通知
        queryWrapper.eq(User::getAccountType, User.ACCOUNT_TYPE_USER);
        queryWrapper.orderByDesc(User::getId);
        queryWrapper.select(User::getId, User::getUserName);
        queryWrapper.last("limit " + size);
        return userService.list(queryWrapper).stream().map(User::getId).collect(Collectors.toList());
    }

    @Override
    public List<User> queryUserListByUids(List<String> uids) {
        if (uids.isEmpty()) {
            return new ArrayList<>();
        }
        // 忽略租户
        return TenantFunctions.callWithIgnoreCheck(() -> {
            LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.in(User::getUid, uids);
            return userService.list(queryWrapper);
        });
    }

    @Override
    public List<Tenant> queryTenantsByStatus(TenantStatus status) {
        // 查询指定状态的租户信息需要忽略租户隔离;当前租户状态忽略
        return TenantFunctions.callWithIgnoreCheck(tenantService::list);
    }
}
