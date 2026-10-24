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

    /**
     * 收藏常规项目
     */
    void collectNormalProject(Long userId, Long projectId);

    /**
     * 取消收藏常规项目
     */
    void unCollectNormalProject(Long userId, Long projectId);

    /**
     * 收藏全栈应用项目
     */
    void collectUserApp(Long userId, Long appId);

    /**
     * 取消收藏全栈应用项目
     */
    void unCollectUserApp(Long userId, Long appId);

    /**
     * 收藏三方应用
     */
    void collectThirdApp(Long userId, Long projectId);

    /**
     * 取消收藏三方应用
     */
    void unCollectThirdApp(Long userId, Long projectId);

    /**
     * 批量查询用户已收藏的目标ID（列表回填 collected 标记用）
     */
    List<Long> queryCollectedTargetIds(Long userId, Published.TargetType targetType, List<Long> targetIds);

    /**
     * 查询用户全部收藏的目标ID（收藏过滤查询用，不分页）
     */
    List<Long> queryAllCollectedTargetIds(Long userId, Published.TargetType targetType);

    /**
     * 清除目标全部用户的收藏关系（目标删除时调用）
     */
    void clearCollect(Published.TargetType targetType, Long targetId);
}
