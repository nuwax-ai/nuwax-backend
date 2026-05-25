package com.xspaceagi.system.application.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xspaceagi.system.application.dto.permission.export.*;
import com.xspaceagi.system.application.service.PermissionDiffService;
import com.xspaceagi.system.spec.constants.PermissionSyncConstants;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.jackson.JsonSerializeUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 权限配置差异比对：对比两个版本的 permission-{version}.json
 */
@Slf4j
@Service
public class PermissionDiffServiceImpl implements PermissionDiffService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    @Override
    public Map<String, Object> generateDiff(String fromVersion, String toVersion) {
        PermissionExportDto from = loadFromClasspath(fromVersion);
        if (from == null) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(),
                    "未找到版本 " + fromVersion + " 的权限 JSON：" + PermissionSyncConstants.buildClasspathPath(fromVersion));
        }
        PermissionExportDto to = loadFromClasspath(toVersion);
        if (to == null) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(),
                    "未找到版本 " + toVersion + " 的权限 JSON：" + PermissionSyncConstants.buildClasspathPath(toVersion));
        }

        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("fromVersion", fromVersion);
        diff.put("toVersion", toVersion);
        diff.put("resources", diffByCode(from.getResources(), to.getResources(), ResourceExportDto::getCode));
        diff.put("menus", diffByCode(from.getMenus(), to.getMenus(), MenuExportDto::getCode));
        diff.put("roles", diffByCode(from.getRoles(), to.getRoles(), RoleExportDto::getCode));
        diff.put("groups", diffByCode(from.getGroups(), to.getGroups(), GroupExportDto::getCode));
        diff.put("menuResources", diffByKey(from.getMenuResources(), to.getMenuResources(),
                mr -> mr.getMenuCode() + "|" + mr.getResourceCode()));
        diff.put("roleMenus", diffByKey(from.getRoleMenus(), to.getRoleMenus(),
                rm -> rm.getRoleCode() + "|" + rm.getMenuCode()));
        diff.put("groupMenus", diffByKey(from.getGroupMenus(), to.getGroupMenus(),
                gm -> gm.getGroupCode() + "|" + gm.getMenuCode()));
        diff.put("dataPermissions", diffByKey(from.getDataPermissions(), to.getDataPermissions(),
                dp -> dp.getTargetType() + "|" + dp.getTargetCode()));
        return diff;
    }

    private <T> Map<String, Object> diffByCode(List<T> fromList, List<T> toList, Function<T, String> codeExtractor) {
        return diffByKey(fromList, toList, codeExtractor);
    }

    private <T> Map<String, Object> diffByKey(List<T> fromList, List<T> toList, Function<T, String> keyExtractor) {
        Map<String, T> fromMap = indexByKey(fromList, keyExtractor);
        Map<String, T> toMap = indexByKey(toList, keyExtractor);

        List<Map<String, Object>> added = new ArrayList<>();
        List<Map<String, Object>> removed = new ArrayList<>();
        List<Map<String, Object>> modified = new ArrayList<>();

        for (Map.Entry<String, T> entry : toMap.entrySet()) {
            String key = entry.getKey();
            T toItem = entry.getValue();
            T fromItem = fromMap.get(key);
            if (fromItem == null) {
                added.add(toMap(toItem));
            } else if (!jsonEquals(fromItem, toItem)) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("key", displayKey(key, toItem));
                change.put("old", toMap(fromItem));
                change.put("new", toMap(toItem));
                modified.add(change);
            }
        }
        for (Map.Entry<String, T> entry : fromMap.entrySet()) {
            if (!toMap.containsKey(entry.getKey())) {
                removed.add(toMap(entry.getValue()));
            }
        }

        Map<String, Object> section = new LinkedHashMap<>();
        section.put("added", added);
        section.put("removed", removed);
        section.put("modified", modified);
        return section;
    }

    private <T> Map<String, T> indexByKey(List<T> list, Function<T, String> keyExtractor) {
        if (CollectionUtils.isEmpty(list)) {
            return Collections.emptyMap();
        }
        return list.stream().collect(Collectors.toMap(keyExtractor, Function.identity(), (a, b) -> a, LinkedHashMap::new));
    }

    /**
     * modified 条目的 key 字段：code 类实体用 code，关联类用复合 key 中第一段（如 roleCode）
     */
    private <T> String displayKey(String compositeKey, T item) {
        if (item instanceof ResourceExportDto dto) {
            return dto.getCode();
        }
        if (item instanceof MenuExportDto dto) {
            return dto.getCode();
        }
        if (item instanceof RoleExportDto dto) {
            return dto.getCode();
        }
        if (item instanceof GroupExportDto dto) {
            return dto.getCode();
        }
        return compositeKey;
    }

    private boolean jsonEquals(Object a, Object b) {
        return Objects.equals(JsonSerializeUtil.toJSONString(a), JsonSerializeUtil.toJSONString(b));
    }

    private Map<String, Object> toMap(Object obj) {
        return JsonSerializeUtil.parseObject(JsonSerializeUtil.toJSONString(obj), MAP_TYPE);
    }

    private PermissionExportDto loadFromClasspath(String version) {
        String path = PermissionSyncConstants.buildClasspathPath(version);
        try {
            ClassPathResource resource = new ClassPathResource(path);
            if (!resource.exists()) {
                return null;
            }
            String json = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return JsonSerializeUtil.parseObject(json, PermissionExportDto.class);
        } catch (IOException e) {
            log.warn("读取权限 JSON 失败：{}", path, e);
            return null;
        }
    }
}
