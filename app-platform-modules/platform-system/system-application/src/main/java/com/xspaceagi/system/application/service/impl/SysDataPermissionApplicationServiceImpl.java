package com.xspaceagi.system.application.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xspaceagi.system.application.service.SysDataPermissionApplicationService;
import com.xspaceagi.system.application.service.SysGroupApplicationService;
import com.xspaceagi.system.application.service.SysRoleApplicationService;
import com.xspaceagi.system.application.service.SysSubjectPermissionApplicationService;
import com.xspaceagi.system.domain.service.SysDataPermissionDomainService;
import com.xspaceagi.system.infra.dao.entity.SysDataPermission;
import com.xspaceagi.system.infra.dao.entity.SysGroup;
import com.xspaceagi.system.infra.dao.entity.SysRole;
import com.xspaceagi.system.sdk.service.dto.MergedGroupDataPermissionDto;
import com.xspaceagi.system.sdk.service.dto.TokenLimit;
import com.xspaceagi.system.sdk.service.dto.UserDataPermissionDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.constants.RedisKeyConstants;
import com.xspaceagi.system.spec.enums.PermissionSubjectTypeEnum;
import com.xspaceagi.system.spec.enums.PermissionTargetTypeEnum;
import com.xspaceagi.system.spec.enums.StatusEnum;
import com.xspaceagi.system.spec.jackson.JsonSerializeUtil;
import com.xspaceagi.system.spec.utils.PermissionCacheUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Stream;

/**
 * 数据权限应用服务实现
 */
@Slf4j
@Service
public class SysDataPermissionApplicationServiceImpl implements SysDataPermissionApplicationService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private SysDataPermissionDomainService sysDataPermissionDomainService;
    @Resource
    private SysRoleApplicationService sysRoleApplicationService;
    @Resource
    private SysGroupApplicationService sysGroupApplicationService;
    @Resource
    private SysSubjectPermissionApplicationService sysSubjectPermissionApplicationService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void add(SysDataPermission dataPermission, UserContext userContext) {
        sysDataPermissionDomainService.add(dataPermission, userContext);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, SysDataPermission dataPermission, UserContext userContext) {
        sysDataPermissionDomainService.update(id, dataPermission, userContext);
    }

    @Override
    public SysDataPermission getByTarget(PermissionTargetTypeEnum targetType, Long targetId) {
        return sysDataPermissionDomainService.getByTarget(targetType, targetId);
    }

    @Override
    public List<SysDataPermission> getByTargetList(PermissionTargetTypeEnum targetType, List<Long> targetIds) {
        return sysDataPermissionDomainService.getByTargetList(targetType, targetIds);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByTaret(PermissionTargetTypeEnum permissionTargetTypeEnum, Long roleId, UserContext userContext) {
        sysDataPermissionDomainService.deleteByTaret(permissionTargetTypeEnum, roleId, userContext);
    }

    @Override
    public UserDataPermissionDto getUserDataPermission(Long userId) {
        if (userId == null) {
            return null;
        }

        Long tenantId = getTenantId();
        String cacheKey = RedisKeyConstants.buildUserPermissionCacheKey(tenantId, userId);
        HashOperations<String, String, String> hashOps = stringRedisTemplate.opsForHash();

        try {
            String dataPermissionJson = hashOps.get(cacheKey, RedisKeyConstants.HASH_FIELD_DATA_PERMISSION);
            String cacheTimeStr = hashOps.get(cacheKey, RedisKeyConstants.HASH_FIELD_CACHE_TIME);

            if (dataPermissionJson != null && cacheTimeStr != null && tenantId != null) {
                // 检查缓存是否有效
                long cacheTime = Long.parseLong(cacheTimeStr);
                if (PermissionCacheUtil.isCacheValid(stringRedisTemplate, tenantId, cacheTime)) {
                    return JsonSerializeUtil.parseObject(dataPermissionJson, new TypeReference<UserDataPermissionDto>() {
                    });
                } else {
                    log.debug("用户数据权限缓存已失效, userId={}, cacheTime={}", userId, cacheTime);
                }
            }
        } catch (Exception e) {
            log.warn("读取用户数据权限缓存失败, userId={}", userId, e);
        }

        // 缓存不存在或已失效，从数据库构建
        UserDataPermissionDto result = buildUserDataPermission(userId);
        // 注意：数据权限会通过getUserMenuTree等方法触发统一缓存的更新，这里不需要单独写入
        return result;
    }

    private Long getTenantId() {
        RequestContext<?> ctx = RequestContext.get();
        return ctx != null ? ctx.getTenantId() : null;
    }

    /**
     * 构建用户数据权限（不含缓存）
     */
    @Override
    public UserDataPermissionDto buildUserDataPermission(Long userId) {
        List<SysRole> roleList = sysRoleApplicationService.getRoleListByUserId(userId);
        roleList = CollectionUtils.isEmpty(roleList) ? new ArrayList<>() : roleList.stream().filter(r -> StatusEnum.isEnabled(r.getStatus())).toList();

        List<SysGroup> groupList = sysGroupApplicationService.getEffectiveGroupListByUserId(userId);

        if (CollectionUtils.isEmpty(roleList) && CollectionUtils.isEmpty(groupList)) {
            return buildNoneDataPermission(userId);
        }

        List<Long> roleIds = roleList.stream().map(SysRole::getId).toList();
        List<Long> groupIds = groupList.stream().map(SysGroup::getId).toList();

        List<SysDataPermission> rolePermissions = sysDataPermissionDomainService.getByTargetList(
                PermissionTargetTypeEnum.ROLE, roleIds);

        List<SysDataPermission> groupPermissions = sysDataPermissionDomainService.getByTargetList(
                PermissionTargetTypeEnum.GROUP, groupIds);

        List<SysDataPermission> allPermissions = Stream.concat(rolePermissions.stream(), groupPermissions.stream())
                .filter(Objects::nonNull)
                .toList();
        if (allPermissions.isEmpty()) {
            return buildNoneDataPermission(userId);
        }

        UserDataPermissionDto result = mergeDataPermissions(userId, allPermissions);

        // 计算有权限访问的主体（基于角色和组权限）
        result.setModelIds(mergeSubjectIds(roleIds, groupIds, PermissionSubjectTypeEnum.MODEL));
        result.setAgentIds(mergeSubjectIds(roleIds, groupIds, PermissionSubjectTypeEnum.AGENT));
        result.setPageAgentIds(mergeSubjectIds(roleIds, groupIds, PermissionSubjectTypeEnum.PAGE));
        List<UserDataPermissionDto.OpenApiConfig> openApiConfigs = mergeOpenApiConfigs(roleIds, groupIds);
        result.setOpenApiConfigs(openApiConfigs);
        result.setKnowledgeIds(mergeSubjectIds(roleIds, groupIds, PermissionSubjectTypeEnum.KNOWLEDGE));
        return result;
    }

    @Override
    public MergedGroupDataPermissionDto getMergedGroupDataPermission(List<Long> groupIds) {
        if (CollectionUtils.isEmpty(groupIds)) {
            return buildNoneMergedGroupDataPermission();
        }

        List<Long> distinctGroupIds = groupIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinctGroupIds.isEmpty()) {
            return buildNoneMergedGroupDataPermission();
        }

        List<SysGroup> groups = sysGroupApplicationService.listGroupsByIds(distinctGroupIds);
        List<Long> enabledGroupIds = CollectionUtils.isEmpty(groups)
                ? List.of()
                : groups.stream()
                .filter(g -> StatusEnum.isEnabled(g.getStatus()))
                .map(SysGroup::getId)
                .filter(Objects::nonNull)
                .toList();
        if (enabledGroupIds.isEmpty()) {
            return buildNoneMergedGroupDataPermission();
        }

        List<SysDataPermission> groupPermissions = sysDataPermissionDomainService.getByTargetList(PermissionTargetTypeEnum.GROUP, enabledGroupIds);
        MergedGroupDataPermissionDto result = CollectionUtils.isEmpty(groupPermissions)
                ? buildNoneMergedGroupDataPermission()
                : toMergedGroupDataPermissionDto(mergeDataPermissions(null, groupPermissions));

        List<Long> emptyRoleIds = List.of();
        result.setModelIds(mergeSubjectIds(emptyRoleIds, enabledGroupIds, PermissionSubjectTypeEnum.MODEL));
        result.setAgentIds(mergeSubjectIds(emptyRoleIds, enabledGroupIds, PermissionSubjectTypeEnum.AGENT));
        result.setPageAgentIds(mergeSubjectIds(emptyRoleIds, enabledGroupIds, PermissionSubjectTypeEnum.PAGE));
        result.setOpenApiConfigs(toMergedOpenApiConfigs(mergeOpenApiConfigs(emptyRoleIds, enabledGroupIds)));
        result.setKnowledgeIds(mergeSubjectIds(emptyRoleIds, enabledGroupIds, PermissionSubjectTypeEnum.KNOWLEDGE));
        return result;
    }

    private MergedGroupDataPermissionDto buildNoneMergedGroupDataPermission() {
        return toMergedGroupDataPermissionDto(buildNoneDataPermission(null));
    }

    private MergedGroupDataPermissionDto toMergedGroupDataPermissionDto(UserDataPermissionDto source) {
        if (source == null) {
            return new MergedGroupDataPermissionDto();
        }
        MergedGroupDataPermissionDto target = new MergedGroupDataPermissionDto();
        BeanUtils.copyProperties(source, target);
        target.setOpenApiConfigs(toMergedOpenApiConfigs(source.getOpenApiConfigs()));
        return target;
    }

    private List<MergedGroupDataPermissionDto.OpenApiConfig> toMergedOpenApiConfigs(
            List<UserDataPermissionDto.OpenApiConfig> openApiConfigs) {
        if (CollectionUtils.isEmpty(openApiConfigs)) {
            return List.of();
        }
        return openApiConfigs.stream().map(cfg -> {
            MergedGroupDataPermissionDto.OpenApiConfig merged = new MergedGroupDataPermissionDto.OpenApiConfig();
            merged.setKey(cfg.getKey());
            merged.setRpm(cfg.getRpm());
            merged.setRpd(cfg.getRpd());
            return merged;
        }).toList();
    }

    // 无任何数据权限
    private UserDataPermissionDto buildNoneDataPermission(Long userId) {
        UserDataPermissionDto dto = new UserDataPermissionDto();
        dto.setUserId(userId);
        dto.setTokenLimit(new TokenLimit(0L));
        dto.setMaxSpaceCount(0);
        dto.setMaxAgentCount(0);
        dto.setMaxPageAppCount(0);
        dto.setMaxKnowledgeCount(0);
        dto.setKnowledgeStorageLimitGb(BigDecimal.valueOf(-1L));
        dto.setMaxDataTableCount(0);
        dto.setMaxScheduledTaskCount(0);
        //dto.setAllowApiExternalCall(0);
        dto.setAgentComputerCpuCores(0);
        dto.setAgentComputerMemoryGb(0);
        //dto.setAgentComputerSwapGb(0);
        dto.setAgentComputerStorageLimitGb(BigDecimal.valueOf(-1L));
        dto.setPageAppStorageLimitGb(BigDecimal.valueOf(-1L));
        dto.setAgentFileStorageDays(0);
        dto.setAgentDailyPromptLimit(0);
        dto.setPageDailyPromptLimit(0);

        dto.setModelIds(List.of());
        dto.setAgentIds(List.of());
        dto.setPageAgentIds(List.of());
        dto.setOpenApiConfigs(List.of());
        dto.setKnowledgeIds(List.of());
        return dto;
    }

    /**
     * 合并多个数据权限为用户的最终数据权限
     */
    private UserDataPermissionDto mergeDataPermissions(Long userId, List<SysDataPermission> permissions) {
        UserDataPermissionDto result = new UserDataPermissionDto();
        result.setUserId(userId);

        // token 限制：取 limitPerDay 最大值，-1 表示不限制
        result.setTokenLimit(mergeTokenLimit(permissions));

        // 配额类：取最大值，-1 表示不限制
        result.setMaxSpaceCount(mergeQuota(permissions.stream().map(SysDataPermission::getMaxSpaceCount).toList()));
        result.setMaxAgentCount(mergeQuota(permissions.stream().map(SysDataPermission::getMaxAgentCount).toList()));
        result.setMaxPageAppCount(mergeQuota(permissions.stream().map(SysDataPermission::getMaxPageAppCount).toList()));
        result.setMaxKnowledgeCount(mergeQuota(permissions.stream().map(SysDataPermission::getMaxKnowledgeCount).toList()));
        result.setKnowledgeStorageLimitGb(mergeDecimalQuota(permissions.stream().map(SysDataPermission::getKnowledgeStorageLimitGb).toList()));
        result.setMaxDataTableCount(mergeQuota(permissions.stream().map(SysDataPermission::getMaxDataTableCount).toList()));
        result.setMaxScheduledTaskCount(mergeQuota(permissions.stream().map(SysDataPermission::getMaxScheduledTaskCount).toList()));

        // allowApiExternalCall：任一为 1 则允许
        //result.setAllowApiExternalCall(mergeAllowApiExternalCall(permissions));

        // 取最大值
        result.setAgentComputerCpuCores(mergeNullableInt(permissions.stream().map(SysDataPermission::getAgentComputerCpuCores).toList(), 2));
        result.setAgentComputerMemoryGb(mergeNullableInt(permissions.stream().map(SysDataPermission::getAgentComputerMemoryGb).toList(), 4));
        result.setAgentComputerSwapGb(null);
        result.setAgentComputerStorageLimitGb(mergeDecimalQuota(permissions.stream().map(SysDataPermission::getAgentComputerStorageLimitGb).toList()));
        result.setPageAppStorageLimitGb(mergeDecimalQuota(permissions.stream().map(SysDataPermission::getPageAppStorageLimitGb).toList()));
        result.setAgentFileStorageDays(mergeQuota(permissions.stream().map(SysDataPermission::getAgentFileStorageDays).toList()));
        result.setAgentDailyPromptLimit(mergeQuota(permissions.stream().map(SysDataPermission::getAgentDailyPromptLimit).toList()));
        result.setPageDailyPromptLimit(mergeQuota(permissions.stream().map(SysDataPermission::getPageDailyPromptLimit).toList()));

        return result;
    }

    /**
     * 合并角色/组对应主体的访问权限ID（取并集去重）
     */
    private List<Long> mergeSubjectIds(List<Long> roleIds, List<Long> groupIds, PermissionSubjectTypeEnum subjectType) {
        Stream<Long> roleSubjectIds = roleIds == null ? Stream.empty() :
                roleIds.stream()
                        .filter(Objects::nonNull)
                        .flatMap(roleId -> {
                            List<Long> ids = sysSubjectPermissionApplicationService.listSubjectIdsByTarget(
                                    PermissionTargetTypeEnum.ROLE, roleId, subjectType);
                            return ids == null ? Stream.empty() : ids.stream();
                        });

        Stream<Long> groupSubjectIds = groupIds == null ? Stream.empty() :
                groupIds.stream()
                        .filter(Objects::nonNull)
                        .flatMap(groupId -> {
                            List<Long> ids = sysSubjectPermissionApplicationService.listSubjectIdsByTarget(
                                    PermissionTargetTypeEnum.GROUP, groupId, subjectType);
                            return ids == null ? Stream.empty() : ids.stream();
                        });

        return Stream.concat(roleSubjectIds, groupSubjectIds)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /**
     * 合并角色与用户组下的开放 API 配置：同一 key 多来源时，rpm/rpd 与配额字段语义一致（任一为 -1 则不限制，否则取最大值）。
     */
    private List<UserDataPermissionDto.OpenApiConfig> mergeOpenApiConfigs(List<Long> roleIds, List<Long> groupIds) {
        Map<String, List<Integer>> rpmByKey = new LinkedHashMap<>();
        Map<String, List<Integer>> rpdByKey = new LinkedHashMap<>();

        if (roleIds != null) {
            for (Long roleId : roleIds) {
                if (roleId == null) {
                    continue;
                }
                Map<String, String> m = sysSubjectPermissionApplicationService.listSubjectKeyConfigByTarget(
                        PermissionTargetTypeEnum.ROLE, roleId, PermissionSubjectTypeEnum.OPEN_API);
                accumulateOpenApiRateLimits(rpmByKey, rpdByKey, m);
            }
        }
        if (groupIds != null) {
            for (Long groupId : groupIds) {
                if (groupId == null) {
                    continue;
                }
                Map<String, String> m = sysSubjectPermissionApplicationService.listSubjectKeyConfigByTarget(
                        PermissionTargetTypeEnum.GROUP, groupId, PermissionSubjectTypeEnum.OPEN_API);
                accumulateOpenApiRateLimits(rpmByKey, rpdByKey, m);
            }
        }

        Set<String> allKeys = new LinkedHashSet<>();
        allKeys.addAll(rpmByKey.keySet());
        allKeys.addAll(rpdByKey.keySet());
        List<UserDataPermissionDto.OpenApiConfig> out = new ArrayList<>();
        for (String key : allKeys) {
            UserDataPermissionDto.OpenApiConfig cfg = new UserDataPermissionDto.OpenApiConfig();
            cfg.setKey(key);
            cfg.setRpm(mergeQuota(rpmByKey.getOrDefault(key, List.of())));
            cfg.setRpd(mergeQuota(rpdByKey.getOrDefault(key, List.of())));
            out.add(cfg);
        }
        return out;
    }

    private void accumulateOpenApiRateLimits(Map<String, List<Integer>> rpmByKey, Map<String, List<Integer>> rpdByKey,
                                             Map<String, String> configMap) {
        if (configMap == null || configMap.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : configMap.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            Integer rpm = null;
            Integer rpd = null;
            if (entry.getValue() != null && !entry.getValue().isBlank()) {
                try {
                    Map<String, Integer> valueMap = JsonSerializeUtil.parseObject(entry.getValue(),
                            new TypeReference<Map<String, Integer>>() {
                            });
                    if (valueMap != null) {
                        rpm = valueMap.get("rpm");
                        rpd = valueMap.get("rpd");
                    }
                } catch (Exception e) {
                    log.debug("解析开放API config 失败, key={}", key, e);
                }
            }
            rpmByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(rpm);
            rpdByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(rpd);
        }
    }

    private TokenLimit mergeTokenLimit(List<SysDataPermission> permissions) {
        List<Long> limits = permissions.stream()
                .map(SysDataPermission::getTokenLimit)
                .filter(Objects::nonNull)
                .map(TokenLimit::getLimitPerDay)
                .filter(Objects::nonNull)
                .toList();
        if (limits.isEmpty()) {
            return new TokenLimit(-1L);
        }
        if (limits.stream().anyMatch(v -> v == null || v == -1L)) {
            return new TokenLimit(-1L);
        }
        long max = limits.stream().mapToLong(Long::longValue).max().orElse(-1L);
        return new TokenLimit(max);
    }

    private Integer mergeQuota(List<Integer> values) {
        List<Integer> valid = values.stream().filter(Objects::nonNull).toList();
        if (valid.isEmpty()) {
            return -1;
        }
        if (valid.stream().anyMatch(v -> v == -1)) {
            return -1;
        }
        return valid.stream().mapToInt(Integer::intValue).max().orElse(-1);
    }

    /**
     * BigDecimal 配额合并，保留与整型配额相同语义：
     * - 全部为 null：返回 -1（不限）
     * - 任一为 -1：返回 -1（不限）
     * - 否则取最大值
     */
    private BigDecimal mergeDecimalQuota(List<BigDecimal> values) {
        List<BigDecimal> valid = values.stream().filter(Objects::nonNull).toList();
        if (valid.isEmpty()) {
            return BigDecimal.valueOf(-1L);
        }
        boolean anyUnlimited = valid.stream().anyMatch(v -> v.compareTo(BigDecimal.valueOf(-1L)) == 0);
        if (anyUnlimited) {
            return BigDecimal.valueOf(-1L);
        }
        return valid.stream().max(BigDecimal::compareTo).orElse(BigDecimal.valueOf(-1L));
    }

    private Integer mergeNullableInt(List<Integer> values, Integer defaultValue) {
        List<Integer> valid = values.stream().filter(Objects::nonNull).toList();
        if (valid.isEmpty()) {
            return defaultValue;
        }
        return valid.stream().mapToInt(Integer::intValue).max().orElse(defaultValue);
    }

//    private Integer mergeAllowApiExternalCall(List<SysDataPermission> permissions) {
//        boolean anyAllow = permissions.stream()
//                .map(SysDataPermission::getAllowApiExternalCall)
//                .anyMatch(v -> v != null && v == 1);
//        return anyAllow ? 1 : 0;
//    }

}

