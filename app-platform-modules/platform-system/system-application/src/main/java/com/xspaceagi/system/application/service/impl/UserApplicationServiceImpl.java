package com.xspaceagi.system.application.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xspaceagi.system.application.dto.SpaceDto;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.dto.UserQueryDto;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.application.service.SysGroupApplicationService;
import com.xspaceagi.system.application.service.SysRoleApplicationService;
import com.xspaceagi.system.application.service.SysUserPermissionCacheService;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.domain.service.UserDomainService;
import com.xspaceagi.system.infra.dao.entity.Space;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.infra.dao.service.UserService;
import com.xspaceagi.system.infra.dao.entity.SysGroup;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.PageQueryVo;
import com.xspaceagi.system.spec.enums.GroupEnum;
import com.xspaceagi.system.spec.enums.AuthTypeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.utils.MD5;
import com.xspaceagi.system.spec.utils.RedisUtil;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class UserApplicationServiceImpl implements UserApplicationService {

    @Resource
    private UserDomainService userDomainService;

    @Resource
    private UserService userService;

    @Resource
    private SpaceApplicationService spaceApplicationService;

    @Resource
    private SysRoleApplicationService sysRoleApplicationService;
    @Resource
    private SysUserPermissionCacheService sysUserPermissionCacheService;

    @Resource
    private RedisUtil redisUtil;

    @Resource
    private SysGroupApplicationService sysGroupApplicationService;

    @Override
    @DSTransactional
    public void add(UserDto userDto) {
        if (StringUtils.isNotBlank(userDto.getEmail())) {
            if (userDomainService.queryByEmail(userDto.getEmail()) != null) {
                throw new BizException("邮箱地址已被占用");
            }
        }
        if (StringUtils.isNotBlank(userDto.getPhone())) {
            if (userDomainService.queryByPhone(userDto.getPhone()) != null) {
                throw new BizException("手机号已被占用");
            }
        }
        if (StringUtils.isBlank(userDto.getPassword())) {
            userDto.setPassword(UUID.randomUUID().toString());
        }
        long pre = System.currentTimeMillis() / 10000L;
        userDto.setId(pre * 10 + redisUtil.increment("new_user_uid:" + pre, 1));
        if (StringUtils.isBlank(userDto.getUserName())) {
            String userName = "user" + userDto.getId();
            userDto.setUserName(userName);
        } else {
            // username不能为纯数字
            if (userDto.getUserName().matches("^[0-9]+$")) {
                throw new BizException("用户名不能为纯数字");
            }
            User user = userDomainService.queryByUserName(userDto.getUserName());
            if (user != null) {
                throw new BizException("用户名已被占用");
            }
            // 用户名禁止为user开头后面为10位的纯数字
            if (userDto.getUserName().matches("^user[0-9]{10}$")) {
                throw new BizException("用户名不符合规范");
            }
        }
        if (StringUtils.isNotBlank(userDto.getNickName())) {
            // 昵称不能为纯数字
            if (userDto.getNickName().matches("^[0-9]+$")) {
                throw new BizException("昵称不能为纯数字");
            }
        }
        if (StringUtils.isBlank(userDto.getUid())) {
            userDto.setUid(userDto.getId().toString());
        }
        User user = new User();
        BeanUtils.copyProperties(userDto, user);
        userDomainService.add(user);
        userDto.setId(user.getId());
        // 创建个人空间
        SpaceDto spaceDto = new SpaceDto();
        spaceDto.setName("个人空间");
        spaceDto.setCreatorId(user.getId());
        spaceDto.setType(Space.Type.Personal);
        spaceDto.setDescription("个人空间");
        spaceApplicationService.add(spaceDto);

        // 新增用户后，为其绑定默认用户组（若当前无用户组绑定）
        Long userId = user.getId();
        List<SysGroup> groups = sysGroupApplicationService.getGroupListByUserId(userId);
        if (groups == null || groups.isEmpty()) {
            SysGroup defaultGroup = sysGroupApplicationService.getGroupByCode(GroupEnum.DEFAULT_GROUP.getCode());
            if (defaultGroup != null && defaultGroup.getId() != null) {
                UserContext userContext = buildUserContext(userId, userDto);
                sysGroupApplicationService.userBindGroup(userId, List.of(defaultGroup.getId()), userContext);
            }
        }
    }

    private UserContext buildUserContext(Long userId, UserDto userDto) {
        RequestContext<?> ctx = RequestContext.get();
        if (ctx != null && ctx.getUserContext() != null) {
            return ctx.getUserContext();
        }

        Long tenantId = ctx != null ? ctx.getTenantId() : null;
        if (tenantId == null) {
            tenantId = userDto.getTenantId();
        }

        return UserContext.builder()
                .userId(userId)
                .uid(userDto.getUid())
                .userName(userDto.getUserName())
                .tenantId(tenantId)
                .build();
    }

    @Override
    public void update(UserDto userDto) {
        Assert.notNull(userDto.getId(), "id must be non-null");
        if (StringUtils.isNotBlank(userDto.getUserName())) {
            // username不能为纯数字
            if (userDto.getUserName().matches("^[0-9]+$")) {
                throw new BizException("用户名不能为纯数字");
            }
            User user = userDomainService.queryByUserName(userDto.getUserName());
            if (user != null && !user.getId().equals(userDto.getId())) {
                throw new BizException("用户名已被占用");
            }
            TenantConfigDto tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
            if (tenantConfigDto.getAuthType() != null && tenantConfigDto.getAuthType() == AuthTypeEnum.CAS.getCode()) {//2.单点登录
                throw new BizException("当前认证模式不允许修改用户名");
            }
            // 用户名禁止为user开头后面为10位的纯数字
            if (user != null && !user.getId().equals(userDto.getId())
                    && userDto.getUserName().matches("^user[0-9]{10}$")) {
                throw new BizException("用户名不符合规范");
            }
        }

        if (StringUtils.isNotBlank(userDto.getEmail())) {
            if (!userDto.getEmail().matches("^[a-zA-Z0-9._-]+@[a-zA-Z0-9_-]+(\\.[a-zA-Z0-9_-]+)+$")) {
                throw new BizException("邮箱地址格式不正确");
            }
            User user = userDomainService.queryByEmail(userDto.getEmail());
            if (user != null && !user.getId().equals(userDto.getId())) {
                throw new BizException("邮箱地址已被占用");
            }
        }

        if (StringUtils.isNotBlank(userDto.getNickName())) {
            // 昵称不能为纯数字
            if (userDto.getNickName().matches("^[0-9]+$")) {
                throw new BizException("昵称不能为纯数字");
            }
        }
        User user = new User();
        User oldUser = userDomainService.queryById(userDto.getId());
        BeanUtils.copyProperties(userDto, user);
        userDomainService.update(user);

        // role 变更
        if (userDto.getRole() != null && oldUser != null && oldUser.getRole() != userDto.getRole()) {
            // 清除用户权限缓存
            sysUserPermissionCacheService.clearCacheByUserIds(List.of(userDto.getId()));

            // role 从 Admin 变为 User 时，清除用户角色绑定（普通用户不能绑定角色）
            if (oldUser.getRole() == User.Role.Admin && userDto.getRole() == User.Role.User) {
                sysRoleApplicationService.userBindRole(userDto.getId(), Collections.emptyList(), RequestContext.get().getUserContext());
            }
        }
    }

    @Override
    public IPage<UserDto> listQuery(PageQueryVo<UserQueryDto> pageQueryVo) {
        // 创建分页对象
        Page<User> userPage = new Page<>(pageQueryVo.getPageNo(), pageQueryVo.getPageSize());
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        User user = new User();
        queryWrapper.setEntity(user);
        queryWrapper.orderByDesc(User::getId);
        // 将PageQueryVo中的属性作为过滤条件
        UserQueryDto userQueryDto = pageQueryVo.getQueryFilter();
        if (userQueryDto != null) {
            BeanUtils.copyProperties(userQueryDto, user);
        }
        if ("".equals(user.getUserName())) {
            user.setUserName(null);
        }
        if (StringUtils.isNotBlank(user.getUserName())) {
            // 判断userName是否为邮箱
            if (user.getUserName().matches("^[a-zA-Z0-9._-]+@[a-zA-Z0-9_-]+(\\.[a-zA-Z0-9_-]+)+$")) {
                user.setEmail(user.getUserName());
            } else if (user.getUserName().matches("^[0-9]{11}$")) {// 判断是否为11位数字
                user.setPhone(user.getUserName());
            } else {
                queryWrapper.and(lambdaQueryWrapper -> lambdaQueryWrapper.like(User::getNickName, user.getUserName()).or().like(User::getUserName, user.getUserName()));
            }
            user.setUserName(null);
        }
        IPage<User> userPageResult = userService.page(userPage, queryWrapper);
        List<UserDto> userDtoList = userPageResult.getRecords().stream()
                .map(user1 -> convert(user1))
                .collect(Collectors.toList());

        // 创建分页对象，用于返回分页数据
        Page<UserDto> userDtoPage = new Page<>(userPage.getCurrent(), userPage.getSize());
        userDtoPage.setTotal(userPage.getTotal());
        userDtoPage.setRecords(userDtoList);

        return userDtoPage;
    }

    @Override
    public UserDto queryById(Long userId) {
        Assert.notNull(userId, "id must be non-null");
        User user = userDomainService.queryById(userId);
        return convert(user);
    }

    @Override
    public UserDto queryUserByPhoneOrEmailWithPassword(String phoneOrEmail, String password) {
        User user;
        if (phoneOrEmail.matches("^[a-zA-Z0-9._-]+@[a-zA-Z0-9_-]+(\\.[a-zA-Z0-9_-]+)+$")) {
            user = userDomainService.queryByEmail(phoneOrEmail);
        } else {
            user = userDomainService.queryByPhone(phoneOrEmail);
            if (user == null) {
                user = userDomainService.queryByUserName(phoneOrEmail);
            }
        }

        if (user != null && (user.getPassword().equals(password) || user.getPassword().equals(MD5.StrongMD5Encode(password)))) {
            return convert(user);
        }
        return null;
    }

    @Override
    public UserDto queryUserByPhone(String phone) {
        Assert.notNull(phone, "phone must be non-null");
        return convert(userDomainService.queryByPhone(phone));
    }

    @Override
    public UserDto queryUserByEmail(String email) {
        Assert.notNull(email, "email must be non-null");
        return convert(userDomainService.queryByEmail(email));
    }

    @Override
    public UserDto queryUserByUserName(String userName) {
        return convert(userDomainService.queryByUserName(userName));
    }

    @Override
    public UserDto queryUserByUid(String uid) {
        return convert(userDomainService.queryUserByUid(uid));
    }

    @Override
    public List<UserDto> queryUserListByIds(List<Long> userIds) {
        List<User> userList = userDomainService.queryUserListByIds(userIds);
        return userList.stream().map(this::convert).collect(Collectors.toList());
    }

    @Override
    public List<Long> queryUserIdList(Long lastId, Integer size) {
        return userDomainService.queryUserIdList(lastId, size);
    }

    private UserDto convert(User user) {
        if (user == null) {
            return null;
        }
        UserDto userDto = new UserDto();
        BeanUtils.copyProperties(user, userDto);
        userDto.setPassword(null);
        return userDto;
    }

    @Override
    public List<UserDto> queryUserListByUids(List<String> uids) {
        List<User> userList = userDomainService.queryUserListByUids(uids);
        return userList.stream()
                .map(this::convert)
                .toList();
    }

    @Override
    public String getUserDynamicCode(Long userId) {
        Object o = redisUtil.get("user:dynamicCode:" + userId);
        if (o != null) {
            redisUtil.expire("user:dynamicCode:" + userId, 5 * 60);// 有查询就续期5分钟
            return o.toString();
        }
        String code = RandomUtil.randomNumbers(6);
        redisUtil.set("user:dynamicCode:" + userId, code, 60 * 5);
        return code;
    }
}
