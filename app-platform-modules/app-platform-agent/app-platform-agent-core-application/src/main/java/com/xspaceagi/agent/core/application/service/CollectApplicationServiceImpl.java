package com.xspaceagi.agent.core.application.service;

import com.xspaceagi.agent.core.adapter.application.CollectApplicationService;
import com.xspaceagi.agent.core.adapter.application.PublishApplicationService;
import com.xspaceagi.agent.core.adapter.dto.PublishedDto;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.adapter.repository.entity.PublishedStatistics;
import com.xspaceagi.agent.core.adapter.repository.entity.UserTargetRelation;
import com.xspaceagi.agent.core.domain.service.PublishDomainService;
import com.xspaceagi.agent.core.domain.service.UserTargetRelationDomainService;
import com.xspaceagi.system.spec.exception.BizException;
import jakarta.annotation.Resource;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;

@Service
public class CollectApplicationServiceImpl implements CollectApplicationService {

    @Resource
    private PublishApplicationService publishApplicationService;

    @Resource
    private PublishDomainService publishDomainService;

    @Resource
    private UserTargetRelationDomainService userTargetRelationDomainService;

    @Override
    public void collect(Long userId, Published.TargetType targetType, Long targetId) {
        if (publishApplicationService.queryPublishedList(targetType, List.of(targetId)).size() == 0) {
            throw new BizException("未发布或已下架");
        }
        boolean res = userTargetRelationDomainService.record(userId, targetType, UserTargetRelation.OpType.Collect, targetId);
        if (res) {
            publishDomainService.incStatisticsCount(targetType, targetId, PublishedStatistics.Key.COLLECT_COUNT.getKey(), 1L);
        }
    }

    @Override
    public void unCollect(Long userId, Published.TargetType targetType, Long targetId) {
        boolean res = userTargetRelationDomainService.unRecord(userId, targetType, UserTargetRelation.OpType.Collect, targetId);
        if (res) {
            publishDomainService.incStatisticsCount(targetType, targetId, PublishedStatistics.Key.COLLECT_COUNT.getKey(), -1L);
        }
    }

    @Override
    public List<PublishedDto> queryCollectList(Long userId, Published.TargetType targetType, Long spaceId) {
        //用户收藏的不会太多，所以这里不分页
        List<UserTargetRelation> userTargetRelations = userTargetRelationDomainService.queryCollectionList(userId, targetType, 1, Integer.MAX_VALUE);
        if (userTargetRelations.size() > 0) {
            List<PublishedDto> publishedDtos = publishApplicationService.queryPublishedList(targetType, userTargetRelations.stream().map(UserTargetRelation::getTargetId).toList());
            //如果spaceId为空，过滤出scope为Global和Tenant的数据
            if (spaceId == null) {
                return publishedDtos.stream().filter(publishedDto -> publishedDto.getScope() == Published.PublishScope.Global || publishedDto.getScope() == Published.PublishScope.Tenant).toList();
            } else {
                //publishedDtos根据targetId去重
                publishedDtos = publishedDtos.stream().collect(Collectors.collectingAndThen(Collectors.toCollection(() -> new TreeSet<>(Comparator.comparing(PublishedDto::getTargetId))), ArrayList::new));
                //如果spaceId不为空，过滤出scope为Global、Tenant和以及spaceId的数据
                return publishedDtos.stream().filter(publishedDto -> publishedDto.getScope() == Published.PublishScope.Global || publishedDto.getScope() == Published.PublishScope.Tenant || publishedDto.getSpaceId().equals(spaceId)).toList();
            }
        }
        return List.of();
    }

    @Override
    public List<PublishedDto> queryCollectList(Long userId, Published.TargetType targetType, List<Long> spaceIds) {
        //用户收藏的不会太多，所以这里不分页
        List<UserTargetRelation> userTargetRelations = userTargetRelationDomainService.queryCollectionList(userId, targetType, 1, Integer.MAX_VALUE);
        if (CollectionUtils.isNotEmpty(userTargetRelations)) {
            List<PublishedDto> publishedDtos = publishApplicationService.queryPublishedList(targetType, userTargetRelations.stream().map(UserTargetRelation::getTargetId).toList());
            //如果spaceIds为空，过滤出scope为Global和Tenant的数据
            if (CollectionUtils.isEmpty(spaceIds)) {
                return publishedDtos.stream().filter(publishedDto -> publishedDto.getScope() == Published.PublishScope.Global || publishedDto.getScope() == Published.PublishScope.Tenant).toList();
            } else {
                //publishedDtos根据targetId去重
                publishedDtos = publishedDtos.stream().collect(Collectors.collectingAndThen(Collectors.toCollection(() -> new TreeSet<>(Comparator.comparing(PublishedDto::getTargetId))), ArrayList::new));
                //如果spaceIds不为空，过滤出scope为Global、Tenant和以及spaceIds的数据
                return publishedDtos.stream().filter(publishedDto -> publishedDto.getScope() == Published.PublishScope.Global || publishedDto.getScope() == Published.PublishScope.Tenant || spaceIds.contains(publishedDto.getSpaceId())).toList();
            }
        }
        return List.of();
    }

    @Override
    public List<PublishedDto> queryCollectListWithoutConfig(Long userId, Published.TargetType targetType, List<Long> spaceIds) {
        //用户收藏的不会太多，所以这里不分页
        List<UserTargetRelation> userTargetRelations = userTargetRelationDomainService.queryCollectionList(userId, targetType, 1, Integer.MAX_VALUE);
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