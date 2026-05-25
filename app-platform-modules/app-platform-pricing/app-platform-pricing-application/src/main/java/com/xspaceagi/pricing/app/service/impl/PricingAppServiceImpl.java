package com.xspaceagi.pricing.app.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xspaceagi.agent.core.sdk.IAgentRpcService;
import com.xspaceagi.agent.core.sdk.IModelRpcService;
import com.xspaceagi.agent.core.sdk.dto.*;
import com.xspaceagi.credit.sdk.dto.UserCreditSummary;
import com.xspaceagi.credit.sdk.rpc.ICreditRpcService;
import com.xspaceagi.mcp.sdk.IMcpApiService;
import com.xspaceagi.mcp.sdk.dto.McpDto;
import com.xspaceagi.pricing.app.service.PricingAppService;
import com.xspaceagi.pricing.infra.dao.entity.ModelPriceTier;
import com.xspaceagi.pricing.infra.dao.entity.PricingConfig;
import com.xspaceagi.pricing.infra.dao.entity.TrialRecord;
import com.xspaceagi.pricing.infra.dao.mapper.ModelPriceTierMapper;
import com.xspaceagi.pricing.infra.dao.service.IModelPriceTierService;
import com.xspaceagi.pricing.infra.dao.service.IPricingConfigService;
import com.xspaceagi.pricing.infra.dao.service.ITrialRecordService;
import com.xspaceagi.pricing.sdk.dto.*;
import com.xspaceagi.pricing.spec.enums.PricingConfigStatusEnum;
import com.xspaceagi.pricing.spec.enums.PricingTypeEnum;
import com.xspaceagi.pricing.spec.enums.TargetTypeEnum;
import com.xspaceagi.subscription.sdk.dto.PlanDTO;
import com.xspaceagi.subscription.sdk.dto.PlanQueryRequest;
import com.xspaceagi.subscription.sdk.dto.SubscriptionQueryRequest;
import com.xspaceagi.subscription.sdk.dto.UserSubscriptionDTO;
import com.xspaceagi.subscription.sdk.rpc.ISubscriptionRpcService;
import com.xspaceagi.subscription.spec.enums.BizTypeEnum;
import com.xspaceagi.subscription.spec.enums.PlanPeriodEnum;
import com.xspaceagi.subscription.spec.enums.SubscriptionStatusEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import com.xspaceagi.system.spec.utils.I18nUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PricingAppServiceImpl implements PricingAppService {

    @Resource
    private IPricingConfigService pricingConfigService;

    @Resource
    private IModelPriceTierService modelPriceTierService;

    @Resource
    private ModelPriceTierMapper modelPriceTierMapper;

    @Resource
    private IAgentRpcService iAgentRpcService;

    @Resource
    private IMcpApiService iMcpApiService;

    @Resource
    private IModelRpcService iModelRpcService;

    @Resource
    private ITrialRecordService trialRecordService;

    @Resource
    private ISubscriptionRpcService iSubscriptionRpcService;

    @Resource
    private ICreditRpcService iCreditRpcService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long savePricingConfig(SavePricingConfigRequest request) {
        if (request.getTargetType() == null) {
            throw new BizException("INVALID_PARAM", "Pricing target type cannot be empty");
        }
        if (request.getTargetId() == null || request.getTargetId().isBlank()) {
            throw new BizException("INVALID_PARAM", "Pricing target ID cannot be empty");
        }
        if (request.getPricingType() == null) {
            throw new BizException("INVALID_PARAM", "Pricing type cannot be empty");
        }

        PricingConfig entity;
        if (request.getId() != null) {
            entity = pricingConfigService.getById(request.getId());
            if (entity == null) {
                throw new BizException("NOT_FOUND", "Pricing config does not exist");
            }
        } else {
            // Check uniqueness: targetType + targetId
            PricingConfig existing = pricingConfigService.lambdaQuery()
                    .eq(PricingConfig::getTargetType, request.getTargetType().getCode())
                    .eq(PricingConfig::getTargetId, request.getTargetId())
                    .one();
            if (existing != null) {
                entity = existing;
            } else {
                entity = new PricingConfig();
            }
        }

        entity.setTargetType(request.getTargetType().getCode());
        entity.setTargetId(request.getTargetId());
        entity.setPricingType(request.getPricingType().getCode());
        entity.setPrice(request.getPrice());
        entity.setTrialCount(request.getTrialCount() != null ? request.getTrialCount() : 0);
        entity.setStatus(request.getStatus() != null ? request.getStatus() : PricingConfigStatusEnum.ENABLED.getCode());
        entity.setSpaceId(request.getSpaceId() != null ? request.getSpaceId() : -1L);

        if (entity.getId() == null) {
            pricingConfigService.save(entity);
        } else {
            pricingConfigService.updateById(entity);
        }

        // Set plan for monthly mode
        if (request.getTargetType() == TargetTypeEnum.SKILL) {
            QueryPricingInfoRequest queryPricingInfo = new QueryPricingInfoRequest();
            queryPricingInfo.setTargetType(TargetTypeEnum.SKILL);
            queryPricingInfo.setTargetId(request.getTargetId());
            queryPricingInfo.setTenantId(request.getTenantId());
            PricingConfigDTO pricingConfigDTO = queryPricingInfo(queryPricingInfo);
            PlanDTO planDTO = (PlanDTO) pricingConfigDTO.getPlans().get(0);
            planDTO.setPrice(pricingConfigDTO.getPrice());
            planDTO.setPeriod(request.getPricingType() == PricingTypeEnum.MONTHLY ? PlanPeriodEnum.MONTH : PlanPeriodEnum.FOREVER);
            iSubscriptionRpcService.updatePlan(planDTO);
        }

        log.info("Save pricing config, id={}, targetType={}, targetId={}, pricingType={}",
                entity.getId(), entity.getTargetType(), entity.getTargetId(), entity.getPricingType());
        return entity.getId();
    }

    @Override
    public List<PricingConfigDTO> listPricingConfigs(PricingConfigQueryRequest query) {
        LambdaQueryWrapper<PricingConfig> wrapper = new LambdaQueryWrapper<>();
        if (CollectionUtils.isNotEmpty(query.getTargetTypes())) {
            wrapper.in(PricingConfig::getTargetType, query.getTargetTypes().stream()
                    .map(TargetTypeEnum::getCode).collect(Collectors.toList()));
        }
        if (query.getTargetType() != null) {
            wrapper.eq(PricingConfig::getTargetType, query.getTargetType().getCode());
        }
        if (query.getTargetId() != null) {
            wrapper.eq(PricingConfig::getTargetId, query.getTargetId());
        }
        if (query.getPricingType() != null) {
            wrapper.eq(PricingConfig::getPricingType, query.getPricingType().getCode());
        }
        if (query.getStatus() != null) {
            wrapper.eq(PricingConfig::getStatus, query.getStatus());
        }
        if (query.getSpaceId() != null) {
            wrapper.eq(PricingConfig::getSpaceId, query.getSpaceId());
        }
        wrapper.orderByDesc(PricingConfig::getModified);
        return pricingConfigService.list(wrapper).stream()
                .map((PricingConfig entity) -> convertConfigToDTO(entity, true)).filter(Objects::nonNull).collect(Collectors.toList());
    }

    @Override
    public List<PricingConfigDTO> listPricingConfigs(TargetTypeEnum targetType, List<String> targetIds) {
        Assert.notNull(targetType, "Request cannot be null");
        Assert.isTrue(CollectionUtils.isNotEmpty(targetIds), "Request cannot be null");
        LambdaQueryWrapper<PricingConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PricingConfig::getTargetType, targetType.getCode());
        wrapper.in(PricingConfig::getTargetId, targetIds);
        return pricingConfigService.list(wrapper).stream()
                .map((PricingConfig entity) -> convertConfigToDTO(entity, false)).filter(Objects::nonNull).collect(Collectors.toList());
    }

    @Override
    public PriceEstimate estimatePrice(Long tenantId, Long userId, List<PriceEstimate.EstimateTarget> estimateTargets) {
        PriceEstimate result = new PriceEstimate();
        result.setPass(true);
        if (estimateTargets == null || estimateTargets.isEmpty()) {
            return result;
        }
        result.setPricingConfigs(new ArrayList<>());

        Map<TargetTypeEnum, List<PriceEstimate.EstimateTarget>> grouped = estimateTargets.stream()
                .filter(t -> t.getTargetType() != null)
                .collect(Collectors.groupingBy(PriceEstimate.EstimateTarget::getTargetType));

        for (Map.Entry<TargetTypeEnum, List<PriceEstimate.EstimateTarget>> entry : grouped.entrySet()) {
            TargetTypeEnum targetType = entry.getKey();
            List<String> targetIds = entry.getValue().stream()
                    .map(PriceEstimate.EstimateTarget::getTargetId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());

            if (targetIds.isEmpty()) {
                continue;
            }

            List<PricingConfigDTO> configs = TenantFunctions.callWithIgnoreCheck(() -> listPricingConfigs(targetType, targetIds));
            // Record paid content
            result.getPricingConfigs().addAll(configs.stream().filter(c -> c.getStatus() != null && c.getStatus() == 1).toList());
            for (PricingConfigDTO config : configs) {
                if (config.getStatus() != null && config.getStatus() == 0) {
                    continue;
                }

                if (config.getPricingType() != null) {
                    switch (config.getPricingType()) {
                        case MONTHLY:
                        case BUYOUT:
                            SubscriptionQueryRequest query = new SubscriptionQueryRequest();
                            query.setTenantId(tenantId);
                            query.setUserId(userId);
                            query.setBizType(BizTypeEnum.fromCode(config.getTargetType().getCode()));
                            query.setBizId(config.getTargetId());
                            query.setShowPlanDescItems(false);
                            query.setStatus(SubscriptionStatusEnum.ACTIVE.getCode());
                            List<UserSubscriptionDTO> userSubscriptions = iSubscriptionRpcService.getUserSubscriptions(query);
                            // Check subscription plan
                            if (CollectionUtils.isEmpty(userSubscriptions)) {
                                Integer trialCount = config.getTrialCount();
                                if (trialCount != null && trialCount > 0 && tenantId != null) {
                                    UpdateTrialCountRequest trialReq = new UpdateTrialCountRequest();
                                    trialReq.setTenantId(tenantId);
                                    trialReq.setUserId(userId);
                                    trialReq.setTargetType(targetType);
                                    trialReq.setTargetId(config.getTargetId());
                                    TrialRecordDTO trialRecord = getTrialCount(trialReq);
                                    if (trialRecord == null || trialRecord.getUsedCount() == null
                                            || trialRecord.getUsedCount() < trialCount) {
                                        result.setTrial(true);
                                        continue;
                                    }
                                }
                                result.setPass(false);
                                result.setMessage(config.getTargetType() == TargetTypeEnum.AGENT ? I18nUtil.systemMessage("Backend.Pricing.Estimate.AgentNotSubscribed") : I18nUtil.systemMessage("Backend.Pricing.Estimate.SkillNotSubscribed"));
                                result.setTargetId(config.getTargetId());
                                return result;
                            }

                            // Check call count limit
                            UserSubscriptionDTO userSubscriptionDTO = userSubscriptions.get(0);
                            Integer callUsedCount = userSubscriptionDTO.getCallUsedCount();
                            Integer callLimitCount = userSubscriptionDTO.getPlan().getCallLimitCount();
                            if (callUsedCount != null && callUsedCount > 0 && callLimitCount != null && callLimitCount > 0 && callUsedCount >= callLimitCount) {
                                result.setPass(false);
                                result.setMessage(config.getTargetType() == TargetTypeEnum.AGENT ? I18nUtil.systemMessage("Backend.Pricing.Estimate.AgentCallLimitExceeded") : I18nUtil.systemMessage("Backend.Pricing.Estimate.SkillCallLimitExceeded"));
                                return result;
                            }
                            result.setSubscription(userSubscriptionDTO);
                            continue;
                        case ONE_TIME:
                        case TIERED:
                            // Allow usage if user has credits above 0
                            UserCreditSummary userCreditSummary = TenantFunctions.callWithIgnoreCheck(() -> iCreditRpcService.getUserCreditSummary(userId));
                            if (userCreditSummary.getTotalCredit() == null || userCreditSummary.getTotalCredit().compareTo(BigDecimal.ZERO) <= 0) {
                                result.setPass(false);
                                result.setMessage(I18nUtil.systemMessage("Backend.Pricing.Estimate.CreditExhausted"));
                                return result;
                            }
                    }
                }
            }
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long addModelPriceTier(ModelPriceTierDTO dto) {
        if (dto.getModelId() == null) {
            throw new BizException("INVALID_PARAM", "Model ID cannot be empty");
        }
        if (dto.getContextLength() == null) {
            throw new BizException("INVALID_PARAM", "Context length cannot be empty");
        }

        ModelPriceTier one = modelPriceTierService.lambdaQuery().eq(ModelPriceTier::getModelId, dto.getModelId())
                .eq(ModelPriceTier::getContextLength, dto.getContextLength()).one();
        ModelPriceTier entity;
        if (one == null) {
            entity = new ModelPriceTier();
            BeanUtils.copyProperties(dto, entity);
            modelPriceTierService.save(entity);
        } else {
            one.setInputPrice(dto.getInputPrice());
            one.setOutputPrice(dto.getOutputPrice());
            one.setCachePrice(dto.getCachePrice());
            modelPriceTierService.updateById(one);
            entity = one;
        }

        // Ensure pricing config exists for this model
        PricingConfig config = pricingConfigService.lambdaQuery()
                .eq(PricingConfig::getTargetType, TargetTypeEnum.MODEL.getCode())
                .eq(PricingConfig::getTargetId, dto.getModelId().toString())
                .one();
        if (config == null) {
            config = PricingConfig.builder()
                    .targetType(TargetTypeEnum.MODEL.getCode())
                    .targetId(dto.getModelId().toString())
                    .pricingType(PricingTypeEnum.TIERED.getCode())
                    .status(PricingConfigStatusEnum.ENABLED.getCode())
                    .trialCount(0)
                    .spaceId(dto.getSpaceId() == null ? -1L : dto.getSpaceId())
                    .build();
            pricingConfigService.save(config);
        }

        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateModelPriceTier(ModelPriceTierDTO dto) {
        if (dto.getId() == null) {
            throw new BizException("INVALID_PARAM", "Tier ID cannot be empty");
        }
        ModelPriceTier entity = new ModelPriceTier();
        BeanUtils.copyProperties(dto, entity);
        return modelPriceTierService.updateById(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteModelPriceTier(Long id) {
        ModelPriceTier tier = modelPriceTierService.getById(id);
        if (tier == null) {
            throw new BizException("NOT_FOUND", "Model price tier does not exist");
        }
        Long modelId = tier.getModelId();
        modelPriceTierService.removeById(id);

        // If no remaining tiers, delete the pricing config
        Long remaining = modelPriceTierMapper.selectCount(
                new LambdaQueryWrapper<ModelPriceTier>()
                        .eq(ModelPriceTier::getModelId, modelId));
        if (remaining == 0) {
            PricingConfig config = pricingConfigService.lambdaQuery()
                    .eq(PricingConfig::getTargetType, TargetTypeEnum.MODEL.getCode())
                    .eq(PricingConfig::getTargetId, modelId.toString())
                    .one();
            if (config != null) {
                pricingConfigService.removeById(config.getId());
            }
        }
        return true;
    }

    @Override
    public List<ModelPriceTierDTO> listModelPriceTiers(Long modelId) {
        return modelPriceTierMapper.selectByModelId(modelId).stream()
                .map(this::convertTierToDTO).collect(Collectors.toList());
    }

    @Override
    public ModelPriceTierDTO queryModelPriceTier(Long id) {
        return convertTierToDTO(modelPriceTierService.getById(id));
    }

    @Override
    public PricingConfigDTO queryPricingConfig(Long id) {
        return convertConfigToDTO(pricingConfigService.getById(id), true);
    }

    @Override
    public void deletePricingConfig(Long id) {
        pricingConfigService.removeById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TrialRecordDTO updateTrialCount(UpdateTrialCountRequest request) {
        if (request.getUserId() == null) {
            throw new BizException("INVALID_PARAM", "User ID cannot be empty");
        }
        if (request.getTargetType() == null) {
            throw new BizException("INVALID_PARAM", "Biz type cannot be empty");
        }
        if (request.getTargetId() == null || request.getTargetId().isBlank()) {
            throw new BizException("INVALID_PARAM", "Biz ID cannot be empty");
        }

        PricingConfig config = pricingConfigService.lambdaQuery()
                .eq(PricingConfig::getTargetType, request.getTargetType().getCode())
                .eq(PricingConfig::getTargetId, request.getTargetId())
                .one();
        if (config == null || config.getTrialCount() == null || config.getTrialCount() <= 0) {
            throw new BizException("NO_TRIAL", "Trial is not supported for this target");
        }

        TrialRecord record = trialRecordService.lambdaQuery()
                .eq(TrialRecord::getUserId, request.getUserId())
                .eq(TrialRecord::getTargetType, request.getTargetType().getCode())
                .eq(TrialRecord::getTargetId, request.getTargetId())
                .one();
        if (record == null) {
            record = TrialRecord.builder()
                    .userId(request.getUserId())
                    .targetType(request.getTargetType().getCode())
                    .targetId(request.getTargetId())
                    .usedCount(1)
                    .build();
            trialRecordService.save(record);
        } else {
            record.setUsedCount(record.getUsedCount() + 1);
            trialRecordService.updateById(record);
        }
        return convertTrialToDTO(record);
    }

    @Override
    public TrialRecordDTO getTrialCount(UpdateTrialCountRequest request) {
        if (request.getUserId() == null) {
            throw new BizException("INVALID_PARAM", "User ID cannot be empty");
        }
        if (request.getTargetType() == null) {
            throw new BizException("INVALID_PARAM", "Biz type cannot be empty");
        }
        if (request.getTargetId() == null || request.getTargetId().isBlank()) {
            throw new BizException("INVALID_PARAM", "Biz ID cannot be empty");
        }
        TrialRecord record = trialRecordService.lambdaQuery()
                .eq(TrialRecord::getUserId, request.getUserId())
                .eq(TrialRecord::getTargetType, request.getTargetType().getCode())
                .eq(TrialRecord::getTargetId, request.getTargetId())
                .one();
        return convertTrialToDTO(record);
    }

    @Override
    public PricingConfigDTO queryPricingInfo(QueryPricingInfoRequest request) {
        if (request.getTargetType() == null) {
            throw new BizException("INVALID_PARAM", "Biz type cannot be empty");
        }
        if (request.getTargetId() == null || request.getTargetId().isBlank()) {
            throw new BizException("INVALID_PARAM", "Biz ID cannot be empty");
        }
        PricingConfig config = pricingConfigService.lambdaQuery()
                .eq(PricingConfig::getTargetType, request.getTargetType().getCode())
                .eq(PricingConfig::getTargetId, request.getTargetId())
                .one();
        if (config == null) {
            config = PricingConfig.builder()
                    .targetType(request.getTargetType().getCode())
                    .targetId(request.getTargetId())
                    .status(0)
                    .build();
        }
        return convertConfigToDTO(config, request.getShowTargetObjectInfo() == null || request.getShowTargetObjectInfo());
    }

    private TrialRecordDTO convertTrialToDTO(TrialRecord entity) {
        if (entity == null) {
            return null;
        }
        TrialRecordDTO dto = new TrialRecordDTO();
        BeanUtils.copyProperties(entity, dto);
        dto.setTargetType(TargetTypeEnum.fromCode(entity.getTargetType()));
        return dto;
    }

    private PricingConfigDTO convertConfigToDTO(PricingConfig entity, boolean showTargetObjectInfo) {
        if (entity == null) {
            return null;
        }
        PricingConfigDTO dto = new PricingConfigDTO();
        BeanUtils.copyProperties(entity, dto);
        dto.setTargetType(TargetTypeEnum.fromCode(entity.getTargetType()));
        dto.setPricingType(PricingTypeEnum.fromCode(entity.getPricingType()));
        if (dto.getTargetType() == TargetTypeEnum.MODEL) {
            dto.setModelPriceTiers(listModelPriceTiers(Long.parseLong(entity.getTargetId())));
        }
        if (!showTargetObjectInfo) {
            return dto;
        }
        if (dto.getTargetType() == TargetTypeEnum.PLUGIN) {
            PluginInfoDto data = iAgentRpcService.getPublishedPluginInfo(Long.parseLong(entity.getTargetId()), entity.getSpaceId()).getData();
            if (data == null) {
                return null;
            }
            dto.setTargetObjectInfo(new TargetObjectInfo(data.getName(), data.getDescription(), data.getIcon()));
        }
        if (dto.getTargetType() == TargetTypeEnum.WORKFLOW) {
            WorkflowInfoDto data = iAgentRpcService.getPublishedWorkflowInfo(Long.parseLong(entity.getTargetId()), entity.getSpaceId()).getData();
            if (data == null) {
                return null;
            }
            dto.setTargetObjectInfo(new TargetObjectInfo(data.getName(), data.getDescription(), data.getIcon()));
        }
        if (dto.getTargetType() == TargetTypeEnum.SKILL) {
            SkillInfoDto data = iAgentRpcService.getPublishedSkillInfo(Long.parseLong(entity.getTargetId()), entity.getSpaceId()).getData();
            if (data == null) {
                return null;
            }
            dto.setTargetObjectInfo(new TargetObjectInfo(data.getName(), data.getDescription(), data.getIcon()));
            if (dto.getPricingType() == PricingTypeEnum.MONTHLY || dto.getPricingType() == PricingTypeEnum.BUYOUT) {
                PlanQueryRequest planQueryRequest = PlanQueryRequest.builder()
                        .tenantId(entity.getTenantId())
                        .bizType(BizTypeEnum.SKILL)
                        .bizId(entity.getTargetId())
                        .status(1)
                        .build();
                List<PlanDTO> planDTOS = iSubscriptionRpcService.listPlans(planQueryRequest);
                if (CollectionUtils.isEmpty(planDTOS)) {
                    PlanDTO planDTO = PlanDTO.builder()
                            .name(data.getName())
                            .description(data.getDescription())
                            .price(BigDecimal.ZERO)
                            .period(dto.getPricingType() == PricingTypeEnum.MONTHLY ? PlanPeriodEnum.MONTH : PlanPeriodEnum.FOREVER)
                            .bizType(BizTypeEnum.SKILL)
                            .bizId(entity.getTargetId())
                            .build();
                    Long planId = iSubscriptionRpcService.createPlan(planDTO);
                    planDTO.setId(planId);
                    planDTOS.add(planDTO);
                }
                dto.setPlans(Arrays.asList(planDTOS.toArray()));
            }
        }
        if (dto.getTargetType() == TargetTypeEnum.MCP) {
            McpDto deployedMcp = iMcpApiService.getDeployedMcp(Long.parseLong(entity.getTargetId()), entity.getSpaceId());
            if (deployedMcp == null) {
                return null;
            }
            dto.setTargetObjectInfo(new TargetObjectInfo(deployedMcp.getName(), deployedMcp.getDescription(), deployedMcp.getIcon()));
        }
        if (dto.getTargetType() == TargetTypeEnum.MODEL) {
            ModelInfoDto modelInfo = iModelRpcService.getModelInfo(Long.parseLong(entity.getTargetId()));
            if (modelInfo == null) {
                return null;
            }
            dto.setTargetObjectInfo(new TargetObjectInfo(modelInfo.getName(), modelInfo.getDescription(), modelInfo.getIcon()));
        }
        if (dto.getTargetType() == TargetTypeEnum.AGENT) {
            AgentInfoDto data = iAgentRpcService.queryPublishedAgentInfo(Long.parseLong(entity.getTargetId())).getData();
            if (data == null) {
                return null;
            }
            dto.setTargetObjectInfo(new TargetObjectInfo(data.getName(), data.getDescription(), data.getIcon()));
            PlanQueryRequest planQueryRequest = PlanQueryRequest.builder()
                    .tenantId(entity.getTenantId())
                    .bizType(BizTypeEnum.AGENT)
                    .bizId(entity.getTargetId())
                    .status(1)
                    .build();
            List<PlanDTO> planDTOS = iSubscriptionRpcService.listPlans(planQueryRequest);
            dto.setPlans(Arrays.asList(planDTOS.toArray()));
        }
        return dto;
    }

    private ModelPriceTierDTO convertTierToDTO(ModelPriceTier entity) {
        ModelPriceTierDTO dto = new ModelPriceTierDTO();
        BeanUtils.copyProperties(entity, dto);
        return dto;
    }
}
