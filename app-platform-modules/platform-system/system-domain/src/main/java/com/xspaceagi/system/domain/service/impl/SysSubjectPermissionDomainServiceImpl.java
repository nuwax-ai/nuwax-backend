package com.xspaceagi.system.domain.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xspaceagi.system.domain.service.SysSubjectPermissionDomainService;
import com.xspaceagi.system.infra.dao.entity.SysSubjectPermission;
import com.xspaceagi.system.infra.dao.service.SysSubjectPermissionService;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.enums.PermissionSubjectTypeEnum;
import com.xspaceagi.system.spec.enums.PermissionTargetTypeEnum;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.enums.YnEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.jackson.JsonSerializeUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.Resource;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class SysSubjectPermissionDomainServiceImpl implements SysSubjectPermissionDomainService {

    @Resource
    private SysSubjectPermissionService sysSubjectPermissionService;

    @Override
    public List<SysSubjectPermission> getBySubject(PermissionSubjectTypeEnum subjectType, Long subjectId) {
        if (subjectType == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "主体类型");
        }
        if (subjectId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "主体ID");
        }
        return sysSubjectPermissionService.list(Wrappers.<SysSubjectPermission>lambdaQuery()
                .eq(SysSubjectPermission::getSubjectType, subjectType.getCode())
                .eq(SysSubjectPermission::getSubjectId, subjectId)
                .eq(SysSubjectPermission::getYn, YnEnum.Y.getKey()));
    }

    @Override
    public List<Long> listSubjectIdsByTarget(PermissionTargetTypeEnum targetType, Long targetId, PermissionSubjectTypeEnum subjectType) {
        if (targetType == null || subjectType == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "目标类型/主体类型");
        }
        if (targetId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "目标ID");
        }
        return sysSubjectPermissionService.list(Wrappers.<SysSubjectPermission>lambdaQuery()
                        .eq(SysSubjectPermission::getTargetType, targetType.getCode())
                        .eq(SysSubjectPermission::getTargetId, targetId)
                        .eq(SysSubjectPermission::getSubjectType, subjectType.getCode())
                        .eq(SysSubjectPermission::getYn, YnEnum.Y.getKey()))
                .stream()
                .map(SysSubjectPermission::getSubjectId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public List<String> listSubjectKeysByTarget(PermissionTargetTypeEnum targetType, Long targetId, PermissionSubjectTypeEnum subjectType) {
        if (targetType == null || subjectType == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "目标类型/主体类型");
        }
        if (targetId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "目标ID");
        }
        return sysSubjectPermissionService.list(Wrappers.<SysSubjectPermission>lambdaQuery()
                        .eq(SysSubjectPermission::getTargetType, targetType.getCode())
                        .eq(SysSubjectPermission::getTargetId, targetId)
                        .eq(SysSubjectPermission::getSubjectType, subjectType.getCode())
                        .eq(SysSubjectPermission::getYn, YnEnum.Y.getKey()))
                .stream()
                .map(SysSubjectPermission::getSubjectKey)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public Map<String, String> listSubjectKeyConfigByTarget(PermissionTargetTypeEnum targetType, Long targetId, PermissionSubjectTypeEnum subjectType) {
        if (targetType == null || subjectType == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "目标类型/主体类型");
        }
        if (targetId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "目标ID");
        }
        return sysSubjectPermissionService.list(Wrappers.<SysSubjectPermission>lambdaQuery()
                        .eq(SysSubjectPermission::getTargetType, targetType.getCode())
                        .eq(SysSubjectPermission::getTargetId, targetId)
                        .eq(SysSubjectPermission::getSubjectType, subjectType.getCode())
                        .eq(SysSubjectPermission::getYn, YnEnum.Y.getKey()))
                .stream()
                .filter(item -> item != null && StringUtils.isNotBlank(item.getSubjectKey()))
                .collect(Collectors.toMap(
                        SysSubjectPermission::getSubjectKey,
                        SysSubjectPermission::getConfig,
                        (a, b) -> b,
                        LinkedHashMap::new
                ));
    }

    @Override
    public void replaceSubjectsByTargetAndSubjectIds(PermissionTargetTypeEnum targetType, Long targetId,
                                                     PermissionSubjectTypeEnum subjectType, List<Long> subjectIds,
                                                     UserContext userContext) {
        if (targetType == null || subjectType == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "目标类型/主体类型");
        }
        if (targetId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "目标ID");
        }

        // 全量覆盖：先删除旧绑定
        sysSubjectPermissionService.remove(Wrappers.<SysSubjectPermission>lambdaQuery()
                .eq(SysSubjectPermission::getTargetType, targetType.getCode())
                .eq(SysSubjectPermission::getTargetId, targetId)
                .eq(SysSubjectPermission::getSubjectType, subjectType.getCode())
                .eq(SysSubjectPermission::getYn, YnEnum.Y.getKey()));

        if (CollectionUtils.isEmpty(subjectIds)) {
            return;
        }
        // 去重后插入
        Set<Long> uniq = new HashSet<>();
        for (Long sid : subjectIds) {
            if (sid != null) {
                uniq.add(sid);
            }
        }
        for (Long sid : uniq) {
            addTarget(subjectType, sid, null, targetType, targetId, userContext);
        }
    }

    @Override
    public void replaceSubjectsByTargetAndSubjectKeyConfig(PermissionTargetTypeEnum targetType, Long targetId, PermissionSubjectTypeEnum subjectType, Map<String, String> subjectKeyConfigMap, UserContext userContext) {
        if (targetType == null || subjectType == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "目标类型/主体类型");
        }
        if (targetId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "目标ID");
        }
        sysSubjectPermissionService.remove(Wrappers.<SysSubjectPermission>lambdaQuery()
                .eq(SysSubjectPermission::getTargetType, targetType.getCode())
                .eq(SysSubjectPermission::getTargetId, targetId)
                .eq(SysSubjectPermission::getSubjectType, subjectType.getCode())
                .eq(SysSubjectPermission::getYn, YnEnum.Y.getKey()));
        if (subjectKeyConfigMap == null || subjectKeyConfigMap.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : subjectKeyConfigMap.entrySet()) {
            String skey = entry.getKey();
            if (StringUtils.isBlank(skey)) {
                continue;
            }
            SysSubjectPermission toSave = new SysSubjectPermission();
            toSave.setSubjectType(subjectType.getCode());
            toSave.setSubjectId(null);
            toSave.setSubjectKey(skey);
            toSave.setTargetType(targetType.getCode());
            toSave.setTargetId(targetId);
            toSave.setConfig(normalizeSubjectConfig(entry.getValue()));
            toSave.setCreatorId(userContext.getUserId());
            toSave.setCreator(userContext.getUserName());
            toSave.setYn(YnEnum.Y.getKey());
            sysSubjectPermissionService.save(toSave);
        }
    }

    private String normalizeSubjectConfig(String rawConfig) {
        if (StringUtils.isBlank(rawConfig)) {
            return rawConfig;
        }
        try {
            Map<String, Object> raw = JsonSerializeUtil.parseObject(rawConfig, new TypeReference<Map<String, Object>>() {});
            if (raw == null || raw.isEmpty()) {
                return rawConfig;
            }
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("rpm", raw.get("rpm"));
            normalized.put("rpd", raw.get("rpd"));
            return JsonSerializeUtil.toJSONString(normalized);
        } catch (Exception e) {
            return rawConfig;
        }
    }

    @Override
    public void addTarget(PermissionSubjectTypeEnum subjectType, Long subjectId, String subjectKey,
                          PermissionTargetTypeEnum targetType, Long targetId,
                          UserContext userContext) {
        if (subjectType == null || targetType == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "主体类型/目标类型");
        }
        if (targetId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "目标ID");
        }
        if (subjectId == null && StringUtils.isBlank(subjectKey)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "主体ID/key");
        }

        SysSubjectPermission exist = sysSubjectPermissionService.getOne(Wrappers.<SysSubjectPermission>lambdaQuery()
                .eq(SysSubjectPermission::getSubjectType, subjectType.getCode())
                .eq(subjectId != null, SysSubjectPermission::getSubjectId, subjectId)
                .eq(StringUtils.isNotBlank(subjectKey), SysSubjectPermission::getSubjectKey, subjectKey)
                .eq(SysSubjectPermission::getTargetType, targetType.getCode())
                .eq(SysSubjectPermission::getTargetId, targetId)
                .eq(SysSubjectPermission::getYn, YnEnum.Y.getKey()));
        if (exist != null) {
            return;
        }

        SysSubjectPermission toSave = new SysSubjectPermission();
        toSave.setSubjectType(subjectType.getCode());
        toSave.setSubjectId(subjectId);
        toSave.setSubjectKey(subjectKey);
        toSave.setTargetType(targetType.getCode());
        toSave.setTargetId(targetId);
        toSave.setCreatorId(userContext.getUserId());
        toSave.setCreator(userContext.getUserName());
        toSave.setYn(YnEnum.Y.getKey());
        sysSubjectPermissionService.save(toSave);
    }

    @Override
    public void removeTarget(PermissionSubjectTypeEnum subjectType, Long subjectId,
                             PermissionTargetTypeEnum targetType, Long targetId,
                             UserContext userContext) {
        if (subjectType == null || targetType == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "主体类型/目标类型");
        }
        if (subjectId == null || targetId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "主体ID/目标ID");
        }

        sysSubjectPermissionService.remove(Wrappers.<SysSubjectPermission>lambdaQuery()
                .eq(SysSubjectPermission::getSubjectType, subjectType.getCode())
                .eq(SysSubjectPermission::getSubjectId, subjectId)
                .eq(SysSubjectPermission::getTargetType, targetType.getCode())
                .eq(SysSubjectPermission::getTargetId, targetId));
    }

    @Override
    public void replaceTargetsBySubject(PermissionSubjectTypeEnum subjectType, Long subjectId,
                                         List<Long> roleIds, List<Long> groupIds,
                                         UserContext userContext) {
        if (subjectType == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "主体类型");
        }
        if (subjectId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "主体ID");
        }

        // 全量覆盖：先删除该主体下所有目标绑定
        sysSubjectPermissionService.remove(Wrappers.<SysSubjectPermission>lambdaQuery()
                .eq(SysSubjectPermission::getSubjectType, subjectType.getCode())
                .eq(SysSubjectPermission::getSubjectId, subjectId)
                .eq(SysSubjectPermission::getYn, YnEnum.Y.getKey()));

        if (CollectionUtils.isEmpty(roleIds) && CollectionUtils.isEmpty(groupIds)) {
            return;
        }
        Set<Long> uniqRoleIds = new HashSet<>();
        if (roleIds != null) {
            roleIds.stream().filter(Objects::nonNull).forEach(uniqRoleIds::add);
        }
        Set<Long> uniqGroupIds = new HashSet<>();
        if (groupIds != null) {
            groupIds.stream().filter(Objects::nonNull).forEach(uniqGroupIds::add);
        }
        for (Long roleId : uniqRoleIds) {
            addTarget(subjectType, subjectId, null, PermissionTargetTypeEnum.ROLE, roleId, userContext);
        }
        for (Long groupId : uniqGroupIds) {
            addTarget(subjectType, subjectId, null, PermissionTargetTypeEnum.GROUP, groupId, userContext);
        }
    }
}
