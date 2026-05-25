package com.xspaceagi.agent.core.infra.rpc;

import com.xspaceagi.agent.core.adapter.dto.config.workflow.*;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.adapter.repository.entity.WorkflowNodeConfig;
import com.xspaceagi.pricing.sdk.dto.*;
import com.xspaceagi.pricing.sdk.rpc.IPricingRpcService;
import com.xspaceagi.pricing.spec.enums.TargetTypeEnum;
import com.xspaceagi.subscription.sdk.dto.SubscriptionQueryRequest;
import com.xspaceagi.subscription.sdk.dto.UserSubscriptionDTO;
import com.xspaceagi.subscription.sdk.rpc.ISubscriptionRpcService;
import com.xspaceagi.subscription.spec.enums.BizTypeEnum;
import com.xspaceagi.subscription.spec.enums.SubscriptionStatusEnum;
import com.xspaceagi.system.spec.common.RequestContext;
import jakarta.annotation.Resource;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ResourcePricingRpcService {

    @Resource
    private IPricingRpcService iPricingRpcService;

    @Resource
    private ISubscriptionRpcService iSubscriptionRpcService;

    public PricingConfigDTO queryPricingInfo(QueryPricingInfoRequest request) {
        return iPricingRpcService.queryPricingInfo(request);
    }

    public TrialRecordDTO getTrialCount(UpdateTrialCountRequest request) {
        return iPricingRpcService.getTrialCount(request);
    }

    public List<UserSubscriptionDTO> getUserSubscriptions(Long userId, BizTypeEnum bizType) {
        SubscriptionQueryRequest query = new SubscriptionQueryRequest();
        query.setTenantId(RequestContext.get().getTenantId());
        query.setUserId(userId);
        query.setBizType(bizType);
        query.setShowPlanDescItems(false);
        query.setStatus(SubscriptionStatusEnum.ACTIVE.getCode());
        return iSubscriptionRpcService.getUserSubscriptions(query);
    }

    public UserSubscriptionDTO getUserSubscription(Long userId, BizTypeEnum bizType, String bizId) {
        SubscriptionQueryRequest query = new SubscriptionQueryRequest();
        query.setTenantId(RequestContext.get().getTenantId());
        query.setUserId(userId);
        query.setBizType(bizType);
        query.setBizId(bizId);
        query.setShowPlanDescItems(false);
        query.setStatus(SubscriptionStatusEnum.ACTIVE.getCode());
        return iSubscriptionRpcService.getUserSubscriptions(query).stream().findFirst().orElse(null);
    }

    public List<PricingConfigDTO> listPricingConfigs(Published.TargetType targetType, List<Long> targetIds) {
        if (CollectionUtils.isEmpty(targetIds)) {
            return List.of();
        }
        TargetTypeEnum targetTypeEnum = null;
        if (targetType == Published.TargetType.Agent) {
            targetTypeEnum = TargetTypeEnum.AGENT;
        }
        if (targetType == Published.TargetType.Skill) {
            targetTypeEnum = TargetTypeEnum.SKILL;
        }
        if (targetType == Published.TargetType.Workflow) {
            targetTypeEnum = TargetTypeEnum.WORKFLOW;
        }
        if (targetType == Published.TargetType.Plugin) {
            targetTypeEnum = TargetTypeEnum.PLUGIN;
        }
        if (targetType == Published.TargetType.Model) {
            targetTypeEnum = TargetTypeEnum.MODEL;
        }
        if (targetType == null) {
            return List.of();
        }
        return iPricingRpcService.listPricingConfigs(targetTypeEnum, targetIds.stream().map(Object::toString).collect(Collectors.toList()));
    }

    public PriceEstimate estimatePrice(Long tenantId, Long userId, List<PriceEstimate.EstimateTarget> estimateTargets) {
        return iPricingRpcService.estimatePrice(tenantId, userId, estimateTargets);
    }

    public void completeWorkflowEstimateTargets(List<PriceEstimate.EstimateTarget> estimateTargets, WorkflowConfigDto targetConfig, Set<Long> workflowIds) {
        targetConfig.getNodes().forEach(node -> completeWorkflowNodeEstimateTargets(estimateTargets, node, workflowIds));
    }

    public void completeWorkflowNodeEstimateTargets(List<PriceEstimate.EstimateTarget> estimateTargets, WorkflowNodeDto node, Set<Long> workflowIds) {
        if (node.getType() == WorkflowNodeConfig.NodeType.Workflow) {
            WorkflowAsNodeConfigDto workflowAsNodeConfigDto = (WorkflowAsNodeConfigDto) node.getNodeConfig();
            if (workflowAsNodeConfigDto.getWorkflowConfig() != null && !workflowIds.contains(workflowAsNodeConfigDto.getWorkflowId())) {
                estimateTargets.add(PriceEstimate.EstimateTarget.builder().targetType(TargetTypeEnum.WORKFLOW).targetId(workflowAsNodeConfigDto.getWorkflowId().toString()).build());
                completeWorkflowEstimateTargets(estimateTargets, workflowAsNodeConfigDto.getWorkflowConfig(), workflowIds);
            }
        }
        if (node.getType() == WorkflowNodeConfig.NodeType.Plugin) {
            PluginNodeConfigDto nodeConfigDto = (PluginNodeConfigDto) node.getNodeConfig();
            estimateTargets.add(PriceEstimate.EstimateTarget.builder().targetType(TargetTypeEnum.PLUGIN).targetId(nodeConfigDto.getPluginId().toString()).build());
        }
        if (node.getType() == WorkflowNodeConfig.NodeType.LLM) {
            LLMNodeConfigDto nodeConfigDto = (LLMNodeConfigDto) node.getNodeConfig();
            estimateTargets.add(PriceEstimate.EstimateTarget.builder().targetType(TargetTypeEnum.MODEL).targetId(nodeConfigDto.getModelId().toString()).build());
            if (CollectionUtils.isNotEmpty(nodeConfigDto.getSkillComponentConfigs())) {
                nodeConfigDto.getSkillComponentConfigs().forEach(skillComponentConfigDto -> {
                    if (skillComponentConfigDto.getType() == LLMNodeConfigDto.SkillComponentConfigDto.Type.Plugin) {
                        estimateTargets.add(PriceEstimate.EstimateTarget.builder().targetType(TargetTypeEnum.PLUGIN).targetId(skillComponentConfigDto.getTypeId().toString()).build());
                    }
                    if (skillComponentConfigDto.getType() == LLMNodeConfigDto.SkillComponentConfigDto.Type.Workflow && skillComponentConfigDto.getTargetConfig() instanceof WorkflowConfigDto) {
                        estimateTargets.add(PriceEstimate.EstimateTarget.builder().targetType(TargetTypeEnum.WORKFLOW).targetId(skillComponentConfigDto.getTypeId().toString()).build());
                        completeWorkflowEstimateTargets(estimateTargets, (WorkflowConfigDto) skillComponentConfigDto.getTargetConfig(), workflowIds);
                    }
                });
            }
        }
    }
}
