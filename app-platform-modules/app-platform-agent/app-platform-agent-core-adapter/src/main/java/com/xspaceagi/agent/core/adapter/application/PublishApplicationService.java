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

    /**
     * 应用列表分页查询（网页应用PageApp+全栈应用UserApp）
     */
    SuperPage<PublishedDto> queryAppList(PublishedQueryDto publishedQueryDto);

    /**
     * 最近使用的应用列表（网页应用PageApp+全栈应用UserApp）
     */
    List<PublishedDto> queryRecentlyUsedApps(String kw, Integer size);

    /**
     * 记录最近使用（如全栈应用的代理访问）
     */
    void addOrUpdateRecentUsed(Long userId, Published.TargetType targetType, Long targetId, String targetSubType);

    List<ResourceGroupDto> queryGroupedPublishedList(PublishedQueryDto publishedQueryDto);

    SuperPage<PublishedDto> queryPublishedListForAt(PublishedQueryDto publishedQueryDto);

    List<PublishedDto> queryPublishedList(Published.TargetType targetType, List<Long> targetIds);

    List<PublishedDto> queryPublishedListWithoutConfig(Published.TargetType targetType, List<Long> targetIds, String kw);

    List<PublishedDto> queryPublishedList(Published.TargetType targetType, List<Long> targetIds, String kw);

    List<PublishedDto> queryPublishedList(Published.TargetType targetType, List<Long> targetIds, String kw, boolean returnConfig, boolean returnFullInfo);

    IPage<PublishedDto> queryPublishedListForManage(PublishedQueryDto publishedQueryDto);

    /**
     * 获取已发布详情
     */
    PublishedDto queryPublished(Published.TargetType targetType, Long targetId);

    PublishedDto queryPublished(Published.TargetType targetType, Long targetId, boolean loadConfig);

    /**
     * 轻量发布查询：仅判存在性与子类型（不含 config/统计/发布者/收藏富化），供点击埋点等热路径使用
     */
    PublishedDto queryPublishedBrief(Published.TargetType targetType, Long targetId);

    PublishedDto queryPublishedWithSpaceId(Published.TargetType targetType, Long targetId, Long spaceId);

    /**
     * 发布申请
     */
    Long publishApply(PublishApplyDto publishApplyDto);

    String publishOrApply(PublishApplySubmitDto publishApplySubmitDto);

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

    /**
     * 按目标对象删除发布记录与统计（项目彻底删除用）
     */
    void deleteByTarget(Published.TargetType targetType, Long targetId);

    /**
     * 按目标对象删除全部用户关系（收藏/最近使用等，项目彻底删除用）；返回删除条数
     */
    int deleteTargetRelations(Published.TargetType targetType, Long targetId);

    PublishedPermissionDto hasPermission(Published.TargetType targetType, Long targetId);

    PublishedPermissionDto hasPermission(Long userId, Published.TargetType targetType, Long targetId);

    void updateAccessControlStatus(Published.TargetType targetType, Long targetId, Integer status);

    void updatePublishName(Published.TargetType targetType, Long targetId, String name);

    Object checkPermissionAndReturnTargetConfig(Published.TargetType targetType, Long targetId);
}
