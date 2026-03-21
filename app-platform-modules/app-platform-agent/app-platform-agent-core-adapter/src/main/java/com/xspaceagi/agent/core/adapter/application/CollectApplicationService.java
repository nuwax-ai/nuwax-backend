package com.xspaceagi.agent.core.adapter.application;

import com.xspaceagi.agent.core.adapter.dto.PublishedDto;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;

import java.util.List;

public interface CollectApplicationService {


    /**
     * 收藏
     */
    void collect(Long userId, Published.TargetType targetType, Long targetId);

    /**
     * 取消收藏
     */
    void unCollect(Long userId, Published.TargetType targetType, Long targetId);


    /**
     * 查询收藏列表
     * @param userId
     * @param targetType
     * @param spaceId
     * @return
     */
    List<PublishedDto> queryCollectList(Long userId, Published.TargetType targetType, Long spaceId);

    /**
     * 查询收藏列表
     */
    List<PublishedDto> queryCollectList(Long userId, Published.TargetType targetType, List<Long> spaceIds);

    /**
     * 查询收藏列表
     */
    List<PublishedDto> queryCollectListWithoutConfig(Long userId, Published.TargetType targetType, List<Long> spaceIds);
}
