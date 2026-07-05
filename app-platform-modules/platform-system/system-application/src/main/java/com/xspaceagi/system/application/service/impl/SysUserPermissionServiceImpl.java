package com.xspaceagi.system.application.service.impl;

import com.xspaceagi.system.application.dto.AuthorizedIds;
import com.xspaceagi.system.application.service.SysGroupApplicationService;
import com.xspaceagi.system.application.service.SysRoleApplicationService;
import com.xspaceagi.system.application.service.SysUserPermissionService;
import com.xspaceagi.system.domain.model.MenuNode;
import com.xspaceagi.system.domain.service.UserDomainService;
import com.xspaceagi.system.domain.model.ResourceNode;
import com.xspaceagi.system.domain.service.SysFlattenService;
import com.xspaceagi.system.infra.dao.entity.SysGroup;
import com.xspaceagi.system.infra.dao.entity.SysRole;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.spec.enums.BindTypeEnum;
import com.xspaceagi.system.spec.enums.StatusEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 资源权限服务实现
 */
@Slf4j
@Service
public class SysUserPermissionServiceImpl implements SysUserPermissionService {

    @Resource
    private SysFlattenService sysFlattenService;
    @Resource
    private SysRoleApplicationService sysRoleApplicationService;
    @Resource
    private SysGroupApplicationService sysGroupApplicationService;
    @Resource
    private UserDomainService userDomainService;

    /**
     * 查询用户拥有的菜单及资源权限（此处返回的是打平结构）
     */
    @Override
    public List<MenuNode> getUserMenuAndResources(User user) {
        if (user == null) {
            throw new IllegalArgumentException("User cannot be empty");
        }
        Long userId = user.getId();

        // 普通用户不能绑定角色，只有用户组权限，忽略角色
        List<SysRole> roleList = user.getRole() == User.Role.User
                ? Collections.emptyList()
                : sysRoleApplicationService.getRoleListByUserId(userId);

        List<SysGroup> groupList = sysGroupApplicationService.getEffectiveGroupListByUserId(userId);

        if (CollectionUtils.isEmpty(roleList) && CollectionUtils.isEmpty(groupList)) {
            return new ArrayList<>();
        }

        // 查询这些角色/用户组的菜单+资源权限
        List<MenuNode> allMenuNodes = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(roleList)) {
            for (SysRole role : roleList) {
                if (!StatusEnum.isEnabled(role.getStatus())) {
                    continue;
                }
                List<MenuNode> roleMenuTree = sysRoleApplicationService.getMenuTreeByRoleId(role.getId());
                if (CollectionUtils.isNotEmpty(roleMenuTree)) {
                    allMenuNodes.addAll(sysFlattenService.flattenMenuTree(roleMenuTree));
                }
            }
        }
        if (CollectionUtils.isNotEmpty(groupList)) {
            for (SysGroup group : groupList) {
                if (!StatusEnum.isEnabled(group.getStatus())) {
                    continue;
                }
                List<MenuNode> groupMenuTree = sysGroupApplicationService.getMenuTreeByGroupId(group.getId());
                if (CollectionUtils.isNotEmpty(groupMenuTree)) {
                    allMenuNodes.addAll(sysFlattenService.flattenMenuTree(groupMenuTree));
                }
            }
        }

        if (CollectionUtils.isEmpty(allMenuNodes)) {
            return new ArrayList<>();
        }

        // 合并多个角色/用户组的菜单权限，并在合并过程中直接过滤掉无权限的菜单和资源
        return mergeUserMenuNodes(allMenuNodes);
    }

    @Override
    public AuthorizedIds getAuthorizedMenuAndResourceIdsFromNodes(List<MenuNode> authorizedMenuNodes) {
        if (CollectionUtils.isEmpty(authorizedMenuNodes)) {
            return new AuthorizedIds(new HashSet<>(), new HashSet<>());
        }
        Set<Long> menuIds = authorizedMenuNodes.stream()
                .filter(n -> n.getId() != null)
                .map(MenuNode::getId)
                .collect(Collectors.toSet());
        Set<Long> resourceIds = extractAuthorizedResourceIds(authorizedMenuNodes);
        return new AuthorizedIds(menuIds, resourceIds);
    }

    /**
     * 提取有权限的资源ID
     */
    private Set<Long> extractAuthorizedResourceIds(List<MenuNode> mergedMenuNodes) {
        Set<Long> authorizedResourceIds = new HashSet<>();
        for (MenuNode menuNode : mergedMenuNodes) {
            if (CollectionUtils.isEmpty(menuNode.getResourceNodes())) {
                continue;
            }
            for (ResourceNode resourceNode : menuNode.getResourceNodes()) {
                //这里不用判断resourceBindType，因为前提是数据都是用户有权限的资源
                if (resourceNode.getId() != null) {
                    authorizedResourceIds.add(resourceNode.getId());
                }
            }
        }
        return authorizedResourceIds;
    }

    /**
     * 合并菜单和资源
     */
    private List<MenuNode> mergeUserMenuNodes(List<MenuNode> nodes) {
        if (CollectionUtils.isEmpty(nodes)) {
            return new ArrayList<>();
        }
        List<MenuNode> result = new ArrayList<>();

        // 分组
        Map<Long, List<MenuNode>> menuGroup = nodes.stream()
                .filter(n -> n.getId() != null)
                .collect(Collectors.groupingBy(MenuNode::getId));

        for (Map.Entry<Long, List<MenuNode>> entry : menuGroup.entrySet()) {
            Long menuId = entry.getKey();
            List<MenuNode> menuNodes = entry.getValue();
            if (CollectionUtils.isEmpty(menuNodes) || menuId == null) {
                continue;
            }

            // 是否有此菜单权限
            boolean hasMenuPermission = menuNodes.stream().anyMatch(n -> n.getMenuBindType() != null && !n.getMenuBindType().equals(BindTypeEnum.NONE.getCode()));
            if (!hasMenuPermission) {
                continue;
            }

            MenuNode menuNode = menuNodes.get(0);
            menuNode.setChildren(null);

            // 多个角色/用户组时取最大权限：ALL(1) > PART(2) > NONE(0)
            Integer mergedMenuBindType = menuNodes.stream()
                    .map(MenuNode::getMenuBindType)
                    .filter(Objects::nonNull)
                    .max(Comparator.comparingInt(SysUserPermissionServiceImpl::menuBindTypePriority))
                    .orElse(BindTypeEnum.NONE.getCode());
            menuNode.setMenuBindType(mergedMenuBindType);

            // 合并资源（先打平再按 resourceId 分组）
            List<ResourceNode> flatResources = menuNodes.stream()
                    .filter(n -> CollectionUtils.isNotEmpty(n.getResourceTree()))
                    .flatMap(n -> sysFlattenService.flattenResourceTree(n.getResourceTree()).stream())
                    .collect(Collectors.toList());

            List<ResourceNode> mergedResourceList = new ArrayList<>();
            if (CollectionUtils.isNotEmpty(flatResources)) {
                Map<Long, List<ResourceNode>> resourceGroup = flatResources.stream()
                        .filter(r -> r.getId() != null)
                        .collect(Collectors.groupingBy(ResourceNode::getId));

                for (Map.Entry<Long, List<ResourceNode>> re : resourceGroup.entrySet()) {
                    List<ResourceNode> resourceNodes = re.getValue();
                    if (CollectionUtils.isEmpty(resourceNodes)) {
                        continue;
                    }

                    boolean hasResourcePermission = resourceNodes.stream().anyMatch(n -> n.getResourceBindType() != null && !n.getResourceBindType().equals(BindTypeEnum.NONE.getCode()));
                    if (!hasResourcePermission) {
                        continue;
                    }

                    // 多个角色/用户组时取最大权限：ALL(1) > PART(2) > NONE(0)
                    ResourceNode resourceNode = resourceNodes.stream()
                            .max(Comparator.comparing(n -> resourceBindTypePriority(n.getResourceBindType())))
                            .orElse(resourceNodes.get(0));
                    resourceNode.setChildren(null);
                    mergedResourceList.add(resourceNode);
                }
            }
            // 此处返回的资源是打平结构，不是树形
            menuNode.setResourceNodes(mergedResourceList);
            menuNode.setResourceTree(null);
            result.add(menuNode);
        }

        return result;
    }

    /**
     * 菜单绑定类型优先级：ALL(1) > PART(2) > NONE(0)
     */
    private static int menuBindTypePriority(Integer bindType) {
        if (bindType == null) {
            return 0;
        }
        if (BindTypeEnum.ALL.getCode().equals(bindType)) {
            return 3;
        }
        if (BindTypeEnum.PART.getCode().equals(bindType)) {
            return 2;
        }
        if (BindTypeEnum.NONE.getCode().equals(bindType)) {
            return 1;
        }
        return 0;
    }

    /**
     * 资源绑定类型优先级：ALL(1) > PART(2) > NONE(0)
     */
    private static int resourceBindTypePriority(Integer bindType) {
        if (bindType == null) {
            return 0;
        }
        if (BindTypeEnum.ALL.getCode().equals(bindType)) {
            return 2;
        }
        if (BindTypeEnum.PART.getCode().equals(bindType)) {
            return 1;
        }
        return 0;
    }

}