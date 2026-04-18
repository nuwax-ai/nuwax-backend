package com.xspaceagi.system.application.service.impl;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.xspaceagi.system.spec.annotation.ClearAllUserPermissionCache;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import com.xspaceagi.system.application.dto.permission.BindRestrictionTargetsDto;
import com.xspaceagi.system.application.dto.permission.SubjectTargetsDto;
import com.xspaceagi.system.application.service.SysSubjectPermissionApplicationService;
import com.xspaceagi.system.application.service.SysGroupApplicationService;
import com.xspaceagi.system.application.service.SysRoleApplicationService;
import com.xspaceagi.system.domain.service.SysSubjectPermissionDomainService;
import com.xspaceagi.system.infra.dao.entity.SysSubjectPermission;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.enums.PermissionSubjectTypeEnum;
import com.xspaceagi.system.spec.enums.PermissionTargetTypeEnum;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class SysSubjectPermissionApplicationServiceImpl implements SysSubjectPermissionApplicationService {

    @Resource
    private SysSubjectPermissionDomainService sysSubjectPermissionDomainService;
    @Resource
    private SysRoleApplicationService sysRoleApplicationService;
    @Resource
    private SysGroupApplicationService sysGroupApplicationService;

    @ClearAllUserPermissionCache
    @Override
    public void bindRestrictionTargets(PermissionSubjectTypeEnum subjectType, Long subjectId,
                                       BindRestrictionTargetsDto bindDto, UserContext userContext) {
        List<Long> roleIds = bindDto != null && bindDto.getRoleIds() != null ? bindDto.getRoleIds() : List.of();
        List<Long> groupIds = bindDto != null && bindDto.getGroupIds() != null ? bindDto.getGroupIds() : List.of();
        sysSubjectPermissionDomainService.replaceTargetsBySubject(subjectType, subjectId, roleIds, groupIds, userContext);
    }

    @Override
    public List<Long> listSubjectIdsByTarget(PermissionTargetTypeEnum targetType, Long targetId, PermissionSubjectTypeEnum subjectType) {
        return sysSubjectPermissionDomainService.listSubjectIdsByTarget(targetType, targetId, subjectType);
    }

    @Override
    public List<String> listSubjectKeysByTarget(PermissionTargetTypeEnum targetType, Long targetId, PermissionSubjectTypeEnum subjectType) {
        return sysSubjectPermissionDomainService.listSubjectKeysByTarget(targetType, targetId, subjectType);
    }

    @Override
    public Map<String, String> listSubjectKeyConfigByTarget(PermissionTargetTypeEnum targetType, Long targetId, PermissionSubjectTypeEnum subjectType) {
        return sysSubjectPermissionDomainService.listSubjectKeyConfigByTarget(targetType, targetId, subjectType);
    }

    @Override
    public SubjectTargetsDto listTargetsBySubject(PermissionSubjectTypeEnum subjectType, Long subjectId) {
        List<SysSubjectPermission> configs = sysSubjectPermissionDomainService.getBySubject(subjectType, subjectId);
        SubjectTargetsDto dto = new SubjectTargetsDto();
        if (CollectionUtils.isEmpty(configs)) {
            return dto;
        }
        List<Long> roleIds = configs.stream()
                .filter(c -> c != null && PermissionTargetTypeEnum.ROLE.getCode().equals(c.getTargetType()))
                .map(SysSubjectPermission::getTargetId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<Long> groupIds = configs.stream()
                .filter(c -> c != null && PermissionTargetTypeEnum.GROUP.getCode().equals(c.getTargetType()))
                .map(SysSubjectPermission::getTargetId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (!roleIds.isEmpty()) {
            dto.setRoles(sysRoleApplicationService.listRolesByIds(roleIds));
        }
        if (!groupIds.isEmpty()) {
            dto.setGroups(sysGroupApplicationService.listGroupsByIds(groupIds));
        }
        return dto;
    }

    // 调用此方法的前提是：主体限制访问
//    @Override
//    public boolean hasSubjectPermission(Long userId, PermissionSubjectTypeEnum subjectType, Long subjectId) {
//        Long tenantId = getTenantId();
//        SubjectPermissionCache cache = getSubjectPermissionCache(tenantId, subjectType, subjectId);
//        if (cache == null) {
//            return false;
//        }
//        if (CollectionUtils.isEmpty(cache.allowRoleIds) && CollectionUtils.isEmpty(cache.allowGroupIds)) {
//            return false;
//        }
//
//        if (userId == null) {
//            return false;
//        }
//
//        // 从用户权限缓存中读取角色ID和用户组ID
//        Set<Long> userRoleIds = getUserRoleIdsFromCache(tenantId, userId);
//        Set<Long> userGroupIds = getUserGroupIdsFromCache(tenantId, userId);
//
//        // 如果缓存中没有，则从数据库查询（这种情况应该很少，因为getUserMenuTree会触发缓存）
//        if (userRoleIds == null || userGroupIds == null) {
//            userRoleIds = new HashSet<>();
//            userGroupIds = new HashSet<>();
//
//            List<SysRole> roles = sysRoleApplicationService.getRoleListByUserId(userId);
//            if (CollectionUtils.isNotEmpty(roles)) {
//                roles.stream().filter(r -> StatusEnum.isEnabled(r.getStatus())).map(SysRole::getId).filter(Objects::nonNull).forEach(userRoleIds::add);
//            }
//            List<SysGroup> groups = sysGroupApplicationService.getGroupListByUserId(userId);
//            if (CollectionUtils.isNotEmpty(groups)) {
//                groups.stream().filter(g -> StatusEnum.isEnabled(g.getStatus())).map(SysGroup::getId).filter(Objects::nonNull).forEach(userGroupIds::add);
//            }
//        }
//
//        // 存在配置即表示限制访问；只要命中任一 targetType 的交集即可放行
//        for (Long rid : cache.allowRoleIds) {
//            if (userRoleIds.contains(rid)) {
//                return true;
//            }
//        }
//        for (Long gid : cache.allowGroupIds) {
//            if (userGroupIds.contains(gid)) {
//                return true;
//            }
//        }
//        return false;
//    }

//    /**
//     * 从用户权限缓存中读取角色ID集合
//     */
//    private Set<Long> getUserRoleIdsFromCache(Long tenantId, Long userId) {
//        if (tenantId == null) {
//            return null;
//        }
//        String cacheKey = RedisKeyConstants.buildUserPermissionCacheKey(tenantId, userId);
//        HashOperations<String, String, String> hashOps = stringRedisTemplate.opsForHash();
//        try {
//            String roleIdsJson = hashOps.get(cacheKey, RedisKeyConstants.HASH_FIELD_ROLE_IDS);
//            String cacheTimeStr = hashOps.get(cacheKey, RedisKeyConstants.HASH_FIELD_CACHE_TIME);
//            if (roleIdsJson != null && cacheTimeStr != null) {
//                long cacheTime = Long.parseLong(cacheTimeStr);
//                if (PermissionCacheUtil.isCacheValid(stringRedisTemplate, tenantId, cacheTime)) {
//                    List<Long> roleIds = JsonSerializeUtil.parseObject(roleIdsJson,
//                            new TypeReference<List<Long>>() {});
//                    return roleIds != null ? new HashSet<>(roleIds) : new HashSet<>();
//                }
//            }
//        } catch (Exception e) {
//            log.warn("从缓存读取用户角色ID失败, userId={}", userId, e);
//        }
//        return null;
//    }
//
//    /**
//     * 从用户权限缓存中读取用户组ID集合
//     */
//    private Set<Long> getUserGroupIdsFromCache(Long tenantId, Long userId) {
//        if (tenantId == null) {
//            return null;
//        }
//        String cacheKey = RedisKeyConstants.buildUserPermissionCacheKey(tenantId, userId);
//        HashOperations<String, String, String> hashOps = stringRedisTemplate.opsForHash();
//        try {
//            String groupIdsJson = hashOps.get(cacheKey, RedisKeyConstants.HASH_FIELD_GROUP_IDS);
//            String cacheTimeStr = hashOps.get(cacheKey, RedisKeyConstants.HASH_FIELD_CACHE_TIME);
//            if (groupIdsJson != null && cacheTimeStr != null) {
//                long cacheTime = Long.parseLong(cacheTimeStr);
//                if (PermissionCacheUtil.isCacheValid(stringRedisTemplate, tenantId, cacheTime)) {
//                    List<Long> groupIds = JsonSerializeUtil.parseObject(groupIdsJson,
//                            new TypeReference<List<Long>>() {});
//                    return groupIds != null ? new HashSet<>(groupIds) : new HashSet<>();
//                }
//            }
//        } catch (Exception e) {
//            log.warn("从缓存读取用户组ID失败, userId={}", userId, e);
//        }
//        return null;
//    }

//    /**
//     * 获取主体访问权限配置（优先使用缓存，租户隔离）
//     */
//    private SubjectPermissionCache getSubjectPermissionCache(Long tenantId, PermissionSubjectTypeEnum subjectType, Long subjectId) {
//        if (tenantId == null) {
//            List<SysSubjectPermission> configs = sysSubjectPermissionDomainService.getBySubject(subjectType, subjectId);
//            return buildSubjectPermissionCache(configs);
//        }
//        String cacheKey = RedisKeyConstants.buildSubjectPermissionCacheKey(tenantId, String.valueOf(subjectType.getCode()), subjectId);
//        try {
//            String cached = stringRedisTemplate.opsForValue().get(cacheKey);
//            if (cached != null) {
//                SubjectPermissionCache cache = JsonSerializeUtil.parseObject(cached,
//                        new TypeReference<SubjectPermissionCache>() {
//                        });
//                // 检查缓存是否有效
//                if (cache != null && cache.cacheTime != null && PermissionCacheUtil.isCacheValid(stringRedisTemplate, tenantId, cache.cacheTime)) {
//                    return cache;
//                } else {
//                    log.debug("主体访问权限缓存已失效, subjectType={}, subjectId={}, cacheTime={}",
//                            subjectType, subjectId, cache != null ? cache.cacheTime : null);
//                }
//            }
//        } catch (Exception e) {
//            log.warn("读取主体访问权限缓存失败, subjectType={}, subjectId={}", subjectType, subjectId, e);
//        }
//
//        List<SysSubjectPermission> configs = sysSubjectPermissionDomainService.getBySubject(subjectType, subjectId);
//        SubjectPermissionCache cache = buildSubjectPermissionCache(configs);
//        cache.cacheTime = System.currentTimeMillis();
//        try {
//            stringRedisTemplate.opsForValue().set(cacheKey,
//                    JsonSerializeUtil.toJSONString(cache),
//                    RedisKeyConstants.USER_PERMISSION_CACHE_EXPIRE_SECONDS,
//                    TimeUnit.SECONDS);
//        } catch (Exception e) {
//            log.warn("写入主体访问权限缓存失败, tenantId={}, subjectType={}, subjectId={}", tenantId, subjectType, subjectId, e);
//        }
//        return cache;
//    }

//    private Long getTenantId() {
//        RequestContext<?> ctx = RequestContext.get();
//        return ctx != null ? ctx.getTenantId() : null;
//    }

//    /**
//     * 构建缓存对象
//     */
//    private SubjectPermissionCache buildSubjectPermissionCache(List<SysSubjectPermission> configs) {
//        SubjectPermissionCache cache = new SubjectPermissionCache();
//        if (CollectionUtils.isEmpty(configs)) {
//            cache.allowRoleIds = new HashSet<>();
//            cache.allowGroupIds = new HashSet<>();
//            return cache;
//        }
//        // 存在配置即表示限制访问
//        cache.allowRoleIds = configs.stream()
//                .filter(c -> c != null && PermissionTargetTypeEnum.ROLE.getCode().equals(c.getTargetType()))
//                .map(SysSubjectPermission::getTargetId)
//                .filter(Objects::nonNull)
//                .collect(Collectors.toSet());
//        cache.allowGroupIds = configs.stream()
//                .filter(c -> c != null && PermissionTargetTypeEnum.GROUP.getCode().equals(c.getTargetType()))
//                .map(SysSubjectPermission::getTargetId)
//                .filter(Objects::nonNull)
//                .collect(Collectors.toSet());
//        return cache;
//    }

//    /**
//     * 主体访问权限缓存对象
//     */
//    private static class SubjectPermissionCache {
//        public Set<Long> allowRoleIds;
//        public Set<Long> allowGroupIds;
//        public Long cacheTime; // 缓存生成时间（毫秒时间戳）
//    }

}
