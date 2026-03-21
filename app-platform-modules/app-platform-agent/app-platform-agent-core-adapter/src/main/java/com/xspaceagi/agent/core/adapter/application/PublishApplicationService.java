package com.xspaceagi.agent.core.adapter.application;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xspaceagi.agent.core.adapter.dto.*;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.system.spec.dto.PageQueryVo;
import com.xspaceagi.system.spec.page.SuperPage;

import java.util.List;

public interface PublishApplicationService {

    /**
     * 获取已发布分页结果
     */
    SuperPage<PublishedDto> queryPublishedList(PublishedQueryDto publishedQueryDto);

    SuperPage<PublishedDto> queryPublishedListForAt(PublishedQueryDto publishedQueryDto);

    List<PublishedDto> queryPublishedList(Published.TargetType targetType, List<Long> targetIds);

    List<PublishedDto> queryPublishedListWithoutConfig(Published.TargetType targetType, List<Long> targetIds, String kw);

    List<PublishedDto> queryPublishedList(Published.TargetType targetType, List<Long> targetIds, String kw);

    IPage<PublishedDto> queryPublishedListForManage(PublishedQueryDto publishedQueryDto);

    /**
     * 获取已发布详情
     */
    PublishedDto queryPublished(Published.TargetType targetType, Long targetId);

    PublishedDto queryPublished(Published.TargetType targetType, Long targetId, boolean loadConfig);

    PublishedDto queryPublishedWithSpaceId(Published.TargetType targetType, Long targetId, Long spaceId);

    /**
     * 发布申请
     */
    Long publishApply(PublishApplyDto publishApplyDto);

    /**
     * 通过发布
     */
    void publish(Long applyId);


    void publish(Published.TargetType targetType, Long targetId, Published.PublishScope scope, List<PublishApplyDto> publishApplies);

    /**
     * 复制发布信息到指定空间
     */
    Long copyPublish(Long userId, Published.TargetType targetType, Long targetId, Long originalSpaceId, Long targetSpaceId);

    /**
     * 拒绝发布
     */
    void rejectPublish(PublishRejectDto publishRejectDto);

    /**
     * 下架
     */
    void offShelf(OffShelfDto offShelfDto);

    /**
     * 查询发布申请列表
     *
     * @param pageQueryVo
     * @return
     */
    IPage<PublishApplyDto> queryPublishApplyList(PageQueryVo<PublishApplyQueryDto> pageQueryVo);

    /**
     * 根据ID查询发布申请
     *
     * @param applyId
     * @return
     */
    PublishApplyDto queryPublishApplyById(Long applyId);

    /**
     * 根据publishId查询数据
     */
    PublishedDto queryPublishedById(Long publishId);

    /**
     * 增加统计数量
     *
     * @param targetType
     * @param targetId
     * @param key
     * @param inc
     */
    void incStatisticsCount(Published.TargetType targetType, Long targetId, String key, Long inc);

    /**
     * 删除发布申请
     */
    void deletePublishedApply(Published.TargetType type, Long targetId);

    StatisticsDto queryStatistics(Published.TargetType type, Long targetId);


    void deleteBySpaceId(Long spaceId);

    PublishedPermissionDto hasPermission(Published.TargetType targetType, Long targetId);

    void updateAccessControlStatus(Published.TargetType targetType, Long targetId, Integer status);

    void updatePublishName(Published.TargetType targetType, Long targetId, String name);
}
