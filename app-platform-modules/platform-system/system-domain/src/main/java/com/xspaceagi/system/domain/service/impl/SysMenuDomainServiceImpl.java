package com.xspaceagi.system.domain.service.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.xspaceagi.system.spec.utils.CodeGeneratorUtil;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xspaceagi.system.domain.model.MenuNode;
import com.xspaceagi.system.domain.model.ResourceNode;
import com.xspaceagi.system.domain.model.SortIndex;
import com.xspaceagi.system.domain.service.SysMenuDomainService;
import com.xspaceagi.system.domain.service.SysResourceDomainService;
import com.xspaceagi.system.infra.dao.entity.SysGroupMenu;
import com.xspaceagi.system.infra.dao.entity.SysMenu;
import com.xspaceagi.system.infra.dao.entity.SysMenuResource;
import com.xspaceagi.system.infra.dao.entity.SysResource;
import com.xspaceagi.system.infra.dao.entity.SysRoleMenu;
import com.xspaceagi.system.infra.dao.service.SysGroupMenuService;
import com.xspaceagi.system.infra.dao.service.SysMenuResourceService;
import com.xspaceagi.system.infra.dao.service.SysMenuService;
import com.xspaceagi.system.infra.dao.service.SysRoleMenuService;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.enums.BindTypeEnum;
import com.xspaceagi.system.spec.enums.OpenTypeEnum;
import com.xspaceagi.system.spec.enums.SourceEnum;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.enums.YnEnum;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 菜单领域服务实现
 */
@Slf4j
@Service
public class SysMenuDomainServiceImpl implements SysMenuDomainService {

    @Resource
    private SysMenuService sysMenuService;
    @Resource
    private SysMenuResourceService sysMenuResourceService;
    @Resource
    private SysRoleMenuService sysRoleMenuService;
    @Resource
    private SysGroupMenuService sysGroupMenuService;
    @Resource
    private SysResourceDomainService sysResourceDomainService;

    private void normalizeMenu(SysMenu menu) {
        menu.setCode(StringUtils.trim(menu.getCode()));
        menu.setName(StringUtils.trim(menu.getName()));
        menu.setDescription(StringUtils.trim(menu.getDescription()));
        menu.setPath(StringUtils.trim(menu.getPath()));

        if (StringUtils.isNotBlank(menu.getCode()) && !menu.getCode().matches("^[a-zA-Z][a-zA-Z0-9_]*$")) {
            throw new IllegalArgumentException("Code may only contain letters, digits and underscores, and must start with a letter");
        }   
        if (StringUtils.length(menu.getCode()) > 100) {
            throw new IllegalArgumentException("Code length cannot exceed 100");
        }
        if (StringUtils.length(menu.getName()) > 50) {
            throw new IllegalArgumentException("Name length cannot exceed 50");
        }
        if (StringUtils.length(menu.getDescription()) > 500) {
            throw new IllegalArgumentException("Description length cannot exceed 500");
        }
        if (StringUtils.length(menu.getPath()) > 500) {
            throw new IllegalArgumentException("Path length cannot exceed 500");
        }
        if (menu.getOpenType() != null && OpenTypeEnum.isInValid(menu.getOpenType())) {
            throw new IllegalArgumentException("Invalid open mode parameter");
        }
    }

    @Override
    public void addMenu(SysMenu menu, MenuNode menuNode, Integer source, UserContext userContext) {
        if (StringUtils.isBlank(menu.getName())) {
            throw new IllegalArgumentException("Name cannot be empty");
        }

        // 如果编码为空，根据名称自动生成编码
        if (StringUtils.isBlank(menu.getCode())) {
            String generatedCode = CodeGeneratorUtil.generateUniqueCodeFromName(
                menu.getName(),
                "menu_",
                code -> queryMenuByCode(code) != null
            );
            menu.setCode(generatedCode);
        }

        normalizeMenu(menu);
        
        // 禁止使用 root 作为编码（不区分大小写），直接返回不入库
        if ("root".equalsIgnoreCase(menu.getCode())) {
            return;
        }

        SysMenu exists = queryMenuByCode(menu.getCode());
        if (exists != null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemMenuCodeAlreadyExists);
        }
        if (menu.getParentId() != null && menu.getParentId() != 0) {
            if (menu.getParentId() < 0) {
                throw new IllegalArgumentException("Invalid parent menu ID");
            } else {
                SysMenu parent = queryMenuById(menu.getParentId());
                if (parent == null) {
                    throw new IllegalArgumentException("Parent menu does not exist");
                }
                if (menu.getParentId().equals(menu.getId())) {
                    throw new IllegalArgumentException("Parent node cannot be itself [id:" + menu.getId() + "]");
                }
            }
        }

        if (menu.getSource() == null) {
            menu.setSource(SourceEnum.CUSTOM.getCode());
        }
        if (menu.getParentId() == null) {
            menu.setParentId(0L);
        }
        if (menu.getSortIndex() == null) {
            menu.setSortIndex(0);
        }
        if (menu.getStatus() == null) {
            menu.setStatus(YesOrNoEnum.Y.getKey());
        }
        menu.setTenantId(userContext.getTenantId());
        menu.setCreatorId(userContext.getUserId());
        menu.setCreator(userContext.getUserName());
        menu.setYn(YnEnum.Y.getKey());
        sysMenuService.save(menu);

        Long menuId = menu.getId();
        if (menuId != null && menuNode != null && CollectionUtils.isNotEmpty(menuNode.getResourceTree())) {
            menuNode.setId(menuId);
            bindResource(menuNode, userContext);
        }

    }

    @Override
    public void updateMenu(SysMenu menu, MenuNode menuNode, Integer source, UserContext userContext) {
        if (menu.getId() == null) {
            throw new IllegalArgumentException("ID cannot be empty");
        }
        normalizeMenu(menu);

        SysMenu exist = queryMenuById(menu.getId());
        if (exist == null) {
            throw new IllegalArgumentException("Menu does not exist");
        }
        if (SourceEnum.SYSTEM.getCode().equals(exist.getSource())) {
            if (menu.getCode() != null && !menu.getCode().equals(exist.getCode())) {
                throw new IllegalArgumentException("Built-in menu code cannot be changed");
            }
        }
        if (menu.getParentId() != null && menu.getParentId() != 0) {
            if (menu.getParentId() < 0) {
                throw new IllegalArgumentException("Invalid parent ID");
            } else {
                SysMenu parent = queryMenuById(menu.getParentId());
                if (parent == null) {
                    throw new IllegalArgumentException("Parent menu does not exist");
                }
                if (menu.getParentId().equals(menu.getId())) {
                    throw new IllegalArgumentException("Parent node cannot be itself [id:" + menu.getId() + "]");
                }
            }
        }

        menu.setModifierId(userContext.getUserId());
        menu.setModifier(userContext.getUserName());
        sysMenuService.updateById(menu);

        Long menuId = menu.getId();
        if (menuNode != null) {
            menuNode.setId(menuId);
            bindResource(menuNode, userContext);
        }
    }

    @Override
    public void deleteMenu(Long menuId, UserContext userContext) {
        SysMenu root = queryMenuById(menuId);
        if (root == null) {
            throw new IllegalArgumentException("Menu does not exist");
        }
        SysMenu exist = queryMenuById(menuId);
        if (exist == null) {
            throw new IllegalArgumentException("Menu does not exist");
        }
        if (SourceEnum.SYSTEM.getCode().equals(exist.getSource())) {
            //throw new IllegalArgumentException("Built-in menu cannot be deleted");
        }

        // 收集以该菜单为根的整棵子树的所有菜单ID（含自身）
        Set<Long> menuIds = collectSubTreeMenuIds(menuId, userContext.getTenantId());

        if (CollectionUtils.isEmpty(menuIds)) {
            return;
        }
        // 1. 删除菜单-资源关联
        sysMenuResourceService.remove(Wrappers.<SysMenuResource>lambdaQuery().in(SysMenuResource::getMenuId, menuIds));
        // 2. 删除菜单-角色关联
        sysRoleMenuService.remove(Wrappers.<SysRoleMenu>lambdaQuery().in(SysRoleMenu::getMenuId, menuIds));
        // 3. 删除菜单-用户组关联
        sysGroupMenuService.remove(Wrappers.<SysGroupMenu>lambdaQuery().in(SysGroupMenu::getMenuId, menuIds));
        // 4. 删除菜单树中所有菜单
        sysMenuService.remove(Wrappers.<SysMenu>lambdaQuery().in(SysMenu::getId, menuIds));
    }

    /**
     * 收集以 rootId 为根的菜单子树中所有菜单ID（含根）
     */
    private Set<Long> collectSubTreeMenuIds(Long rootId, Long tenantId) {
        List<SysMenu> allMenus = sysMenuService.list(Wrappers.<SysMenu>lambdaQuery()
                .eq(SysMenu::getTenantId, tenantId)
                .eq(SysMenu::getYn, YnEnum.Y.getKey()));
        Map<Long, List<SysMenu>> childrenMap = new HashMap<>();
        if (CollectionUtils.isNotEmpty(allMenus)) {
            for (SysMenu m : allMenus) {
                Long parentId = m.getParentId();
                if (parentId != null && parentId != 0L) {
                    childrenMap.computeIfAbsent(parentId, k -> new ArrayList<>()).add(m);
                }
            }
        }
        Set<Long> out = new HashSet<>();
        collectSubTreeIds(rootId, childrenMap, out);
        return out;
    }

    private void collectSubTreeIds(Long id, Map<Long, List<SysMenu>> childrenMap, Set<Long> out) {
        out.add(id);
        for (SysMenu c : childrenMap.getOrDefault(id, List.of())) {
            collectSubTreeIds(c.getId(), childrenMap, out);
        }
    }

    @Override
    public SysMenu queryMenuById(Long menuId) {
        LambdaQueryWrapper<SysMenu> wrapper = Wrappers.<SysMenu>lambdaQuery()
                .eq(SysMenu::getId, menuId)
                .eq(SysMenu::getYn, YnEnum.Y.getKey());
        return sysMenuService.getOne(wrapper);
    }

    @Override
    public SysMenu queryMenuByCode(String code) {
        LambdaQueryWrapper<SysMenu> wrapper = Wrappers.<SysMenu>lambdaQuery()
                .eq(SysMenu::getCode, code)
                .eq(SysMenu::getYn, YnEnum.Y.getKey());
        return sysMenuService.getOne(wrapper);
    }

    @Override
    public List<SysMenu> queryMenuList(SysMenu menu) {
        LambdaQueryWrapper<SysMenu> wrapper = Wrappers.<SysMenu>lambdaQuery()
                .eq(SysMenu::getYn, YnEnum.Y.getKey())
                .orderByAsc(SysMenu::getSortIndex);
        if (menu != null) {
            wrapper.eq(StringUtils.isNotBlank(menu.getCode()), SysMenu::getCode, menu.getCode())
                    .like(StringUtils.isNotBlank(menu.getName()), SysMenu::getName, menu.getName())
                    .eq(menu.getSource() != null, SysMenu::getSource, menu.getSource())
                    .eq(menu.getOpenType() != null, SysMenu::getOpenType, menu.getOpenType())
                    .eq(menu.getParentId() != null, SysMenu::getParentId, menu.getParentId())
                    .eq(menu.getStatus() != null, SysMenu::getStatus, menu.getStatus());
        }
        return sysMenuService.list(wrapper);
    }

    @Override
    public List<SysMenu> queryMenuByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return new ArrayList<>();
        }
        return sysMenuService.listByIds(ids);
    }


    @Override
    public List<SysMenuResource> queryResourceListByMenuId(Long menuId) {
        LambdaQueryWrapper<SysMenuResource> wrapper = Wrappers.<SysMenuResource>lambdaQuery()
                .eq(SysMenuResource::getMenuId, menuId)
                .eq(SysMenuResource::getYn, YnEnum.Y.getKey());
        return sysMenuResourceService.list(wrapper);
    }

    @Override
    public List<SysMenuResource> queryResourceListByMenuIds(java.util.Collection<Long> menuIds) {
        if (menuIds == null || menuIds.isEmpty()) {
            return new ArrayList<>();
        }
        LambdaQueryWrapper<SysMenuResource> wrapper = Wrappers.<SysMenuResource>lambdaQuery()
                .in(SysMenuResource::getMenuId, menuIds)
                .eq(SysMenuResource::getYn, YnEnum.Y.getKey());
        return sysMenuResourceService.list(wrapper);
    }

    @Override
    public void bindResource(MenuNode menuNode, UserContext userContext) {
        if (menuNode == null || menuNode.getId() == null) {
            throw new IllegalArgumentException("Menu ID cannot be empty");
        }

        Long menuId = menuNode.getId();
        // 根节点 menuId=0 是合法的，不需要校验是否存在

        log.info("开始绑定菜单资源: menuId={}, resourceTree={}", menuId,
                menuNode.getResourceTree() != null ? menuNode.getResourceTree().size() : 0);

        // 物理删除原绑定关系
        LambdaUpdateWrapper<SysMenuResource> deleteWrapper = Wrappers.<SysMenuResource>lambdaUpdate().eq(SysMenuResource::getMenuId, menuId);
        sysMenuResourceService.remove(deleteWrapper);

        // 扁平化资源列表
        List<ResourceNode> resourceList = menuNode.getFlattenResourceList();
        log.info("扁平化后的资源列表大小: {}", resourceList != null ? resourceList.size() : 0);
        if (CollectionUtils.isEmpty(resourceList)) {
            log.warn("菜单 {} 的资源列表为空，跳过绑定", menuId);
            return;
        }

        // 如果存在绑定类型为 ALL 的资源，需要过滤掉其子资源
        // 1. 查询所有资源，构建父子关系映射
        List<SysResource> allResources = sysResourceDomainService.queryResourceList(null);
        Map<Long, SysResource> resourceMap = new HashMap<>();
        Map<Long, List<SysResource>> resourceChildrenMap = new HashMap<>();
        if (CollectionUtils.isNotEmpty(allResources)) {
            for (SysResource resource : allResources) {
                resourceMap.put(resource.getId(), resource);
                Long parentId = resource.getParentId();
                if (parentId != null && parentId != 0L) {
                    resourceChildrenMap.computeIfAbsent(parentId, k -> new ArrayList<>()).add(resource);
                }
            }
        }

        // 2. 验证前端传入的数据
        for (ResourceNode resourceNode : resourceList) {
            Long resourceId = resourceNode.getId();
            if (resourceId == null) {
                throw new IllegalArgumentException("Resource ID cannot be empty");
            }
            
            // 根节点 resourceId=0 是合法的，不需要校验是否存在
            // 验证资源是否存在（跳过根节点 id=0）
            if (resourceId != 0L && !resourceMap.containsKey(resourceId)) {
                throw new IllegalArgumentException("Resource ID [" + resourceId + "] does not exist");
            }
            
            // 验证绑定类型是否有效
            Integer resourceBindType = resourceNode.getResourceBindType();
            if (resourceBindType != null && BindTypeEnum.isInValid(resourceBindType)) {
                throw new IllegalArgumentException("For resource ID [" + resourceId + "], bind type [" + resourceBindType + "] is invalid; must be 0 (NONE), 1 (ALL), or 2 (PART)");
            }
        }

        // 3. 找出所有绑定类型为 ALL 的资源ID，并收集其所有子资源ID
        Set<Long> allBindResourceIds = resourceList.stream()
                .filter(r -> r.getId() != null && BindTypeEnum.ALL.getCode().equals(r.getResourceBindType()))
                .map(ResourceNode::getId)
                .collect(Collectors.toSet());

        Set<Long> childrenResourceIdsToExclude = new HashSet<>();
        for (Long allBindResourceId : allBindResourceIds) {
            collectChildrenResourceIds(allBindResourceId, resourceChildrenMap, childrenResourceIdsToExclude);
        }

        // 4. 过滤掉子资源，只保留父资源（绑定类型为 ALL 的资源本身）；resourceBindType=0 或 null 的不入库
        List<SysMenuResource> menuResourceList = resourceList.stream()
                .filter(resourceNode -> {
                    Long resourceId = resourceNode.getId();
                    Integer resourceBindType = resourceNode.getResourceBindType();
                    // 如果是绑定类型为 ALL 的资源的子资源，则过滤掉
                    if (resourceId == null || childrenResourceIdsToExclude.contains(resourceId)) {
                        return false;
                    }
                    // resourceBindType=0(NONE) 或 null 的不存数据库
                    if (resourceBindType == null || BindTypeEnum.NONE.getCode().equals(resourceBindType)) {
                        return false;
                    }
                    return true;
                })
                .map(resourceNode -> {
                    SysMenuResource menuResource = new SysMenuResource();
                    menuResource.setMenuId(menuId);
                    menuResource.setResourceId(resourceNode.getId());
                    menuResource.setResourceBindType(resourceNode.getResourceBindType());

                    menuResource.setTenantId(userContext.getTenantId());
                    menuResource.setCreatorId(userContext.getUserId());
                    menuResource.setCreator(userContext.getUserName());
                    menuResource.setYn(YnEnum.Y.getKey());
                    return menuResource;
                })
                .collect(Collectors.toList());

        log.info("菜单 {} 过滤后的资源列表大小: {}, 需要排除的子资源数量: {}",
                menuId, menuResourceList.size(), childrenResourceIdsToExclude.size());

        if (!CollectionUtils.isEmpty(menuResourceList)) {
            log.info("菜单 {} 准备保存 {} 条资源绑定记录", menuId, menuResourceList.size());
            sysMenuResourceService.saveBatch(menuResourceList);
            log.info("菜单 {} 成功保存 {} 条资源绑定记录", menuId, menuResourceList.size());
        } else {
            log.warn("菜单 {} 过滤后的资源列表为空，未保存任何数据", menuId);
        }
    }

    @Override
    public boolean batchUpdateMenuSort(List<SortIndex> sortIndexList, UserContext userContext) {
        if (CollectionUtils.isEmpty(sortIndexList)) {
            return false;
        }
        boolean hasUpdateParent = false;

        List<Long> parentIdsToValidate = sortIndexList.stream().map(SortIndex::getParentId).filter(pid -> pid != null && pid != 0).toList();

        Set<Long> existingParentIds = new HashSet<>();
        if (!parentIdsToValidate.isEmpty()) {
            List<SysMenu> parents = sysMenuService.listByIds(parentIdsToValidate);
            if (CollectionUtils.isNotEmpty(parents)) {
                existingParentIds = parents.stream().map(SysMenu::getId).collect(Collectors.toSet());
            }
        }
        for (SortIndex item : sortIndexList) {
            if (item == null || item.getId() == null) {
                throw new IllegalArgumentException("Menu ID cannot be empty");
            }
            SysMenu updateMenu = new SysMenu();
            updateMenu.setId(item.getId());
            boolean hasUpdate = false;
            if (item.getParentId() != null) {
                if (item.getParentId() < 0) {
                    throw new IllegalArgumentException("Invalid parent ID: menuId=" + item.getId());
                }
                if (item.getParentId() != 0 && !existingParentIds.contains(item.getParentId())) {
                    throw new IllegalArgumentException("Parent node does not exist [parentId:" + item.getParentId() + "]");
                }
                if (item.getParentId().equals(item.getId())) {
                    throw new IllegalArgumentException("Parent node cannot be itself [id:" + item.getId() + "]");
                }
                updateMenu.setParentId(item.getParentId());
                hasUpdate = true;
                hasUpdateParent = true;
            }
            if (item.getSortIndex() != null) {
                updateMenu.setSortIndex(item.getSortIndex());
                hasUpdate = true;
            }
            if (hasUpdate) {
                updateMenu.setModifierId(userContext.getUserId());
                updateMenu.setModifier(userContext.getUserName());
                sysMenuService.updateById(updateMenu);
            }
        }
        return hasUpdateParent;
    }

    /**
     * 递归收集某个资源节点下的所有子资源ID
     */
    private void collectChildrenResourceIds(Long resourceId,
                                           Map<Long, List<SysResource>> resourceChildrenMap,
                                           Set<Long> idSet) {
        List<SysResource> children = resourceChildrenMap.get(resourceId);
        if (CollectionUtils.isEmpty(children)) {
            return;
        }
        for (SysResource child : children) {
            if (child.getId() == null) {
                continue;
            }
            if (idSet.add(child.getId())) {
                collectChildrenResourceIds(child.getId(), resourceChildrenMap, idSet);
            }
        }
    }
}


