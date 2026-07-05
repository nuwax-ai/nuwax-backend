package com.xspaceagi.agent.core.domain.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xspaceagi.agent.core.adapter.dto.PublishApplyQueryDto;
import com.xspaceagi.agent.core.adapter.dto.PublishedQueryDto;
import com.xspaceagi.agent.core.adapter.dto.StatisticsDto;
import com.xspaceagi.agent.core.adapter.repository.entity.PublishApply;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.system.spec.dto.PageQueryVo;
import com.xspaceagi.system.spec.page.SuperPage;

import java.util.List;
import java.util.Map;

public interface PublishDomainService {


    /**
     * 删除
     */
    void deleteByTargetId(Published.TargetType targetType, Long targetId);

    /**
     * 发布申请
     */
    void publishApply(PublishApply publishApply);

    /**
     * 审核通过并发布
     */
    void publish(PublishApply publishApply, List<Published> publishedList);

    void savePublished(Published published);

    void updatePublishedGroupInfo(Published.TargetType targetType, Long targetId, Long groupId);

    /**
     * 审核不通过
     */
    void rejectPublish(Long applyId);

    /**
     * ID查询发布申请
     */
    PublishApply queryPublishApplyById(Long applyId);

    /**
     * 通过类型和id查询发布记录
     */
    List<PublishApply> queryPublishApplyList(Published.TargetType targetType, Long targetId);

    List<PublishApply> queryPublishApplyingList(Published.TargetType targetType, Long targetId);

    /**
     * 更新发布申请
     */
    void updatePublishApply(PublishApply publishApply);

    void offShelfTemplate(Long publishId);


    /**
     * 分页查询发布记录(工作流,插件,智能体)
     *
     * @param publishedQueryDto 查询条件
     * @return 分页结果
     */
    SuperPage<Published> queryPublishedList(PublishedQueryDto publishedQueryDto);

    IPage<Published> queryPublishedListForManage(PublishedQueryDto publishedQueryDto);


    List<Published> queryPublishedList(Published.TargetType targetType, List<Long> targetIds, String kw);

    List<Published> queryPublishedListWithoutConfig(Published.TargetType targetType, List<Long> targetIds, String kw);

    List<Published> queryPublishedList(Published.TargetType targetType, List<Long> targetIds);

    List<Published> queryPublishedListWithoutConfig(Published.TargetType targetType, List<Long> targetIds);

    Published queryPublishedByTargetId(Published.TargetType targetType, Long targetId);

    Published queryPublishedById(Long publishId);

    IPage<PublishApply> queryPublishApplyList(PageQueryVo<PublishApplyQueryDto> pageQueryVo);

    void incStatisticsCount(Published.TargetType targetType, Long targetId, String key, Long inc);

    StatisticsDto queryStatisticsCount(Published.TargetType targetType, Long targetId);

    List<StatisticsDto> queryStatisticsCountList(Published.TargetType targetType, List<Long> targetIds);

    void addPublishedStatistics(Published.TargetType targetType, Long targetId, Map<String, Long> object);

    Published queryPublished(Long publishedId);

    void deleteByPublishedId(Long id);

    void deletePublishedApply(Published.TargetType type, Long targetId);

    void deletePublishedApplyById(Long applyId);

    void deleteBySpaceId(Long spaceId);
}
