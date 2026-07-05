package com.xspaceagi.agent.core.adapter.application;

import com.xspaceagi.agent.core.adapter.dto.ResourceGroupDto;
import com.xspaceagi.agent.core.adapter.repository.entity.ResourceGroupRelation;

import java.util.List;
import java.util.Map;

public interface ResourceGroupApplicationService {

    Long add(ResourceGroupDto dto);

    void update(ResourceGroupDto dto);

    void delete(Long id);

    ResourceGroupDto queryById(Long id);

    ResourceGroupDto queryByName(Long spaceId, String type, String name);

    List<ResourceGroupDto> queryList(String name, List<String> types, Long spaceId);

    List<ResourceGroupDto> queryList(List<Long> groupIds);

    void addResourceToGroup(Long groupId, String targetType, Long targetId);

    void removeResourceFromGroup(Long groupId, String targetType, Long targetId);

    List<ResourceGroupRelation> queryGroupRelations(Long groupId);

    List<ResourceGroupRelation> queryGroupRelations(List<Map<String, Long>> targets);

    ResourceGroupRelation queryResourceGroupRelation(String type, Long targetId);
}
