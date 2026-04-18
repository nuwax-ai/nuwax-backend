package com.xspaceagi.system.domain.service.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import com.xspaceagi.system.domain.model.MenuNode;
import com.xspaceagi.system.domain.model.ResourceNode;
import com.xspaceagi.system.domain.service.SysFlattenService;
import com.xspaceagi.system.domain.service.SysMenuDomainService;
import com.xspaceagi.system.infra.dao.entity.SysMenu;
import com.xspaceagi.system.infra.dao.entity.SysMenuResource;
import com.xspaceagi.system.infra.dao.entity.SysResource;
import com.xspaceagi.system.spec.enums.BindTypeEnum;
import com.xspaceagi.system.spec.enums.OpenTypeEnum;
import com.xspaceagi.system.spec.enums.SourceEnum;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.enums.YnEnum;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;

import jakarta.annotation.Resource;

/**
 * 角色/用户组绑定菜单与资源时的共用逻辑。
 * 提供：构建菜单/资源映射、菜单绑定类型传播、资源树过滤与重建等。
 */
@Component
public class MenuBindResourceHelper {

    @Resource
    private SysMenuDomainService sysMenuDomainService;
    @Resource
    private SysFlattenService sysFlattenService;

    /**
     * 菜单与资源的映射容器
     */
    public static class MenuResourceMaps {
        public final Map<Long, SysMenu> menuMap;
        public final Map<Long, List<SysMenu>> menuChildrenMap;
        public final Map<Long, SysResource> resourceMap;
        public final Map<Long, List<SysResource>> resourceChildrenMap;

        public MenuResourceMaps(Map<Long, SysMenu> menuMap, Map<Long, List<SysMenu>> menuChildrenMap,
                               Map<Long, SysResource> resourceMap, Map<Long, List<SysResource>> resourceChildrenMap) {
            this.menuMap = menuMap;
            this.menuChildrenMap = menuChildrenMap;
            this.resourceMap = resourceMap;
            this.resourceChildrenMap = resourceChildrenMap;
        }
    }

    /**
     * 根据全量菜单和资源构建映射（menuMap、menuChildrenMap、resourceMap、resourceChildrenMap）
     */
    public MenuResourceMaps buildMenuAndResourceMaps(List<SysMenu> allMenus, List<SysResource> allResources) {
        Map<Long, SysMenu> menuMap = allMenus.stream().collect(Collectors.toMap(SysMenu::getId, m -> m));
        // parentId=0 表示 root 的子节点，需纳入 menuChildrenMap 以便 root 为 ALL 时能正确排除其所有子节点
        Map<Long, List<SysMenu>> menuChildrenMap = new HashMap<>();
        for (SysMenu menu : allMenus) {
            Long parentId = menu.getParentId();
            if (parentId != null) {
                menuChildrenMap.computeIfAbsent(parentId, k -> new ArrayList<>()).add(menu);
            }
        }
        Map<Long, SysResource> resourceMap = allResources.stream()
                .collect(Collectors.toMap(SysResource::getId, r -> r));
        Map<Long, List<SysResource>> resourceChildrenMap = new HashMap<>();
        for (SysResource resource : allResources) {
            Long parentId = resource.getParentId();
            if (parentId != null && parentId != 0L) {
                resourceChildrenMap.computeIfAbsent(parentId, k -> new ArrayList<>()).add(resource);
            }
        }
        return new MenuResourceMaps(menuMap, menuChildrenMap, resourceMap, resourceChildrenMap);
    }

    /**
     * 根据前端传入的菜单列表构建 menuBindType 映射，并对 ALL/NONE 进行递归传播（单遍遍历）
     */
    public Map<Long, Integer> buildAndPropagateMenuBindTypeMap(List<MenuNode> menuBindResourceList,
                                                              Map<Long, List<SysMenu>> menuChildrenMap) {
        Map<Long, Integer> menuBindTypeMap = new HashMap<>();
        Set<Long> processedMenuIds = new HashSet<>();
        for (MenuNode menuNode : menuBindResourceList) {
            if (menuNode.getId() == null) {
                continue;
            }
            Long menuId = menuNode.getId();
            Integer bindType = menuNode.getMenuBindType();
            menuBindTypeMap.put(menuId, bindType);
            if (BindTypeEnum.ALL.getCode().equals(bindType)) {
                propagateMenuBindType(menuId, menuChildrenMap, menuBindTypeMap,
                        processedMenuIds, BindTypeEnum.ALL.getCode());
            } else if (BindTypeEnum.NONE.getCode().equals(bindType)) {
                propagateMenuBindType(menuId, menuChildrenMap, menuBindTypeMap,
                        processedMenuIds, BindTypeEnum.NONE.getCode());
            }
        }
        return menuBindTypeMap;
    }

    /**
     * 收集某菜单的全部子节点 ID（不包含自身）。
     * 用于绑定入库时：menuBindType=1(ALL) 的节点只需存父节点，子节点不重复入库。
     */
    public static Set<Long> collectAllDescendantMenuIds(Long menuId, Map<Long, List<SysMenu>> menuChildrenMap) {
        Set<Long> descendantIds = new HashSet<>();
        if (menuId == null || menuChildrenMap == null) {
            return descendantIds;
        }
        List<SysMenu> children = menuChildrenMap.get(menuId);
        if (CollectionUtils.isEmpty(children)) {
            return descendantIds;
        }
        for (SysMenu child : children) {
            if (child.getId() != null) {
                descendantIds.add(child.getId());
                descendantIds.addAll(collectAllDescendantMenuIds(child.getId(), menuChildrenMap));
            }
        }
        return descendantIds;
    }

    /**
     * 递归传播菜单绑定类型
     */
    public static void propagateMenuBindType(Long menuId, Map<Long, List<SysMenu>> menuChildrenMap,
                                             Map<Long, Integer> menuBindTypeMap, Set<Long> processedMenuIds,
                                             Integer bindType) {
        if (processedMenuIds.contains(menuId)) {
            return;
        }
        processedMenuIds.add(menuId);
        List<SysMenu> children = menuChildrenMap.get(menuId);
        if (CollectionUtils.isEmpty(children)) {
            return;
        }
        for (SysMenu child : children) {
            if (child.getId() == null) {
                continue;
            }
            menuBindTypeMap.put(child.getId(), bindType);
            propagateMenuBindType(child.getId(), menuChildrenMap, menuBindTypeMap, processedMenuIds, bindType);
        }
    }

    /**
     * 处理菜单的资源树绑定逻辑：校验范围、过滤 ALL 子资源、过滤 resourceBindType=0/null，并重建树。
     * 支持前端仅传叶子节点（扁平列表）：会自动向上构建父节点并推断父节点的 resourceBindType。
     * 父节点绑定类型推断规则：子节点全为1→父为1，全为0→父为0，否则父为2(PART)。
     */
    public List<ResourceNode> processResourceTreeForMenu(Long menuId, List<ResourceNode> resourceTree,
                                                         Map<Long, SysResource> resourceMap,
                                                         Map<Long, List<SysResource>> resourceChildrenMap) {
        if (CollectionUtils.isEmpty(resourceTree)) {
            return null;
        }
        List<SysMenuResource> menuResources = sysMenuDomainService.queryResourceListByMenuId(menuId);
        Set<Long> menuAuthorizedResourceIds = new HashSet<>();
        if (CollectionUtils.isNotEmpty(menuResources)) {
            for (SysMenuResource menuResource : menuResources) {
                if (menuResource.getResourceId() != null) {
                    menuAuthorizedResourceIds.add(menuResource.getResourceId());
                    if (BindTypeEnum.ALL.getCode().equals(menuResource.getResourceBindType())) {
                        collectChildrenResourceIds(menuResource.getResourceId(), resourceChildrenMap, menuAuthorizedResourceIds);
                    }
                }
            }
        }
        List<ResourceNode> flattenResourceList = sysFlattenService.flattenResourceTree(resourceTree);
        // 向上构建父节点并推断父节点的 resourceBindType（前端仅传叶子节点时）
        flattenResourceList = expandWithAncestorNodes(flattenResourceList, resourceMap, resourceChildrenMap);
        for (ResourceNode resourceNode : flattenResourceList) {
            Long resourceId = resourceNode.getId();
            if (resourceId == null) {
                continue;
            }
            if (resourceId != 0L && !resourceMap.containsKey(resourceId)) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemMenuBindResourceNotFound, resourceId);
            }
            if (!menuAuthorizedResourceIds.contains(resourceId)) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemMenuBindResourceOutOfScope, resourceId, menuId);
            }
            Integer resourceBindType = resourceNode.getResourceBindType();
            if (resourceBindType != null && BindTypeEnum.isInValid(resourceBindType)) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemMenuBindResourceBindTypeInvalid, resourceId, resourceBindType);
            }
        }
        Set<Long> allBindResourceIds = flattenResourceList.stream()
                .filter(r -> r.getId() != null && BindTypeEnum.ALL.getCode().equals(r.getResourceBindType()))
                .map(ResourceNode::getId)
                .collect(Collectors.toSet());
        Set<Long> childrenResourceIdsToExclude = new HashSet<>();
        for (Long allBindResourceId : allBindResourceIds) {
            collectChildrenResourceIds(allBindResourceId, resourceChildrenMap, childrenResourceIdsToExclude);
        }
        List<ResourceNode> filteredResourceList = flattenResourceList.stream()
                .filter(resourceNode -> {
                    Long resourceId = resourceNode.getId();
                    Integer resourceBindType = resourceNode.getResourceBindType();
                    if (resourceId == null || childrenResourceIdsToExclude.contains(resourceId)) {
                        return false;
                    }
                    if (resourceBindType == null || BindTypeEnum.NONE.getCode().equals(resourceBindType)) {
                        return false;
                    }
                    return true;
                })
                .collect(Collectors.toList());
        return rebuildResourceTree(filteredResourceList, resourceMap, resourceChildrenMap);
    }

    /**
     * 向上构建父节点并推断父节点的 resourceBindType。
     * 当前端仅传叶子节点时，根据 parentId 链补齐祖先节点；
     * 父节点 resourceBindType 推断规则：直接子节点全为1→父为1，全为0→父为0，否则父为2(PART)。
     * 需考虑祖先节点的全部子节点：未传入的子节点视为 NONE(0)。
     */
    private List<ResourceNode> expandWithAncestorNodes(List<ResourceNode> flattenResourceList,
                                                       Map<Long, SysResource> resourceMap,
                                                       Map<Long, List<SysResource>> resourceChildrenMap) {
        if (CollectionUtils.isEmpty(flattenResourceList) || resourceMap == null) {
            return flattenResourceList;
        }
        Map<Long, ResourceNode> nodeMap = flattenResourceList.stream()
                .filter(n -> n.getId() != null)
                .collect(Collectors.toMap(ResourceNode::getId, n -> n, (a, b) -> a));
        Set<Long> ancestorIds = new HashSet<>();
        for (ResourceNode node : flattenResourceList) {
            if (node.getId() == null) {
                continue;
            }
            SysResource resource = resourceMap.get(node.getId());
            if (resource == null) {
                continue;
            }
            Long parentId = resource.getParentId();
            while (parentId != null && parentId != 0L && resourceMap.containsKey(parentId)) {
                if (!nodeMap.containsKey(parentId)) {
                    ancestorIds.add(parentId);
                }
                SysResource parentResource = resourceMap.get(parentId);
                parentId = parentResource != null ? parentResource.getParentId() : null;
            }
        }
        if (ancestorIds.isEmpty()) {
            return flattenResourceList;
        }
        List<Long> sortedAncestorIds = sortAncestorIdsByDepth(ancestorIds, resourceMap);
        for (Long ancestorId : sortedAncestorIds) {
            SysResource resource = resourceMap.get(ancestorId);
            if (resource == null) {
                continue;
            }
            // 祖先节点的全部直接子节点：传入的用其 resourceBindType，未传入的视为 NONE(0)
            List<Integer> allChildrenBindTypes = collectAllChildrenBindTypes(ancestorId, nodeMap, resourceMap,
                    resourceChildrenMap);
            Integer inferredBindType = inferResourceBindTypeFromChildrenBindTypes(allChildrenBindTypes);
            ResourceNode ancestorNode = new ResourceNode();
            ancestorNode.setId(ancestorId);
            ancestorNode.setParentId(resource.getParentId());
            ancestorNode.setResourceBindType(inferredBindType);
            ancestorNode.setCode(resource.getCode());
            nodeMap.put(ancestorId, ancestorNode);
        }
        return new ArrayList<>(nodeMap.values());
    }

    /**
     * 按深度升序排序祖先ID（叶子级祖先先处理，用于自底向上推断 resourceBindType）
     */
    private static List<Long> sortAncestorIdsByDepth(Set<Long> ancestorIds, Map<Long, SysResource> resourceMap) {
        Map<Long, Integer> depthMap = new HashMap<>();
        for (Long id : ancestorIds) {
            int depth = 0;
            Long currentId = id;
            while (currentId != null && currentId != 0L) {
                SysResource r = resourceMap.get(currentId);
                currentId = r != null ? r.getParentId() : null;
                depth++;
            }
            depthMap.put(id, depth);
        }
        // 按深度降序，先处理叶子级祖先（如513），再处理根级祖先（如512）
        return ancestorIds.stream()
                .sorted(Comparator.comparing(depthMap::get).reversed())
                .collect(Collectors.toList());
    }

    /**
     * 收集祖先节点全部直接子节点的 resourceBindType：
     * 在 nodeMap（传入的节点）中的用其真实值，未传入的视为 NONE(0)。
     */
    private static List<Integer> collectAllChildrenBindTypes(Long ancestorId,
                                                            Map<Long, ResourceNode> nodeMap,
                                                            Map<Long, SysResource> resourceMap,
                                                            Map<Long, List<SysResource>> resourceChildrenMap) {
        List<SysResource> allChildren = resourceChildrenMap != null ? resourceChildrenMap.get(ancestorId) : null;
        if (CollectionUtils.isEmpty(allChildren)) {
            return new ArrayList<>();
        }
        List<Integer> bindTypes = new ArrayList<>(allChildren.size());
        for (SysResource child : allChildren) {
            if (child.getId() == null) {
                continue;
            }
            ResourceNode nodeInSelection = nodeMap.get(child.getId());
            if (nodeInSelection != null) {
                Integer bt = nodeInSelection.getResourceBindType();
                bindTypes.add(bt != null ? bt : BindTypeEnum.NONE.getCode());
            } else {
                bindTypes.add(BindTypeEnum.NONE.getCode());
            }
        }
        return bindTypes;
    }

    /**
     * 根据直接子节点的 resourceBindType 推断父节点的 resourceBindType。
     * 全为1→1(ALL)，全为0→0(NONE)，否则→2(PART)。
     */
    private static Integer inferResourceBindTypeFromChildrenBindTypes(List<Integer> childrenBindTypes) {
        if (CollectionUtils.isEmpty(childrenBindTypes)) {
            return BindTypeEnum.NONE.getCode();
        }
        boolean allAll = childrenBindTypes.stream().allMatch(b -> BindTypeEnum.ALL.getCode().equals(b));
        if (allAll) {
            return BindTypeEnum.ALL.getCode();
        }
        boolean allNone = childrenBindTypes.stream().allMatch(b -> BindTypeEnum.NONE.getCode().equals(b));
        if (allNone) {
            return BindTypeEnum.NONE.getCode();
        }
        return BindTypeEnum.PART.getCode();
    }

    /**
     * 根据扁平列表重新构建资源树（按 resourceId 去重，避免同一节点重复挂到父节点或根下）
     */
    public static List<ResourceNode> rebuildResourceTree(List<ResourceNode> flatList,
                                                         Map<Long, SysResource> resourceMap,
                                                         Map<Long, List<SysResource>> resourceChildrenMap) {
        if (CollectionUtils.isEmpty(flatList)) {
            return null;
        }
        // 按 resourceId 去重，同一 resourceId 只保留一个节点，避免存入重复数据
        Map<Long, ResourceNode> nodeMap = new HashMap<>();
        for (ResourceNode node : flatList) {
            if (node.getId() != null) {
                nodeMap.put(node.getId(), node);
            }
        }
        // 清空所有节点的 children，避免沿用入参树上的旧子节点导致重复（重建时只通过下方挂载逻辑设置子节点）
        for (ResourceNode node : nodeMap.values()) {
            node.setChildren(null);
        }
        List<ResourceNode> rootNodes = new ArrayList<>();
        // 遍历去重后的 nodeMap.values()，确保每个节点只挂载一次
        for (ResourceNode node : nodeMap.values()) {
            if (node.getId() == null) {
                continue;
            }
            if (node.getId() == 0L) {
                rootNodes.add(node);
                continue;
            }
            SysResource resource = resourceMap.get(node.getId());
            if (resource == null) {
                continue;
            }
            Long parentId = resource.getParentId();
            if (parentId == null || parentId == 0L || !nodeMap.containsKey(parentId)) {
                rootNodes.add(node);
            } else {
                ResourceNode parent = nodeMap.get(parentId);
                if (parent.getChildren() == null) {
                    parent.setChildren(new ArrayList<>());
                }
                parent.getChildren().add(node);
            }
        }
        return rootNodes.isEmpty() ? null : rootNodes;
    }

    /**
     * 递归收集某个资源节点下的所有子资源ID
     */
    public static void collectChildrenResourceIds(Long resourceId, Map<Long, List<SysResource>> resourceChildrenMap,
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

    // ---------- 角色/用户组 getMenuTree 共用：根菜单、菜单树构建、自下而上/自上而下打标、资源树填充 ----------

    /**
     * 返回虚拟根菜单（id=0）
     */
    public static SysMenu getRootMenu() {
        SysMenu rootMenu = new SysMenu();
        rootMenu.setId(0L);
        rootMenu.setCode("root");
        rootMenu.setName("根节点");
        rootMenu.setDescription("根节点");
        rootMenu.setSource(SourceEnum.SYSTEM.getCode());
        rootMenu.setStatus(YesOrNoEnum.Y.getKey());
        rootMenu.setParentId(null);
        rootMenu.setOpenType(OpenTypeEnum.CURRENT_TAB.getCode());
        rootMenu.setCreatorId(0L);
        rootMenu.setCreator("System");
        rootMenu.setYn(YnEnum.Y.getKey());
        return rootMenu;
    }

    /**
     * 构建菜单树形结构（根节点集合），会修改 menuList
     */
    public static void buildMenuTree(List<MenuNode> menuList) {
        if (CollectionUtils.isEmpty(menuList)) {
            return;
        }
        Map<Long, MenuNode> menuMap = menuList.stream()
                .filter(node -> node.getId() != null)
                .collect(Collectors.toMap(MenuNode::getId, node -> node, (a, b) -> a));
        List<MenuNode> rootMenus = new ArrayList<>();
        for (MenuNode menuNode : menuList) {
            Long parentId = menuNode.getParentId();
            Long menuId = menuNode.getId();
            if (menuId != null && menuId == 0L) {
                rootMenus.add(menuNode);
                continue;
            }
            if (parentId == null || parentId == 0L) {
                MenuNode root = menuMap.get(0L);
                if (root != null) {
                    if (root.getChildren() == null) {
                        root.setChildren(new ArrayList<>());
                    }
                    root.getChildren().add(menuNode);
                } else {
                    rootMenus.add(menuNode);
                }
            } else {
                MenuNode parent = menuMap.get(parentId);
                if (parent != null) {
                    if (parent.getChildren() == null) {
                        parent.setChildren(new ArrayList<>());
                    }
                    parent.getChildren().add(menuNode);
                } else {
                    rootMenus.add(menuNode);
                }
            }
        }
        sortMenuTree(rootMenus);
        menuList.clear();
        menuList.addAll(rootMenus);
    }

    /**
     * 递归排序菜单树
     */
    public static void sortMenuTree(List<MenuNode> menuList) {
        if (CollectionUtils.isEmpty(menuList)) {
            return;
        }
        menuList.sort((a, b) -> {
            Integer sortA = a.getSortIndex() != null ? a.getSortIndex() : 0;
            Integer sortB = b.getSortIndex() != null ? b.getSortIndex() : 0;
            return sortA.compareTo(sortB);
        });
        for (MenuNode menuNode : menuList) {
            if (CollectionUtils.isNotEmpty(menuNode.getChildren())) {
                sortMenuTree(menuNode.getChildren());
            }
        }
    }

    /**
     * 自下而上打标菜单绑定类型：显式绑定的保留，未显式绑定的根据子菜单合成 NONE/ALL/PART
     */
    public static void adjustMenuBindTypeBottomUp(MenuNode node, Set<Long> explicitlyBoundMenuIds) {
        if (node == null) {
            return;
        }
        List<MenuNode> children = node.getChildren();
        if (CollectionUtils.isNotEmpty(children)) {
            for (MenuNode child : children) {
                adjustMenuBindTypeBottomUp(child, explicitlyBoundMenuIds);
            }
            Long menuId = node.getId();
            if (menuId != null && explicitlyBoundMenuIds.contains(menuId)) {
                return;
            }
            boolean allNone = true;
            boolean allAll = true;
            boolean hasNonNone = false;
            for (MenuNode child : children) {
                Integer bindType = child.getMenuBindType();
                if (bindType == null || BindTypeEnum.NONE.getCode().equals(bindType)) {
                    allAll = false;
                } else if (BindTypeEnum.ALL.getCode().equals(bindType)) {
                    hasNonNone = true;
                    allNone = false;
                } else {
                    hasNonNone = true;
                    allNone = false;
                    allAll = false;
                }
            }
            if (allNone) {
                node.setMenuBindType(BindTypeEnum.NONE.getCode());
            } else if (allAll && hasNonNone) {
                node.setMenuBindType(BindTypeEnum.ALL.getCode());
            } else {
                node.setMenuBindType(BindTypeEnum.PART.getCode());
            }
        } else {
            Long menuId = node.getId();
            if (menuId == null || explicitlyBoundMenuIds.contains(menuId)) {
                return;
            }
            if (node.getMenuBindType() == null) {
                node.setMenuBindType(BindTypeEnum.NONE.getCode());
            }
        }
    }

    /**
     * 针对 root 节点自上而下强制传播 ALL/NONE 到所有子菜单
     */
    public static void propagateRootMenuBindType(MenuNode root) {
        if (root == null) {
            return;
        }
        Integer bindType = root.getMenuBindType();
        if (bindType == null || BindTypeEnum.PART.getCode().equals(bindType)) {
            return;
        }
        if (!BindTypeEnum.ALL.getCode().equals(bindType) && !BindTypeEnum.NONE.getCode().equals(bindType)) {
            return;
        }
        if (CollectionUtils.isNotEmpty(root.getChildren())) {
            propagateMenuBindTypeFromRootChildren(root.getChildren(), bindType);
        }
    }

    private static void propagateMenuBindTypeFromRootChildren(List<MenuNode> children, Integer bindType) {
        if (CollectionUtils.isEmpty(children)) {
            return;
        }
        for (MenuNode child : children) {
            child.setMenuBindType(bindType);
            if (CollectionUtils.isNotEmpty(child.getChildren())) {
                propagateMenuBindTypeFromRootChildren(child.getChildren(), bindType);
            }
        }
    }

    /**
     * 填充资源树中的资源详细信息
     */
    public static void fillResourceTreeDetails(List<ResourceNode> resourceNodes, Map<Long, SysResource> resourceMap) {
        if (CollectionUtils.isEmpty(resourceNodes) || resourceMap == null || resourceMap.isEmpty()) {
            return;
        }
        for (ResourceNode node : resourceNodes) {
            if (node.getId() != null) {
                SysResource resource = resourceMap.get(node.getId());
                if (resource != null) {
                    node.setParentId(resource.getParentId());
                    node.setCode(resource.getCode());
                    node.setName(resource.getName());
                    node.setDescription(resource.getDescription());
                    node.setSource(resource.getSource());
                    node.setType(resource.getType());
                    node.setPath(resource.getPath());
                    node.setIcon(resource.getIcon());
                    node.setSortIndex(resource.getSortIndex());
                    node.setStatus(resource.getStatus());
                }
            }
            if (CollectionUtils.isNotEmpty(node.getChildren())) {
                fillResourceTreeDetails(node.getChildren(), resourceMap);
            }
        }
    }
}
