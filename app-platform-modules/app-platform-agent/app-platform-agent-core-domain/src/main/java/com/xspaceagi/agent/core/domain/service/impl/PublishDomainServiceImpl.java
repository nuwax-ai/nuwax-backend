package com.xspaceagi.agent.core.domain.service.impl;

import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xspaceagi.agent.core.adapter.dto.PublishApplyQueryDto;
import com.xspaceagi.agent.core.adapter.dto.PublishedQueryDto;
import com.xspaceagi.agent.core.adapter.dto.StatisticsDto;
import com.xspaceagi.agent.core.adapter.repository.PublishApplyRepository;
import com.xspaceagi.agent.core.adapter.repository.PublishedRepository;
import com.xspaceagi.agent.core.adapter.repository.PublishedStatisticsRepository;
import com.xspaceagi.agent.core.adapter.repository.entity.PublishApply;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.adapter.repository.entity.PublishedStatistics;
import com.xspaceagi.agent.core.adapter.repository.entity.ResourceGroupRelation;
import com.xspaceagi.agent.core.domain.service.PublishDomainService;
import com.xspaceagi.agent.core.domain.service.ResourceGroupRelationDomainService;
import com.xspaceagi.agent.core.spec.enums.UsageScenarioEnum;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.PageQueryVo;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.page.SuperPage;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class PublishDomainServiceImpl implements PublishDomainService {

    @Resource
    private PublishApplyRepository publishApplyRepository;

    @Resource
    private PublishedRepository publishedRepository;

    @Resource
    private PublishedStatisticsRepository publishedStatisticsRepository;

    @Resource
    private ResourceGroupRelationDomainService resourceGroupRelationDomainService;

    @Override
    public void deleteByTargetId(Published.TargetType targetType, Long targetId) {
        Assert.notNull(targetType, "targetType不能为空");
        Assert.notNull(targetId, "targetId不能为空");
        publishedRepository.remove(new QueryWrapper<>(Published.builder().targetType(targetType).targetId(targetId).build()));
        publishedStatisticsRepository.remove(new QueryWrapper<>(PublishedStatistics.builder().targetType(targetType).targetId(targetId).build()));
    }

    @Override
    public void publishApply(PublishApply publishApply) {
        publishApplyRepository.save(publishApply);
    }

    @Override
    @DSTransactional
    public void publish(PublishApply publishApply, List<Published> publishedList) {
        Assert.notNull(publishApply, "publishApply不能为空");
        Assert.notNull(publishedList, "publishedList不能为空");
        Assert.notNull(publishApply.getTargetType(), "targetType不能为空");
        Assert.notNull(publishApply.getTargetId(), "targetId不能为空");
        //先删除已发布的渠道
        LambdaQueryWrapper<Published> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Published::getTargetType, publishApply.getTargetType());
        queryWrapper.eq(Published::getTargetId, publishApply.getTargetId());
        queryWrapper.eq(Published::getScope, publishApply.getScope());
        publishedRepository.remove(queryWrapper);
        publishedList.forEach(published -> publishedRepository.save(published));
        // 分组内其他工具时间更新
        if (!CollectionUtils.isEmpty(publishedList)) {
            Published published = publishedList.get(0);
            if (published.getGroupId() != null) {
                List<ResourceGroupRelation> resourceGroupRelations = resourceGroupRelationDomainService.listByGroupId(published.getGroupId());
                resourceGroupRelations.forEach(resourceGroupRelation -> {
                    LambdaQueryWrapper<Published> queryWrapper1 = new LambdaQueryWrapper<>();
                    queryWrapper1.eq(Published::getTargetType, resourceGroupRelation.getTargetType());
                    queryWrapper1.eq(Published::getTargetId, resourceGroupRelation.getTargetId());
                    publishedRepository.update(Published.builder()
                            .groupId(published.getGroupId())
                            .modified(published.getModified())
                            .build(), queryWrapper1);
                });
            }
        }


        PublishApply publishApplyUpdate = new PublishApply();
        publishApplyUpdate.setId(publishApply.getId());
        publishApplyUpdate.setPublishStatus(Published.PublishStatus.Published);
        publishApplyRepository.updateById(publishApplyUpdate);
    }

    public void savePublished(Published published) {
        publishedRepository.save(published);
    }

    @Override
    public void updatePublishedGroupInfo(Published.TargetType targetType, Long targetId, Long groupId) {
        if (groupId != null) {
            boolean update = publishedRepository.update(Published.builder().groupId(groupId).build(), new QueryWrapper<>(Published.builder().targetType(targetType).targetId(targetId).build()));
            if (update) {
                //更新相同分组的时间
                publishedRepository.update(Published.builder().modified(new Date()).build(), new QueryWrapper<>(Published.builder().groupId(groupId).build()));
            }
        } else {
            UpdateWrapper<Published> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq("target_type", targetType);
            updateWrapper.eq("target_id", targetId);
            updateWrapper.set("group_id", null);
            publishedRepository.update(updateWrapper);
        }
    }

    @Override
    public void rejectPublish(Long applyId) {
        PublishApply publishApply = queryPublishApplyById(applyId);
        publishApply.setPublishStatus(Published.PublishStatus.Rejected);
        publishApplyRepository.updateById(publishApply);
    }

    @Override
    public PublishApply queryPublishApplyById(Long applyId) {
        return publishApplyRepository.getById(applyId);
    }

    @Override
    public List<PublishApply> queryPublishApplyList(Published.TargetType targetType, Long targetId) {
        Assert.notNull(targetType, "targetType不能为空");
        Assert.notNull(targetId, "targetId不能为空");
        return publishApplyRepository.list(new QueryWrapper<>(PublishApply.builder().targetType(targetType).targetId(targetId).build()));
    }

    @Override
    public List<PublishApply> queryPublishApplyingList(Published.TargetType targetType, Long targetId) {
        Assert.notNull(targetType, "targetType不能为空");
        Assert.notNull(targetId, "targetId不能为空");
        return publishApplyRepository.list(new QueryWrapper<>(PublishApply.builder().targetType(targetType).targetId(targetId).publishStatus(Published.PublishStatus.Applying).build()));
    }

    @Override
    public void updatePublishApply(PublishApply publishApply) {
        Assert.notNull(publishApply, "publishApply不能为空");
        Assert.notNull(publishApply.getId(), "id不能为空");
        publishApplyRepository.updateById(publishApply);
    }

    @Override
    public void offShelfTemplate(Long publishId) {
        Published published = publishedRepository.getById(publishId);
        if (published != null) {
            if (Objects.equals(published.getOnlyTemplate(), YesOrNoEnum.Y.getKey())) {
                publishedRepository.removeById(publishId);
            } else {
                published.setAllowCopy(YesOrNoEnum.N.getKey());
                publishedRepository.updateById(published);
            }
        }
    }

    @Override
    public SuperPage<Published> queryPublishedList(PublishedQueryDto publishedQueryDto) {
        Assert.notNull(publishedQueryDto.getTargetType(), "targetType不能为空");
        if (publishedQueryDto.getSpaceId() != null) {
            publishedQueryDto.setSpaceIds(Collections.singletonList(publishedQueryDto.getSpaceId()));
        }
        boolean justReturnSpaceData = publishedQueryDto.getJustReturnSpaceData() != null && publishedQueryDto.getJustReturnSpaceData();
        LambdaQueryWrapper<Published> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Published::getTargetType, publishedQueryDto.getTargetType());

        if (!CollectionUtils.isEmpty(publishedQueryDto.getSpaceIds())) {
            if (!justReturnSpaceData) {
                queryWrapper.and(publishedLambdaQueryWrapper -> publishedLambdaQueryWrapper.in(Published::getSpaceId, publishedQueryDto.getSpaceIds()).or().eq(Published::getScope, Published.PublishScope.Tenant));
            } else {
                queryWrapper.in(Published::getSpaceId, publishedQueryDto.getSpaceIds());
            }
        } else {
            queryWrapper.eq(Published::getScope, Published.PublishScope.Tenant);
        }
        if (StringUtils.isNotBlank(publishedQueryDto.getCategory())) {
            queryWrapper.eq(Published::getCategory, publishedQueryDto.getCategory());
        }
        if (StringUtils.isNotBlank(publishedQueryDto.getKw())) {
            queryWrapper.apply("MATCH(name, description) AGAINST({0} IN BOOLEAN MODE)", publishedQueryDto.getKw());
        }
        if (publishedQueryDto.getTargetSubType() != null) {
            if (Published.TargetSubType.ChatBot == publishedQueryDto.getTargetSubType()) {
                queryWrapper.in(Published::getTargetSubType, Published.TargetSubType.ChatBot, Published.TargetSubType.Single, Published.TargetSubType.TaskAgent);
            } else {
                queryWrapper.eq(Published::getTargetSubType, publishedQueryDto.getTargetSubType());
            }
        }

        if (publishedQueryDto.getAllowCopy() != null && publishedQueryDto.getAllowCopy().equals(YesOrNoEnum.Y.getKey())) {
            queryWrapper.eq(Published::getAllowCopy, publishedQueryDto.getAllowCopy());
        }
        if (publishedQueryDto.getOnlyTemplate() != null) {
            queryWrapper.eq(Published::getOnlyTemplate, publishedQueryDto.getOnlyTemplate());
        }
        if (publishedQueryDto.getAccessControl() != null) {
            queryWrapper.eq(Published::getAccessControl, publishedQueryDto.getAccessControl());
        }
        if (!CollectionUtils.isEmpty(publishedQueryDto.getTargetIds())) {
            queryWrapper.in(Published::getTargetId, publishedQueryDto.getTargetIds());
        }
        if (!CollectionUtils.isEmpty(publishedQueryDto.getExcludeIds())) {
            queryWrapper.notIn(Published::getTargetId, publishedQueryDto.getExcludeIds());
        }
        // 技能扩展字段筛选（ext JSON）
        if (publishedQueryDto.getTargetType() == Published.TargetType.Skill) {
            Boolean supportTaskAgent = extractUsageScenarioFlag(publishedQueryDto.getUsageScenarios(), UsageScenarioEnum.TaskAgent);
            Boolean supportPageApp = extractUsageScenarioFlag(publishedQueryDto.getUsageScenarios(), UsageScenarioEnum.PageApp);
            if (Boolean.TRUE.equals(supportTaskAgent)) {
                queryWrapper.apply(
                        "(CAST(JSON_UNQUOTE(JSON_EXTRACT(ext, '$.supportTaskAgent')) AS UNSIGNED) = 1 OR ext IS NULL)"
                );
            }
            if (Boolean.TRUE.equals(supportPageApp)) {
                queryWrapper.apply(
                        "CAST(JSON_UNQUOTE(JSON_EXTRACT(ext, '$.supportPageApp')) AS UNSIGNED) = 1"
                );
            }
        }
        if (publishedQueryDto.getTargetType() == Published.TargetType.Workflow) {
            queryWrapper.apply("(JSON_UNQUOTE(JSON_EXTRACT(ext, '$.workflowType')) = 'Workflow' OR ext IS NULL)");
        }
        if (!CollectionUtils.isEmpty(publishedQueryDto.getAgentTypes())) {
            List<String> types = publishedQueryDto.getAgentTypes();
            String placeholders = types.stream()
                    .map(t -> "'" + t.replace("'", "''") + "'")
                    .collect(Collectors.joining(","));
            queryWrapper.apply("(JSON_UNQUOTE(JSON_EXTRACT(ext, '$.agentType')) IN (" + placeholders + ") OR ext IS NULL)");//NULL兼容之前的数据
        }
        List<Published> recommendAgentList = new ArrayList<>();
        if (publishedQueryDto.getTargetType() == Published.TargetType.Agent
                && publishedQueryDto.getShowRecommend() != null && publishedQueryDto.getShowRecommend()
                && !justReturnSpaceData && publishedQueryDto.getAccessControl() == null) {
            //广场中排除站点默认智能体
            List<Long> excludeAgentIds = new ArrayList<>();
            TenantConfigDto tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
            if (tenantConfigDto.getDefaultAgentId() != null) {
                excludeAgentIds.add(tenantConfigDto.getDefaultAgentId());
            }
            //未选择分类时排除推荐的智能体，后续会统一展示在最前面
            if (StringUtils.isBlank(publishedQueryDto.getCategory()) && !CollectionUtils.isEmpty(publishedQueryDto.getRecommendIds())) {
                excludeAgentIds.addAll(publishedQueryDto.getRecommendIds());
                //展示在最前面
                if (publishedQueryDto.obtainPage() == 1) {
                    //查询推荐智能体
                    recommendAgentList = queryPublishedList(Published.TargetType.Agent, publishedQueryDto.getRecommendIds());
                    if (StringUtils.isNotBlank(publishedQueryDto.getKw())) {
                        recommendAgentList.removeIf(recommendAgent -> !recommendAgent.getName().contains(publishedQueryDto.getKw()));
                    }
                    if (publishedQueryDto.getTargetSubType() != null) {
                        if (Published.TargetSubType.ChatBot == publishedQueryDto.getTargetSubType()) {
                            recommendAgentList.removeIf(a -> a.getTargetSubType() != Published.TargetSubType.ChatBot
                                    && a.getTargetSubType() != Published.TargetSubType.Single && a.getTargetSubType() != Published.TargetSubType.TaskAgent);
                        } else {
                            recommendAgentList.removeIf(a -> a.getTargetSubType() != publishedQueryDto.getTargetSubType());
                        }
                    }
                    //根据tenantConfigDto.getRecommendAgentIds()从前到后的顺序对recommendAgentList排序
                    recommendAgentList.sort(Comparator.comparing(agent -> {
                        int index = publishedQueryDto.getRecommendIds().indexOf(agent.getTargetId());
                        return index == -1 ? Integer.MAX_VALUE : index;
                    }));

                }
            }
            if (!CollectionUtils.isEmpty(excludeAgentIds)) {
                queryWrapper.notIn(Published::getTargetId, excludeAgentIds);
            }
        }

        Integer size = publishedQueryDto.obtainPageSize();
        //查询总条数
        var dataTotalCount = 0L;
        Integer page = publishedQueryDto.obtainPage();
        if (publishedQueryDto.getLastTimestamp() == null) {
            dataTotalCount = publishedRepository.count(queryWrapper);
            queryWrapper.last("limit " + (page - 1) * size + "," + size);
        } else {
            queryWrapper.le(Published::getModified, new Date(publishedQueryDto.getLastTimestamp()));
            queryWrapper.last("limit " + size);
        }
        // 不查config字段
        if (!publishedQueryDto.isReturnConfig()) {
            queryWrapper.select(Published.class, info -> !"config".equals(info.getColumn()));
        }
        queryWrapper.orderByDesc(Published::getModified);
        //查询数据列表
        var dataList = publishedRepository.list(queryWrapper);
        dataList.addAll(0, recommendAgentList);
        //去除agentId重复的记录
        Set<Long> targetIds = new HashSet<>();
        dataList = dataList.stream().filter(published -> {
            if (targetIds.contains(published.getTargetId())) {
                return false;
            }
            targetIds.add(published.getTargetId());
            return true;
        }).collect(Collectors.toList());
        if (publishedQueryDto.getTargetIds() != null && !publishedQueryDto.getTargetIds().isEmpty()) {
            dataList.sort(Comparator.comparing(published -> {
                int index = publishedQueryDto.getTargetIds().indexOf(published.getTargetId());
                return index == -1 ? Integer.MAX_VALUE : index;
            }));
        }
        dataTotalCount += recommendAgentList.size();
        SuperPage<Published> iPage = new SuperPage(page, size, dataTotalCount, dataList);
        return iPage;
    }

    @Override
    public IPage<Published> queryPublishedListForManage(PublishedQueryDto publishedQueryDto) {
        Page<Published> page = new Page<>(publishedQueryDto.obtainPage(), publishedQueryDto.obtainPageSize());
        QueryWrapper<Published> queryWrapper = new QueryWrapper<>();
        queryWrapper.orderByDesc("id");

        if (publishedQueryDto.getTargetType() != null) {
            queryWrapper.eq("target_type", publishedQueryDto.getTargetType());
        }
        if (publishedQueryDto.getTargetSubType() != null) {
            if (publishedQueryDto.getTargetSubType() == Published.TargetSubType.Agent) {
                queryWrapper.in("target_sub_type", Published.TargetSubType.ChatBot, Published.TargetSubType.TaskAgent, Published.TargetSubType.Single);
            } else {
                queryWrapper.eq("target_sub_type", publishedQueryDto.getTargetSubType());
            }
        }
        if (StringUtils.isNotBlank(publishedQueryDto.getKw())) {
            queryWrapper.like("name", publishedQueryDto.getKw());
        }
        queryWrapper.eq("scope", Published.PublishScope.Tenant);

        // 如果targetType=Skill，则查询数据库的时候不查config字段
        if (publishedQueryDto.getTargetType() == Published.TargetType.Skill) {
            queryWrapper.select(Published.class, info -> !"config".equals(info.getColumn()));
        }

        return publishedRepository.page(page, queryWrapper);
    }

    @Override
    public List<Published> queryPublishedList(Published.TargetType targetType, List<Long> targetIds) {
        return queryPublishedList(targetType, targetIds, null);
    }

    @Override
    public List<Published> queryPublishedListWithoutConfig(Published.TargetType targetType, List<Long> targetIds) {
        return queryPublishedListWithoutConfig(targetType, targetIds, null);
    }

    @Override
    public List<Published> queryPublishedList(Published.TargetType targetType, List<Long> targetIds, String kw) {
        Assert.notNull(targetType, "targetType不能为空");
        if (CollectionUtils.isEmpty(targetIds)) {
            return Collections.emptyList();
        }
        //targetIds去重
        targetIds = targetIds.stream().distinct().collect(Collectors.toList());
        LambdaQueryWrapper<Published> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Published::getTargetType, targetType);
        queryWrapper.in(Published::getTargetId, targetIds);
        if (StringUtils.isNotBlank(kw)) {
            queryWrapper.like(Published::getName, kw);
        }
        List<Published> publishedList = publishedRepository.list(queryWrapper);
        //publishedList根据targetId去重
        return publishedList;
    }

    @Override
    public List<Published> queryPublishedListWithoutConfig(Published.TargetType targetType, List<Long> targetIds, String kw) {
        Assert.notNull(targetType, "targetType不能为空");
        if (CollectionUtils.isEmpty(targetIds)) {
            return Collections.emptyList();
        }
        //targetIds去重
        targetIds = targetIds.stream().distinct().collect(Collectors.toList());
        LambdaQueryWrapper<Published> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Published::getTargetType, targetType);
        queryWrapper.in(Published::getTargetId, targetIds);
        if (StringUtils.isNotBlank(kw)) {
            queryWrapper.like(Published::getName, kw);
        }
        queryWrapper.select(Published.class, info -> !"config".equals(info.getColumn()));
        //publishedList根据targetId去重
        return publishedRepository.list(queryWrapper);
    }

    @Override
    public Published queryPublishedByTargetId(Published.TargetType targetType, Long targetId) {
        Assert.notNull(targetType, "targetType不能为空");
        LambdaQueryWrapper<Published> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Published::getTargetType, targetType);
        queryWrapper.eq(Published::getTargetId, targetId);
        List<Published> publishedList = publishedRepository.list(queryWrapper);
        if (publishedList.isEmpty()) {
            return null;
        }
        //优先展示发布为全局的
        List<Published> collect = publishedList.stream().filter(published -> published.getScope() == Published.PublishScope.Tenant).collect(Collectors.toList());
        if (!collect.isEmpty()) {
            return collect.get(0);
        }
        return publishedList.get(0);
    }

    @Override
    public Published queryPublishedById(Long publishId) {
        return publishedRepository.getById(publishId);
    }

    @Override
    public IPage<PublishApply> queryPublishApplyList(PageQueryVo<PublishApplyQueryDto> pageQueryVo) {
        Page<PublishApply> applyPage = new Page<>(pageQueryVo.getPageNo(), pageQueryVo.getPageSize());
        QueryWrapper<PublishApply> queryWrapper = new QueryWrapper<>();
        queryWrapper.orderByDesc("id");
        if (pageQueryVo.getQueryFilter() != null) {
            PublishApplyQueryDto publishApplyQueryDto = pageQueryVo.getQueryFilter();
            if (publishApplyQueryDto.getTargetType() != null) {
                queryWrapper.eq("target_type", publishApplyQueryDto.getTargetType());
            }
            if (publishApplyQueryDto.getTargetSubType() != null) {
                if (publishApplyQueryDto.getTargetSubType() == Published.TargetSubType.Agent) {
                    queryWrapper.in("target_sub_type", Published.TargetSubType.ChatBot, Published.TargetSubType.TaskAgent, Published.TargetSubType.Single);
                } else {
                    queryWrapper.eq("target_sub_type", publishApplyQueryDto.getTargetSubType());
                }
            }
            if (publishApplyQueryDto.getPublishStatus() != null) {
                queryWrapper.eq("publish_status", publishApplyQueryDto.getPublishStatus());
            }
            if (StringUtils.isNotBlank(publishApplyQueryDto.getKw())) {
                queryWrapper.like("name", publishApplyQueryDto.getKw());
            }
        }
        return publishApplyRepository.page(applyPage, queryWrapper);
    }

    @Override
    public void incStatisticsCount(Published.TargetType targetType, Long targetId, String key, Long inc) {
        Assert.notNull(targetType, "targetType不能为空");
        Assert.notNull(targetId, "targetId不能为空");
        Assert.notNull(inc, "inc不能为空");
        publishedStatisticsRepository.incCount(targetType, targetId, key, inc);
    }

    @Override
    public StatisticsDto queryStatisticsCount(Published.TargetType targetType, Long targetId) {
        //查询出统计信息列表，然后转换成Class<T> clazz
        LambdaQueryWrapper<PublishedStatistics> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(PublishedStatistics::getTargetType, targetType);
        queryWrapper.eq(PublishedStatistics::getTargetId, targetId);
        List<PublishedStatistics> statistics = publishedStatisticsRepository.list(queryWrapper);
        //statistics name作为key，value作为value生成map
        Map<String, Long> statisticsMap = statistics.stream().collect(Collectors.toMap(PublishedStatistics::getName, PublishedStatistics::getValue));
        //statisticsMap转T的对象，Map转对象工具
        StatisticsDto statisticsDto = safeMapToObject(statisticsMap, StatisticsDto.class);
        statisticsDto.setTargetId(targetId);
        return statisticsDto;
    }

    @Override
    public List<StatisticsDto> queryStatisticsCountList(Published.TargetType targetType, List<Long> targetIds) {
        if (CollectionUtils.isEmpty(targetIds)) {
            return Collections.emptyList();
        }
        LambdaQueryWrapper<PublishedStatistics> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(PublishedStatistics::getTargetType, targetType);
        queryWrapper.in(PublishedStatistics::getTargetId, targetIds);
        List<PublishedStatistics> statistics = publishedStatisticsRepository.list(queryWrapper);
        //List<PublishedStatistics> 用targetId分组
        Map<Long, List<PublishedStatistics>> statisticsMap = statistics.stream().collect(Collectors.groupingBy(PublishedStatistics::getTargetId));
        List<StatisticsDto> result = new ArrayList<>();
        for (Long targetId : targetIds) {
            List<PublishedStatistics> statisticsList = statisticsMap.get(targetId);
            if (statisticsList != null) {
                Map<String, Long> statisticsMap1 = statisticsList.stream().collect(Collectors.toMap(PublishedStatistics::getName, PublishedStatistics::getValue));
                StatisticsDto t = safeMapToObject(statisticsMap1, StatisticsDto.class);
                t.setTargetId(targetId);
                result.add(t);
            }
        }
        return result;
    }

    @Override
    public void addPublishedStatistics(Published.TargetType targetType, Long targetId, Map<String, Long> object) {
        object.forEach((key, value) -> {
            PublishedStatistics publishedStatistics = new PublishedStatistics();
            publishedStatistics.setName(key);
            publishedStatistics.setValue(value);
            publishedStatistics.setTargetId(targetId);
            publishedStatistics.setTargetType(targetType);
            publishedStatisticsRepository.save(publishedStatistics);
        });
    }

    @Override
    public void deleteByPublishedId(Long id) {
        publishedRepository.removeById(id);
    }

    @Override
    public void deletePublishedApply(Published.TargetType type, Long targetId) {
        LambdaQueryWrapper<PublishApply> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(PublishApply::getTargetType, type);
        queryWrapper.eq(PublishApply::getTargetId, targetId);
        queryWrapper.eq(PublishApply::getPublishStatus, Published.PublishStatus.Applying);
        publishApplyRepository.remove(queryWrapper);
    }

    @Override
    public void deletePublishedApplyById(Long applyId) {
        publishApplyRepository.removeById(applyId);
    }

    @Override
    public Published queryPublished(Long publishedId) {
        return publishedRepository.getById(publishedId);
    }

    @Override
    public void deleteBySpaceId(Long spaceId) {
        LambdaQueryWrapper<Published> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Published::getSpaceId, spaceId);
        publishedRepository.remove(queryWrapper);
    }

    private static <T> T mapToObject(Map<String, Long> map, Class<T> clazz) throws Exception {
        T instance = clazz.getDeclaredConstructor().newInstance();
        for (Map.Entry<String, Long> entry : map.entrySet()) {
            String key = entry.getKey();
            Long value = entry.getValue();
            Field field = clazz.getDeclaredField(key);
            field.setAccessible(true);
            field.set(instance, value);
        }
        return instance;
    }

    private static <T> T safeMapToObject(Map<String, Long> map, Class<T> clazz) {
        try {
            return mapToObject(map, clazz);
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert map to object", e);
        }
    }

    private Boolean extractUsageScenarioFlag(List<UsageScenarioEnum> usageScenarios, UsageScenarioEnum targetScenario) {
        if (targetScenario == null) {
            return null;
        }
        if (usageScenarios == null || usageScenarios.isEmpty()) {
            return null;
        }
        return usageScenarios.contains(targetScenario) ? Boolean.TRUE : null;
    }
}
