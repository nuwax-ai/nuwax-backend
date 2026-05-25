package com.xspaceagi.agent.core.application.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.google.common.collect.Lists;
import com.xspaceagi.agent.core.adapter.application.*;
import com.xspaceagi.agent.core.adapter.constant.SkillFileFormatConstants;
import com.xspaceagi.agent.core.adapter.dto.*;
import com.xspaceagi.agent.core.adapter.dto.config.AgentConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.PluginDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowConfigDto;
import com.xspaceagi.agent.core.adapter.repository.PublishedRepository;
import com.xspaceagi.agent.core.adapter.repository.entity.*;
import com.xspaceagi.agent.core.adapter.util.SkillNameUtil;
import com.xspaceagi.agent.core.domain.service.*;
import com.xspaceagi.agent.core.infra.rpc.CustomPageRpcService;
import com.xspaceagi.agent.core.infra.rpc.ResourcePricingRpcService;
import com.xspaceagi.agent.core.infra.rpc.dto.PageDto;
import com.xspaceagi.agent.core.spec.enums.PluginTypeEnum;
import com.xspaceagi.agent.core.spec.utils.CopyRelationCacheUtil;
import com.xspaceagi.file.application.service.FileManagementService;
import com.xspaceagi.file.domain.model.FileRecordDomain;
import com.xspaceagi.file.sdk.IFileAccessService;
import com.xspaceagi.pricing.sdk.dto.PricingConfigDTO;
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
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.file.InMemoryMultipartFile;
import com.xspaceagi.system.spec.jackson.JsonSerializeUtil;
import com.xspaceagi.system.spec.page.SuperPage;
import com.xspaceagi.system.spec.utils.I18nUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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

    @Resource
    private PublishedRepository publishedRepository;
    @Resource
    private FileManagementService fileManagementService;
    @Resource
    private IFileAccessService iFileAccessService;

    private static final String TARGET_TYPE_SKILL_PUBLISH_APPLY = "skill_publish_apply";
    private static final String TARGET_TYPE_SKILL_PUBLISHED = "skill_published";

    @Resource
    private ResourcePricingRpcService resourcePricingRpcService;

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
        List<Long> targetIds = publishedList.stream().map(Published::getTargetId).collect(Collectors.toList());
        List<PricingConfigDTO> pricingConfigs = resourcePricingRpcService.listPricingConfigs(publishedQueryDto.getTargetType(), targetIds);
        Map<String, PricingConfigDTO> pricingConfigMap = pricingConfigs.stream().collect(Collectors.toMap(PricingConfigDTO::getTargetId, pricingConfigDTO -> pricingConfigDTO));
        List<PublishedDto> dtoList = publishedList.stream().map(published -> {
            PublishedDto publishedDto = new PublishedDto();
            BeanUtils.copyProperties(published, publishedDto);
            PricingConfigDTO pricingConfigDTO = pricingConfigMap.get(published.getTargetId().toString());
            if (pricingConfigDTO != null && YesOrNoEnum.Y.getKey().equals(pricingConfigDTO.getStatus())) {
                publishedDto.setPaymentRequired(true);
                publishedDto.setPrice(pricingConfigDTO.getPrice());
            }
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

        List<PricingConfigDTO> pricingConfigs = resourcePricingRpcService.listPricingConfigs(targetType, targetIds);
        //定价管理，增加是否为付费状态
        Map<String, PricingConfigDTO> pricingConfigMap = pricingConfigs.stream().collect(Collectors.toMap(PricingConfigDTO::getTargetId, pricingConfigDTO -> pricingConfigDTO));
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
            PricingConfigDTO pricingConfigDTO = pricingConfigMap.get(published.getTargetId().toString());
            if (pricingConfigDTO != null && YesOrNoEnum.Y.getKey().equals(pricingConfigDTO.getStatus())) {
                publishedDto.setPaymentRequired(true);
                publishedDto.setPrice(pricingConfigDTO.getPrice());
            }
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
        Assert.notNull(publishApplyDto, "publishApplyDto cannot be null");
        Assert.notNull(publishApplyDto.getApplyUser(), "applyUser cannot be null");
        Assert.notNull(publishApplyDto.getTargetType(), "targetType cannot be null");
        Assert.notNull(publishApplyDto.getTargetId(), "targetId cannot be null");
        Assert.notNull(publishApplyDto.getTargetConfig(), "targetConfig cannot be null");
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
        if (publishApplyDto.getTargetType() == Published.TargetType.Skill) {
            SkillConfigDto skillConfigDto = skillApplicationService.queryById(publishApplyDto.getTargetId(), true);
            List<SkillFileDto> snapshotFiles = snapshotSkillFiles(skillConfigDto == null ? List.of() : skillConfigDto.getFiles(),
                    TARGET_TYPE_SKILL_PUBLISH_APPLY, publishApplyDto.getTargetId());
            SkillPublishedConfigDto applyConfig = buildSkillConfigPayload(publishApplyDto, snapshotFiles, null);
            publishApply.setConfig(JSON.toJSONString(applyConfig, JSONWriter.Feature.LargeObject));
        } else {
            publishApply.setConfig(JSON.toJSONString(publishApplyDto.getTargetConfig(), JSONWriter.Feature.LargeObject));
        }
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
                .description(I18nUtil.systemMessage("Backend.Publish.ConfigHistory.PublishApply"))
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
        Assert.notNull(publishApplyDto, "applyId is invalid");
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
        String tempSkillPublishedConfigJson = null;
        if (targetType == Published.TargetType.Skill) {
            PublishApplyDto sourceDto = publishApplies.get(0);
            List<SkillFileDto> sourceFiles = resolveSkillPublishSourceFiles(sourceDto);
            SkillPublishedConfigDto skillPublishedConfig = buildPublishedSkillConfig(sourceDto, sourceFiles);
            tempSkillPublishedConfigJson = JSON.toJSONString(skillPublishedConfig, JSONWriter.Feature.LargeObject);
        }
        final String skillPublishedConfigJson = tempSkillPublishedConfigJson;
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
            published.setExt(publishApplyDto.getExt());
            // 启用 LargeObject 特性，支持序列化包含大文件内容的配置
            if (publishApplyDto.getTargetType() == Published.TargetType.Skill) {
                published.setConfig(skillPublishedConfigJson);
            } else {
                published.setConfig((publishApplyDto.getTargetConfig() instanceof String)
                        ? (String) publishApplyDto.getTargetConfig()
                        : JSON.toJSONString(publishApplyDto.getTargetConfig(), JSONWriter.Feature.LargeObject));
            }
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
        publishApply.setExt(publishApplies.get(0).getExt());
        publishDomainService.publish(publishApply, publishedList);
        publishApplies.forEach(publishApplyDto -> {
            PublishApply apply = new PublishApply();
            apply.setId(publishApplyDto.getId());
            apply.setPublishStatus(Published.PublishStatus.Published);
            publishDomainService.updatePublishApply(apply);
            if (publishApplyDto.getScope() == Published.PublishScope.Tenant || publishApplyDto.getSpaceId() != -1) {
                UserDto userDto = userApplicationService.queryById(publishApply.getApplyUserId());
                Map<String, String> langMap = userDto == null ? RequestContext.get().getLangMap() : userDto.getLangMap();
                String message = I18nUtil.systemMessage(langMap, "Backend.Publish.Message.PublishSuccess", Published.getTargetTypeName(publishApply.getTargetType()), publishApply.getName());
                if (publishApply.getScope() == Published.PublishScope.Space) {
                    SpaceDto spaceDto = spaceApplicationService.queryById(publishApplyDto.getSpaceId());
                    if (spaceDto != null) {
                        message = I18nUtil.systemMessage(langMap, "Backend.Publish.Message.PublishToSpaceSuccess", Published.getTargetTypeName(publishApply.getTargetType()), publishApply.getName(), spaceDto.getName());
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
        Assert.notNull(publishApply, "applyId is invalid");
        publishDomainService.rejectPublish(publishRejectDto.getApplyId());
        ConfigHistory configHistory = ConfigHistory.builder()
                .config(publishApply.getConfig())
                .targetId(publishApply.getTargetId())
                .description(I18nUtil.systemMessage("Backend.Publish.ConfigHistory.PublishRejected"))
                .targetType(publishApply.getTargetType())
                .opUserId(RequestContext.get().getUserId())
                .type(ConfigHistory.Type.PublishApplyReject)
                .build();
        configHistoryDomainService.addConfigHistory(configHistory);
        UserDto userDto = userApplicationService.queryById(publishApply.getApplyUserId());
        Map<String, String> langMap = userDto == null ? RequestContext.get().getLangMap() : userDto.getLangMap();
        String reason;
        if (StringUtils.isNotBlank(publishRejectDto.getReason())) {
            reason = I18nUtil.systemMessage(langMap, "Backend.Publish.Message.PublishFailedWithReason", Published.getTargetTypeName(publishApply.getTargetType()), publishApply.getName(), publishRejectDto.getReason());
        } else {
            reason = I18nUtil.systemMessage(langMap, "Backend.Publish.Message.PublishFailed", Published.getTargetTypeName(publishApply.getTargetType()), publishApply.getName());
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
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentPublishIdInvalid);
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

        UserDto userDto = userApplicationService.queryById(published.getUserId());
        Map<String, String> langMap = userDto == null ? RequestContext.get().getLangMap() : userDto.getLangMap();
        String reason;
        if (StringUtils.isNotBlank(offShelfDto.getReason())) {
            reason = I18nUtil.systemMessage(langMap, "Backend.Publish.Message.OffShelfWithReason", Published.getTargetTypeName(published.getTargetType()), published.getName(), offShelfDto.getReason());
        } else {
            reason = I18nUtil.systemMessage(langMap, "Backend.Publish.Message.OffShelf", Published.getTargetTypeName(published.getTargetType()), published.getName());
        }
        notifyMessageApplicationService.sendNotifyMessage(SendNotifyMessageDto.builder()
                .scope(NotifyMessage.MessageScope.System)
                .content(reason)
                .senderId(RequestContext.get().getUserId())
                .userIds(Arrays.asList(published.getUserId()))
                .build());

        ConfigHistory configHistory = ConfigHistory.builder()
                .targetId(published.getTargetId())
                .description(I18nUtil.systemMessage(langMap, "Backend.Publish.ConfigHistory.OffShelf"))
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

    private SkillPublishedConfigDto buildPublishedSkillConfig(PublishApplyDto publishApplyDto, List<SkillFileDto> applySnapshotFiles) {
        List<SkillFileDto> publishedSnapshotFiles = snapshotSkillFiles(applySnapshotFiles, TARGET_TYPE_SKILL_PUBLISHED,
                publishApplyDto.getTargetId());
        String skillNameForZip = resolveSkillNameFromSkillMd(publishedSnapshotFiles, publishApplyDto.getName());
        String zipFileUrl = uploadSkillZip(skillNameForZip, publishedSnapshotFiles, publishApplyDto.getTargetId());
        return buildSkillConfigPayload(publishApplyDto, publishedSnapshotFiles, zipFileUrl);
    }

    private SkillPublishedConfigDto buildSkillConfigPayload(PublishApplyDto publishApplyDto, List<SkillFileDto> files, String zipFileUrl) {
        SkillPublishedConfigDto configDto = new SkillPublishedConfigDto();
        configDto.setFormat(SkillFileFormatConstants.SKILL_FILES_V2);
        configDto.setId(publishApplyDto.getTargetId());
        configDto.setName(publishApplyDto.getName());
        configDto.setDescription(publishApplyDto.getDescription());
        configDto.setIcon(publishApplyDto.getIcon());
        configDto.setFiles(files == null ? List.of() : files);
        configDto.setZipFileUrl(zipFileUrl);
        return configDto;
    }

    private List<SkillFileDto> resolveSkillPublishSourceFiles(PublishApplyDto publishApplyDto) {
        if (publishApplyDto == null) {
            return List.of();
        }
        // 优先使用 publish_apply 表中 config 快照，避免 targetConfig 对象缺 files 导致空发布
        if (publishApplyDto.getId() != null) {
            PublishApply dbApply = publishDomainService.queryPublishApplyById(publishApplyDto.getId());
            if (dbApply != null && StringUtils.isNotBlank(dbApply.getConfig())) {
                List<SkillFileDto> fromApplyConfig = parseSkillFileIndexes(dbApply.getConfig());
                if (isSnapshotSourceFilesValid(fromApplyConfig)) {
                    return fromApplyConfig;
                }
                if (CollectionUtils.isNotEmpty(fromApplyConfig)) {
                    log.warn("publish_apply.config files invalid, fallback to targetConfig, applyId={}", publishApplyDto.getId());
                }
            }
        }
        return parseSkillFileIndexes(publishApplyDto.getTargetConfig());
    }

    private List<SkillFileDto> parseSkillFileIndexes(Object targetConfig) {
        if (targetConfig == null) {
            return List.of();
        }
        try {
            if (targetConfig instanceof String str) {
                if (StringUtils.isBlank(str)) {
                    return List.of();
                }
                String trimmed = str.trim();
                if (trimmed.startsWith("[")) {
                    try {
                        List<Object> list = JSON.parseArray(trimmed, Object.class);
                        if (CollectionUtils.isNotEmpty(list)) {
                            return normalizeSkillFileIndexes(list);
                        }
                    } catch (Exception ignored) {
                        // fallback below
                    }
                }
                if (trimmed.startsWith("{")) {
                    JSONObject jsonObject = JSON.parseObject(trimmed);
                    if (jsonObject != null && jsonObject.containsKey("files")) {
                        return normalizeSkillFileIndexes(jsonObject.getJSONArray("files"));
                    }
                    SkillFileDto singleFile = toSkillFileDto(jsonObject);
                    return singleFile == null ? List.of() : List.of(singleFile);
                }
                return List.of();
            }
            if (targetConfig instanceof Collection<?> c) {
                return normalizeSkillFileIndexes(c);
            }
            if (targetConfig instanceof JSONObject jsonObject && jsonObject.containsKey("files")) {
                return normalizeSkillFileIndexes(jsonObject.getJSONArray("files"));
            }
            if (targetConfig instanceof SkillConfigDto skillConfigDto) {
                return normalizeSkillFileIndexes(skillConfigDto.getFiles());
            }
            if (targetConfig instanceof SkillPublishedConfigDto publishedConfigDto) {
                return normalizeSkillFileIndexes(publishedConfigDto.getFiles());
            }
            if (targetConfig instanceof Map<?, ?> map && map.get("files") instanceof Collection<?> files) {
                return normalizeSkillFileIndexes(files);
            }
        } catch (Exception e) {
            log.warn("parse skill file index failed", e);
        }
        return List.of();
    }

    private List<SkillFileDto> snapshotSkillFiles(Collection<?> sourceFiles, String targetType, Long skillId) {
        if (CollectionUtils.isEmpty(sourceFiles)) {
            return List.of();
        }
        List<SkillFileDto> results = new ArrayList<>();
        for (Object rawFile : sourceFiles) {
            SkillFileDto sourceFile = toSkillFileDto(rawFile);
            if (sourceFile == null || StringUtils.isBlank(sourceFile.getName())) {
                continue;
            }
            SkillFileDto snapshotFile = new SkillFileDto();
            snapshotFile.setName(sourceFile.getName());
            snapshotFile.setIsDir(Boolean.TRUE.equals(sourceFile.getIsDir()));
            if (Boolean.TRUE.equals(sourceFile.getIsDir())) {
                results.add(snapshotFile);
                continue;
            }
            byte[] bytes;
            if (StringUtils.isNotBlank(sourceFile.getContents())) {
                bytes = sourceFile.getContents().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            } else {
                if (StringUtils.isBlank(sourceFile.getFileProxyUrl())) {
                    log.warn("invalid skill file index, missing file source, targetType={}, skillId={}, fileName={}",
                            targetType, skillId, sourceFile.getName());
                    throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSkillConfigParseFailed);
                }
                bytes = downloadFileBytes(sourceFile.getFileProxyUrl());
            }
            FileRecordDomain fileRecord = uploadBytes(bytes, sourceFile.getName(), targetType, skillId);
            snapshotFile.setFileProxyUrl(fileRecord.getFileUrl());
            results.add(snapshotFile);
        }
        return results;
    }

    private List<SkillFileDto> normalizeSkillFileIndexes(Collection<?> files) {
        if (CollectionUtils.isEmpty(files)) {
            return List.of();
        }
        List<SkillFileDto> results = new ArrayList<>();
        for (Object item : files) {
            SkillFileDto file = toSkillFileDto(item);
            if (file == null || StringUtils.isBlank(file.getName())) {
                continue;
            }
            results.add(file);
        }
        return results;
    }

    private SkillFileDto toSkillFileDto(Object item) {
        if (item == null) {
            return null;
        }
        if (item instanceof SkillFileDto skillFileDto) {
            return skillFileDto;
        }
        if (item instanceof JSONObject jsonObject && jsonObject.containsKey("files")) {
            // 配置对象，不是文件索引
            return null;
        }
        if (item instanceof Map<?, ?> map && map.containsKey("files")) {
            // 配置对象，不是文件索引
            return null;
        }
        if (item instanceof String str) {
            String value = str.trim();
            if (StringUtils.isBlank(value)) {
                return null;
            }
            if (value.startsWith("{") && value.endsWith("}")) {
                try {
                    JSONObject obj = JSON.parseObject(value);
                    if (obj != null && obj.containsKey("files")) {
                        return null;
                    }
                    SkillFileDto dto = JSON.parseObject(value, SkillFileDto.class);
                    if (dto != null && StringUtils.isNotBlank(dto.getName())) {
                        return dto;
                    }
                } catch (Exception ignored) {
                    // fallback to plain path
                }
            }
            SkillFileDto dto = new SkillFileDto();
            if (value.endsWith("/")) {
                dto.setName(value.substring(0, value.length() - 1));
                dto.setIsDir(true);
            } else {
                dto.setName(value);
                dto.setIsDir(false);
            }
            return dto;
        }
        try {
            SkillFileDto dto = JSON.parseObject(JSON.toJSONString(item), SkillFileDto.class);
            if (dto != null && StringUtils.isNotBlank(dto.getName())) {
                return dto;
            }
        } catch (Exception ignored) {
            // ignore malformed element
        }
        return null;
    }

    private boolean isSnapshotSourceFilesValid(List<SkillFileDto> files) {
        if (CollectionUtils.isEmpty(files)) {
            return false;
        }
        return files.stream().allMatch(file ->
                file != null
                        && StringUtils.isNotBlank(file.getName())
                        && (Boolean.TRUE.equals(file.getIsDir())
                        || StringUtils.isNotBlank(file.getFileProxyUrl())
                        || StringUtils.isNotBlank(file.getContents())));
    }

    private String uploadSkillZip(String skillName, List<SkillFileDto> files, Long skillId) {
        byte[] zipBytes = buildZipBytes(skillName, files);
        String zipFileName = skillName + ".zip";
        FileRecordDomain fileRecord = uploadBytes(zipBytes, zipFileName, TARGET_TYPE_SKILL_PUBLISHED, skillId);
        return fileRecord.getFileUrl();
    }

    private byte[] buildZipBytes(String skillName, List<SkillFileDto> files) {
        if (CollectionUtils.isEmpty(files)) {
            return new byte[0];
        }
        String folderName = StringUtils.isBlank(skillName) ? "skill" : skillName;
        String baseDir = folderName.endsWith("/") ? folderName : folderName + "/";
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos, java.nio.charset.StandardCharsets.UTF_8)) {
            Set<String> addedEntries = new HashSet<>();
            for (SkillFileDto file : files) {
                if (file == null || StringUtils.isBlank(file.getName())) {
                    continue;
                }
                String normalizedName = file.getName().startsWith("/") ? file.getName().substring(1) : file.getName();
                String entryName = baseDir + normalizedName;
                if (Boolean.TRUE.equals(file.getIsDir())) {
                    if (!entryName.endsWith("/")) {
                        entryName = entryName + "/";
                    }
                    if (addedEntries.add(entryName)) {
                        zos.putNextEntry(new ZipEntry(entryName));
                        zos.closeEntry();
                    }
                    continue;
                }
                ensureZipParentDirectories(zos, entryName, baseDir, addedEntries);
                if (!addedEntries.add(entryName)) {
                    continue;
                }
                zos.putNextEntry(new ZipEntry(entryName));
                byte[] bytes = downloadFileBytes(file.getFileProxyUrl());
                zos.write(bytes);
                zos.closeEntry();
            }
            zos.finish();
            return baos.toByteArray();
        } catch (Exception e) {
            log.error("build skill zip failed", e);
            return new byte[0];
        }
    }

    private void ensureZipParentDirectories(ZipOutputStream zos, String entryName, String baseDir, Set<String> addedEntries) throws Exception {
        String relativePath = entryName.startsWith(baseDir) ? entryName.substring(baseDir.length()) : entryName;
        if (!relativePath.contains("/")) {
            return;
        }
        String[] pathParts = relativePath.split("/");
        StringBuilder currentPath = new StringBuilder(baseDir);
        for (int i = 0; i < pathParts.length - 1; i++) {
            if (StringUtils.isBlank(pathParts[i])) {
                continue;
            }
            currentPath.append(pathParts[i]).append("/");
            String dirPath = currentPath.toString();
            if (addedEntries.add(dirPath)) {
                zos.putNextEntry(new ZipEntry(dirPath));
                zos.closeEntry();
            }
        }
    }

    private FileRecordDomain uploadBytes(byte[] bytes, String path, String targetType, Long skillId) {
        String fileName = path;
        int idx = path.lastIndexOf('/');
        if (idx >= 0 && idx < path.length() - 1) {
            fileName = path.substring(idx + 1);
        }
        String contentType = fileName.endsWith(".zip") ? "application/zip" : "application/octet-stream";
        InMemoryMultipartFile multipartFile = new InMemoryMultipartFile("file", fileName, contentType, bytes);
        Long tenantId = RequestContext.get() != null ? RequestContext.get().getTenantId() : null;
        Long userId = RequestContext.get() != null ? RequestContext.get().getUserId() : null;
        return fileManagementService.uploadFile(multipartFile, tenantId, userId, targetType, skillId, path, true);
    }

    private byte[] downloadFileBytes(String fileProxyUrl) {
        if (StringUtils.isBlank(fileProxyUrl)) {
            return new byte[0];
        }
        String fileKey = extractFileKey(fileProxyUrl);
        if (StringUtils.isNotBlank(fileKey)) {
            try (InputStream inputStream = fileManagementService.downloadFile(fileKey)) {
                if (inputStream != null) {
                    return readAllBytes(inputStream);
                }
            } catch (Exception e) {
                log.warn("download file failed by key={}", fileKey, e);
            }
        }
        try (InputStream inputStream = new URI(fileProxyUrl).toURL().openStream()) {
            return readAllBytes(inputStream);
        } catch (Exception e) {
            log.warn("download file failed by url={}", fileProxyUrl, e);
            return new byte[0];
        }
    }

    private String resolveSkillNameFromSkillMd(List<SkillFileDto> files, String fallbackName) {
        SkillConfigDto skillConfigDto = new SkillConfigDto();
        skillConfigDto.setFiles(files);
        skillConfigDto.setEnName(fallbackName);
        skillConfigDto.setName(fallbackName);
        SkillNameUtil.backfillName(skillConfigDto, iFileAccessService);
        return StringUtils.defaultIfBlank(skillConfigDto.getEnName(), skillConfigDto.getName());
    }

    private byte[] readAllBytes(InputStream inputStream) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int len;
        while ((len = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, len);
        }
        return outputStream.toByteArray();
    }

    private String extractFileKey(String fileProxyUrl) {
        if (StringUtils.isBlank(fileProxyUrl)) {
            return null;
        }
        String value = fileProxyUrl;
        int queryIdx = value.indexOf('?');
        if (queryIdx > -1) {
            value = value.substring(0, queryIdx);
        }
        int markerIdx = value.indexOf("/api/f/");
        if (markerIdx > -1) {
            return value.substring(markerIdx + "/api/f/".length());
        }
        return value.startsWith("/") ? value : "/" + value;
    }

    private String generateTargetTypeKey(Published.TargetType targetType) {
        String key = targetType.name();
        if (RequestContext.get() != null && RequestContext.get().getRequestId() != null) {
            key = key + ":" + RequestContext.get().getRequestId();
        }
        return key;
    }
}
