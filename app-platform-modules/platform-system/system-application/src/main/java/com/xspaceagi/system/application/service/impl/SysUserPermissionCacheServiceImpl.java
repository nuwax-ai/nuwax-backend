package com.xspaceagi.system.application.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xspaceagi.system.application.converter.MenuTreeUtil;
import com.xspaceagi.system.application.dto.AuthorizedIds;
import com.xspaceagi.system.application.dto.permission.MenuNodeDto;
import com.xspaceagi.system.application.dto.permission.ResourceNodeDto;
import com.xspaceagi.system.application.service.*;
import com.xspaceagi.system.domain.model.MenuNode;
import com.xspaceagi.system.domain.model.ResourceNode;
import com.xspaceagi.system.domain.service.SysGroupDomainService;
import com.xspaceagi.system.domain.service.SysRoleDomainService;
import com.xspaceagi.system.domain.service.UserDomainService;
import com.xspaceagi.system.infra.dao.entity.*;
import com.xspaceagi.system.sdk.service.dto.UserDataPermissionDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.constants.RedisKeyConstants;
import com.xspaceagi.system.spec.enums.BindTypeEnum;
import com.xspaceagi.system.spec.enums.GroupEnum;
import com.xspaceagi.system.spec.enums.RoleEnum;
import com.xspaceagi.system.spec.enums.StatusEnum;
import com.xspaceagi.system.spec.exception.ResourcePermissionException;
import com.xspaceagi.system.spec.jackson.JsonSerializeUtil;
import com.xspaceagi.system.spec.utils.PermissionCacheUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 用户权限缓存服务
 */
@Slf4j
@Service
public class SysUserPermissionCacheServiceImpl implements SysUserPermissionCacheService {

    @Resource
    private UserDomainService userDomainService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private SysRoleDomainService sysRoleDomainService;
    @Resource
    private SysGroupDomainService sysGroupDomainService;
    @Resource
    private SysUserPermissionService sysUserPermissionService;
    @Resource
    private SysMenuApplicationService sysMenuApplicationService;
    @Resource
    private SysRoleApplicationService sysRoleApplicationService;
    @Resource
    private SysGroupApplicationService sysGroupApplicationService;
    @Resource
    private SysResourceApplicationService sysResourceApplicationService;
    @Resource
    private SysDataPermissionApplicationService sysDataPermissionApplicationService;

    /**
     * 获取用户的菜单权限树（优先用缓存）
     */
    @Override
    public List<MenuNodeDto> getUserMenuTree(Long userId) {
        if (userId == null) {
            return new ArrayList<>();
        }

        Long tenantId = getTenantId();
        String cacheKey = RedisKeyConstants.buildUserPermissionCacheKey(tenantId, userId);
        HashOperations<String, String, String> hashOps = stringRedisTemplate.opsForHash();
        
        try {
            // 从Hash中读取缓存
            String menuTreeJson = hashOps.get(cacheKey, RedisKeyConstants.HASH_FIELD_MENU_TREE);
            String cacheTimeStr = hashOps.get(cacheKey, RedisKeyConstants.HASH_FIELD_CACHE_TIME);
            
            if (menuTreeJson != null && cacheTimeStr != null && tenantId != null) {
                // 检查缓存是否有效（比较缓存生成时间和权限最新生效时间）
                long cacheTime = Long.parseLong(cacheTimeStr);
                if (PermissionCacheUtil.isCacheValid(stringRedisTemplate, tenantId, cacheTime)) {
                    return JsonSerializeUtil.parseObject(menuTreeJson, new TypeReference<List<MenuNodeDto>>() {});
                } else {
                    log.debug("用户缓存已失效, userId={}, cacheTime={}", userId, cacheTime);
                }
            }
        } catch (Exception e) {
            log.warn("读取用户菜单缓存失败, userId={}", userId, e);
        }

        // 确保用户有默认绑定后再构建
        User user = userDomainService.queryById(userId);
        if (user == null) {
            throw new IllegalArgumentException("User does not exist, id=" + userId);
        }
        ensureUserDefaultBindings(user);
        
        List<MenuNodeDto> menuTree = buildUserMenuTree(user);
        Set<String> resourceCodes = extractAuthorizedResourceCodesFromMenuTree(menuTree);
        UserDataPermissionDto dataPermission = sysDataPermissionApplicationService.buildUserDataPermission(userId);
        
        // 查询用户的角色ID和用户组ID（用于主体权限检查）
        List<Long> roleIds = new ArrayList<>();
        List<Long> groupIds = new ArrayList<>();
        try {
            List<SysRole> roles = sysRoleApplicationService.getRoleListByUserId(userId);
            if (CollectionUtils.isNotEmpty(roles)) {
                roleIds = roles.stream()
                        .filter(r -> StatusEnum.isEnabled(r.getStatus()))
                        .map(SysRole::getId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
            }
            List<SysGroup> groups = sysGroupApplicationService.getGroupListByUserId(userId);
            if (CollectionUtils.isNotEmpty(groups)) {
                groupIds = groups.stream()
                        .filter(g -> StatusEnum.isEnabled(g.getStatus()))
                        .map(SysGroup::getId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            log.warn("查询用户角色/用户组失败, userId={}", userId, e);
        }
        
        // 保存到Hash缓存（租户隔离，tenantId 为空时跳过缓存写入）
        try {
            if (tenantId == null) {
                log.debug("tenantId 为空，跳过权限缓存写入, userId={}", userId);
                return menuTree;
            }
            long currentTime = System.currentTimeMillis();
            hashOps.put(cacheKey, RedisKeyConstants.HASH_FIELD_MENU_TREE, 
                    JsonSerializeUtil.toJSONString(menuTree));
            hashOps.put(cacheKey, RedisKeyConstants.HASH_FIELD_RESOURCE_CODES, 
                    JsonSerializeUtil.toJSONString(resourceCodes));
            if (dataPermission != null) {
                hashOps.put(cacheKey, RedisKeyConstants.HASH_FIELD_DATA_PERMISSION, 
                        JsonSerializeUtil.toJSONString(dataPermission));
            }
            hashOps.put(cacheKey, RedisKeyConstants.HASH_FIELD_ROLE_IDS, 
                    JsonSerializeUtil.toJSONString(roleIds));
            hashOps.put(cacheKey, RedisKeyConstants.HASH_FIELD_GROUP_IDS, 
                    JsonSerializeUtil.toJSONString(groupIds));
            hashOps.put(cacheKey, RedisKeyConstants.HASH_FIELD_CACHE_TIME, 
                    String.valueOf(currentTime));
            stringRedisTemplate.expire(cacheKey, 
                    RedisKeyConstants.USER_PERMISSION_CACHE_EXPIRE_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("写入用户权限缓存失败, userId={}", userId, e);
        }
        return menuTree;
    }



    @Override
    public void clearCacheByUserIds(List<Long> userIds) {
        Long tenantId = getTenantId();
        clearCacheByTenantAndUserIds(tenantId, userIds);
    }

    @Override
    public void clearCacheByTenantAndUserIds(Long tenantId, List<Long> userIds) {
        if (CollectionUtils.isEmpty(userIds)) {
            log.warn("userIds 为空，跳过清除用户权限缓存");
            return;
        }
        if (tenantId == null) {
            throw new IllegalArgumentException("Failed to clear user cache: invalid tenantId");
        }
        if (userIds.size() > RedisKeyConstants.CLEAR_CACHE_BY_USER_IDS_THRESHOLD) {
            // 用户数量太大，避免定向删除占用资源，直接全部失效
            clearCacheAll();
        }

        Set<String> keysToDelete = new HashSet<>();
        for (Long userId : userIds) {
            if (userId != null) {
                keysToDelete.add(RedisKeyConstants.buildUserPermissionCacheKey(tenantId, userId));
            }
        }
        try {
            if (!keysToDelete.isEmpty()) {
                log.info("批量清除用户权限缓存, 共{}个用户, keysCount={}", CollectionUtils.size(userIds), CollectionUtils.size(keysToDelete));
                stringRedisTemplate.delete(keysToDelete);
            }

            // 注意：不需要清除主体权限缓存
            // 1. 主体权限缓存存储的是智能体/页面允许哪些角色/组访问
            // 2. 用户权限变化（角色/组变化）不影响主体权限配置本身
            // 3. 权限检查时（hasSubjectPermission）会重新查询用户的角色/组，然后与主体权限缓存比对
            // 4. 角色/用户组绑定数据权限时，通过注解@ClearAllUserPermissionCache会清除所有缓存
        } catch (Exception e) {
            log.warn("批量清除用户权限缓存失败", e);
        }
    }

    @Override
    public void clearCacheAll() {
        Long tenantId = getTenantId();
        clearCacheAllByTenant(tenantId);
    }

    @Override
    public void clearCacheAllByTenant(Long tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("Failed to clear cache: invalid tenantId");
        }
        try {
            // 更新该租户的权限最新生效时间，使该租户下所有缓存失效
            long currentTime = System.currentTimeMillis();
            String latestTimeKey = RedisKeyConstants.buildPermissionLatestTimeKey(tenantId);
            stringRedisTemplate.opsForValue().set(latestTimeKey, String.valueOf(currentTime));
            log.info("更新权限最新时间, 租户{}下所有权限缓存将失效, time={}", tenantId, currentTime);
            log.info("清除所有用户权限缓存完成");
        } catch (Exception e) {
            log.warn("Failed to clear all user permission caches", e);
        }
    }

    @Override
    public void checkResourcePermissionAny(Long userId, List<String> resourceCodes) {
        if (CollectionUtils.isEmpty(resourceCodes)) {
            throw new ResourcePermissionException("资源编码列表不能为空");
        }
        for (String code : resourceCodes) {
            if (code != null && !code.trim().isEmpty() && hasResourcePermission(userId, code.trim())) {
                return;
            }
        }
        throw new ResourcePermissionException(
                String.format("用户[%s]没有资源[%s]中任一权限", userId, String.join(",", resourceCodes)));
    }

    /**
     * 检查用户是否有资源权限，优先使用 Redis 缓存
     */
    private boolean hasResourcePermission(Long userId, String resourceCode) {
        if (userId == null || resourceCode == null) {
            return false;
        }
        Long tenantId = getTenantId();
        String cacheKey = RedisKeyConstants.buildUserPermissionCacheKey(tenantId, userId);
        HashOperations<String, String, String> hashOps = stringRedisTemplate.opsForHash();
        
        try {
            // 从Hash中读取资源码和缓存时间
            String resourceCodesJson = hashOps.get(cacheKey, RedisKeyConstants.HASH_FIELD_RESOURCE_CODES);
            String cacheTimeStr = hashOps.get(cacheKey, RedisKeyConstants.HASH_FIELD_CACHE_TIME);
            
            if (resourceCodesJson != null && cacheTimeStr != null && tenantId != null) {
                // 检查缓存是否有效
                long cacheTime = Long.parseLong(cacheTimeStr);
                if (PermissionCacheUtil.isCacheValid(stringRedisTemplate, tenantId, cacheTime)) {
                    Set<String> resourceCodes = JsonSerializeUtil.parseObject(resourceCodesJson,
                            new TypeReference<Set<String>>() {});
                    return resourceCodes != null && resourceCodes.contains(resourceCode);
                } else {
                    log.debug("用户资源码缓存已失效, userId={}, cacheTime={}", userId, cacheTime);
                }
            }
        } catch (Exception e) {
            log.warn("读取用户资源码缓存失败, userId={}", userId, e);
        }

        // 缓存不存在或已失效，从菜单树获取（getUserMenuTree内部会处理缓存）
        List<MenuNodeDto> menuTree = getUserMenuTree(userId);
        Set<String> resourceCodes = extractAuthorizedResourceCodesFromMenuTree(menuTree);
        return resourceCodes.contains(resourceCode);
    }

    /**
     * 从菜单树中提取有权限的资源码
     */
    private Set<String> extractAuthorizedResourceCodesFromMenuTree(List<MenuNodeDto> menuTree) {
        Set<String> resourceCodes = new HashSet<>();
        if (CollectionUtils.isEmpty(menuTree)) {
            return resourceCodes;
        }
        for (MenuNodeDto menu : menuTree) {
            if (CollectionUtils.isNotEmpty(menu.getResourceTree())) {
                // 先打平资源树，再按绑定类型过滤
                List<ResourceNodeDto> flatResources = ResourceNodeDto.flattenResourceTree(menu.getResourceTree());
                for (ResourceNodeDto resourceNode : flatResources) {
                    if (resourceNode.getId() != null
                            && resourceNode.getResourceBindType() != null
                            && !BindTypeEnum.NONE.getCode().equals(resourceNode.getResourceBindType())) {
                        resourceCodes.add(resourceNode.getCode());
                    }
                }
            }

            if (CollectionUtils.isNotEmpty(menu.getChildren())) {
                resourceCodes.addAll(extractAuthorizedResourceCodesFromMenuTree(menu.getChildren()));
            }
        }
        return resourceCodes;
    }

    /**
     * 确保用户有默认绑定：无用户组时绑定默认组，Admin无角色时绑定SUPER_ADMIN
     */
    private void ensureUserDefaultBindings(User user) {
        Long userId = user.getId();
        UserContext userContext = buildUserContext(userId);

        // 1. 无用户组时绑定默认用户组
        List<SysGroup> groups = sysGroupApplicationService.getGroupListByUserId(userId);
        if (CollectionUtils.isEmpty(groups)) {
            SysGroup defaultGroup = sysGroupApplicationService.getGroupByCode(GroupEnum.DEFAULT_GROUP.getCode());
            if (defaultGroup != null && defaultGroup.getId() != null) {
                try {
                    sysGroupApplicationService.userBindGroup(userId, List.of(defaultGroup.getId()), userContext);
                    log.info("用户{}无绑定用户组，已自动绑定默认用户组", userId);
                } catch (Exception e) {
                    log.warn("用户{}绑定默认用户组失败", userId, e);
                }
            }
        }

        // 2. Admin类型且无角色时绑定SUPER_ADMIN
        if (user.getRole() == User.Role.Admin) {
            List<SysRole> roles = sysRoleApplicationService.getRoleListByUserId(userId);
            if (CollectionUtils.isEmpty(roles)) {
                SysRole superAdminRole = sysRoleApplicationService.getRoleByCode(RoleEnum.SUPER_ADMIN.getCode());
                if (superAdminRole != null && superAdminRole.getId() != null) {
                    try {
                        sysRoleApplicationService.userBindRole(userId, List.of(superAdminRole.getId()), userContext);
                        log.info("Admin用户{}无绑定角色，已自动绑定SUPER_ADMIN角色", userId);
                    } catch (Exception e) {
                        log.warn("Admin用户{}绑定SUPER_ADMIN角色失败", userId, e);
                    }
                }
            }
        }
    }

    private UserContext buildUserContext(Long userId) {
        RequestContext<?> ctx = RequestContext.get();
        if (ctx != null && ctx.getUserContext() != null) {
            return ctx.getUserContext();
        }
        return UserContext.builder().userId(userId).build();
    }

    private List<MenuNodeDto> buildUserMenuTree(User user) {
        Long userId = user.getId();
        // 查询用户拥有的菜单及资源权限（此处返回的是打平结构）
        List<MenuNode> authorizedMenuNodes = sysUserPermissionService.getUserMenuAndResources(user);
        if (CollectionUtils.isEmpty(authorizedMenuNodes)) {
            return new ArrayList<>();
        }

        AuthorizedIds authorizedIds = sysUserPermissionService.getAuthorizedMenuAndResourceIdsFromNodes(authorizedMenuNodes);
        List<SysMenu> originMenus = authorizedIds.getMenuIds().isEmpty()
                ? new ArrayList<>()
                : sysMenuApplicationService.getMenuByIds(authorizedIds.getMenuIds());
        List<SysResource> ogiginResources = authorizedIds.getResourceIds().isEmpty()
                ? new ArrayList<>()
                : sysResourceApplicationService.getResourceByIds(authorizedIds.getResourceIds());

        if (CollectionUtils.isEmpty(originMenus)) {
            return new ArrayList<>();
        }

        Map<Long, MenuNode> authorizedMenuNodeMap = authorizedMenuNodes.stream()
                .filter(n -> n.getId() != null)
                .collect(Collectors.toMap(MenuNode::getId, n -> n, (a, b) -> a));

        List<MenuNodeDto> menuDtos = originMenus.stream()
                .map(originMenu -> {
                    MenuNodeDto dto = new MenuNodeDto();
                    BeanUtils.copyProperties(originMenu, dto);
                    MenuNode menuNode = authorizedMenuNodeMap.get(originMenu.getId());
                    // 映射子菜单绑定类型
                    List<ResourceNode> resources;
                    if (menuNode != null) {
                        dto.setMenuBindType(menuNode.getMenuBindType());
                        resources = CollectionUtils.isNotEmpty(menuNode.getResourceNodes()) ? menuNode.getResourceNodes() : menuNode.getResourceTree();
                    } else {
                        resources = new ArrayList<>();
                    }
                    dto.setResourceNodes(convertResourceTreeForUser(resources, ogiginResources));

                    return dto;
                }).collect(Collectors.toList());

        return MenuTreeUtil.buildMenuTree(menuDtos);
    }

    /**
     * 从请求上下文获取当前租户ID
     */
    private Long getTenantId() {
        RequestContext<?> ctx = RequestContext.get();
        return ctx != null ? ctx.getTenantId() : null;
    }

    private List<ResourceNodeDto> convertResourceTreeForUser(List<ResourceNode> resourceNodes,
                                                              List<SysResource> ogiginResources) {
        if (CollectionUtils.isEmpty(resourceNodes)) {
            return new ArrayList<>();
        }
        Map<Long, SysResource> originResourceMap = ogiginResources.stream()
                .collect(Collectors.toMap(SysResource::getId, r -> r));
        return convertResourceTreeRecursive(resourceNodes, originResourceMap);
    }

    private List<ResourceNodeDto> convertResourceTreeRecursive(List<ResourceNode> resourceNodes,
                                                               Map<Long, SysResource> originResourceMap) {
        if (CollectionUtils.isEmpty(resourceNodes)) {
            return new ArrayList<>();
        }
        List<ResourceNodeDto> result = new ArrayList<>();
        for (ResourceNode node : resourceNodes) {
            SysResource originResource = originResourceMap.get(node.getId());
            if (originResource == null) {
                log.warn("权限引用的资源不存在或已删除, resourceId={}", node.getId());
                continue;
            }
            ResourceNodeDto dto = new ResourceNodeDto();
            BeanUtils.copyProperties(originResource, dto);
            dto.setId(node.getId());
            dto.setResourceBindType(node.getResourceBindType());

            if (CollectionUtils.isNotEmpty(node.getChildren())) {
                dto.setChildren(convertResourceTreeRecursive(node.getChildren(), originResourceMap));
            }
            result.add(dto);
        }
        return result;
    }

    @Override
    public Long getPermissionLatestCacheTime() {
        Long tenantId = getTenantId();
        if (tenantId == null) {
            return null;
        }
        try {
            String latestTimeKey = RedisKeyConstants.buildPermissionLatestTimeKey(tenantId);
            String latestTimeStr = stringRedisTemplate.opsForValue().get(latestTimeKey);
            return latestTimeStr != null ? Long.parseLong(latestTimeStr) : null;
        } catch (Exception e) {
            log.warn("获取权限缓存时间失败, tenantId={}", tenantId, e);
            return null;
        }
    }

    @Override
    public void clearCacheForRoleUsers(Long roleId) {
        if (roleId == null) {
            return;
        }
        long count = sysRoleDomainService.countUsersByRoleId(roleId);
        if (count == 0) {
            return;
        }
        if (count > RedisKeyConstants.CLEAR_CACHE_BY_USER_IDS_THRESHOLD) {
            clearCacheAll();
        } else {
            List<Long> userIds = sysRoleDomainService.getUserIdsByRoleId(roleId);
            if (CollectionUtils.isNotEmpty(userIds)) {
                clearCacheByUserIds(userIds);
            }
        }
    }

    @Override
    public void clearCacheForGroupUsers(Long groupId) {
        if (groupId == null) {
            return;
        }
        long count = sysGroupDomainService.countUsersByGroupId(groupId);
        if (count == 0) {
            return;
        }
        if (count > RedisKeyConstants.CLEAR_CACHE_BY_USER_IDS_THRESHOLD) {
            clearCacheAll();
        } else {
            List<Long> userIds = sysGroupDomainService.getUserIdsByGroupId(groupId);
            if (CollectionUtils.isNotEmpty(userIds)) {
                clearCacheByUserIds(userIds);
            }
        }
    }

}
