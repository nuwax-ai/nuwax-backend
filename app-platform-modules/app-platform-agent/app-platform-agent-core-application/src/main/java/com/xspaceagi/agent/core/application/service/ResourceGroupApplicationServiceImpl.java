package com.xspaceagi.agent.core.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xspaceagi.agent.core.adapter.application.ResourceGroupApplicationService;
import com.xspaceagi.agent.core.adapter.dto.ResourceGroupDto;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.adapter.repository.entity.ResourceGroup;
import com.xspaceagi.agent.core.adapter.repository.entity.ResourceGroupRelation;
import com.xspaceagi.agent.core.domain.service.PublishDomainService;
import com.xspaceagi.agent.core.domain.service.ResourceGroupDomainService;
import com.xspaceagi.agent.core.domain.service.ResourceGroupRelationDomainService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ResourceGroupApplicationServiceImpl implements ResourceGroupApplicationService {

    @Resource
    private ResourceGroupDomainService resourceGroupDomainService;

    @Resource
    private ResourceGroupRelationDomainService resourceGroupRelationDomainService;

    @Resource
    private PublishDomainService publishDomainService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long add(ResourceGroupDto dto) {
        if (StringUtils.isBlank(dto.getName())) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "name");
        }
        if (StringUtils.isBlank(dto.getType())) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "type");
        }

        ResourceGroup resourceGroup = new ResourceGroup();
        BeanUtils.copyProperties(dto, resourceGroup);
        resourceGroup.setId(null);
        resourceGroup.setCreated(null);
        resourceGroup.setModified(null);
        resourceGroup.setToolCount(0);
        resourceGroup.setTenantId(RequestContext.get().getTenantId());
        resourceGroup.setCreatorId(RequestContext.get().getUserId());

        return resourceGroupDomainService.add(resourceGroup);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(ResourceGroupDto dto) {
        if (dto.getId() == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "id");
        }
        if (StringUtils.isBlank(dto.getName())) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "name");
        }

        ResourceGroup exist = resourceGroupDomainService.queryById(dto.getId());
        if (exist == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.resourceDataNotFound);
        }

        ResourceGroup resourceGroup = new ResourceGroup();
        resourceGroup.setId(dto.getId());
        resourceGroup.setName(dto.getName());
        resourceGroup.setDescription(dto.getDescription());
        resourceGroup.setIcon(dto.getIcon());
        resourceGroup.setType(dto.getType());
        resourceGroup.setSpaceId(dto.getSpaceId());
        resourceGroup.setModified(new Date());

        resourceGroupDomainService.update(resourceGroup);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("Resource group ID cannot be empty");
        }
        resourceGroupDomainService.delete(id);

        List<ResourceGroupRelation> resourceGroupRelations = queryGroupRelations(id);
        resourceGroupRelations.forEach(relation -> publishDomainService.updatePublishedGroupInfo(Published.TargetType.valueOf(relation.getTargetType()), relation.getTargetId(), null));
        resourceGroupRelations.forEach(relation -> resourceGroupRelationDomainService.delete(relation.getId()));
    }

    @Override
    public ResourceGroupDto queryById(Long id) {
        ResourceGroup resourceGroup = resourceGroupDomainService.queryById(id);
        if (resourceGroup == null) {
            return null;
        }
        ResourceGroupDto dto = new ResourceGroupDto();
        BeanUtils.copyProperties(resourceGroup, dto);
        return dto;
    }

    @Override
    public ResourceGroupDto queryByName(Long spaceId, String type, String name) {
        LambdaQueryWrapper<ResourceGroup> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper
                .eq(spaceId != null, ResourceGroup::getSpaceId, spaceId)
                .eq(StringUtils.isNotBlank(type), ResourceGroup::getType, type)
                .eq(StringUtils.isNotBlank(name), ResourceGroup::getName, name);
        return resourceGroupDomainService.list(queryWrapper).stream().map(entity -> {
            ResourceGroupDto dto = new ResourceGroupDto();
            BeanUtils.copyProperties(entity, dto);
            return dto;
        }).findFirst().orElse(null);
    }

    @Override
    public List<ResourceGroupDto> queryList(String name, List<String> types, Long spaceId) {
        LambdaQueryWrapper<ResourceGroup> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper
                .eq(spaceId != null, ResourceGroup::getSpaceId, spaceId)
                .in(!CollectionUtils.isEmpty(types), ResourceGroup::getType, types)
                .like(StringUtils.isNotBlank(name), ResourceGroup::getName, name)
                .orderByDesc(ResourceGroup::getId);

        List<ResourceGroup> list = resourceGroupDomainService.list(queryWrapper);
        return list.stream().map(entity -> {
            ResourceGroupDto dto = new ResourceGroupDto();
            BeanUtils.copyProperties(entity, dto);
            return dto;
        }).collect(Collectors.toList());
    }

    @Override
    public List<ResourceGroupDto> queryList(List<Long> groupIds) {
        LambdaQueryWrapper<ResourceGroup> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(ResourceGroup::getId, groupIds);
        return resourceGroupDomainService.list(queryWrapper).stream().map(entity -> {
            ResourceGroupDto dto = new ResourceGroupDto();
            BeanUtils.copyProperties(entity, dto);
            return dto;
        }).collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addResourceToGroup(Long groupId, String targetType, Long targetId) {
        ResourceGroup group = resourceGroupDomainService.queryById(groupId);
        if (group == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.resourceDataNotFound);
        }

        LambdaQueryWrapper<ResourceGroupRelation> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper
                .eq(ResourceGroupRelation::getGroupId, groupId)
                .eq(ResourceGroupRelation::getTargetType, targetType)
                .eq(ResourceGroupRelation::getTargetId, targetId);
        List<ResourceGroupRelation> existList = resourceGroupRelationDomainService.list(queryWrapper);
        if (!existList.isEmpty()) {
            return;
        }

        ResourceGroupRelation relation = ResourceGroupRelation.builder()
                .tenantId(RequestContext.get().getTenantId())
                .groupId(groupId)
                .targetType(targetType)
                .targetId(targetId)
                .build();
        resourceGroupRelationDomainService.add(relation);

        ResourceGroup updateGroup = new ResourceGroup();
        updateGroup.setId(groupId);
        updateGroup.setToolCount(resourceGroupRelationDomainService.countByGroupId(groupId));
        resourceGroupDomainService.update(updateGroup);

        publishDomainService.updatePublishedGroupInfo(Published.TargetType.valueOf(targetType), targetId, groupId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeResourceFromGroup(Long groupId, String targetType, Long targetId) {
        resourceGroupRelationDomainService.deleteByGroupIdAndTarget(groupId, targetType, targetId);

        ResourceGroup updateGroup = new ResourceGroup();
        updateGroup.setId(groupId);
        updateGroup.setToolCount(resourceGroupRelationDomainService.countByGroupId(groupId));
        resourceGroupDomainService.update(updateGroup);

        publishDomainService.updatePublishedGroupInfo(Published.TargetType.valueOf(targetType), targetId, null);
    }

    @Override
    public List<ResourceGroupRelation> queryGroupRelations(Long groupId) {
        return resourceGroupRelationDomainService.listByGroupId(groupId);
    }

    @Override
    public List<ResourceGroupRelation> queryGroupRelations(List<Map<String, Long>> targets) {
        LambdaQueryWrapper<ResourceGroupRelation> queryWrapper = new LambdaQueryWrapper<>();
        for (Map<String, Long> target : targets) {
            for (String key : target.keySet()) {
                queryWrapper.or(wrapper -> {
                    wrapper.eq(ResourceGroupRelation::getTargetType, key);
                    wrapper.eq(ResourceGroupRelation::getTargetId, target.get(key));
                });
            }
        }
        return resourceGroupRelationDomainService.list(queryWrapper);
    }

    @Override
    public ResourceGroupRelation queryResourceGroupRelation(String type, Long targetId) {
        LambdaQueryWrapper<ResourceGroupRelation> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper
                .eq(ResourceGroupRelation::getTargetType, type)
                .eq(ResourceGroupRelation::getTargetId, targetId);
        return resourceGroupRelationDomainService.queryOne(queryWrapper);
    }
}
