package com.xspaceagi.system.web.controller.permission;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.xspaceagi.system.application.dto.permission.*;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.utils.I18nUtil;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.xspaceagi.system.application.service.SysMenuApplicationService;
import com.xspaceagi.system.application.service.SysResourceApplicationService;
import com.xspaceagi.system.domain.model.MenuNode;
import com.xspaceagi.system.domain.model.SortIndex;
import com.xspaceagi.system.infra.dao.entity.SysMenu;
import com.xspaceagi.system.infra.dao.entity.SysMenuResource;
import com.xspaceagi.system.infra.dao.entity.SysResource;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.BindTypeEnum;
import com.xspaceagi.system.spec.enums.OpenTypeEnum;
import com.xspaceagi.system.spec.enums.SourceEnum;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.web.controller.base.BaseController;
import com.xspaceagi.system.application.converter.MenuBindResourceModelConverter;
import com.xspaceagi.system.application.converter.MenuTreeUtil;
import com.xspaceagi.system.application.converter.ResourceTreeUtil;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

import static com.xspaceagi.system.spec.enums.ResourceEnum.*;

@Slf4j
@Tag(name = "权限管理-菜单", description = "菜单相关接口")
@RestController
@RequestMapping("/api/system/menu")
public class SysMenuController extends BaseController {
    
    @Resource
    private SysMenuApplicationService sysMenuApplicationService;

    @Resource
    private SysResourceApplicationService sysResourceApplicationService;

    @RequireResource(MENU_MANAGE_ADD)
    @Operation(summary = "添加菜单")
    @PostMapping(value = "/add",produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Void> addMenu(@RequestBody SysMenuAddDto dto) {
        if (dto == null) {
            return ReqResult.error("参数不能为空");
        }
        if (dto.getSource() != null && SourceEnum.isInValid(dto.getSource())) {
            return ReqResult.error("参数source错误");
        }
        if (dto.getOpenType() != null && OpenTypeEnum.isInValid(dto.getOpenType())) {
            return ReqResult.error("参数openType错误");
        }
        if (dto.getStatus() != null && YesOrNoEnum.isInValid(dto.getStatus())) {
            return ReqResult.error("参数status错误");
        }

        SysMenu menu = new SysMenu();
        BeanUtils.copyProperties(dto, menu);

        MenuNode menuNode = null;
        if (CollectionUtils.isNotEmpty(dto.getResourceTree())) {
            SysMenuBindResourceDto bindResourceDto = new SysMenuBindResourceDto();
            bindResourceDto.setResourceTree(dto.getResourceTree());
            menuNode = MenuBindResourceModelConverter.convertToMenuNode(bindResourceDto);
        }
        sysMenuApplicationService.addMenu(menu, menuNode, SourceEnum.CUSTOM.getCode(), getUser());
        return ReqResult.success();
    }

    @RequireResource(MENU_MANAGE_MODIFY)
    @Operation(summary = "更新菜单")
    @PostMapping("/update")
    public ReqResult<Void> updateMenu(@RequestBody SysMenuUpdateDto dto) {
        if (dto == null) {
            return ReqResult.error("参数不能为空");
        }
        if (dto.getSource() != null && SourceEnum.isInValid(dto.getSource())) {
            return ReqResult.error("参数source错误");
        }
        if (dto.getOpenType() != null && OpenTypeEnum.isInValid(dto.getOpenType())) {
            return ReqResult.error("参数openType错误");
        }
        if (dto.getStatus() != null && YesOrNoEnum.isInValid(dto.getStatus())) {
            return ReqResult.error("参数status错误");
        }

        SysMenu menu = new SysMenu();
        BeanUtils.copyProperties(dto, menu);

        MenuNode menuNode = null;
        if (CollectionUtils.isNotEmpty(dto.getResourceTree())) {
            SysMenuBindResourceDto bindResourceDto = new SysMenuBindResourceDto();
            bindResourceDto.setResourceTree(dto.getResourceTree());
            menuNode = MenuBindResourceModelConverter.convertToMenuNode(bindResourceDto);
        }

        menu.setCode(null); // code不允许更新
        sysMenuApplicationService.updateMenu(menu, menuNode, SourceEnum.CUSTOM.getCode(), getUser());
        return ReqResult.success();
    }

    @RequireResource(MENU_MANAGE_DELETE)
    @Operation(summary = "删除菜单")
    @PostMapping("/delete/{menuId}")
    public ReqResult<Void> deleteMenu(@PathVariable Long menuId) {
        if (menuId == null) {
            return ReqResult.error("参数不能为空");
        }
        sysMenuApplicationService.deleteMenu(menuId, getUser());
        return ReqResult.success();
    }

    @RequireResource(MENU_MANAGE_QUERY)
    @Operation(summary = "根据ID查询菜单")
    @GetMapping("/{menuId}")
    public ReqResult<MenuNodeDto> getMenuById(@PathVariable Long menuId) {
        if (menuId == null) {
            return ReqResult.error("参数不能为空");
        }
        SysMenu menu = sysMenuApplicationService.getMenuById(menuId);
        if (menu == null) {
            return ReqResult.error("菜单不存在");
        }
        MenuNodeDto dto = new MenuNodeDto();
        BeanUtils.copyProperties(menu, dto);

        ReqResult<List<ResourceNodeDto>> resourceListByMenuId = getResourceListByMenuId(menuId);
        dto.setResourceTree(resourceListByMenuId.getData());

        I18nUtil.replaceSystemMessage(dto);
        return ReqResult.success(dto);
    }

    @RequireResource(MENU_MANAGE_QUERY)
    @Operation(summary = "根据编码查询菜单")
    @GetMapping("/code/{menuCode}")
    public ReqResult<MenuNodeDto> getMenuByCode(@PathVariable String menuCode) {
        if (StringUtils.isBlank(menuCode)) {
            return ReqResult.error("参数不能为空");
        }
        SysMenu menu = sysMenuApplicationService.getMenuByCode(menuCode);
        if (menu == null) {
            return ReqResult.error("菜单不存在");
        }
        MenuNodeDto dto = new MenuNodeDto();
        BeanUtils.copyProperties(menu, dto);

        ReqResult<List<ResourceNodeDto>> resourceListByMenuId = getResourceListByMenuId(menu.getId());
        dto.setResourceTree(resourceListByMenuId.getData());

        I18nUtil.replaceSystemMessage(dto);
        return ReqResult.success(dto);
    }

    @RequireResource(MENU_MANAGE_QUERY)
    @Operation(summary = "根据条件查询菜单列表（树形结构）")
    @GetMapping("/list")
    public ReqResult<List<MenuNodeDto>> getMenuList(SysMenuQueryDto queryDto) {
        SysMenu sysMenu = new SysMenu();
        if (queryDto != null) {
            BeanUtils.copyProperties(queryDto, sysMenu);
        }
        List<SysMenu> menuList = sysMenuApplicationService.getMenuList(sysMenu);
        
        List<MenuNodeDto> menuDtoList = menuList.stream().map(menu -> {
            MenuNodeDto dto = new MenuNodeDto();
            BeanUtils.copyProperties(menu, dto);
            return dto;
        }).collect(Collectors.toList());

        List<MenuNodeDto> treeList = MenuTreeUtil.buildMenuTree(menuDtoList);
        // 统一处理：如果 parentId 为空，则设置为 0
        fillMenuParentIdIfNull(treeList);
        
        // 如果 queryDto 为 null 或所有字段都为空（查询完整菜单树），需要添加根节点
        if (isMenuQueryEmpty(queryDto)) {
            MenuNodeDto rootNode = getMenuRoot();
            rootNode.setChildren(treeList);
            List<MenuNodeDto> result = new ArrayList<>();
            result.add(rootNode);

            I18nUtil.replaceSystemMessage(treeList);
            return ReqResult.success(result);
        }

        I18nUtil.replaceSystemMessage(treeList);
        return ReqResult.success(treeList);
    }

    @RequireResource(MENU_MANAGE_MODIFY)
    @Operation(summary = "批量调整菜单顺序")
    @PostMapping("/update-sort")
    public ReqResult<Void> updateMenuSort(@RequestBody SortIndexUpdateDto dto) {
        if (dto == null || CollectionUtils.isEmpty(dto.getItems())) {
            return ReqResult.error("参数不能为空");
        }
        List<SortIndex> sortIndexList = dto.getItems().stream()
                .map(item -> {
                    SortIndex model = new SortIndex();
                    model.setId(item.getId());
                    model.setParentId(item.getParentId());
                    model.setSortIndex(item.getSortIndex());
                    return model;
                })
                .collect(Collectors.toList());
        sysMenuApplicationService.batchUpdateMenuSort(sortIndexList, getUser());
        return ReqResult.success();
    }

    @RequireResource(MENU_MANAGE_MODIFY)
    @Operation(summary = "菜单绑定资源")
    @PostMapping("/bind-resource")
    public ReqResult<Void> bindResource(@RequestBody SysMenuBindResourceDto dto) {
        if (dto == null || dto.getMenuId() == null) {
            return ReqResult.error("参数不能为空");
        }
        MenuNode menuNode = MenuBindResourceModelConverter.convertToMenuNode(dto);
        sysMenuApplicationService.bindResource(menuNode, getUser());
        return ReqResult.success();
    }

    @RequireResource(MENU_MANAGE_QUERY)
    @Operation(summary = "查询菜单绑定的资源（树形结构）")
    @GetMapping("/list-resource/{menuId}")
    public ReqResult<List<ResourceNodeDto>> getResourceListByMenuId(@PathVariable Long menuId) {
        if (menuId == null) {
            return ReqResult.error("参数不能为空");
        }
        
        // 查询菜单绑定的资源列表（可能为空）
        List<SysMenuResource> menuResourceList = sysMenuApplicationService.getResourceListByMenuId(menuId);
        
        // 查询所有资源列表（构建完整资源树）
        List<SysResource> resourceList = sysResourceApplicationService.getResourceList(null);

        // 构建资源父子关系映射
        Map<Long, List<SysResource>> resourceChildrenMap = new HashMap<>();
        for (SysResource resource : resourceList) {
            Long parentId = resource.getParentId();
            if (parentId != null && parentId != 0L) {
                resourceChildrenMap.computeIfAbsent(parentId, k -> new ArrayList<>()).add(resource);
            }
        }

        // 提取 资源ID->绑定类型 映射（初始映射，只包含数据库中存储的资源）
        Map<Long, Integer> resourceBindTypeMap = CollectionUtils.isEmpty(menuResourceList)
                ? new HashMap<>()
                : menuResourceList.stream()
                    .filter(mr -> mr.getResourceId() != null)
                    .collect(Collectors.toMap(
                            SysMenuResource::getResourceId,
                            SysMenuResource::getResourceBindType,
                            (existing, replacement) -> existing // 如果有重复，保留第一个
                    ));

        // 对于绑定类型为 ALL 的资源，需要将其所有子资源也标记为 ALL
        // 注意：ALL 类型的资源会强制覆盖子资源的绑定类型
        Set<Long> allBindResourceIds = new HashSet<>();
        for (SysMenuResource menuResource : menuResourceList) {
            if (menuResource.getResourceId() == null) {
                continue;
            }
            Integer bindType = menuResource.getResourceBindType();
            if (BindTypeEnum.ALL.getCode().equals(bindType)) {
                allBindResourceIds.add(menuResource.getResourceId());
            }
        }
        
        // 对于 ALL 类型的资源，递归收集所有子资源，并强制设置为 ALL
        Set<Long> processedResourceIds = new HashSet<>();
        for (Long allBindResourceId : allBindResourceIds) {
            collectChildrenAndSetBindType(allBindResourceId, resourceChildrenMap, 
                    resourceBindTypeMap, processedResourceIds, BindTypeEnum.ALL.getCode());
        }

        // 构建资源树（完整资源树）
        // 注意：对于 PART 类型的资源，子节点会根据 resourceBindTypeMap 中的具体值来标记
        // 如果子节点不在 resourceBindTypeMap 中，则标记为 NONE（未绑定）
        List<ResourceNodeDto> resourceTree = ResourceTreeUtil.buildResourceTree(resourceList, resourceBindTypeMap);
        
        // 检查资源树中是否已经有 root 根节点
        boolean hasRootNode = resourceTree.stream()
                .anyMatch(node -> "root".equals(node.getCode()) && node.getId() != null && node.getId() == 0L);
        
        // 如果没有 root 根节点，则添加
        if (!hasRootNode) {
            ResourceNodeDto rootNode = getResourceRoot();

            // 根据业务逻辑设置根节点的绑定类型
            Integer rootBindType;
            if (CollectionUtils.isEmpty(menuResourceList)) {
                // 如果菜单绑定的资源列表为空，设置为 NONE
                rootBindType = BindTypeEnum.NONE.getCode();
            } else if (CollectionUtils.isEmpty(resourceTree)) {
                // 如果资源树为空，设置为 NONE
                rootBindType = BindTypeEnum.NONE.getCode();
            } else {
                // 检查所有根节点的直接子节点（parentId=0或null）的绑定类型是否都是 ALL
                // 从原始资源列表中找出所有根节点（parentId=0或null），检查它们的绑定类型
                List<SysResource> rootResources = resourceList.stream()
                        .filter(resource -> resource.getParentId() == null || resource.getParentId() == 0L)
                        .collect(Collectors.toList());
                
                if (rootResources.isEmpty()) {
                    rootBindType = BindTypeEnum.NONE.getCode();
                } else {
                    boolean allChildrenAreAll = rootResources.stream()
                            .allMatch(resource -> {
                                Integer bindType = resourceBindTypeMap.get(resource.getId());
                                return bindType != null && BindTypeEnum.ALL.getCode().equals(bindType);
                            });
                    if (allChildrenAreAll) {
                        rootBindType = BindTypeEnum.ALL.getCode();
                    } else {
                        rootBindType = BindTypeEnum.PART.getCode();
                    }
                }
            }
            rootNode.setResourceBindType(rootBindType);
            rootNode.setChildren(resourceTree);
            List<ResourceNodeDto> result = new ArrayList<>();
            result.add(rootNode);

            I18nUtil.replaceSystemMessage(result);
            return ReqResult.success(result);
        }
        
        // 如果已经有 root 根节点，直接返回资源树
        I18nUtil.replaceSystemMessage(resourceTree);
        return ReqResult.success(resourceTree);
    }

    /**
     * 递归收集某个资源的所有子资源，并强制设置绑定类型
     * 注意：此方法用于处理 ALL 类型的资源，会强制覆盖子资源的绑定类型
     */
    private void collectChildrenAndSetBindType(Long resourceId,
                                               Map<Long, List<SysResource>> resourceChildrenMap,
                                               Map<Long, Integer> resourceBindTypeMap,
                                               Set<Long> processedResourceIds,
                                               Integer bindType) {
        if (processedResourceIds.contains(resourceId)) {
            return; // 避免重复处理
        }
        processedResourceIds.add(resourceId);

        List<SysResource> children = resourceChildrenMap.get(resourceId);
        if (CollectionUtils.isEmpty(children)) {
            return;
        }
        for (SysResource child : children) {
            if (child.getId() == null) {
                continue;
            }
            // 强制设置子资源的绑定类型（ALL 类型会覆盖子资源的原有绑定类型）
            resourceBindTypeMap.put(child.getId(), bindType);
            // 递归处理子资源的子资源
            collectChildrenAndSetBindType(child.getId(), resourceChildrenMap, resourceBindTypeMap, 
                    processedResourceIds, bindType);
        }
    }

    /**
     * 判断菜单查询条件是否为空
     * @param queryDto 查询条件
     * @return true表示查询条件为空（null或所有字段都为null/空值）
     */
    private boolean isMenuQueryEmpty(SysMenuQueryDto queryDto) {
        if (queryDto == null) {
            return true;
        }
        return StringUtils.isBlank(queryDto.getCode())
                && StringUtils.isBlank(queryDto.getName())
                && queryDto.getSource() == null
                && queryDto.getParentId() == null
                && queryDto.getStatus() == null;
    }

    /**
     * 递归处理菜单树，如果 parentId 为空则设置为 0
     */
    private void fillMenuParentIdIfNull(List<MenuNodeDto> list) {
        if (CollectionUtils.isEmpty(list)) {
            return;
        }
        for (MenuNodeDto node : list) {
            if (node.getParentId() == null) {
                node.setParentId(0L);
            }
            if (CollectionUtils.isNotEmpty(node.getChildren())) {
                fillMenuParentIdIfNull(node.getChildren());
            }
        }
    }

    private MenuNodeDto getMenuRoot() {
        MenuNodeDto rootNode = new MenuNodeDto();
        rootNode.setId(0L);
        rootNode.setCode("root");
        rootNode.setName("根节点");
        rootNode.setParentId(null);
        rootNode.setDescription("根节点");
        rootNode.setSource(SourceEnum.SYSTEM.getCode());
        rootNode.setOpenType(OpenTypeEnum.CURRENT_TAB.getCode());
        rootNode.setStatus(YesOrNoEnum.Y.getKey());
        return rootNode;
    }

    private ResourceNodeDto getResourceRoot() {
        ResourceNodeDto rootNode = new ResourceNodeDto();
        rootNode.setId(0L);
        rootNode.setCode("root");
        rootNode.setName("根节点");
        rootNode.setDescription("根节点");
        rootNode.setParentId(null);
        rootNode.setSource(SourceEnum.SYSTEM.getCode());
        rootNode.setStatus(YesOrNoEnum.Y.getKey());
        return rootNode;
    }
}