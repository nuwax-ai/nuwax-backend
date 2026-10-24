package com.xspaceagi.agent.core.adapter.application;

import com.xspaceagi.agent.core.adapter.dto.PublishedDto;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;

import java.util.List;

/**
 * 用户启用清单，实现方式对齐 {@link CollectApplicationService}。
 * 启用仅记录用户与目标的关系，不校验发布状态、不计入统计。
 */
public interface EnableApplicationService {

    /**
     * 启用
     */
    void enable(Long userId, Published.TargetType targetType, Long targetId);

    /**
     * 取消启用
     */
    void unEnable(Long userId, Published.TargetType targetType, Long targetId);

    /**
     * 查询启用列表（不返回 config，仅返回展示所需的基础信息）
     */
    List<PublishedDto> queryEnableListWithoutConfig(Long userId, Published.TargetType targetType, List<Long> spaceIds);
}
