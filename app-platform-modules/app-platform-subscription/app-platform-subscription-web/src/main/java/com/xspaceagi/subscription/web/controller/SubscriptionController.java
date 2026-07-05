package com.xspaceagi.subscription.web.controller;

import com.xspaceagi.agent.core.sdk.IAgentRpcService;
import com.xspaceagi.agent.core.sdk.dto.AgentInfoDto;
import com.xspaceagi.agent.core.sdk.dto.SkillInfoDto;
import com.xspaceagi.bill.sdk.dto.CreateOrderRequest;
import com.xspaceagi.bill.sdk.dto.OrderDTO;
import com.xspaceagi.bill.sdk.rpc.IBillRpcService;
import com.xspaceagi.bill.spec.enums.TargetTypeEnum;
import com.xspaceagi.subscription.api.rpc.SubscriptionRpcService;
import com.xspaceagi.subscription.app.service.SubscriptionPlanAppService;
import com.xspaceagi.subscription.app.service.UserSubscriptionAppService;
import com.xspaceagi.subscription.sdk.dto.*;
import com.xspaceagi.subscription.spec.enums.BizTypeEnum;
import com.xspaceagi.subscription.web.controller.dto.MySubscriptionDTO;
import com.xspaceagi.pay.sdk.enums.PayMode;
import com.xspaceagi.system.application.util.DefaultIconUrlUtil;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/subscription")
@Tag(name = "用户订阅相关接口")
public class SubscriptionController {

    @Resource
    private UserSubscriptionAppService userSubscriptionAppService;

    @Resource
    private SubscriptionPlanAppService subscriptionPlanAppService;

    @Resource
    private SubscriptionRpcService subscriptionRpcService;

    @Resource
    private IBillRpcService iBillRpcService;

    @Resource
    private IAgentRpcService iAgentRpcService;

    @PostMapping("/order/create")
    @Operation(summary = "创建订阅订单")
    public ReqResult<OrderDTO> createOrder(
            @RequestParam Long planId, @RequestParam(required = false) PayMode payMode) {
        PlanDTO planDTO = subscriptionPlanAppService.getPlanById(planId);
        if (planDTO == null) {
            return ReqResult.error("Invalid planId");
        }
        if (planDTO.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            RequestContext<Object> ctx = RequestContext.get();
            CreateSubscriptionRequest request = new CreateSubscriptionRequest();
            request.setTenantId(ctx.getTenantId());
            request.setUserId(ctx.getUserId());
            request.setPlanId(planId);
            request.setBizType(planDTO.getBizType());
            request.setExtra(Map.of("plan", planDTO));
            subscriptionRpcService.createSubscription(request);
            return ReqResult.success();
        }
        CreateOrderRequest request = new CreateOrderRequest();
        request.setTenantId(RequestContext.get().getTenantId());
        request.setUserId(RequestContext.get().getUserId());
        request.setDescription("订阅[" + planDTO.getName() + "]");
        request.setBizType(com.xspaceagi.bill.spec.enums.BizTypeEnum.SUBSCRIPTION);
        CreateOrderRequest.CreateOrderItem item = new CreateOrderRequest.CreateOrderItem();
        item.setTargetName(planDTO.getName());
        item.setTargetType(TargetTypeEnum.PLAN.getCode());
        item.setTargetId(planDTO.getId());
        item.setPrice(planDTO.getPrice());
        item.setCount(1);
        item.setSnapshot(Map.of("plan", planDTO));
        request.setItems(List.of(item));
        request.setPayMode(payMode);
        OrderDTO order = iBillRpcService.createOrder(request);
        return ReqResult.success(order);
    }

    @GetMapping("/system/plans")
    @Operation(summary = "查询可订阅的系统计划列表")
    public ReqResult<List<PlanDTO>> listPlans() {
        PlanQueryRequest query = new PlanQueryRequest();
        query.setBizType(BizTypeEnum.SYSTEM);
        query.setBizId(null);
        query.setStatus(1);
        return ReqResult.success(subscriptionPlanAppService.listPlans(query));
    }

    @GetMapping("/my")
    @Operation(summary = "查询我的订阅")
    public ReqResult<MySubscriptionDTO> getMySubscriptions(@RequestParam(required = false) BizTypeEnum bizType, @RequestParam(required = false) String bizId) {
        SubscriptionQueryRequest query = new SubscriptionQueryRequest();
        query.setUserId(RequestContext.get().getUserId());
        query.setBizType(bizType);
        query.setBizId(bizId);
        if (bizType == null) {
            query.setBizType(BizTypeEnum.SYSTEM);
            query.setBizId(null);
        }
        List<UserSubscriptionDTO> subscriptions = userSubscriptionAppService.getUserSubscriptions(query);
        MySubscriptionDTO dto = new MySubscriptionDTO();
        dto.setSubscriptions(subscriptions);
        if (bizType == BizTypeEnum.SYSTEM || StringUtils.isNotBlank(bizId)) {
            dto.setCurrentSubscription(subscriptions.stream().findFirst().orElse(null));
        }
        if (bizType == BizTypeEnum.AGENT && CollectionUtils.isNotEmpty(subscriptions)) {
            List<Long> agentIds = subscriptions.stream().map(subscriptionDTO -> Long.parseLong(subscriptionDTO.getBizId())).collect(Collectors.toList());
            Map<Long, AgentInfoDto> agentInfoDtoMap = iAgentRpcService.queryAgentInfoList(agentIds).getData().stream().collect(Collectors.toMap(AgentInfoDto::getId, agentInfoDto -> agentInfoDto, (a, b) -> a));
            subscriptions.forEach(subscriptionDTO -> {
                AgentInfoDto agentInfoDto = agentInfoDtoMap.get(Long.parseLong(subscriptionDTO.getBizId()));
                if (agentInfoDto != null) {
                    subscriptionDTO.setBizName(agentInfoDto.getName());
                    subscriptionDTO.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(agentInfoDto.getIcon(), agentInfoDto.getName(), "agent"));
                }
            });
        }
        if (bizType == BizTypeEnum.SKILL && CollectionUtils.isNotEmpty(subscriptions)) {
            subscriptions.forEach(subscriptionDTO -> {
                SkillInfoDto publishedSkillInfo = iAgentRpcService.getPublishedSkillInfo(Long.parseLong(subscriptionDTO.getBizId()), null).getData();
                if (publishedSkillInfo != null) {
                    subscriptionDTO.setBizName(publishedSkillInfo.getName());
                    subscriptionDTO.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(publishedSkillInfo.getIcon(), publishedSkillInfo.getName(), "skill"));
                }
            });
        }
        return ReqResult.success(dto);
    }
}
