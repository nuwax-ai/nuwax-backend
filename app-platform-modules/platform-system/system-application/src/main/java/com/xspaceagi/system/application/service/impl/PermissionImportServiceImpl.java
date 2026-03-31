package com.xspaceagi.system.application.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xspaceagi.system.application.dto.permission.export.*;
import com.xspaceagi.system.application.service.PermissionImportService;
import com.xspaceagi.system.application.service.SysUserPermissionCacheService;
import com.xspaceagi.system.domain.model.ResourceNode;
import com.xspaceagi.system.infra.dao.entity.*;
import com.xspaceagi.system.infra.dao.service.*;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.constants.PermissionSyncConstants;
import com.xspaceagi.system.spec.enums.PermissionTargetTypeEnum;
import com.xspaceagi.system.spec.enums.SourceEnum;
import com.xspaceagi.system.spec.enums.YnEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.jackson.JsonSerializeUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 权限数据导入服务实现
 * 使用code防重：存在则更新，不存在则新增
 */
@Slf4j
@Service
public class PermissionImportServiceImpl implements PermissionImportService {

    private static final String CREATOR_SYSTEM = "system";

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
    @Resource
    private SysUserPermissionCacheService sysUserPermissionCacheService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void importToTenant(Tenant tenant, String version) {
        try {
            if (tenant == null || tenant.getId() == null) {
                throw new BizException("菜单权限导入失败,租户ID无效");
            }
            RequestContext.setThreadTenantId(tenant.getId());
            doImport(tenant, version);

            sysUserPermissionCacheService.clearCacheAllByTenant(tenant.getId());
        } finally {
            RequestContext.remove();
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void importDiffToTenant(Tenant tenant, String version) {
        try {
            if (tenant == null || tenant.getId() == null) {
                throw new BizException("菜单权限差异导入失败,租户ID无效");
            }
            RequestContext.setThreadTenantId(tenant.getId());
            doImportDiff(tenant, version);

            sysUserPermissionCacheService.clearCacheAllByTenant(tenant.getId());
        } finally {
            RequestContext.remove();
        }
    }

    private void doImport(Tenant tenant, String version) {
        Long tenantId = tenant.getId();
        PermissionExportDto dto = loadFromClasspath(version);
        if (dto == null) {
            log.warn("权限导入：未找到版本 {} 的JSON，跳过", version);
            return;
        }

        Map<String, Long> resourceCodeToId = new HashMap<>();
        Map<String, Long> menuCodeToId = new HashMap<>();
        Map<String, Long> roleCodeToId = new HashMap<>();
        Map<String, Long> groupCodeToId = new HashMap<>();

        // 1. Resource（按parentCode排序，先父后子）
        List<ResourceExportDto> sortedResources = sortResourcesByParent(dto.getResources());
        for (ResourceExportDto r : sortedResources) {
            SysResource entity = resolveOrCreateResource(tenantId, r, resourceCodeToId);
            if (entity != null) {
                resourceCodeToId.put(r.getCode(), entity.getId());
            }
        }

        // 2. Menu
        List<MenuExportDto> sortedMenus = sortMenusByParent(dto.getMenus());
        for (MenuExportDto m : sortedMenus) {
            SysMenu entity = resolveOrCreateMenu(tenantId, m, menuCodeToId);
            if (entity != null) {
                menuCodeToId.put(m.getCode(), entity.getId());
            }
        }

        // 3. Role
        for (RoleExportDto r : dto.getRoles()) {
            SysRole entity = resolveOrCreateRole(tenantId, r);
            if (entity != null) {
                roleCodeToId.put(r.getCode(), entity.getId());
            }
        }
        // 4. Group
        for (GroupExportDto g : dto.getGroups()) {
            SysGroup entity = resolveOrCreateGroup(tenantId, g);
            if (entity != null) {
                groupCodeToId.put(g.getCode(), entity.getId());
            }
        }

        // 5. MenuResource（防重：menuCode+resourceCode）
        for (MenuResourceExportDto mr : dto.getMenuResources()) {
            Long menuId = menuCodeToId.get(mr.getMenuCode());
            Long resourceId = resourceCodeToId.get(mr.getResourceCode());
            if (menuId == null || resourceId == null) continue;
            resolveOrCreateMenuResource(tenantId, menuId, resourceId, mr);
        }

        // 6. RoleMenu
        for (RoleMenuExportDto rm : dto.getRoleMenus()) {
            Long roleId = roleCodeToId.get(rm.getRoleCode());
            Long menuId = menuCodeToId.get(rm.getMenuCode());
            if (roleId == null || menuId == null) continue;
            String resourceTreeJson = buildResourceTreeJson(rm.getResourceTree(), resourceCodeToId);
            resolveOrCreateRoleMenu(tenantId, roleId, menuId, rm, resourceTreeJson);
        }

        // 7. GroupMenu
        for (GroupMenuExportDto gm : dto.getGroupMenus()) {
            Long groupId = groupCodeToId.get(gm.getGroupCode());
            Long menuId = menuCodeToId.get(gm.getMenuCode());
            if (groupId == null || menuId == null) continue;
            String resourceTreeJson = buildResourceTreeJson(gm.getResourceTree(), resourceCodeToId);
            resolveOrCreateGroupMenu(tenantId, groupId, menuId, gm, resourceTreeJson);
        }

        // 8. DataPermission
        for (DataPermissionExportDto dp : dto.getDataPermissions()) {
            Long targetId = null;
            if (Integer.valueOf(2).equals(dp.getTargetType())) { // ROLE
                targetId = roleCodeToId.get(dp.getTargetCode());
            } else if (Integer.valueOf(3).equals(dp.getTargetType())) { // GROUP
                targetId = groupCodeToId.get(dp.getTargetCode());
            }
            if (targetId == null) continue;
            resolveOrCreateDataPermission(tenantId, dp, targetId);
        }

        log.info("权限导入完成，租户ID：{}，版本：{}", tenantId, version);
    }

    /**
     * 基于差异 JSON 导入：只对差异中的新增 / 修改记录做 upsert，不处理删除
     */
    @SuppressWarnings("unchecked")
    private void doImportDiff(Tenant tenant, String version) {
        Long tenantId = tenant.getId();
        Map<String, Object> diff = loadDiffFromClasspath(version);
        if (diff == null) {
            log.warn("权限差异导入：未找到版本 {} 的差异 JSON，跳过", version);
            return;
        }

        // 预加载当前租户下资源的 code->id、code->parentId 映射，便于重建 resource_tree_json
        List<SysResource> tenantResources = sysResourceService.list(Wrappers.<SysResource>lambdaQuery()
                        .eq(SysResource::getTenantId, tenantId)
                        .eq(SysResource::getYn, YnEnum.Y.getKey()));
        Map<String, Long> resourceCodeToId = tenantResources.stream()
                .collect(Collectors.toMap(SysResource::getCode, SysResource::getId, (a, b) -> a));
        Map<String, Long> resourceCodeToParentId = tenantResources.stream()
                // HashMap 不允许 value 为 null；同时与正常绑定中 root parentId=0 的表现保持一致
                .collect(Collectors.toMap(
                        SysResource::getCode,
                        r -> r.getParentId() != null ? r.getParentId() : 0L,
                        (a, b) -> a));
        Map<String, Long> menuCodeToId = sysMenuService.list(Wrappers.<SysMenu>lambdaQuery()
                        .eq(SysMenu::getTenantId, tenantId)
                        .eq(SysMenu::getYn, YnEnum.Y.getKey()))
                .stream().collect(Collectors.toMap(SysMenu::getCode, SysMenu::getId, (a, b) -> a));
        Map<String, Long> roleCodeToId = sysRoleService.list(Wrappers.<SysRole>lambdaQuery()
                        .eq(SysRole::getTenantId, tenantId)
                        .eq(SysRole::getYn, YnEnum.Y.getKey()))
                .stream().collect(Collectors.toMap(SysRole::getCode, SysRole::getId, (a, b) -> a));
        Map<String, Long> groupCodeToId = sysGroupService.list(Wrappers.<SysGroup>lambdaQuery()
                        .eq(SysGroup::getTenantId, tenantId)
                        .eq(SysGroup::getYn, YnEnum.Y.getKey()))
                .stream().collect(Collectors.toMap(SysGroup::getCode, SysGroup::getId, (a, b) -> a));

        // 1. Resource
        Map<String, Object> resources = (Map<String, Object>) diff.get("resources");
        if (resources != null) {
            List<Map<String, Object>> added = (List<Map<String, Object>>) resources.get("added");
            if (CollectionUtils.isNotEmpty(added)) {
                for (Map<String, Object> m : added) {
                    ResourceExportDto r = JsonSerializeUtil.parseObject(JsonSerializeUtil.toJSONString(m), ResourceExportDto.class);
                    SysResource entity = resolveOrCreateResource(tenantId, r, resourceCodeToId);
                    if (entity != null) {
                        resourceCodeToId.put(r.getCode(), entity.getId());
                        resourceCodeToParentId.put(r.getCode(), entity.getParentId());
                    }
                }
            }
            List<Map<String, Object>> modified = (List<Map<String, Object>>) resources.get("modified");
            if (CollectionUtils.isNotEmpty(modified)) {
                for (Map<String, Object> item : modified) {
                    Map<String, Object> newVal = (Map<String, Object>) item.get("new");
                    if (newVal == null) {
                        continue;
                    }
                    ResourceExportDto r = JsonSerializeUtil.parseObject(JsonSerializeUtil.toJSONString(newVal), ResourceExportDto.class);
                    SysResource entity = resolveOrCreateResource(tenantId, r, resourceCodeToId);
                    if (entity != null) {
                        resourceCodeToId.put(r.getCode(), entity.getId());
                        resourceCodeToParentId.put(r.getCode(), entity.getParentId());
                    }
                }
            }
        }

        // 2. Menu
        Map<String, Object> menus = (Map<String, Object>) diff.get("menus");
        if (menus != null) {
            List<Map<String, Object>> added = (List<Map<String, Object>>) menus.get("added");
            if (CollectionUtils.isNotEmpty(added)) {
                for (Map<String, Object> m : added) {
                    MenuExportDto dto = JsonSerializeUtil.parseObject(JsonSerializeUtil.toJSONString(m), MenuExportDto.class);
                    SysMenu entity = resolveOrCreateMenu(tenantId, dto, menuCodeToId);
                    if (entity != null) {
                        menuCodeToId.put(dto.getCode(), entity.getId());
                    }
                }
            }
            List<Map<String, Object>> modified = (List<Map<String, Object>>) menus.get("modified");
            if (CollectionUtils.isNotEmpty(modified)) {
                for (Map<String, Object> item : modified) {
                    Map<String, Object> newVal = (Map<String, Object>) item.get("new");
                    if (newVal == null) {
                        continue;
                    }
                    MenuExportDto dto = JsonSerializeUtil.parseObject(JsonSerializeUtil.toJSONString(newVal), MenuExportDto.class);
                    SysMenu entity = resolveOrCreateMenu(tenantId, dto, menuCodeToId);
                    if (entity != null) {
                        menuCodeToId.put(dto.getCode(), entity.getId());
                    }
                }
            }
        }

        // 3. Role
        Map<String, Object> roles = (Map<String, Object>) diff.get("roles");
        if (roles != null) {
            List<Map<String, Object>> added = (List<Map<String, Object>>) roles.get("added");
            if (CollectionUtils.isNotEmpty(added)) {
                for (Map<String, Object> m : added) {
                    RoleExportDto dto = JsonSerializeUtil.parseObject(JsonSerializeUtil.toJSONString(m), RoleExportDto.class);
                    SysRole entity = resolveOrCreateRole(tenantId, dto);
                    if (entity != null) {
                        roleCodeToId.put(dto.getCode(), entity.getId());
                    }
                }
            }
            List<Map<String, Object>> modified = (List<Map<String, Object>>) roles.get("modified");
            if (CollectionUtils.isNotEmpty(modified)) {
                for (Map<String, Object> item : modified) {
                    Map<String, Object> newVal = (Map<String, Object>) item.get("new");
                    if (newVal == null) {
                        continue;
                    }
                    RoleExportDto dto = JsonSerializeUtil.parseObject(JsonSerializeUtil.toJSONString(newVal), RoleExportDto.class);
                    SysRole entity = resolveOrCreateRole(tenantId, dto);
                    if (entity != null) {
                        roleCodeToId.put(dto.getCode(), entity.getId());
                    }
                }
            }
        }

        // 4. Group
        Map<String, Object> groups = (Map<String, Object>) diff.get("groups");
        if (groups != null) {
            List<Map<String, Object>> added = (List<Map<String, Object>>) groups.get("added");
            if (CollectionUtils.isNotEmpty(added)) {
                for (Map<String, Object> m : added) {
                    GroupExportDto dto = JsonSerializeUtil.parseObject(JsonSerializeUtil.toJSONString(m), GroupExportDto.class);
                    SysGroup entity = resolveOrCreateGroup(tenantId, dto);
                    if (entity != null) {
                        groupCodeToId.put(dto.getCode(), entity.getId());
                    }
                }
            }
            List<Map<String, Object>> modified = (List<Map<String, Object>>) groups.get("modified");
            if (CollectionUtils.isNotEmpty(modified)) {
                for (Map<String, Object> item : modified) {
                    Map<String, Object> newVal = (Map<String, Object>) item.get("new");
                    if (newVal == null) {
                        continue;
                    }
                    GroupExportDto dto = JsonSerializeUtil.parseObject(JsonSerializeUtil.toJSONString(newVal), GroupExportDto.class);
                    SysGroup entity = resolveOrCreateGroup(tenantId, dto);
                    if (entity != null) {
                        groupCodeToId.put(dto.getCode(), entity.getId());
                    }
                }
            }
        }

        // 5. MenuResource
        Map<String, Object> menuResources = (Map<String, Object>) diff.get("menuResources");
        if (menuResources != null) {
            List<Map<String, Object>> added = (List<Map<String, Object>>) menuResources.get("added");
            if (CollectionUtils.isNotEmpty(added)) {
                for (Map<String, Object> m : added) {
                    MenuResourceExportDto dto = JsonSerializeUtil.parseObject(JsonSerializeUtil.toJSONString(m), MenuResourceExportDto.class);
                    Long menuId = menuCodeToId.get(dto.getMenuCode());
                    Long resourceId = resourceCodeToId.get(dto.getResourceCode());
                    if (menuId == null || resourceId == null) {
                        continue;
                    }
                    resolveOrCreateMenuResource(tenantId, menuId, resourceId, dto);
                }
            }
            List<Map<String, Object>> modified = (List<Map<String, Object>>) menuResources.get("modified");
            if (CollectionUtils.isNotEmpty(modified)) {
                for (Map<String, Object> item : modified) {
                    Map<String, Object> newVal = (Map<String, Object>) item.get("new");
                    if (newVal == null) {
                        continue;
                    }
                    MenuResourceExportDto dto = JsonSerializeUtil.parseObject(JsonSerializeUtil.toJSONString(newVal), MenuResourceExportDto.class);
                    Long menuId = menuCodeToId.get(dto.getMenuCode());
                    Long resourceId = resourceCodeToId.get(dto.getResourceCode());
                    if (menuId == null || resourceId == null) {
                        continue;
                    }
                    resolveOrCreateMenuResource(tenantId, menuId, resourceId, dto);
                }
            }
        }

        // 6. RoleMenu
        Map<String, Object> roleMenus = (Map<String, Object>) diff.get("roleMenus");
        if (roleMenus != null) {
            List<Map<String, Object>> added = (List<Map<String, Object>>) roleMenus.get("added");
            if (CollectionUtils.isNotEmpty(added)) {
                for (Map<String, Object> m : added) {
                    RoleMenuExportDto dto = JsonSerializeUtil.parseObject(JsonSerializeUtil.toJSONString(m), RoleMenuExportDto.class);
                    Long roleId = roleCodeToId.get(dto.getRoleCode());
                    Long menuId = menuCodeToId.get(dto.getMenuCode());
                    if (roleId == null || menuId == null) {
                        continue;
                    }
                    String resourceTreeJson = buildResourceTreeJson(dto.getResourceTree(), resourceCodeToId, resourceCodeToParentId);
                    resolveOrCreateRoleMenu(tenantId, roleId, menuId, dto, resourceTreeJson);
                }
            }
            List<Map<String, Object>> modified = (List<Map<String, Object>>) roleMenus.get("modified");
            if (CollectionUtils.isNotEmpty(modified)) {
                for (Map<String, Object> item : modified) {
                    Map<String, Object> newVal = (Map<String, Object>) item.get("new");
                    if (newVal == null) {
                        continue;
                    }
                    RoleMenuExportDto dto = JsonSerializeUtil.parseObject(JsonSerializeUtil.toJSONString(newVal), RoleMenuExportDto.class);
                    Long roleId = roleCodeToId.get(dto.getRoleCode());
                    Long menuId = menuCodeToId.get(dto.getMenuCode());
                    if (roleId == null || menuId == null) {
                        continue;
                    }
                    String resourceTreeJson = buildResourceTreeJson(dto.getResourceTree(), resourceCodeToId, resourceCodeToParentId);
                    resolveOrCreateRoleMenu(tenantId, roleId, menuId, dto, resourceTreeJson);
                }
            }
        }

        // 7. GroupMenu
        Map<String, Object> groupMenus = (Map<String, Object>) diff.get("groupMenus");
        if (groupMenus != null) {
            List<Map<String, Object>> added = (List<Map<String, Object>>) groupMenus.get("added");
            if (CollectionUtils.isNotEmpty(added)) {
                for (Map<String, Object> m : added) {
                    GroupMenuExportDto dto = JsonSerializeUtil.parseObject(JsonSerializeUtil.toJSONString(m), GroupMenuExportDto.class);
                    Long groupId = groupCodeToId.get(dto.getGroupCode());
                    Long menuId = menuCodeToId.get(dto.getMenuCode());
                    if (groupId == null || menuId == null) {
                        continue;
                    }
                    String resourceTreeJson = buildResourceTreeJson(dto.getResourceTree(), resourceCodeToId, resourceCodeToParentId);
                    resolveOrCreateGroupMenu(tenantId, groupId, menuId, dto, resourceTreeJson);
                }
            }
            List<Map<String, Object>> modified = (List<Map<String, Object>>) groupMenus.get("modified");
            if (CollectionUtils.isNotEmpty(modified)) {
                for (Map<String, Object> item : modified) {
                    Map<String, Object> newVal = (Map<String, Object>) item.get("new");
                    if (newVal == null) {
                        continue;
                    }
                    GroupMenuExportDto dto = JsonSerializeUtil.parseObject(JsonSerializeUtil.toJSONString(newVal), GroupMenuExportDto.class);
                    Long groupId = groupCodeToId.get(dto.getGroupCode());
                    Long menuId = menuCodeToId.get(dto.getMenuCode());
                    if (groupId == null || menuId == null) {
                        continue;
                    }
                    String resourceTreeJson = buildResourceTreeJson(dto.getResourceTree(), resourceCodeToId, resourceCodeToParentId);
                    resolveOrCreateGroupMenu(tenantId, groupId, menuId, dto, resourceTreeJson);
                }
            }
        }

        // 8. DataPermission
        Map<String, Object> dataPermissions = (Map<String, Object>) diff.get("dataPermissions");
        if (dataPermissions != null) {
            List<Map<String, Object>> added = (List<Map<String, Object>>) dataPermissions.get("added");
            if (CollectionUtils.isNotEmpty(added)) {
                for (Map<String, Object> m : added) {
                    DataPermissionExportDto dto = JsonSerializeUtil.parseObject(JsonSerializeUtil.toJSONString(m), DataPermissionExportDto.class);
                    Long targetId = resolveTargetId(dto, roleCodeToId, groupCodeToId);
                    if (targetId == null) {
                        continue;
                    }
                    resolveOrCreateDataPermission(tenantId, dto, targetId);
                }
            }
            List<Map<String, Object>> modified = (List<Map<String, Object>>) dataPermissions.get("modified");
            if (CollectionUtils.isNotEmpty(modified)) {
                for (Map<String, Object> item : modified) {
                    Map<String, Object> newVal = (Map<String, Object>) item.get("new");
                    if (newVal == null) {
                        continue;
                    }
                    DataPermissionExportDto dto = JsonSerializeUtil.parseObject(JsonSerializeUtil.toJSONString(newVal), DataPermissionExportDto.class);
                    Long targetId = resolveTargetId(dto, roleCodeToId, groupCodeToId);
                    if (targetId == null) {
                        continue;
                    }
                    resolveOrCreateDataPermission(tenantId, dto, targetId);
                }
            }
        }

        log.info("权限差异导入完成，租户ID：{}，版本：{}", tenantId, version);
    }

    private Long resolveTargetId(DataPermissionExportDto dto,
            Map<String, Long> roleCodeToId,
            Map<String, Long> groupCodeToId) {
        if (dto == null || dto.getTargetType() == null) {
            return null;
        }

        if (PermissionTargetTypeEnum.ROLE.getCode().equals(dto.getTargetType())) {
            return roleCodeToId.get(dto.getTargetCode());
        } else if (PermissionTargetTypeEnum.GROUP.getCode().equals(dto.getTargetType())) {
            return groupCodeToId.get(dto.getTargetCode());
        }
        return null;
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
            log.warn("读取权限JSON失败：{}", path, e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadDiffFromClasspath(String version) {
        String path = "permission/permission-" + version + "-diff.json";
        try {
            ClassPathResource resource = new ClassPathResource(path);
            if (!resource.exists()) {
                return null;
            }
            String json = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return JsonSerializeUtil.parseObject(json, Map.class);
        } catch (IOException e) {
            log.warn("读取权限差异 JSON 失败：{}", path, e);
            return null;
        }
    }

    private List<ResourceExportDto> sortResourcesByParent(List<ResourceExportDto> list) {
        Map<String, ResourceExportDto> byCode = list.stream().collect(Collectors.toMap(ResourceExportDto::getCode, x -> x, (a, b) -> a));
        List<ResourceExportDto> result = new ArrayList<>();
        Set<String> added = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        for (ResourceExportDto r : list) {
            if (StringUtils.isBlank(r.getParentCode()) || !byCode.containsKey(r.getParentCode())) {
                queue.add(r.getCode());
            }
        }
        while (!queue.isEmpty()) {
            String code = queue.poll();
            if (added.contains(code)) continue;
            ResourceExportDto r = byCode.get(code);
            if (r == null) continue;
            result.add(r);
            added.add(code);
            for (ResourceExportDto child : list) {
                if (code.equals(child.getParentCode()) && !added.contains(child.getCode())) {
                    queue.add(child.getCode());
                }
            }
        }
        for (ResourceExportDto r : list) {
            if (!added.contains(r.getCode())) result.add(r);
        }
        return result;
    }

    private List<MenuExportDto> sortMenusByParent(List<MenuExportDto> list) {
        Map<String, MenuExportDto> byCode = list.stream().collect(Collectors.toMap(MenuExportDto::getCode, x -> x, (a, b) -> a));
        List<MenuExportDto> result = new ArrayList<>();
        Set<String> added = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        for (MenuExportDto m : list) {
            if (StringUtils.isBlank(m.getParentCode()) || !byCode.containsKey(m.getParentCode())) {
                queue.add(m.getCode());
            }
        }
        while (!queue.isEmpty()) {
            String code = queue.poll();
            if (added.contains(code)) {
                continue;
            }
            MenuExportDto m = byCode.get(code);
            if (m == null) {
                continue;
            }
            result.add(m);
            added.add(code);
            for (MenuExportDto child : list) {
                if (code.equals(child.getParentCode()) && !added.contains(child.getCode())) {
                    queue.add(child.getCode());
                }
            }
        }
        for (MenuExportDto m : list) {
            if (!added.contains(m.getCode())) {
                result.add(m);
            }
        }
        return result;
    }

    private SysResource resolveOrCreateResource(Long tenantId, ResourceExportDto r, Map<String, Long> codeToId) {
        SysResource existing = sysResourceService.getOne(Wrappers.<SysResource>lambdaQuery()
                .eq(SysResource::getTenantId, tenantId).eq(SysResource::getCode, r.getCode()).eq(SysResource::getYn, YnEnum.Y.getKey()));
        Long parentId;
        if (StringUtils.isNotBlank(r.getParentCode())) {
            parentId = codeToId.get(r.getParentCode());
            if (parentId == null) {
                return null;
            }
        } else {
            parentId = 0L;
        }

        SysResource entity = existing != null ? existing : new SysResource();
        entity.setTenantId(tenantId);
        entity.setCode(r.getCode());
        entity.setName(r.getName());
        entity.setDescription(r.getDescription());
        entity.setSource(SourceEnum.SYSTEM.getCode());
        entity.setType(r.getType());
        entity.setParentId(parentId);
        entity.setPath(r.getPath());
        entity.setIcon(r.getIcon());
        entity.setSortIndex(r.getSortIndex());
        entity.setStatus(r.getStatus());
        entity.setCreatorId(0L);
        entity.setCreator(CREATOR_SYSTEM);
        entity.setModifierId(null);
        entity.setModifier(null);
        entity.setYn(YnEnum.Y.getKey());

        if (existing != null) {
            sysResourceService.updateById(entity);
        } else {
            sysResourceService.save(entity);
        }
        return entity;
    }

    private SysMenu resolveOrCreateMenu(Long tenantId, MenuExportDto m, Map<String, Long> codeToId) {
        SysMenu existing = sysMenuService.getOne(Wrappers.<SysMenu>lambdaQuery()
                .eq(SysMenu::getTenantId, tenantId).eq(SysMenu::getCode, m.getCode()).eq(SysMenu::getYn, YnEnum.Y.getKey()));
        Long parentId;
        if (StringUtils.isNotBlank(m.getParentCode())) {
            parentId = codeToId.get(m.getParentCode());
            if (parentId == null) {
                return null;
            }
        } else {
            parentId = 0L;
        }

        SysMenu entity = existing != null ? existing : new SysMenu();
        entity.setTenantId(tenantId);
        entity.setParentId(parentId);
        entity.setCode(m.getCode());
        entity.setName(m.getName());
        entity.setDescription(m.getDescription());
        entity.setSource(SourceEnum.SYSTEM.getCode());
        entity.setPath(m.getPath());
        entity.setOpenType(m.getOpenType());
        entity.setIcon(m.getIcon());
        entity.setSortIndex(m.getSortIndex());
        entity.setStatus(m.getStatus());
        entity.setCreatorId(0L);
        entity.setCreator(CREATOR_SYSTEM);
        entity.setModifierId(null);
        entity.setModifier(null);
        entity.setYn(YnEnum.Y.getKey());

        if (existing != null) {
            sysMenuService.updateById(entity);
        } else {
            sysMenuService.save(entity);
        }
        return entity;
    }

    private SysRole resolveOrCreateRole(Long tenantId, RoleExportDto r) {
        SysRole existing = sysRoleService.getOne(Wrappers.<SysRole>lambdaQuery()
                .eq(SysRole::getTenantId, tenantId).eq(SysRole::getCode, r.getCode()).eq(SysRole::getYn, YnEnum.Y.getKey()));
        SysRole entity = existing != null ? existing : new SysRole();
        entity.setTenantId(tenantId);
        entity.setCode(r.getCode());
        entity.setName(r.getName());
        entity.setDescription(r.getDescription());
        entity.setSource(SourceEnum.SYSTEM.getCode());
        entity.setStatus(r.getStatus());
        entity.setSortIndex(r.getSortIndex());
        entity.setCreatorId(0L);
        entity.setCreator(CREATOR_SYSTEM);
        entity.setModifierId(null);
        entity.setModifier(null);
        entity.setYn(YnEnum.Y.getKey());

        if (existing != null) {
            sysRoleService.updateById(entity);
        } else {
            sysRoleService.save(entity);
        }
        return entity;
    }

    private SysGroup resolveOrCreateGroup(Long tenantId, GroupExportDto g) {
        SysGroup existing = sysGroupService.getOne(Wrappers.<SysGroup>lambdaQuery()
                .eq(SysGroup::getTenantId, tenantId).eq(SysGroup::getCode, g.getCode()).eq(SysGroup::getYn, YnEnum.Y.getKey()));
        SysGroup entity = existing != null ? existing : new SysGroup();
        entity.setTenantId(tenantId);
        entity.setCode(g.getCode());
        entity.setName(g.getName());
        entity.setDescription(g.getDescription());
        entity.setMaxUserCount(g.getMaxUserCount());
        entity.setSource(SourceEnum.SYSTEM.getCode());
        entity.setStatus(g.getStatus());
        entity.setSortIndex(g.getSortIndex());
        entity.setCreatorId(0L);
        entity.setCreator(CREATOR_SYSTEM);
        entity.setModifierId(null);
        entity.setModifier(null);
        entity.setYn(YnEnum.Y.getKey());

        if (existing != null) {
            sysGroupService.updateById(entity);
        } else {
            sysGroupService.save(entity);
        }
        return entity;
    }

    private void resolveOrCreateMenuResource(Long tenantId, Long menuId, Long resourceId, MenuResourceExportDto mr) {
        SysMenuResource existing = sysMenuResourceService.getOne(Wrappers.<SysMenuResource>lambdaQuery()
                .eq(SysMenuResource::getTenantId, tenantId).eq(SysMenuResource::getMenuId, menuId)
                .eq(SysMenuResource::getResourceId, resourceId).eq(SysMenuResource::getYn, YnEnum.Y.getKey()));
        SysMenuResource entity = existing != null ? existing : new SysMenuResource();
        entity.setTenantId(tenantId);
        entity.setMenuId(menuId);
        entity.setResourceId(resourceId);
        entity.setResourceBindType(mr.getResourceBindType() != null ? mr.getResourceBindType() : 0);
        entity.setCreatorId(0L);
        entity.setCreator(CREATOR_SYSTEM);
        entity.setModifierId(null);
        entity.setModifier(null);
        entity.setYn(YnEnum.Y.getKey());

        if (existing != null) {
            sysMenuResourceService.updateById(entity);
        } else {
            sysMenuResourceService.save(entity);
        }
    }

    private void resolveOrCreateRoleMenu(Long tenantId, Long roleId, Long menuId, RoleMenuExportDto rm, String resourceTreeJson) {
        SysRoleMenu existing = sysRoleMenuService.getOne(Wrappers.<SysRoleMenu>lambdaQuery()
                .eq(SysRoleMenu::getTenantId, tenantId).eq(SysRoleMenu::getRoleId, roleId)
                .eq(SysRoleMenu::getMenuId, menuId).eq(SysRoleMenu::getYn, YnEnum.Y.getKey()));
        SysRoleMenu entity = existing != null ? existing : new SysRoleMenu();
        entity.setTenantId(tenantId);
        entity.setRoleId(roleId);
        entity.setMenuId(menuId);
        entity.setMenuBindType(rm.getMenuBindType() != null ? rm.getMenuBindType() : 0);
        entity.setResourceTreeJson(resourceTreeJson);
        entity.setCreatorId(0L);
        entity.setCreator(CREATOR_SYSTEM);
        entity.setModifierId(null);
        entity.setModifier(null);
        entity.setYn(YnEnum.Y.getKey());

        if (existing != null) {
            sysRoleMenuService.updateById(entity);
        } else {
            sysRoleMenuService.save(entity);
        }
    }

    private void resolveOrCreateGroupMenu(Long tenantId, Long groupId, Long menuId, GroupMenuExportDto gm, String resourceTreeJson) {
        SysGroupMenu existing = sysGroupMenuService.getOne(Wrappers.<SysGroupMenu>lambdaQuery()
                .eq(SysGroupMenu::getTenantId, tenantId).eq(SysGroupMenu::getGroupId, groupId)
                .eq(SysGroupMenu::getMenuId, menuId).eq(SysGroupMenu::getYn, YnEnum.Y.getKey()));
        SysGroupMenu entity = existing != null ? existing : new SysGroupMenu();
        entity.setTenantId(tenantId);
        entity.setGroupId(groupId);
        entity.setMenuId(menuId);
        entity.setMenuBindType(gm.getMenuBindType() != null ? gm.getMenuBindType() : 0);
        entity.setResourceTreeJson(resourceTreeJson);
        entity.setCreatorId(0L);
        entity.setCreator(CREATOR_SYSTEM);
        entity.setModifierId(null);
        entity.setModifier(null);
        entity.setYn(YnEnum.Y.getKey());

        if (existing != null) {
            sysGroupMenuService.updateById(entity);
        } else {
            sysGroupMenuService.save(entity);
        }
    }

    private void resolveOrCreateDataPermission(Long tenantId, DataPermissionExportDto dp, Long targetId) {
        SysDataPermission existing = sysDataPermissionService.getOne(Wrappers.<SysDataPermission>lambdaQuery()
                .eq(SysDataPermission::getTenantId, tenantId).eq(SysDataPermission::getTargetType, dp.getTargetType())
                .eq(SysDataPermission::getTargetId, targetId).eq(SysDataPermission::getYn, YnEnum.Y.getKey()));
        SysDataPermission entity = existing != null ? existing : new SysDataPermission();
        entity.setTenantId(tenantId);
        entity.setTargetType(dp.getTargetType());
        entity.setTargetId(targetId);
        entity.setTokenLimit(dp.getTokenLimit());
        entity.setMaxSpaceCount(dp.getMaxSpaceCount());
        entity.setMaxAgentCount(dp.getMaxAgentCount());
        entity.setMaxPageAppCount(dp.getMaxPageAppCount());
        entity.setMaxKnowledgeCount(dp.getMaxKnowledgeCount());
        entity.setKnowledgeStorageLimitGb(dp.getKnowledgeStorageLimitGb());
        entity.setMaxDataTableCount(dp.getMaxDataTableCount());
        entity.setMaxScheduledTaskCount(dp.getMaxScheduledTaskCount());
        entity.setAgentComputerCpuCores(dp.getAgentComputerCpuCores());
        entity.setAgentComputerMemoryGb(dp.getAgentComputerMemoryGb());
        entity.setAgentComputerSwapGb(dp.getAgentComputerSwapGb());
        entity.setAgentFileStorageDays(dp.getAgentFileStorageDays());
        entity.setAgentDailyPromptLimit(dp.getAgentDailyPromptLimit());
        entity.setPageDailyPromptLimit(dp.getPageDailyPromptLimit());
        entity.setCreatorId(0L);
        entity.setCreator(CREATOR_SYSTEM);
        entity.setModifierId(null);
        entity.setModifier(null);
        entity.setYn(YnEnum.Y.getKey());

        if (existing != null) {
            sysDataPermissionService.updateById(entity);
        } else {
            sysDataPermissionService.save(entity);
        }
    }

    private String buildResourceTreeJson(List<ResourceNodeExportDto> tree, Map<String, Long> resourceCodeToId) {
        if (CollectionUtils.isEmpty(tree)) {
            return null;
        }
        return buildResourceTreeJson(tree, resourceCodeToId, null);
    }

    private String buildResourceTreeJson(List<ResourceNodeExportDto> tree,
                                          Map<String, Long> resourceCodeToId,
                                          Map<String, Long> resourceCodeToParentId) {
        if (CollectionUtils.isEmpty(tree)) {
            return null;
        }
        List<ResourceNode> nodes = tree.stream()
                .map(n -> convertExportToNode(n, resourceCodeToId, resourceCodeToParentId))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (nodes.isEmpty()) {
            return null;
        }
        return JsonSerializeUtil.toJSONString(nodes);
    }

    private ResourceNode convertExportToNode(ResourceNodeExportDto dto,
                                            Map<String, Long> resourceCodeToId,
                                            Map<String, Long> resourceCodeToParentId) {
        if (dto == null || StringUtils.isBlank(dto.getCode())) {
            return null;
        }
        Long id = resourceCodeToId.get(dto.getCode());
        if (id == null) {
            return null;
        }
        ResourceNode node = new ResourceNode();
        node.setId(id);
        node.setCode(dto.getCode());
        if (resourceCodeToParentId != null) {
            node.setParentId(resourceCodeToParentId.get(dto.getCode()));
        }
        node.setResourceBindType(dto.getResourceBindType());
        if (CollectionUtils.isNotEmpty(dto.getChildren())) {
            node.setChildren(dto.getChildren().stream()
                    .map(c -> convertExportToNode(c, resourceCodeToId, resourceCodeToParentId))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList()));
        }
        return node;
    }
}
