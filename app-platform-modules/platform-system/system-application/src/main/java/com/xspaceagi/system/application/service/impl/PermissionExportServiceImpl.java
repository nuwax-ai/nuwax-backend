package com.xspaceagi.system.application.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.xspaceagi.system.application.dto.permission.export.*;
import com.xspaceagi.system.application.service.PermissionExportService;
import com.xspaceagi.system.domain.model.ResourceNode;
import com.xspaceagi.system.infra.dao.entity.*;
import com.xspaceagi.system.infra.dao.service.*;
import com.xspaceagi.system.spec.enums.PermissionTargetTypeEnum;
import com.xspaceagi.system.spec.enums.SourceEnum;
import com.xspaceagi.system.spec.enums.YnEnum;
import com.xspaceagi.system.spec.jackson.JsonSerializeUtil;
import jakarta.annotation.Resource;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 权限数据导出服务实现，导出主租户内置数据
 */
@Service
public class PermissionExportServiceImpl implements PermissionExportService {

    @Resource
    private SysResourceService sysResourceService;
    @Resource
    private SysMenuService sysMenuService;
    @Resource
    private SysRoleService sysRoleService;
    @Resource
    private SysGroupService sysGroupService;
    @Resource
    private SysMenuResourceService sysMenuResourceService;
    @Resource
    private SysRoleMenuService sysRoleMenuService;
    @Resource
    private SysGroupMenuService sysGroupMenuService;
    @Resource
    private SysDataPermissionService sysDataPermissionService;

    @Override
    public PermissionExportDto exportConfig(String version) {
        PermissionExportDto dto = new PermissionExportDto();
        dto.setVersion(version);

        var resourceList = sysResourceService.list(Wrappers.<SysResource>lambdaQuery()
                .eq(SysResource::getSource, SourceEnum.SYSTEM.getCode())
                .eq(SysResource::getYn, YnEnum.Y.getKey()));
        var menuList = sysMenuService.list(Wrappers.<SysMenu>lambdaQuery()
                .eq(SysMenu::getSource, SourceEnum.SYSTEM.getCode())
                .eq(SysMenu::getYn, YnEnum.Y.getKey()));
        var roleList = sysRoleService.list(Wrappers.<SysRole>lambdaQuery()
                .eq(SysRole::getSource, SourceEnum.SYSTEM.getCode())
                .eq(SysRole::getYn, YnEnum.Y.getKey()));
        var groupList = sysGroupService.list(Wrappers.<SysGroup>lambdaQuery()
                .eq(SysGroup::getSource, SourceEnum.SYSTEM.getCode())
                .eq(SysGroup::getYn, YnEnum.Y.getKey()));


        // List<Long> resourceIds = resourceList.stream().map(SysResource::getId).toList();
        List<Long> menuIds = menuList.stream().map(SysMenu::getId).toList();
        List<Long> roleIds = roleList.stream().map(SysRole::getId).toList();
        List<Long> groupIds = groupList.stream().map(SysGroup::getId).toList();

        Map<Long, String> resourceIdToCode = resourceList.stream()
                .collect(Collectors.toMap(SysResource::getId, SysResource::getCode, (a, b) -> a));
        Map<Long, String> menuIdToCode = menuList.stream()
                .collect(Collectors.toMap(SysMenu::getId, SysMenu::getCode, (a, b) -> a));

        // Resource
        for (SysResource e : resourceList) {
            ResourceExportDto r = new ResourceExportDto();
            r.setCode(e.getCode());
            r.setName(e.getName());
            r.setDescription(e.getDescription());
            r.setSource(e.getSource());
            r.setType(e.getType());
            r.setParentCode(e.getParentId() != null && e.getParentId() != 0
                    ? resourceIdToCode.getOrDefault(e.getParentId(), null) : null);
            r.setPath(e.getPath());
            r.setIcon(e.getIcon());
            r.setSortIndex(e.getSortIndex());
            r.setStatus(e.getStatus());
            dto.getResources().add(r);
        }
        sortByDepthAndSortIndex(dto.getResources(),
                ResourceExportDto::getCode,
                ResourceExportDto::getParentCode,
                ResourceExportDto::getSortIndex);

        // Menu
        for (SysMenu e : menuList) {
            MenuExportDto m = new MenuExportDto();
            m.setParentCode(e.getParentId() != null && e.getParentId() != 0
                    ? menuIdToCode.getOrDefault(e.getParentId(), null) : null);
            m.setCode(e.getCode());
            m.setName(e.getName());
            m.setDescription(e.getDescription());
            m.setSource(e.getSource());
            m.setPath(e.getPath());
            m.setOpenType(e.getOpenType());
            m.setIcon(e.getIcon());
            m.setSortIndex(e.getSortIndex());
            m.setStatus(e.getStatus());
            dto.getMenus().add(m);
        }
        sortByDepthAndSortIndex(dto.getMenus(),
                MenuExportDto::getCode,
                MenuExportDto::getParentCode,
                MenuExportDto::getSortIndex);

        // Role, Group
        for (SysRole e : roleList) {
            RoleExportDto r = new RoleExportDto();
            r.setCode(e.getCode());
            r.setName(e.getName());
            r.setDescription(e.getDescription());
            r.setSource(e.getSource());
            r.setStatus(e.getStatus());
            r.setSortIndex(e.getSortIndex());
            dto.getRoles().add(r);
        }
        for (SysGroup e : groupList) {
            GroupExportDto g = new GroupExportDto();
            g.setCode(e.getCode());
            g.setName(e.getName());
            g.setDescription(e.getDescription());
            g.setMaxUserCount(e.getMaxUserCount());
            g.setSource(e.getSource());
            g.setStatus(e.getStatus());
            g.setSortIndex(e.getSortIndex());
            dto.getGroups().add(g);
        }

        // MenuResource
        var menuResourceList = sysMenuResourceService.list(Wrappers.<SysMenuResource>lambdaQuery()
                .in(SysMenuResource::getMenuId, menuIds)
                .eq(SysMenuResource::getYn, YnEnum.Y.getKey()));
        for (SysMenuResource e : menuResourceList) {
            String menuCode = menuIdToCode.get(e.getMenuId());
            String resourceCode = resourceIdToCode.get(e.getResourceId());
            if (menuCode == null || resourceCode == null) {
                continue;
            }
            MenuResourceExportDto mr = new MenuResourceExportDto();
            mr.setMenuCode(menuCode);
            mr.setResourceCode(resourceCode);
            mr.setResourceBindType(e.getResourceBindType());
            dto.getMenuResources().add(mr);
        }

        // RoleMenu
        var roleMenuList = sysRoleMenuService.list(Wrappers.<SysRoleMenu>lambdaQuery()
                .in(SysRoleMenu::getRoleId, roleIds)
                .eq(SysRoleMenu::getYn, YnEnum.Y.getKey()));
        Map<Long, String> roleIdToCode = roleList.stream().collect(Collectors.toMap(SysRole::getId, SysRole::getCode, (a, b) -> a));
        for (SysRoleMenu e : roleMenuList) {
            String roleCode = roleIdToCode.get(e.getRoleId());
            String menuCode = menuIdToCode.get(e.getMenuId());
            if (roleCode == null || menuCode == null) {
                continue;
            }
            RoleMenuExportDto rm = new RoleMenuExportDto();
            rm.setRoleCode(roleCode);
            rm.setMenuCode(menuCode);
            rm.setMenuBindType(e.getMenuBindType());
            rm.setResourceTree(convertResourceTreeToCode(StringUtils.trimToEmpty(e.getResourceTreeJson()), resourceIdToCode));
            dto.getRoleMenus().add(rm);
        }

        // GroupMenu
        var groupMenuList = sysGroupMenuService.list(Wrappers.<SysGroupMenu>lambdaQuery()
                .in(SysGroupMenu::getGroupId, groupIds)
                .eq(SysGroupMenu::getYn, YnEnum.Y.getKey()));
        Map<Long, String> groupIdToCode = groupList.stream().collect(Collectors.toMap(SysGroup::getId, SysGroup::getCode, (a, b) -> a));
        for (SysGroupMenu e : groupMenuList) {
            String groupCode = groupIdToCode.get(e.getGroupId());
            String menuCode = menuIdToCode.get(e.getMenuId());
            if (groupCode == null || menuCode == null) {
                continue;
            }
            GroupMenuExportDto gm = new GroupMenuExportDto();
            gm.setGroupCode(groupCode);
            gm.setMenuCode(menuCode);
            gm.setMenuBindType(e.getMenuBindType());
            gm.setResourceTree(convertResourceTreeToCode(StringUtils.trimToEmpty(e.getResourceTreeJson()), resourceIdToCode));
            dto.getGroupMenus().add(gm);
        }

        // DataPermission
        var roleDpList = sysDataPermissionService.list(Wrappers.<SysDataPermission>lambdaQuery()
                .eq(SysDataPermission::getTargetType, PermissionTargetTypeEnum.ROLE.getCode())
                .in(SysDataPermission::getTargetId, roleIds)
                .eq(SysDataPermission::getYn, YnEnum.Y.getKey()));
        var groupDpList = sysDataPermissionService.list(Wrappers.<SysDataPermission>lambdaQuery()
                .eq(SysDataPermission::getTargetType, PermissionTargetTypeEnum.GROUP.getCode())
                .in(SysDataPermission::getTargetId, groupIds)
                .eq(SysDataPermission::getYn, YnEnum.Y.getKey()));

        if (CollectionUtils.isNotEmpty(roleDpList)) {
            for (SysDataPermission e : roleDpList) {
                String roleCode = roleIdToCode.get(e.getTargetId());
                if (roleCode == null) {
                    continue;
                }
                DataPermissionExportDto dp = buildDataPermission(e, roleCode);
                dto.getDataPermissions().add(dp);
            }
        }
        if (CollectionUtils.isNotEmpty(groupDpList)) {
            for (SysDataPermission e : groupDpList) {
                String groupCode = groupIdToCode.get(e.getTargetId());
                if (groupCode == null) {
                    continue;
                }
                DataPermissionExportDto dp = buildDataPermission(e, groupCode);
                dto.getDataPermissions().add(dp);
            }
        }
        return dto;
    }

    private DataPermissionExportDto buildDataPermission(SysDataPermission e, String targetCode) {
        DataPermissionExportDto dp = new DataPermissionExportDto();
        dp.setTargetType(e.getTargetType());
        dp.setTargetCode(targetCode);
        dp.setTokenLimit(e.getTokenLimit());
        dp.setMaxSpaceCount(e.getMaxSpaceCount());
        dp.setMaxAgentCount(e.getMaxAgentCount());
        dp.setMaxPageAppCount(e.getMaxPageAppCount());
        dp.setMaxKnowledgeCount(e.getMaxKnowledgeCount());
        dp.setKnowledgeStorageLimitGb(normalizeExportDecimal(e.getKnowledgeStorageLimitGb()));
        dp.setMaxDataTableCount(e.getMaxDataTableCount());
        dp.setMaxScheduledTaskCount(e.getMaxScheduledTaskCount());
        dp.setAgentComputerCpuCores(e.getAgentComputerCpuCores());
        dp.setAgentComputerMemoryGb(e.getAgentComputerMemoryGb());
        dp.setAgentComputerSwapGb(e.getAgentComputerSwapGb());
        dp.setAgentComputerStorageLimitGb(normalizeExportDecimal(e.getAgentComputerStorageLimitGb()));
        dp.setPageAppStorageLimitGb(normalizeExportDecimal(e.getPageAppStorageLimitGb()));
        dp.setAgentFileStorageDays(e.getAgentFileStorageDays());
        dp.setAgentDailyPromptLimit(e.getAgentDailyPromptLimit());
        dp.setPageDailyPromptLimit(e.getPageDailyPromptLimit());
        return dp;
    }

    /**
     * 导出时去掉 BigDecimal 末尾无意义的 0
     */
    private static BigDecimal normalizeExportDecimal(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.stripTrailingZeros();
    }

    /**
     * 按深度排序（父节点在前），同级按 sortIndex 排序
     */
    private <T> void sortByDepthAndSortIndex(List<T> list,
            Function<T, String> codeGetter,
            Function<T, String> parentCodeGetter,
            Function<T, Integer> sortIndexGetter) {
        if (CollectionUtils.isEmpty(list)) {
            return;
        }
        Map<String, T> codeMap = list.stream()
                .collect(Collectors.toMap(codeGetter, t -> t, (a, b) -> a));
        Map<String, Integer> depthCache = new HashMap<>();
        for (T item : list) {
            computeDepth(codeGetter.apply(item), codeMap, parentCodeGetter, depthCache);
        }
        list.sort(Comparator
                .comparingInt((T t) -> depthCache.getOrDefault(codeGetter.apply(t), 0))
                .thenComparing(t -> sortIndexGetter.apply(t) != null ? sortIndexGetter.apply(t) : 0));
    }

    private <T> int computeDepth(String code, Map<String, T> codeMap,
            Function<T, String> parentCodeGetter, Map<String, Integer> depthCache) {
        if (StringUtils.isBlank(code)) {
            return 0;
        }
        T item = codeMap.get(code);
        if (item == null) {
            return 0;
        }
        if (depthCache.containsKey(code)) {
            return depthCache.get(code);
        }
        String parentCode = parentCodeGetter.apply(item);
        if (StringUtils.isBlank(parentCode) || !codeMap.containsKey(parentCode)) {
            depthCache.put(code, 0);
            return 0;
        }
        int d = 1 + computeDepth(parentCode, codeMap, parentCodeGetter, depthCache);
        depthCache.put(code, d);
        return d;
    }

    private List<ResourceNodeExportDto> convertResourceTreeToCode(String json, Map<Long, String> resourceIdToCode) {
        if (StringUtils.isBlank(json)) {
            return Collections.emptyList();
        }
        try {
            List<ResourceNode> nodes = JsonSerializeUtil.parseObject(json, new TypeReference<List<ResourceNode>>() {
            });
            if (CollectionUtils.isEmpty(nodes)) {
                return Collections.emptyList();
            }
            return nodes.stream()
                    .map(n -> convertNodeToExport(n, resourceIdToCode))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private ResourceNodeExportDto convertNodeToExport(ResourceNode node, Map<Long, String> resourceIdToCode) {
        if (node == null || node.getId() == null) {
            return null;
        }
        String code = resourceIdToCode.get(node.getId());
        if (code == null) {
            return null;
        }
        ResourceNodeExportDto dto = new ResourceNodeExportDto();
        dto.setCode(code);
        dto.setResourceBindType(node.getResourceBindType());
        if (CollectionUtils.isNotEmpty(node.getChildren())) {
            dto.setChildren(node.getChildren().stream()
                    .map(n -> convertNodeToExport(n, resourceIdToCode))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList()));
        }
        return dto;
    }
}
