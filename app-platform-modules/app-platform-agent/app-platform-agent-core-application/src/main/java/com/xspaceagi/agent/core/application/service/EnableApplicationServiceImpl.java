package com.xspaceagi.agent.core.application.service;

import com.xspaceagi.agent.core.adapter.application.EnableApplicationService;
import com.xspaceagi.agent.core.adapter.application.PublishApplicationService;
import com.xspaceagi.agent.core.adapter.dto.PublishedDto;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.adapter.repository.entity.UserTargetRelation;
import com.xspaceagi.agent.core.domain.service.UserTargetRelationDomainService;
import jakarta.annotation.Resource;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;

@Service
public class EnableApplicationServiceImpl implements EnableApplicationService {

    @Resource
    private PublishApplicationService publishApplicationService;

    @Resource
    private UserTargetRelationDomainService userTargetRelationDomainService;

    @Override
    public void enable(Long userId, Published.TargetType targetType, Long targetId) {
        userTargetRelationDomainService.record(userId, targetType, UserTargetRelation.OpType.Enable, targetId);
    }

    @Override
    public void unEnable(Long userId, Published.TargetType targetType, Long targetId) {
        userTargetRelationDomainService.unRecord(userId, targetType, UserTargetRelation.OpType.Enable, targetId);
    }

    @Override
    public List<PublishedDto> queryEnableListWithoutConfig(Long userId, Published.TargetType targetType, List<Long> spaceIds) {
        //用户启用的不会太多，所以这里不分页
        List<UserTargetRelation> userTargetRelations = userTargetRelationDomainService.queryEnableList(userId, targetType, 1, Integer.MAX_VALUE);
        if (CollectionUtils.isNotEmpty(userTargetRelations)) {
            List<PublishedDto> publishedDtos = publishApplicationService.queryPublishedListWithoutConfig(targetType, userTargetRelations.stream().map(UserTargetRelation::getTargetId).toList(), null);
            //如果spaceIds为空，过滤出scope为Global和Tenant的数据
            if (CollectionUtils.isEmpty(spaceIds)) {
                return publishedDtos.stream().filter(publishedDto -> publishedDto.getScope() == Published.PublishScope.Global || publishedDto.getScope() == Published.PublishScope.Tenant).toList();
            } else {
                //如果spaceIds不为空，过滤出scope为Global、Tenant和以及spaceIds的数据
                publishedDtos = publishedDtos.stream().filter(publishedDto -> publishedDto.getScope() == Published.PublishScope.Global || publishedDto.getScope() == Published.PublishScope.Tenant || spaceIds.contains(publishedDto.getSpaceId())).toList();

                //去重必须放在最后
                publishedDtos = publishedDtos.stream().collect(Collectors.collectingAndThen(Collectors.toCollection(() -> new TreeSet<>(Comparator.comparing(PublishedDto::getTargetId))), ArrayList::new));
                return publishedDtos;
            }
        }
        return List.of();
    }
}
