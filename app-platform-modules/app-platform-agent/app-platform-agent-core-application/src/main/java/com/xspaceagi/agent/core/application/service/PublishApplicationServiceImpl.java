package com.xspaceagi.agent.core.application.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.google.common.collect.Lists;
import com.xspaceagi.agent.core.adapter.application.*;
import com.xspaceagi.agent.core.adapter.dto.*;
import com.xspaceagi.agent.core.adapter.dto.config.AgentConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.PluginDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowConfigDto;
import com.xspaceagi.agent.core.adapter.repository.PublishedRepository;
import com.xspaceagi.agent.core.adapter.repository.entity.*;
import com.xspaceagi.agent.core.domain.service.*;
import com.xspaceagi.agent.core.infra.rpc.CustomPageRpcService;
import com.xspaceagi.agent.core.infra.rpc.dto.PageDto;
import com.xspaceagi.agent.core.spec.enums.PluginTypeEnum;
import com.xspaceagi.agent.core.spec.utils.CopyRelationCacheUtil;
import com.xspaceagi.system.application.dto.SendNotifyMessageDto;
import com.xspaceagi.system.application.dto.SpaceDto;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.NotifyMessageApplicationService;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.infra.dao.entity.NotifyMessage;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.PageQueryVo;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.jackson.JsonSerializeUtil;
import com.xspaceagi.system.spec.page.SuperPage;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.*;
import java.util.stream.Collectors;

import static com.xspaceagi.agent.core.adapter.repository.entity.Published.TargetSubType.ChatBot;
import static com.xspaceagi.agent.core.adapter.repository.entity.Published.TargetSubType.PageApp;

@Slf4j
@Service
public class PublishApplicationServiceImpl implements PublishApplicationService {

    @Resource
    private PublishDomainService publishDomainService;

    @Resource
    private UserApplicationService userApplicationService;

    @Resource
    private ConfigHistoryDomainService configHistoryDomainService;

    @Resource
    private AgentDomainService agentDomainService;

    @Resource
    private WorkflowDomainService workflowDomainService;

    @Resource
    private PluginDomainService pluginDomainService;

    @Resource
    private SkillDomainService skillDomainService;

    @Resource
    private NotifyMessageApplicationService notifyMessageApplicationService;

    @Resource
    private UserTargetRelationDomainService userTargetRelationDomainService;

    @Resource
    private SpaceApplicationService spaceApplicationService;

    @Resource
    private AgentApplicationService agentApplicationService;

    @Resource
    private WorkflowApplicationService workflowApplicationService;

    @Resource
    private PluginApplicationService pluginApplicationService;

    @Resource
    private SkillApplicationService skillApplicationService;

    @Resource
    private CustomPageRpcService customPageRpcService;
    @Autowired
    private PublishedRepository publishedRepository;

    @Override
    public SuperPage<PublishedDto> queryPublishedList(PublishedQueryDto publishedQueryDto) {
        SuperPage<Published> publishedList = publishDomainService.queryPublishedList(publishedQueryDto);
        var dataList = convertPublishedList(publishedQueryDto.getTargetType(), publishedList.getRecords());

        return SuperPage.build(publishedList, dataList);
    }

    @Override
    public SuperPage<PublishedDto> queryPublishedListForAt(PublishedQueryDto publishedQueryDto) {
        SuperPage<Published> publishedPage = publishDomainService.queryPublishedList(publishedQueryDto);
        List<Published> publishedList = publishedPage.getRecords();
        if (CollectionUtils.isEmpty(publishedList)) {
            return new SuperPage<>(publishedPage.getCurrent(), publishedPage.getSize(), publishedPage.getTotal(), null);
        }

        List<PublishedDto> dtoList = publishedList.stream().map(published -> {
            PublishedDto publishedDto = new PublishedDto();
            BeanUtils.copyProperties(published, publishedDto);
            return publishedDto;
        }).toList();

        return new SuperPage<>(publishedPage.getCurrent(), publishedPage.getSize(), publishedPage.getTotal(), dtoList);
    }

    private List<PublishedDto> convertPublishedList(Published.TargetType targetType, List<Published> publishedList) {
        // 从agentPublishedList中获取agentIds
        List<Long> targetIds = publishedList.stream().map(Published::getTargetId).collect(Collectors.toList());
        List<StatisticsDto> statisticsList = publishDomainService.queryStatisticsCountList(targetType, targetIds);
        Map<Long, StatisticsDto> statisticsMap = statisticsList.stream()
                .collect(Collectors.toMap(StatisticsDto::getTargetId, statisticsDto -> statisticsDto, (k1, k2) -> k1));

        // 从agentPublishedList中获取userIds
        List<Long> userIds = publishedList.stream().map(Published::getUserId).collect(Collectors.toList());
        Map<Long, UserDto> userMap = userApplicationService.queryUserListByIds(userIds).stream()
                .collect(Collectors.toMap(UserDto::getId, userDto -> userDto));

        // 补齐收藏状态
        Map<Long, UserTargetRelation> userTargetRelationMap;
        if (RequestContext.get().getUserId() != null && targetType != null) {
            List<UserTargetRelation> userTargetRelationList = userTargetRelationDomainService
                    .queryUserTargetRelationByTargetIds(RequestContext.get().getUserId(), targetType,
                            UserTargetRelation.OpType.Collect, targetIds);
            // 转map
            userTargetRelationMap = userTargetRelationList.stream().collect(
                    Collectors.toMap(UserTargetRelation::getTargetId, userTargetRelation -> userTargetRelation));
        } else {
            userTargetRelationMap = Map.of();
        }

        // 补齐封面图
        List<Long> agentIdList = publishedList.stream().filter(p -> p.getTargetType() == Published.TargetType.Agent && p.getTargetSubType() == Published.TargetSubType.PageApp)
                .map(Published::getTargetId).toList();

        List<PageDto> pageDtoList = customPageRpcService.queryPageListByAgentIds(agentIdList);
        Map<Long, PageDto> pageDtoMap = CollectionUtils.isNotEmpty(pageDtoList) ? pageDtoList.stream()
                .filter(dto -> dto.getDevAgentId() != null)
                .collect(Collectors.toMap(
                        PageDto::getDevAgentId,
                        dto -> dto,
                        (v1, v2) -> v1 // 如果有重复，保留第一个
                )) : Map.of();

        return publishedList.stream().map(published -> {
            PublishedDto publishedDto = new PublishedDto();
            BeanUtils.copyProperties(published, publishedDto);
            publishedDto.setStatistics(statisticsMap.get(published.getTargetId()));
            UserDto userDto = userMap.get(published.getUserId());
            if (userDto != null) {
                completePublishUser(publishedDto, userDto);
            }
            if (userTargetRelationMap.get(published.getTargetId()) != null) {
                publishedDto.setCollect(true);
            }
            if (published.getTargetType() == Published.TargetType.Agent) {
                try {
                    publishedDto.setAgentType(PageApp.equals(publishedDto.getTargetSubType()) ? PageApp.name() : ChatBot.name());
                } catch (Exception e) {
                    // ignore
                    log.error("parse agent config error", e);
                }
                // 补齐封面图（仅针对PageApp类型）
                if (published.getTargetSubType() == Published.TargetSubType.PageApp) {
                    PageDto pageDto = pageDtoMap.get(published.getTargetId());
                    if (pageDto != null) {
                        if (pageDto.getCoverImg() != null) {
                            publishedDto.setCoverImg(pageDto.getCoverImg());
                        }
                        if (pageDto.getCoverImgSourceType() != null) {
                            publishedDto.setCoverImgSourceType(pageDto.getCoverImgSourceType());
                        }
                    }
                }
            }
            // 不返回config
            publishedDto.setConfig(null);
            return publishedDto;
        }).collect(Collectors.toList());
    }

    @Override
    public List<PublishedDto> queryPublishedList(Published.TargetType targetType, List<Long> targetIds) {
        return queryPublishedList(targetType, targetIds, null);
    }

    @Override
    public List<PublishedDto> queryPublishedListWithoutConfig(Published.TargetType targetType, List<Long> targetIds, String kw) {
        try {
            RequestContext.addTenantIgnoreEntity(Published.class);
            List<Published> publishedList = publishDomainService.queryPublishedListWithoutConfig(targetType, targetIds, kw);
            if (CollectionUtils.isEmpty(publishedList)) {
                return List.of();
            }
            //这里不能去重publishedList，因为调用方同时需要系统广场和空间广场的数据

            return publishedList.stream().map(published -> {
                    PublishedDto publishedDto = new PublishedDto();
                    BeanUtils.copyProperties(published, publishedDto);
                    return publishedDto;
                }).toList();
        } finally {
            RequestContext.removeTenantIgnoreEntity(Published.class);
        }
    }

    @Override
    public List<PublishedDto> queryPublishedList(Published.TargetType targetType, List<Long> targetIds, String kw) {
        try {
            RequestContext.addTenantIgnoreEntity(Published.class);
            List<Published> publishedList = publishDomainService.queryPublishedList(targetType, targetIds, kw);
            //去重publishedList
            publishedList = publishedList.stream().collect(Collectors.collectingAndThen(Collectors.toCollection(() -> new TreeSet<>(Comparator.comparing(Published::getTargetId))), ArrayList::new));
            return convertPublishedList(targetType, publishedList);
        } finally {
            RequestContext.removeTenantIgnoreEntity(Published.class);
        }
    }

    @Override
    public IPage<PublishedDto> queryPublishedListForManage(PublishedQueryDto publishedQueryDto) {
        IPage<Published> publishedIPage = publishDomainService.queryPublishedListForManage(publishedQueryDto);
        List<Published> publishedList = publishedIPage.getRecords();
        List<PublishedDto> publishedDtos = convertPublishedList(publishedQueryDto.getTargetType(), publishedList);
        Map<Long, Published> publishedMap = publishedList.stream().collect(Collectors.toMap(Published::getId, published -> published));
        publishedDtos.forEach(publishedDto -> {
            if (publishedDto.getTargetType() == Published.TargetType.Plugin) {
                try {
                    publishedDto.setPluginType(PluginTypeEnum.valueOf(JSON.parseObject(publishedMap.get(publishedDto.getId()).getConfig()).getString("type")));
                } catch (Exception e) {
                    //  ignore 非正常数据导致异常，忽略
                }
            }
        });
        //转map
        Map<Long, PublishedDto> publishedDtoMap = publishedDtos.stream().collect(Collectors.toMap(PublishedDto::getId, publishedDto -> publishedDto));
        return publishedIPage.convert(published -> publishedDtoMap.get(published.getId()));
    }

    private void completePublishUser(PublishedDto publishedDto, UserDto userDto) {
        if (userDto == null) {
            return;
        }
        if (RequestContext.get() != null && RequestContext.get().getTenantConfig() != null) {
            TenantConfigDto tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
            if (publishedDto.getTargetType() == Published.TargetType.Agent && tenantConfigDto.getOfficialAgentIds() != null && StringUtils.isNotBlank(tenantConfigDto.getOfficialUserName())) {
                if (tenantConfigDto.getOfficialAgentIds().contains(publishedDto.getTargetId())) {
                    publishedDto.setPublishUser(PublishUserDto.builder()
                            .userId(userDto.getId())
                            .userName(tenantConfigDto.getOfficialUserName())
                            .nickName(tenantConfigDto.getOfficialUserName())
                            .avatar(StringUtils.isNotBlank(tenantConfigDto.getSiteLogo()) ? tenantConfigDto.getSiteLogo() : "")
                            .build());
                    return;
                }
            } else if (publishedDto.getTargetType() == Published.TargetType.Plugin && tenantConfigDto.getOfficialPluginIds() != null && StringUtils.isNotBlank(tenantConfigDto.getOfficialUserName())) {
                if (Lists.newArrayList(tenantConfigDto.getOfficialPluginIds().split(",")).contains(publishedDto.getTargetId().toString())) {
                    publishedDto.setPublishUser(PublishUserDto.builder()
                            .userId(userDto.getId())
                            .userName(tenantConfigDto.getOfficialUserName())
                            .nickName(tenantConfigDto.getOfficialUserName())
                            .avatar(StringUtils.isNotBlank(tenantConfigDto.getSiteLogo()) ? tenantConfigDto.getSiteLogo() : "")
                            .build());
                    return;
                }
            } else if (publishedDto.getTargetType() == Published.TargetType.Workflow && tenantConfigDto.getOfficialWorkflowIds() != null && StringUtils.isNotBlank(tenantConfigDto.getOfficialUserName())) {
                if (Lists.newArrayList(tenantConfigDto.getOfficialWorkflowIds().split(",")).contains(publishedDto.getTargetId().toString())) {
                    publishedDto.setPublishUser(PublishUserDto.builder()
                            .userId(userDto.getId())
                            .userName(tenantConfigDto.getOfficialUserName())
                            .nickName(tenantConfigDto.getOfficialUserName())
                            .avatar(StringUtils.isNotBlank(tenantConfigDto.getSiteLogo()) ? tenantConfigDto.getSiteLogo() : "")
                            .build());
                    return;
                }
            } else if (publishedDto.getTargetType() == Published.TargetType.Skill && tenantConfigDto.getOfficialSkillIds() != null && StringUtils.isNotBlank(tenantConfigDto.getOfficialUserName())) {
                if (Lists.newArrayList(tenantConfigDto.getOfficialSkillIds().split(",")).contains(publishedDto.getTargetId().toString())) {
                    publishedDto.setPublishUser(PublishUserDto.builder()
                            .userId(userDto.getId())
                            .userName(tenantConfigDto.getOfficialUserName())
                            .nickName(tenantConfigDto.getOfficialUserName())
                            .avatar(StringUtils.isNotBlank(tenantConfigDto.getSiteLogo()) ? tenantConfigDto.getSiteLogo() : "")
                            .build());
                    return;
                }
            }
        }
        publishedDto.setPublishUser(PublishUserDto.builder()
                .userId(userDto.getId())
                .userName(userDto.getUserName())
                .nickName(userDto.getNickName())
                .avatar(userDto.getAvatar())
                .build());
    }

    @Override
    public PublishedDto queryPublished(Published.TargetType targetType, Long targetId) {
        return queryPublished(targetType, targetId, true);
    }

    @Override
    public PublishedDto queryPublished(Published.TargetType targetType, Long targetId, boolean loadConfig) {
        if (targetId == null) {
            return null;
        }
        List<Published> publishedList;

        if (loadConfig) {
            publishedList = publishDomainService.queryPublishedList(targetType, List.of(targetId));
        } else {
            publishedList = publishDomainService.queryPublishedListWithoutConfig(targetType, List.of(targetId));
        }

        if (CollectionUtils.isNotEmpty(publishedList)) {
            // 按照 scope 排序：Global、Tenant、Space
            publishedList.sort(Comparator.comparing(published -> {
                Published.PublishScope scope = published.getScope();
                return switch (scope) {
                    case Global -> 0;
                    case Tenant -> 1;
                    case Space -> 2;
                };
            }));
            PublishedDto publishedDto = new PublishedDto();
            BeanUtils.copyProperties(publishedList.get(0), publishedDto);
            RequestContext.addTenantIgnoreEntity(User.class);
            UserDto userDto = userApplicationService.queryById(publishedList.get(0).getUserId());
            RequestContext.removeTenantIgnoreEntity(User.class);
            completePublishUser(publishedDto, userDto);
            List<StatisticsDto> statisticsList = publishDomainService.queryStatisticsCountList(targetType, List.of(targetId));
            if (CollectionUtils.isNotEmpty(statisticsList)) {
                publishedDto.setStatistics(statisticsList.get(0));
            } else {
                publishedDto.setStatistics(new StatisticsDto());
            }
            if (RequestContext.get() != null && RequestContext.get().getUserId() != null) {
                List<UserTargetRelation> userTargetRelationList = userTargetRelationDomainService
                        .queryUserTargetRelationByTargetIds(RequestContext.get().getUserId(), targetType,
                                UserTargetRelation.OpType.Collect, List.of(targetId));
                if (userTargetRelationList != null && !userTargetRelationList.isEmpty()) {
                    publishedDto.setCollect(true);
                }
            }
            List<Published> collect = publishedList.stream().filter(published -> published.getScope() == Published.PublishScope.Tenant).toList();
            if (collect.isEmpty()) {
                publishedDto.setPublishedSpaceIds(publishedList.stream().map(Published::getSpaceId).collect(Collectors.toList()));
            } else {
                publishedDto.setScope(Published.PublishScope.Tenant);
            }
            //从publishedList中获取modified的时间为最新的
            publishedDto.setModified(publishedList.stream().max(Comparator.comparing(Published::getModified)).get().getModified());
            return publishedDto;
        }
        return null;
    }

    @Override
    public PublishedDto queryPublishedWithSpaceId(Published.TargetType targetType, Long targetId, Long spaceId) {
        PublishedDto published = queryPublished(targetType, targetId);
        if (published == null) {
            return null;
        }
        //published.getPublishedSpaceIds() == null 时为全局发布
        if (published.getPublishedSpaceIds() == null || published.getPublishedSpaceIds().contains(spaceId)) {
            return published;
        }
        return null;
    }

    @Override
    public Long publishApply(PublishApplyDto publishApplyDto) {
        Assert.notNull(publishApplyDto, "publishApplyDto不能为空");
        Assert.notNull(publishApplyDto.getApplyUser(), "applyUser不能为空");
        Assert.notNull(publishApplyDto.getTargetType(), "targetType不能为空");
        Assert.notNull(publishApplyDto.getTargetId(), "targetId不能为空");
        Assert.notNull(publishApplyDto.getTargetConfig(), "targetConfig不能为空");
        if (publishApplyDto.getChannels() == null) {
            publishApplyDto.setChannels(new ArrayList<>());
        }
        List<PublishApply> publishApplyList = publishDomainService
                .queryPublishApplyingList(publishApplyDto.getTargetType(), publishApplyDto.getTargetId());
        // 过滤出等待审核的
        publishApplyList = publishApplyList.stream()
                .filter(publishApply -> {
                    if (publishApply.getScope() != publishApplyDto.getScope()) {
                        return false;
                    }
                    if (publishApply.getScope() == Published.PublishScope.Space && !publishApplyDto.getSpaceId().equals(publishApply.getSpaceId())) {
                        return false;
                    }
                    return true;
                })
                .collect(Collectors.toList());
        if (publishApplyList.size() > 0) {
            publishApplyList.forEach(publishApply -> publishDomainService.deletePublishedApplyById(publishApply.getId()));
        }
        PublishApply publishApply = new PublishApply();
        BeanUtils.copyProperties(publishApplyDto, publishApply);
        publishApply.setPublishStatus(Published.PublishStatus.Applying);
        // 启用 LargeObject 特性，支持序列化包含大文件内容的配置
        publishApply.setConfig(JSON.toJSONString(publishApplyDto.getTargetConfig(), JSONWriter.Feature.LargeObject));
        publishApply.setApplyUserId(publishApplyDto.getApplyUser().getId());
        publishApply.setChannels(JSON.toJSONString(publishApplyDto.getChannels(), JSONWriter.Feature.LargeObject));
        publishDomainService.publishApply(publishApply);

        Published.PublishStatus publishStatus = Published.PublishStatus.Applying;

        String hisConfig = publishApply.getConfig();

        if (publishApply.getTargetType() == Published.TargetType.Skill) {
            SkillConfigDto tempSkill = skillApplicationService.queryById(publishApply.getTargetId(), false);
            hisConfig = JsonSerializeUtil.toJSONStringGeneric(tempSkill);
        }

        ConfigHistory configHistory = ConfigHistory.builder()
                .config(hisConfig)
                .targetId(publishApply.getTargetId())
                .description("发布申请")
                .type(ConfigHistory.Type.PublishApply)
                .targetType(publishApply.getTargetType())
                .opUserId(publishApply.getApplyUserId())
                .build();
        configHistoryDomainService.addConfigHistory(configHistory);

        if (publishApply.getTargetType() == Published.TargetType.Agent) {
            AgentConfig agentConfig = new AgentConfig();
            agentConfig.setId(publishApply.getTargetId());
            agentConfig.setPublishStatus(publishStatus);
            agentDomainService.update(agentConfig);
        }

        if (publishApply.getTargetType() == Published.TargetType.Workflow) {
            WorkflowConfig workflowConfig = new WorkflowConfig();
            workflowConfig.setId(publishApply.getTargetId());
            workflowConfig.setPublishStatus(publishStatus);
            workflowDomainService.update(workflowConfig);
        }

        if (publishApply.getTargetType() == Published.TargetType.Plugin) {
            PluginConfig pluginConfig = new PluginConfig();
            pluginConfig.setId(publishApply.getTargetId());
            pluginConfig.setPublishStatus(publishStatus);
            pluginDomainService.update(pluginConfig);
        }

        if (publishApply.getTargetType() == Published.TargetType.Skill) {
            SkillConfig skillConfig = new SkillConfig();
            skillConfig.setId(publishApply.getTargetId());
            skillConfig.setPublishStatus(publishStatus);
            skillDomainService.update(skillConfig);
        }

        return publishApply.getId();
    }

    @Override
    @DSTransactional
    public void publish(Long applyId) {
        PublishApplyDto publishApplyDto = queryPublishApplyById(applyId);
        Assert.notNull(publishApplyDto, "applyId错误");
        publish(publishApplyDto.getTargetType(), publishApplyDto.getTargetId(), publishApplyDto.getScope(), List.of(publishApplyDto));
    }

    @Override
    @DSTransactional
    public void publish(Published.TargetType targetType, Long targetId, Published.PublishScope scope, List<PublishApplyDto> publishApplies) {
        if (CollectionUtils.isEmpty(publishApplies)) {
            List<Published> publishedList = publishDomainService.queryPublishedList(targetType, List.of(targetId));
            //根据scope过滤
            publishedList.forEach(published -> {
                //删除未再次勾选的发布目标
                if (published.getScope() == scope) {
                    publishDomainService.deleteByPublishedId(published.getId());
                }
            });
            return;
        }
        Integer accessControlStatus;
        if (targetType == Published.TargetType.Agent) {
            AgentConfig agentConfig = agentDomainService.queryById(targetId);
            if (agentConfig != null) {
                accessControlStatus = agentConfig.getAccessControl();
            } else {
                accessControlStatus = 0;
            }
        } else {
            accessControlStatus = 0;
        }
        Date now = new Date();
        List<Published> publishedList = publishApplies.stream().map(publishApplyDto -> {
            Published published = new Published();
            published.setSpaceId(publishApplyDto.getSpaceId());
            published.setUserId(publishApplyDto.getApplyUser().getId());
            published.setTargetId(publishApplyDto.getTargetId());
            published.setTargetType(publishApplyDto.getTargetType());
            published.setTargetSubType(publishApplyDto.getTargetSubType());
            published.setName(publishApplyDto.getName());
            published.setDescription(publishApplyDto.getDescription());
            published.setIcon(publishApplyDto.getIcon());
            // 启用 LargeObject 特性，支持序列化包含大文件内容的配置
            published.setConfig((publishApplyDto.getTargetConfig() instanceof String) ? (String) publishApplyDto.getTargetConfig() : JSON.toJSONString(publishApplyDto.getTargetConfig(), JSONWriter.Feature.LargeObject));
            published.setChannel(Published.PublishChannel.System);
            published.setRemark(publishApplyDto.getRemark());
            published.setScope(publishApplyDto.getScope());
            published.setCategory(publishApplyDto.getCategory());
            published.setAllowCopy(publishApplyDto.getAllowCopy());
            published.setOnlyTemplate(publishApplyDto.getOnlyTemplate());
            published.setModified(now);
            published.setCreated(now);
            published.setAccessControl(accessControlStatus);
            return published;
        }).collect(Collectors.toList());
        PublishApply publishApply = new PublishApply();
        publishApply.setId(publishApplies.get(0).getId());
        publishApply.setPublishStatus(Published.PublishStatus.Published);
        publishApply.setConfig(publishedList.get(0).getConfig());
        publishApply.setApplyUserId(publishApplies.get(0).getApplyUser().getId());
        publishApply.setTargetType(publishedList.get(0).getTargetType());
        publishApply.setTargetSubType(publishedList.get(0).getTargetSubType());
        publishApply.setTargetId(publishedList.get(0).getTargetId());
        publishApply.setRemark(publishedList.get(0).getRemark());
        publishApply.setScope(publishedList.get(0).getScope());
        publishApply.setSpaceId(publishApplies.get(0).getSpaceId());
        publishApply.setName(publishApplies.get(0).getName());
        publishApply.setDescription(publishApplies.get(0).getDescription());
        publishApply.setIcon(publishApplies.get(0).getIcon());
        publishDomainService.publish(publishApply, publishedList);
        publishApplies.forEach(publishApplyDto -> {
            PublishApply apply = new PublishApply();
            apply.setId(publishApplyDto.getId());
            apply.setPublishStatus(Published.PublishStatus.Published);
            publishDomainService.updatePublishApply(apply);
            if (publishApplyDto.getScope() == Published.PublishScope.Tenant || publishApplyDto.getSpaceId() != -1) {
                String message = "你提交的" + Published.getTargetTypeName(publishApply.getTargetType()) + "[" + publishApply.getName() + "]发布成功";
                if (publishApply.getScope() == Published.PublishScope.Space) {
                    SpaceDto spaceDto = spaceApplicationService.queryById(publishApplyDto.getSpaceId());
                    if (spaceDto != null) {
                        message = "你提交的" + Published.getTargetTypeName(publishApply.getTargetType()) + "[" + publishApply.getName() + "]已成功发布到空间[" + spaceDto.getName() + "]";
                    }
                }

                notifyMessageApplicationService.sendNotifyMessage(SendNotifyMessageDto.builder()
                        .scope(NotifyMessage.MessageScope.System)
                        .content(message)
                        .senderId(RequestContext.get().getUserId())
                        .userIds(Arrays.asList(publishApply.getApplyUserId()))
                        .build());
            }
        });

        String hisConfig = publishApply.getConfig();
        if (publishApply.getTargetType() == Published.TargetType.Skill) {
            SkillConfigDto tempSkill = skillApplicationService.queryById(publishApply.getTargetId(), false);
            hisConfig = JsonSerializeUtil.toJSONStringGeneric(tempSkill);
        }

        ConfigHistory configHistory = ConfigHistory.builder()
                .config(hisConfig)
                .targetId(publishApply.getTargetId())
                .opUserId(RequestContext.get().getUserId())
                .description(publishApply.getRemark())
                .targetType(publishApply.getTargetType())
                .type(ConfigHistory.Type.Publish)
                .build();
        configHistoryDomainService.addConfigHistory(configHistory);

        if (publishApply.getTargetType() == Published.TargetType.Agent) {
            AgentConfig agentConfig = new AgentConfig();
            agentConfig.setPublishStatus(Published.PublishStatus.Published);
            agentConfig.setId(publishApply.getTargetId());
            agentConfig.setModified(now);
            agentDomainService.update(agentConfig);
        }

        if (publishApply.getTargetType() == Published.TargetType.Workflow) {
            WorkflowConfig workflowConfig = new WorkflowConfig();
            workflowConfig.setPublishStatus(Published.PublishStatus.Published);
            workflowConfig.setId(publishApply.getTargetId());
            workflowConfig.setModified(now);
            workflowDomainService.update(workflowConfig);
        }

        if (publishApply.getTargetType() == Published.TargetType.Plugin) {
            PluginConfig pluginConfig = new PluginConfig();
            pluginConfig.setPublishStatus(Published.PublishStatus.Published);
            pluginConfig.setId(publishApply.getTargetId());
            pluginConfig.setModified(now);
            pluginDomainService.update(pluginConfig);
        }

        if (publishApply.getTargetType() == Published.TargetType.Skill) {
            SkillConfig skillConfig = new SkillConfig();
            skillConfig.setPublishStatus(Published.PublishStatus.Published);
            skillConfig.setId(publishApply.getTargetId());
            skillConfig.setModified(now);
            skillDomainService.update(skillConfig);
        }
    }

    @Override
    @DSTransactional
    public Long copyPublish(Long userId, Published.TargetType targetType, Long targetId, Long originalSpaceId, Long targetSpaceId) {
        if (originalSpaceId.equals(targetSpaceId)) {
            return targetId;
        }
        List<Published> publishedList = publishDomainService.queryPublishedList(targetType, List.of(targetId));
        if (CollectionUtils.isEmpty(publishedList)) {
            return targetId;
        }
        Object value = CopyRelationCacheUtil.get(generateTargetTypeKey(targetType), targetSpaceId, targetId);
        if (value != null) {
            return (Long) value;
        }
        Published published = publishedList.get(0);
        Long newTargetId = targetId;
        if (targetType == Published.TargetType.Agent) {
            AgentConfigDto agentConfigDto = agentApplicationService.queryPublishedConfig(targetId, false);
            if (agentConfigDto != null && agentConfigDto.getSpaceId().equals(originalSpaceId)) {
                newTargetId = agentApplicationService.copyAgent(userId, agentConfigDto, targetSpaceId);
                AgentConfigDto agentConfigDto1 = agentApplicationService.queryById(newTargetId);
                agentConfigDto1.setPublishStatus(Published.PublishStatus.Published);
                agentApplicationService.update(agentConfigDto1);
                published.setName(agentConfigDto1.getName());
                // 启用 LargeObject 特性，支持序列化包含大文件内容的配置
                published.setConfig(JSON.toJSONString(agentConfigDto1, JSONWriter.Feature.LargeObject));
                published.setModified(agentConfigDto1.getModified());
                published.setCreated(agentConfigDto1.getCreated());
            }
        }
        if (targetType == Published.TargetType.Plugin) {
            PluginDto pluginDto = pluginApplicationService.queryPublishedPluginConfig(targetId, null);
            if (pluginDto != null && pluginDto.getSpaceId().equals(originalSpaceId)) {
                newTargetId = pluginApplicationService.copyPlugin(userId, pluginDto, targetSpaceId);
                PluginDto pluginDto1 = pluginApplicationService.queryById(newTargetId);
                PluginConfig pluginConfig = new PluginConfig();
                pluginConfig.setId(newTargetId);
                pluginConfig.setPublishStatus(Published.PublishStatus.Published);
                pluginDomainService.update(pluginConfig);
                published.setName(pluginDto1.getName());
                // 启用 LargeObject 特性，支持序列化包含大文件内容的配置
                published.setConfig(JSON.toJSONString(pluginDto1, JSONWriter.Feature.LargeObject));
                published.setModified(pluginDto1.getModified());
                published.setCreated(pluginDto1.getCreated());
            }
        }
        if (targetType == Published.TargetType.Workflow) {
            WorkflowConfigDto workflowDto = workflowApplicationService.queryPublishedWorkflowConfig(targetId, null, false);
            if (workflowDto != null && workflowDto.getSpaceId().equals(originalSpaceId)) {
                newTargetId = workflowApplicationService.copyWorkflow(userId, workflowDto, targetSpaceId);
                WorkflowConfigDto workflowConfigDto = workflowApplicationService.queryById(newTargetId);
                WorkflowConfig workflowConfig = new WorkflowConfig();
                workflowConfig.setId(workflowConfigDto.getId());
                workflowConfig.setPublishStatus(Published.PublishStatus.Published);
                workflowDomainService.update(workflowConfig);
                published.setName(workflowConfigDto.getName());
                // 启用 LargeObject 特性，支持序列化包含大文件内容的配置
                published.setConfig(JSON.toJSONString(workflowConfigDto, JSONWriter.Feature.LargeObject));
                published.setModified(workflowConfigDto.getModified());
                published.setCreated(workflowConfigDto.getCreated());
            }
        }
        if (targetType == Published.TargetType.Skill) {
            SkillConfigDto skillConfigDto = skillApplicationService.queryPublishedSkillConfig(targetId, null, true);
            if (skillConfigDto != null && skillConfigDto.getSpaceId() != null && skillConfigDto.getSpaceId().equals(originalSpaceId)) {
                newTargetId = skillApplicationService.copySkill(skillConfigDto, targetSpaceId);
                SkillConfigDto skillConfigDto1 = skillApplicationService.queryById(newTargetId);
                SkillConfig skillConfig = new SkillConfig();
                skillConfig.setId(skillConfigDto1.getId());
                skillConfig.setPublishStatus(Published.PublishStatus.Published);
                skillDomainService.update(skillConfig);
                published.setName(skillConfigDto1.getName());
                // 启用 LargeObject 特性，支持序列化包含大文件内容的配置
                published.setConfig(JSON.toJSONString(skillConfigDto1, JSONWriter.Feature.LargeObject));
                published.setModified(skillConfigDto1.getModified());
                published.setCreated(skillConfigDto1.getCreated());
            }
        }

        published.setSpaceId(targetSpaceId);
        published.setId(null);
        published.setAllowCopy(YesOrNoEnum.N.getKey());
        published.setOnlyTemplate(YesOrNoEnum.N.getKey());
        published.setScope(Published.PublishScope.Space);
        published.setTargetId(newTargetId);
        publishDomainService.savePublished(published);
        CopyRelationCacheUtil.put(generateTargetTypeKey(targetType), targetSpaceId, targetId, newTargetId);
        return newTargetId;
    }

    @Override
    public void rejectPublish(PublishRejectDto publishRejectDto) {
        PublishApply publishApply = publishDomainService.queryPublishApplyById(publishRejectDto.getApplyId());
        Assert.notNull(publishApply, "applyId错误");
        publishDomainService.rejectPublish(publishRejectDto.getApplyId());
        ConfigHistory configHistory = ConfigHistory.builder()
                .config(publishApply.getConfig())
                .targetId(publishApply.getTargetId())
                .description("发布申请被拒绝")
                .targetType(publishApply.getTargetType())
                .opUserId(RequestContext.get().getUserId())
                .type(ConfigHistory.Type.PublishApplyReject)
                .build();
        configHistoryDomainService.addConfigHistory(configHistory);

        String reason = "你提交的" + Published.getTargetTypeName(publishApply.getTargetType()) + "【" + publishApply.getName() + "】发布失败";
        if (StringUtils.isNotBlank(publishRejectDto.getReason())) {
            reason += "，原因如下：\n```\n" + publishRejectDto.getReason() + "\n```";
        }
        notifyMessageApplicationService.sendNotifyMessage(SendNotifyMessageDto.builder()
                .scope(NotifyMessage.MessageScope.System)
                .content(reason)
                .senderId(RequestContext.get().getUserId())
                .userIds(Arrays.asList(publishApply.getApplyUserId()))
                .build());

        if (publishApply.getTargetType() == Published.TargetType.Agent) {
            AgentConfig agentConfig = new AgentConfig();
            agentConfig.setId(publishApply.getTargetId());
            agentConfig.setPublishStatus(Published.PublishStatus.Developing);
            agentDomainService.update(agentConfig);
        }

        if (publishApply.getTargetType() == Published.TargetType.Workflow) {
            WorkflowConfig workflowConfig = new WorkflowConfig();
            workflowConfig.setId(publishApply.getTargetId());
            workflowConfig.setPublishStatus(Published.PublishStatus.Developing);
            workflowDomainService.update(workflowConfig);
        }

        if (publishApply.getTargetType() == Published.TargetType.Plugin) {
            PluginConfig pluginConfig = new PluginConfig();
            pluginConfig.setId(publishApply.getTargetId());
            pluginConfig.setPublishStatus(Published.PublishStatus.Developing);
            pluginDomainService.update(pluginConfig);
        }

        if (publishApply.getTargetType() == Published.TargetType.Skill) {
            SkillConfig skillConfig = new SkillConfig();
            skillConfig.setId(publishApply.getTargetId());
            skillConfig.setPublishStatus(Published.PublishStatus.Developing);
            skillDomainService.update(skillConfig);
        }

    }

    @Override
    public void offShelf(OffShelfDto offShelfDto) {
        Published published = publishDomainService.queryPublished(offShelfDto.getPublishId());
        if (published == null) {
            throw new BizException("发布ID错误");
        }
        publishDomainService.deleteByPublishedId(offShelfDto.getPublishId());

        // 判断是否已全部下架，如果所有渠道都已下架，则修改状态为开发中
        List<Published> publishedList = publishDomainService.queryPublishedList(published.getTargetType(), List.of(published.getTargetId()));
        if (publishedList.size() == 0) {
            if (published.getTargetType() == Published.TargetType.Agent) {
                agentDomainService.update(AgentConfig.builder().id(published.getTargetId()).publishStatus(Published.PublishStatus.Developing).build());
            }
            if (published.getTargetType() == Published.TargetType.Workflow) {
                WorkflowConfig workflowConfig = new WorkflowConfig();
                workflowConfig.setPublishStatus(Published.PublishStatus.Developing);
                workflowConfig.setId(published.getTargetId());
                workflowDomainService.update(workflowConfig);
            }

            if (published.getTargetType() == Published.TargetType.Plugin) {
                PluginConfig pluginConfig = new PluginConfig();
                pluginConfig.setPublishStatus(Published.PublishStatus.Developing);
                pluginConfig.setId(published.getTargetId());
                pluginDomainService.update(pluginConfig);
            }

            if (published.getTargetType() == Published.TargetType.Skill) {
                SkillConfig skillConfig = new SkillConfig();
                skillConfig.setPublishStatus(Published.PublishStatus.Developing);
                skillConfig.setId(published.getTargetId());
                skillDomainService.update(skillConfig);
            }
        }

        String reason = "你的" + Published.getTargetTypeName(published.getTargetType()) + "【" + published.getName() + "】已下架";
        if (StringUtils.isNotBlank(offShelfDto.getReason())) {
            reason += "，原因如下：\n```\n" + offShelfDto.getReason() + "\n```";
        }
        notifyMessageApplicationService.sendNotifyMessage(SendNotifyMessageDto.builder()
                .scope(NotifyMessage.MessageScope.System)
                .content(reason)
                .senderId(RequestContext.get().getUserId())
                .userIds(Arrays.asList(published.getUserId()))
                .build());

        ConfigHistory configHistory = ConfigHistory.builder()
                .targetId(published.getTargetId())
                .description("下架")
                .targetType(published.getTargetType())
                .opUserId(RequestContext.get().getUserId())
                .type(ConfigHistory.Type.OffShelf)
                .config(published.getConfig())
                .build();
        configHistoryDomainService.addConfigHistory(configHistory);
    }

    @Override
    public IPage<PublishApplyDto> queryPublishApplyList(PageQueryVo<PublishApplyQueryDto> pageQueryVo) {
        IPage<PublishApply> publishApplyIPage = publishDomainService.queryPublishApplyList(pageQueryVo);
        // 从agentPublishedList中获取userIds
        List<Long> userIds = publishApplyIPage.getRecords().stream().map(PublishApply::getApplyUserId)
                .collect(Collectors.toList());
        Map<Long, UserDto> userMap = userApplicationService.queryUserListByIds(userIds).stream()
                .collect(Collectors.toMap(UserDto::getId, userDto -> userDto));
        return publishApplyIPage.convert(publishApply -> {
            PublishApplyDto publishApplyDto = new PublishApplyDto();
            BeanUtils.copyProperties(publishApply, publishApplyDto);
            publishApplyDto.setChannels(JSON.parseArray(publishApply.getChannels(), Published.PublishChannel.class));
            publishApplyDto.setApplyUser(userMap.get(publishApply.getApplyUserId()));
            if (publishApply.getTargetType() == Published.TargetType.Plugin) {
                publishApplyDto.setPluginType(PluginTypeEnum.valueOf(JSON.parseObject(publishApply.getConfig()).getString("type")));
            }
            return publishApplyDto;
        });

    }

    @Override
    public PublishApplyDto queryPublishApplyById(Long applyId) {
        PublishApply publishApply = publishDomainService.queryPublishApplyById(applyId);
        if (publishApply != null) {
            PublishApplyDto publishApplyDto = new PublishApplyDto();
            BeanUtils.copyProperties(publishApply, publishApplyDto);
            publishApplyDto.setTargetConfig(publishApply.getConfig());
            publishApplyDto.setApplyUser(userApplicationService.queryById(publishApply.getApplyUserId()));
            return publishApplyDto;
        }
        return null;
    }

    @Override
    public PublishedDto queryPublishedById(Long publishId) {
        Published published = publishDomainService.queryPublishedById(publishId);
        if (published != null) {
            PublishedDto publishedDto = new PublishedDto();
            BeanUtils.copyProperties(published, publishedDto);
            return publishedDto;
        }
        return null;
    }

    @Override
    public void incStatisticsCount(Published.TargetType targetType, Long targetId, String key, Long inc) {
        publishDomainService.incStatisticsCount(targetType, targetId, key, inc);
    }

    @Override
    public void deletePublishedApply(Published.TargetType type, Long targetId) {
        publishDomainService.deletePublishedApply(type, targetId);
    }


    @Override
    public StatisticsDto queryStatistics(Published.TargetType type, Long targetId) {
        return publishDomainService.queryStatisticsCount(type, targetId);
    }

    @Override
    public void deleteBySpaceId(Long spaceId) {
        publishDomainService.deleteBySpaceId(spaceId);
    }

    @Override
    public PublishedPermissionDto hasPermission(Published.TargetType targetType, Long targetId) {
        PublishedPermissionDto publishedPermissionDto = new PublishedPermissionDto();
        if (RequestContext.get() == null || RequestContext.get().getUserId() == null) {
            return publishedPermissionDto;
        }
        UserDto userDto = (UserDto) RequestContext.get().getUser();
        if (userDto != null && userDto.getRole() == User.Role.Admin) {
            publishedPermissionDto.setView(true);
            publishedPermissionDto.setExecute(true);
            publishedPermissionDto.setCopy(true);
            return publishedPermissionDto;
        }
        List<SpaceDto> spaceDtos = spaceApplicationService.queryListByUserId(RequestContext.get().getUserId());
        //提取spaceDtos中的id转set
        Set<Long> spaceIds = spaceDtos.stream().map(SpaceDto::getId).collect(Collectors.toSet());
        List<Published> publishedList = publishDomainService.queryPublishedList(targetType, List.of(targetId));
        for (Published published : publishedList) {
            if (published.getScope() == Published.PublishScope.Tenant) {
                publishedPermissionDto.setView(true);
                publishedPermissionDto.setExecute(true);
                if (Objects.equals(published.getAllowCopy(), YesOrNoEnum.Y.getKey())) {
                    publishedPermissionDto.setCopy(true);
                }
            }
            if (spaceIds.contains(published.getSpaceId())) {
                publishedPermissionDto.setView(true);
                publishedPermissionDto.setExecute(true);
            }
            if (spaceIds.contains(published.getSpaceId()) && Objects.equals(published.getAllowCopy(), YesOrNoEnum.Y.getKey())) {
                publishedPermissionDto.setCopy(true);
            }
        }
        return publishedPermissionDto;
    }

    @Override
    public void updateAccessControlStatus(Published.TargetType targetType, Long targetId, Integer status) {
        Published published = new Published();
        published.setAccessControl(status == null ? 0 : status);
        UpdateWrapper<Published> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("target_type", targetType);
        updateWrapper.eq("target_id", targetId);
        publishedRepository.update(published, updateWrapper);
    }

    @Override
    public void updatePublishName(Published.TargetType targetType, Long targetId, String name) {
        Published published = new Published();
        published.setName(name);
        UpdateWrapper<Published> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("target_type", targetType);
        updateWrapper.eq("target_id", targetId);
        publishedRepository.update(published, updateWrapper);
    }

    private String generateTargetTypeKey(Published.TargetType targetType) {
        String key = targetType.name();
        if (RequestContext.get() != null && RequestContext.get().getRequestId() != null) {
            key = key + ":" + RequestContext.get().getRequestId();
        }
        return key;
    }
}
