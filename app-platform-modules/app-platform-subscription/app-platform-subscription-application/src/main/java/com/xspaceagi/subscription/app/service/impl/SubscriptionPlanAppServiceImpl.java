package com.xspaceagi.subscription.app.service.impl;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xspaceagi.agent.core.sdk.IAgentRpcService;
import com.xspaceagi.agent.core.sdk.IModelRpcService;
import com.xspaceagi.agent.core.sdk.dto.AgentInfoDto;
import com.xspaceagi.agent.core.sdk.dto.ModelInfoDto;
import com.xspaceagi.knowledge.sdk.response.KnowledgeConfigVo;
import com.xspaceagi.knowledge.sdk.sevice.IKnowledgeConfigRpcService;
import com.xspaceagi.subscription.app.service.SubscriptionPlanAppService;
import com.xspaceagi.subscription.infra.dao.entity.Plan;
import com.xspaceagi.subscription.infra.dao.entity.UserSubscription;
import com.xspaceagi.subscription.infra.dao.mapper.PlanMapper;
import com.xspaceagi.subscription.infra.dao.mapper.UserSubscriptionMapper;
import com.xspaceagi.subscription.infra.dao.service.IPlanService;
import com.xspaceagi.subscription.sdk.dto.PlanDTO;
import com.xspaceagi.subscription.sdk.dto.PlanItemGroupDTO;
import com.xspaceagi.subscription.sdk.dto.PlanQueryRequest;
import com.xspaceagi.subscription.spec.enums.BizTypeEnum;
import com.xspaceagi.subscription.spec.enums.PlanPeriodEnum;
import com.xspaceagi.subscription.spec.enums.PlanStatusEnum;
import com.xspaceagi.system.sdk.permission.IPermissionCacheRpcSerivce;
import com.xspaceagi.system.sdk.permission.IUserDataPermissionRpcService;
import com.xspaceagi.system.sdk.service.dto.MergedGroupDataPermissionDto;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class SubscriptionPlanAppServiceImpl implements SubscriptionPlanAppService {

    @Resource
    private IPlanService planService;

    @Resource
    private PlanMapper planMapper;

    @Resource
    private UserSubscriptionMapper userSubscriptionMapper;

    @Resource
    private IUserDataPermissionRpcService iUserDataPermissionRpcService;

    @Resource
    private IModelRpcService iModelRpcService;

    @Resource
    private IAgentRpcService iAgentRpcService;

    @Resource
    private IKnowledgeConfigRpcService iKnowledgeConfigRpcService;

    @Resource
    private IPermissionCacheRpcSerivce iPermissionCacheRpcSerivce;

    @Override
    public Long createPlan(PlanDTO dto) {
        if (dto.getName() == null || dto.getName().isBlank()) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "计划名称不能为空");
        }
        if (dto.getBizType() == null) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "业务类型不能为空");
        }
        if (!BizTypeEnum.SYSTEM.equals(dto.getBizType()) && (dto.getBizId() == null || dto.getBizId().isBlank())) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "非系统业务类型必须指定业务对象ID");
        }

        Plan entity = convertToEntity(dto);
        if (entity.getStatus() == null) {
            entity.setStatus(PlanStatusEnum.ONLINE.getCode());
        }
        if (entity.getIsHot() == null) {
            entity.setIsHot(false);
        }
        if (entity.getCreditAmount() == null) {
            entity.setCreditAmount(java.math.BigDecimal.ZERO);
        }
        if (entity.getCallLimitCount() == null) {
            entity.setCallLimitCount(-1);
        }
        if (entity.getFunctionOnly() == null) {
            entity.setFunctionOnly(false);
        }
        planService.save(entity);
        return entity.getId();
    }

    @Override
    public boolean updatePlan(PlanDTO dto) {
        if (dto.getId() == null) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "计划ID不能为空");
        }
        PlanDTO oldDto = getPlanById(dto.getId());
        Plan entity = convertToEntity(dto);
        try {
            return planService.updateById(entity);
        } finally {
            if (oldDto != null && dto.getGroupIds() != null) {
                iPermissionCacheRpcSerivce.clearCacheAllByTenant(oldDto.getTenantId());
            }
        }
    }

    @Override
    public boolean offlinePlan(Long id) {
        Plan entity = new Plan();
        entity.setId(id);
        entity.setStatus(PlanStatusEnum.OFFLINE.getCode());
        return planService.updateById(entity);
    }

    @Override
    public boolean deletePlan(Long id) {
        Long count = userSubscriptionMapper.selectCount(
                new LambdaQueryWrapper<UserSubscription>()
                        .eq(UserSubscription::getPlanId, id));
        if (count != null && count > 0) {
            throw new BizException("PLAN_HAS_SUBSCRIPTION", "该计划已有用户订阅，无法删除");
        }
        return planService.removeById(id);
    }

    @Override
    public PlanDTO getPlanById(Long id) {
        Plan entity = planService.getById(id);
        return entity != null ? convertToDTO(entity, true) : null;
    }

    @Override
    public PlanDTO getPlanById(Long id, boolean showPlanDescItems) {
        Plan entity = planService.getById(id);
        return entity != null ? convertToDTO(entity, showPlanDescItems) : null;
    }

    @Override
    public List<PlanDTO> listPlans(PlanQueryRequest query) {
        List<Plan> plans = planMapper.selectListWithFilters(query);
        return plans.stream().map((Plan entity) -> convertToDTO(entity, true)).collect(Collectors.toList());
    }

    @Override
    public PlanDTO getFreePlan(BizTypeEnum bizType, String bizId, boolean showPlanDescItems) {
        Plan freePlan = planService.lambdaQuery()
                .eq(Plan::getBizType, bizType.getCode())
                .eq(bizType != BizTypeEnum.SYSTEM, Plan::getBizId, bizId)
                .eq(Plan::getStatus, PlanStatusEnum.ONLINE.getCode())
                .eq(Plan::getPrice, BigDecimal.ZERO)
                .orderByAsc(Plan::getSort)
                .orderByAsc(Plan::getId)
                .last("LIMIT 1")
                .one();
        return convertToDTO(freePlan, showPlanDescItems);
    }

    private Plan convertToEntity(PlanDTO dto) {
        Plan entity = new Plan();
        BeanUtils.copyProperties(dto, entity);
        if (dto.getBizType() != null) {
            entity.setBizType(dto.getBizType().getCode());
        }

        entity.setPeriod(dto.getPeriod() != null ? dto.getPeriod().getCode() : PlanPeriodEnum.FOREVER.getCode());
        if (dto.getExtra() != null) {
            entity.setExtra(JSON.toJSONString(dto.getExtra()));
        }
        if (dto.getGroupIds() != null) {
            entity.setGroupIds(JSON.toJSONString(dto.getGroupIds()));
        }
        return entity;
    }

    private PlanDTO convertToDTO(Plan entity, boolean showPlanDescItems) {
        if (entity == null) {
            return null;
        }
        PlanDTO dto = new PlanDTO();
        BeanUtils.copyProperties(entity, dto);
        dto.setBizType(BizTypeEnum.fromCode(entity.getBizType()));
        dto.setPeriod(PlanPeriodEnum.getByCode(entity.getPeriod()));

        List<PlanItemGroupDTO> itemGroups = new ArrayList<>();
        PlanItemGroupDTO itemGroup = new PlanItemGroupDTO();
        itemGroups.add(itemGroup);
        itemGroup.setName("基础权限");
        itemGroup.setDescription("基础权限");
        itemGroup.setGroupType(PlanItemGroupDTO.GroupType.BASE);
        itemGroup.setItems(new ArrayList<>());
        if (dto.getBizType() == BizTypeEnum.SYSTEM) {
            dto.setItemGroups(itemGroups);
        }
        try {
            if (entity.getExtra() != null) {
                dto.setExtra(JSON.parseObject(entity.getExtra()));
            }
            if (entity.getGroupIds() != null) {
                dto.setGroupIds(JSON.parseArray(entity.getGroupIds(), Long.class));
                if (CollectionUtils.isNotEmpty(dto.getGroupIds()) && showPlanDescItems) {
                    MergedGroupDataPermissionDto mergedGroupDataPermission = iUserDataPermissionRpcService.getMergedGroupDataPermission(dto.getGroupIds());
                    if (mergedGroupDataPermission != null) {
                        itemGroup.getItems().add(new PlanItemGroupDTO.ItemDTO("工作空间", "可创建工作空间数量 " + (mergedGroupDataPermission.getMaxSpaceCount() == null || mergedGroupDataPermission.getMaxSpaceCount() < 0 ? "无限制" : mergedGroupDataPermission.getMaxSpaceCount()), "workspace", mergedGroupDataPermission.getMaxSpaceCount() != null && mergedGroupDataPermission.getMaxSpaceCount() != 0));
                        itemGroup.getItems().add(new PlanItemGroupDTO.ItemDTO("智能体", "可创建智能体数量 " + (mergedGroupDataPermission.getMaxAgentCount() == null || mergedGroupDataPermission.getMaxAgentCount() < 0 ? "无限制" : mergedGroupDataPermission.getMaxAgentCount()), "agent", mergedGroupDataPermission.getMaxAgentCount() != null && mergedGroupDataPermission.getMaxAgentCount() != 0));
                        itemGroup.getItems().add(new PlanItemGroupDTO.ItemDTO("网页应用", "可创建网页应用数量 " + (mergedGroupDataPermission.getMaxPageAppCount() == null || mergedGroupDataPermission.getMaxPageAppCount() < 0 ? "无限制" : mergedGroupDataPermission.getMaxPageAppCount()), "pageApp", mergedGroupDataPermission.getMaxPageAppCount() != null && mergedGroupDataPermission.getMaxPageAppCount() != 0));
                        itemGroup.getItems().add(new PlanItemGroupDTO.ItemDTO("知识库", "可创建知识库数量 " + (mergedGroupDataPermission.getMaxKnowledgeCount() == null || mergedGroupDataPermission.getMaxKnowledgeCount() < 0 ? "无限制" : mergedGroupDataPermission.getMaxKnowledgeCount()), "kb", mergedGroupDataPermission.getMaxKnowledgeCount() != null && mergedGroupDataPermission.getMaxKnowledgeCount() != 0));
                        itemGroup.getItems().add(new PlanItemGroupDTO.ItemDTO("知识库", "知识库存储空间上限 " + (mergedGroupDataPermission.getKnowledgeStorageLimitGb() == null || mergedGroupDataPermission.getKnowledgeStorageLimitGb().doubleValue() < 0 ? "无限制" : mergedGroupDataPermission.getKnowledgeStorageLimitGb() + " GB"), "kb", mergedGroupDataPermission.getKnowledgeStorageLimitGb() != null && mergedGroupDataPermission.getKnowledgeStorageLimitGb().doubleValue() != 0));
                        itemGroup.getItems().add(new PlanItemGroupDTO.ItemDTO("数据表", "可创建数据表数量 " + (mergedGroupDataPermission.getMaxDataTableCount() == null || mergedGroupDataPermission.getMaxDataTableCount() < 0 ? "无限制" : mergedGroupDataPermission.getMaxDataTableCount()), "table", mergedGroupDataPermission.getKnowledgeStorageLimitGb() != null && mergedGroupDataPermission.getKnowledgeStorageLimitGb().doubleValue() != 0));
                        itemGroup.getItems().add(new PlanItemGroupDTO.ItemDTO("定时任务", "可创建定时任务数量 " + (mergedGroupDataPermission.getMaxScheduledTaskCount() == null || mergedGroupDataPermission.getMaxScheduledTaskCount() < 0 ? "无限制" : mergedGroupDataPermission.getMaxScheduledTaskCount()), "task", mergedGroupDataPermission.getMaxScheduledTaskCount() != null && mergedGroupDataPermission.getMaxScheduledTaskCount() != 0));
                        itemGroup.getItems().add(new PlanItemGroupDTO.ItemDTO("智能体电脑", "个人智能体电脑内存 " + (mergedGroupDataPermission.getAgentComputerMemoryGb() == null || mergedGroupDataPermission.getAgentComputerMemoryGb().doubleValue() < 0 ? "无限制" : mergedGroupDataPermission.getAgentComputerMemoryGb() + " GB"), "agentComputer", mergedGroupDataPermission.getAgentComputerMemoryGb() != null && mergedGroupDataPermission.getAgentComputerMemoryGb() != 0));

                        //模型权限
                        List<ModelInfoDto> modelInfoList = iModelRpcService.getModelInfoList(mergedGroupDataPermission.getModelIds());
                        if (CollectionUtils.isNotEmpty(modelInfoList)) {
                            itemGroup = new PlanItemGroupDTO();
                            itemGroups.add(itemGroup);
                            itemGroup.setName("模型权限");
                            itemGroup.setDescription("模型权限");
                            itemGroup.setGroupType(PlanItemGroupDTO.GroupType.MODEL);
                            itemGroup.setItems(modelInfoList.stream().map(modelInfo -> new PlanItemGroupDTO.ItemDTO(modelInfo.getName(), modelInfo.getDescription(), modelInfo.getIcon(), true)).collect(Collectors.toList()));
                        }

                        //智能体权限
                        List<AgentInfoDto> agentInfoList = iAgentRpcService.queryAgentInfoList(mergedGroupDataPermission.getAgentIds()).getData();
                        if (CollectionUtils.isNotEmpty(agentInfoList)) {
                            itemGroup = new PlanItemGroupDTO();
                            itemGroups.add(itemGroup);
                            itemGroup.setName("智能体权限");
                            itemGroup.setDescription("智能体权限");
                            itemGroup.setGroupType(PlanItemGroupDTO.GroupType.AGENT);
                            itemGroup.setItems(agentInfoList.stream().map(agentInfo -> new PlanItemGroupDTO.ItemDTO(agentInfo.getName(), agentInfo.getDescription(), agentInfo.getIcon(), true)).collect(Collectors.toList()));
                        }

                        //网页应用权限
                        List<AgentInfoDto> pageAgentInfoList = iAgentRpcService.queryAgentInfoList(mergedGroupDataPermission.getPageAgentIds()).getData();
                        if (CollectionUtils.isNotEmpty(pageAgentInfoList)) {
                            itemGroup = new PlanItemGroupDTO();
                            itemGroups.add(itemGroup);
                            itemGroup.setName("网页应用权限");
                            itemGroup.setDescription("网页应用权限");
                            itemGroup.setGroupType(PlanItemGroupDTO.GroupType.APP);
                            itemGroup.setItems(pageAgentInfoList.stream().map(agentInfo -> new PlanItemGroupDTO.ItemDTO(agentInfo.getName(), agentInfo.getDescription(), agentInfo.getIcon(), true)).collect(Collectors.toList()));
                        }

                        //知识库权限
                        List<KnowledgeConfigVo> knowledgeInfoList = iKnowledgeConfigRpcService.listByIds(mergedGroupDataPermission.getKnowledgeIds());
                        if (CollectionUtils.isNotEmpty(knowledgeInfoList)) {
                            itemGroup = new PlanItemGroupDTO();
                            itemGroups.add(itemGroup);
                            itemGroup.setName("知识库权限");
                            itemGroup.setDescription("知识库权限");
                            itemGroup.setGroupType(PlanItemGroupDTO.GroupType.KB);
                            itemGroup.setItems(knowledgeInfoList.stream().map(knowledgeInfo -> new PlanItemGroupDTO.ItemDTO(knowledgeInfo.getName(), knowledgeInfo.getDescription(), knowledgeInfo.getIcon(), true)).collect(Collectors.toList()));
                        }

                        //API权限
                        List<MergedGroupDataPermissionDto.OpenApiConfig> openApiConfigs = mergedGroupDataPermission.getOpenApiConfigs();
                        if (CollectionUtils.isNotEmpty(openApiConfigs)) {
                            itemGroup = new PlanItemGroupDTO();
                            itemGroups.add(itemGroup);
                            itemGroup.setName("API权限");
                            itemGroup.setDescription("API权限");
                            itemGroup.setGroupType(PlanItemGroupDTO.GroupType.API);
                            itemGroup.setOpenApiConfigs(openApiConfigs);
                        }
                    }
                }
            }
        } catch (Exception e) {
            //  ignore
        }
        return dto;
    }
}
