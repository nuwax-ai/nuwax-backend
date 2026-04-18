package com.xspaceagi.system.domain.service.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.xspaceagi.system.spec.enums.*;
import com.xspaceagi.system.spec.utils.CodeGeneratorUtil;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.xspaceagi.system.domain.model.ResourceNode;
import com.xspaceagi.system.domain.model.SortIndex;
import com.xspaceagi.system.domain.service.SysResourceDomainService;
import com.xspaceagi.system.infra.dao.entity.SysGroupMenu;
import com.xspaceagi.system.infra.dao.entity.SysMenuResource;
import com.xspaceagi.system.infra.dao.entity.SysResource;
import com.xspaceagi.system.infra.dao.entity.SysRoleMenu;
import com.xspaceagi.system.infra.dao.service.SysGroupMenuService;
import com.xspaceagi.system.infra.dao.service.SysMenuResourceService;
import com.xspaceagi.system.infra.dao.service.SysResourceService;
import com.xspaceagi.system.infra.dao.service.SysRoleMenuService;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.jackson.JsonSerializeUtil;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 系统资源领域服务实现
 */
@Slf4j
@Service
public class SysResourceDomainServiceImpl implements SysResourceDomainService {
    
    @Resource
    private SysResourceService sysResourceService;
    @Resource
    private SysMenuResourceService sysMenuResourceService;
    @Resource
    private SysRoleMenuService sysRoleMenuService;
    @Resource
    private SysGroupMenuService sysGroupMenuService;

    private void normalizeResource(SysResource resource) {
        resource.setCode(StringUtils.trim(resource.getCode()));
        resource.setName(StringUtils.trim(resource.getName()));
        resource.setDescription(StringUtils.trim(resource.getDescription()));
        resource.setPath(StringUtils.trim(resource.getPath()));

        if (StringUtils.isNotBlank(resource.getCode()) && !resource.getCode().matches("^[a-zA-Z][a-zA-Z0-9_]*$")) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRbacResourceCodeFormatInvalid);
        }
        if (StringUtils.length(resource.getCode()) > 100) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRbacResourceCodeLengthExceeded);
        }
        if (StringUtils.length(resource.getName()) > 50) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRbacNameLengthExceeded);
        }
        if (StringUtils.length(resource.getDescription()) > 500) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRbacDescLengthExceeded);
        }
        if (StringUtils.length(resource.getPath()) > 500) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRbacPathLengthExceeded);
        }
    }

    @Override
    public void addResource(SysResource resource, UserContext userContext) {
        if (StringUtils.isBlank(resource.getName())) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "名称");
        }
        if (resource.getType() == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "类型");
        }

        // 如果编码为空，根据名称自动生成编码
        if (StringUtils.isBlank(resource.getCode())) {
            String generatedCode = CodeGeneratorUtil.generateUniqueCodeFromName(
                resource.getName(),
                "resource_",
                code -> queryResourceByCode(code) != null
            );
            resource.setCode(generatedCode);
        }

        normalizeResource(resource);
        
        // 禁止使用 root 作为编码（不区分大小写），直接返回不入库
        if ("root".equalsIgnoreCase(resource.getCode())) {
            return;
        }

        SysResource exists = queryResourceByCode(resource.getCode());
        if (exists != null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemResourceCodeDuplicate);
        }
        if (resource.getParentId() != null && resource.getParentId() != 0) {
            if (resource.getParentId() < 0) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRbacParentIdInvalid);
            } else {
                SysResource parent = queryResourceById(resource.getParentId());
                if (parent == null) {
                    throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRbacParentResourceNotFoundWithParentId,
                            resource.getParentId());
                }
                if (!parent.getType().equals(ResourceTypeEnum.MODULE.getCode())) {
                    throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRbacParentNameNotModule,
                            parent.getName());
                }
                if (resource.getParentId().equals(resource.getId())) {
                    throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRbacParentSelfReferenceForbidden,
                            resource.getId());
                }
            }
        }

        if (resource.getSource() == null) {
            resource.setSource(SourceEnum.CUSTOM.getCode());
        }
        if (resource.getParentId() == null) {
            resource.setParentId(0L);
        }
        if (resource.getSortIndex() == null) {
            resource.setSortIndex(0);
        }
        if (resource.getStatus() == null) {
            resource.setStatus(YesOrNoEnum.Y.getKey());
        }
        resource.setTenantId(userContext.getTenantId());
        resource.setCreatorId(userContext.getUserId());
        resource.setCreator(userContext.getUserName());
        resource.setYn(YnEnum.Y.getKey());
        sysResourceService.save(resource);
    }

    @Override
    public void updateResource(SysResource resource, UserContext userContext) {
        if (resource.getId() == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "ID");
        }
        normalizeResource(resource);
        SysResource exist = queryResourceById(resource.getId());
        if (exist == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemResourceNotFound);
        }
        if (SourceEnum.SYSTEM.getCode().equals(exist.getSource())) {
            if (resource.getCode() != null && !resource.getCode().equals(exist.getCode())) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemBuiltinResourceCodeImmutable);
            }
            if (resource.getName() != null && !resource.getName().equals(exist.getName())) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemBuiltinResourceNameImmutable);
            }
            if (resource.getStatus() != null && !resource.getStatus().equals(StatusEnum.ENABLED.getCode())) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemBuiltinResourceCannotDisable);
            }
        }
        if (resource.getParentId() != null && resource.getParentId() != 0) {
            if (resource.getParentId() < 0) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRbacParentIdInvalid);
            } else {
                SysResource parent = queryResourceById(resource.getParentId());
                if (parent == null) {
                    throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRbacParentResourceNotFound);
                }
                if (!parent.getType().equals(ResourceTypeEnum.MODULE.getCode())) {
                    throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRbacParentNameNotModule,
                            parent.getName());
                }
                if (resource.getParentId().equals(resource.getId())) {
                    throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRbacParentSelfReferenceForbidden,
                            resource.getId());
                }
            }
        }

        resource.setModifierId(userContext.getUserId());
        resource.setModifier(userContext.getUserName());
        sysResourceService.updateById(resource);
    }

    @Override
    public void deleteResource(Long resourceId, UserContext userContext) {
        if (resourceId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "ID");
        }
        SysResource root = queryResourceById(resourceId);
        if (root == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemResourceNotFound);
        }
        if (SourceEnum.SYSTEM.getCode().equals(root.getSource())) {
            //throw new BizException("系统内置资源不能删除");
        }

        Long tenantId = userContext.getTenantId();
        // 收集以该资源为根的整棵子树的所有资源ID（含自身）
        Set<Long> resourceIds = collectSubTreeResourceIds(resourceId, tenantId);

        if (CollectionUtils.isEmpty(resourceIds)) {
            return;
        }
        // 1. 删除菜单-资源关联
        sysMenuResourceService.remove(Wrappers.<SysMenuResource>lambdaQuery().in(SysMenuResource::getResourceId, resourceIds));
        // 2. 更新角色-菜单的 resource_tree_json，移除已删除的资源节点
        updateRoleMenuResourceTree(resourceIds, tenantId);
        // 3. 更新用户组-菜单的 resource_tree_json，移除已删除的资源节点
        updateGroupMenuResourceTree(resourceIds, tenantId);
        // 4. 删除资源树中所有资源
        sysResourceService.remove(Wrappers.<SysResource>lambdaQuery().in(SysResource::getId, resourceIds));
    }

    @Override
    public boolean batchUpdateResourceSort(List<SortIndex> sortIndexList, UserContext userContext) {
        if (CollectionUtils.isEmpty(sortIndexList)) {
            return false;
        }
        boolean hasUpdateParent = false;
        List<Long> parentIdsToValidate = sortIndexList.stream().map(SortIndex::getParentId).filter(pid -> pid != null && pid != 0).toList();

        Set<Long> existingParentIds = new HashSet<>();
        if (!parentIdsToValidate.isEmpty()) {
            List<SysResource> parents = sysResourceService.listByIds(parentIdsToValidate);
            if (CollectionUtils.isNotEmpty(parents)) {
                existingParentIds = parents.stream().map(SysResource::getId).collect(Collectors.toSet());
            }
            parents.forEach(p -> {
                if (!p.getType().equals(ResourceTypeEnum.MODULE.getCode())) {
                    throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRbacParentNameNotModule,
                            p.getName());
                }
            });
        }
        for (SortIndex item : sortIndexList) {
            if (item == null || item.getId() == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "资源ID");
            }
            SysResource updateResource = new SysResource();
            updateResource.setId(item.getId());
            boolean hasUpdate = false;
            if (item.getParentId() != null) {
                if (item.getParentId() < 0) {
                    throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRbacParentIdInvalidForResource,
                            item.getId());
                }
                if (item.getParentId() != 0 && !existingParentIds.contains(item.getParentId())) {
                    throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRbacParentNodeNotFound,
                            item.getParentId());
                }
                if (item.getParentId().equals(item.getId())) {
                    throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRbacParentSelfReferenceForbidden,
                            item.getId());
                }
                updateResource.setParentId(item.getParentId());
                hasUpdate = true;
                hasUpdateParent = true;
            }
            if (item.getSortIndex() != null) {
                updateResource.setSortIndex(item.getSortIndex());
                hasUpdate = true;
            }
            if (hasUpdate) {
                updateResource.setModifierId(userContext.getUserId());
                updateResource.setModifier(userContext.getUserName());
                sysResourceService.updateById(updateResource);
            }
        }
        return hasUpdateParent;
    }

    /**
     * 收集以 rootId 为根的资源子树中所有资源ID（含根）
     */
    private Set<Long> collectSubTreeResourceIds(Long rootId, Long tenantId) {
        LambdaQueryWrapper<SysResource> wrapper = Wrappers.<SysResource>lambdaQuery()
                .eq(SysResource::getYn, YnEnum.Y.getKey());
        if (tenantId != null) {
            wrapper.eq(SysResource::getTenantId, tenantId);
        }
        List<SysResource> allResources = sysResourceService.list(wrapper);
        Map<Long, List<SysResource>> childrenMap = new HashMap<>();
        if (CollectionUtils.isNotEmpty(allResources)) {
            for (SysResource r : allResources) {
                Long parentId = r.getParentId();
                if (parentId != null && parentId != 0L) {
                    childrenMap.computeIfAbsent(parentId, k -> new ArrayList<>()).add(r);
                }
            }
        }
        Set<Long> out = new HashSet<>();
        collectSubTreeResourceIdsRec(rootId, childrenMap, out);
        return out;
    }

    private void collectSubTreeResourceIdsRec(Long id, Map<Long, List<SysResource>> childrenMap, Set<Long> out) {
        out.add(id);
        for (SysResource c : childrenMap.getOrDefault(id, List.of())) {
            if (c.getId() != null) {
                collectSubTreeResourceIdsRec(c.getId(), childrenMap, out);
            }
        }
    }

    /**
     * 更新 sys_role_menu 的 resource_tree_json，移除已删除的资源ID对应节点
     */
    private void updateRoleMenuResourceTree(Set<Long> toDeleteIds, Long tenantId) {
        LambdaQueryWrapper<SysRoleMenu> wrapper = Wrappers.<SysRoleMenu>lambdaQuery()
                .isNotNull(SysRoleMenu::getResourceTreeJson)
                .ne(SysRoleMenu::getResourceTreeJson, "");
        if (tenantId != null) {
            wrapper.eq(SysRoleMenu::getTenantId, tenantId);
        }
        List<SysRoleMenu> list = sysRoleMenuService.list(wrapper);
        for (SysRoleMenu rm : list) {
            List<ResourceNode> tree = null;
            try {
                tree = JsonSerializeUtil.parseObject(rm.getResourceTreeJson(), new TypeReference<List<ResourceNode>>() {});
            } catch (Exception e) {
                log.error("resourceTreeJson deserialize failed: " + e.getMessage(), e);
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemResourceTreeJsonInvalid);
            }
            if (tree == null || !containsAnyResourceId(tree, toDeleteIds)) {
                continue;
            }
            List<ResourceNode> pruned = pruneResourceTree(tree, toDeleteIds);
            rm.setResourceTreeJson(CollectionUtils.isEmpty(pruned) ? null : JsonSerializeUtil.toJSONString(pruned));
            sysRoleMenuService.updateById(rm);
        }
    }

    /**
     * 更新 sys_group_menu 的 resource_tree_json，移除已删除的资源ID对应节点
     */
    private void updateGroupMenuResourceTree(Set<Long> toDeleteIds, Long tenantId) {
        LambdaQueryWrapper<SysGroupMenu> wrapper = Wrappers.<SysGroupMenu>lambdaQuery()
                .isNotNull(SysGroupMenu::getResourceTreeJson)
                .ne(SysGroupMenu::getResourceTreeJson, "");
        if (tenantId != null) {
            wrapper.eq(SysGroupMenu::getTenantId, tenantId);
        }
        List<SysGroupMenu> list = sysGroupMenuService.list(wrapper);
        for (SysGroupMenu gm : list) {
            List<ResourceNode> tree = null;
            try {
                tree = JsonSerializeUtil.parseObject(gm.getResourceTreeJson(), new TypeReference<List<ResourceNode>>() {});
            } catch (Exception e) {
                log.error("resourceTreeJson deserialize failed: " + e.getMessage(), e);
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemResourceTreeJsonInvalid);
            }
            if (tree == null || !containsAnyResourceId(tree, toDeleteIds)) {
                continue;
            }
            List<ResourceNode> pruned = pruneResourceTree(tree, toDeleteIds);
            gm.setResourceTreeJson(CollectionUtils.isEmpty(pruned) ? null : JsonSerializeUtil.toJSONString(pruned));
            sysGroupMenuService.updateById(gm);
        }
    }

    /**
     * 将 parseObjectGeneric 得到的 Object（List/Map 结构）转为 List&lt;ResourceNode&gt;
     */
    private List<ResourceNode> parseResourceTreeFromObject(Object obj) {
        if (obj == null || !(obj instanceof List)) {
            return null;
        }
        List<ResourceNode> out = new ArrayList<>();
        for (Object e : (List<?>) obj) {
            ResourceNode n = parseResourceNodeFromObject(e);
            if (n != null) {
                out.add(n);
            }
        }
        return out;
    }

    private ResourceNode parseResourceNodeFromObject(Object obj) {
        if (obj == null || !(obj instanceof Map)) {
            return null;
        }
        Map<?, ?> m = (Map<?, ?>) obj;
        ResourceNode n = new ResourceNode();
        Object ro = m.get("resourceId");
        if (ro instanceof Number) {
            n.setId(((Number) ro).longValue());
        }
        Object rbo = m.get("resourceBindType");
        if (rbo instanceof Number) {
            n.setResourceBindType(((Number) rbo).intValue());
        }
        Object ch = m.get("children");
        n.setChildren(parseResourceTreeFromObject(ch));
        return n;
    }

    /**
     * 递归检查资源树中是否包含 toCheck 中的任意 resourceId
     */
    private boolean containsAnyResourceId(List<ResourceNode> tree, Set<Long> toCheck) {
        if (CollectionUtils.isEmpty(tree)) {
            return false;
        }
        for (ResourceNode n : tree) {
            if (n.getId() != null && toCheck.contains(n.getId())) {
                return true;
            }
            if (containsAnyResourceId(n.getChildren(), toCheck)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 递归修剪资源树：移除 resourceId 在 toDelete 中的节点（及其整棵子树）
     */
    private List<ResourceNode> pruneResourceTree(List<ResourceNode> tree, Set<Long> toDelete) {
        if (CollectionUtils.isEmpty(tree)) {
            return null;
        }
        List<ResourceNode> out = new ArrayList<>();
        for (ResourceNode n : tree) {
            if (n.getId() != null && toDelete.contains(n.getId())) {
                continue;
            }
            ResourceNode c = new ResourceNode();
            c.setId(n.getId());
            c.setResourceBindType(n.getResourceBindType());
            List<ResourceNode> prunedCh = pruneResourceTree(n.getChildren(), toDelete);
            c.setChildren(prunedCh);
            out.add(c);
        }
        return out.isEmpty() ? null : out;
    }

    @Override
    public SysResource queryResourceById(Long resourceId) {
        LambdaQueryWrapper<SysResource> wrapper = Wrappers.<SysResource>lambdaQuery()
                .eq(SysResource::getId, resourceId)
                .eq(SysResource::getYn, YnEnum.Y.getKey());
        return sysResourceService.getOne(wrapper);
    }

    @Override
    public SysResource queryResourceByCode(String resourceCode) {
        LambdaQueryWrapper<SysResource> wrapper = Wrappers.<SysResource>lambdaQuery()
                .eq(SysResource::getCode, resourceCode)
                .eq(SysResource::getYn, YnEnum.Y.getKey());
        return sysResourceService.getOne(wrapper);
    }

    @Override
    public List<SysResource> queryResourceList(SysResource resource) {
        LambdaQueryWrapper<SysResource> wrapper = Wrappers.<SysResource>lambdaQuery()
                .eq(SysResource::getYn, YnEnum.Y.getKey())
                .orderByAsc(SysResource::getSortIndex);
        if (resource != null) {
            wrapper.eq(StringUtils.isNotBlank(resource.getCode()), SysResource::getCode, resource.getCode())
                    .like(StringUtils.isNotBlank(resource.getName()), SysResource::getName, resource.getName())
                    .eq(Objects.nonNull(resource.getSource()), SysResource::getSource, resource.getSource())
                    .eq(Objects.nonNull(resource.getType()), SysResource::getType, resource.getType())
                    .eq(Objects.nonNull(resource.getParentId()), SysResource::getParentId, resource.getParentId())
                    .eq(Objects.nonNull(resource.getStatus()), SysResource::getStatus, resource.getStatus());
        }
        return sysResourceService.list(wrapper);
    }

    @Override
    public List<SysResource> queryResourceByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return new ArrayList<>();
        }
        return sysResourceService.listByIds(ids);
    }
}

