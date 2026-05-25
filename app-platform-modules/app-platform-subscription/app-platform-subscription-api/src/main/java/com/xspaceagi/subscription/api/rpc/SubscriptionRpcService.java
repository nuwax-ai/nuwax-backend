package com.xspaceagi.subscription.api.rpc;

import com.xspaceagi.subscription.app.service.SubscriptionPlanAppService;
import com.xspaceagi.subscription.app.service.UserSubscriptionAppService;
import com.xspaceagi.subscription.sdk.dto.*;
import com.xspaceagi.subscription.sdk.rpc.ISubscriptionRpcService;
import com.xspaceagi.system.spec.common.RequestContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.List;

@Slf4j
@Service
public class SubscriptionRpcService implements ISubscriptionRpcService {

    @Resource
    private UserSubscriptionAppService userSubscriptionAppService;

    @Resource
    private SubscriptionPlanAppService subscriptionPlanAppService;

    @Override
    public List<UserSubscriptionDTO> getUserSubscriptions(SubscriptionQueryRequest request) {
        Assert.notNull(request, "参数不能为空");
        Assert.notNull(request.getUserId(), "用户ID不能为空");
        Assert.notNull(request.getTenantId(), "租户ID不能为空");
        if (RequestContext.get() == null) {
            RequestContext.setThreadTenantId(request.getTenantId());
            try {
                return userSubscriptionAppService.getUserSubscriptions(request);
            } finally {
                RequestContext.remove();
            }
        }
        return userSubscriptionAppService.getUserSubscriptions(request);
    }

    @Override
    public UserSubscriptionDTO getUserCurrentSystemSubscription(Long userId) {
        return userSubscriptionAppService.getUserCurrentSystemSubscription(userId, false);
    }

    @Override
    public UserSubscriptionDTO createSubscription(CreateSubscriptionRequest request) {
        Assert.notNull(request, "参数不能为空");
        Assert.notNull(request.getUserId(), "用户ID不能为空");
        Assert.notNull(request.getTenantId(), "租户ID不能为空");
        Assert.notNull(request.getPlanId(), "计划ID不能为空");
        if (RequestContext.get() == null) {
            RequestContext.setThreadTenantId(request.getTenantId());
            try {
                return userSubscriptionAppService.createSubscription(request);
            } finally {
                RequestContext.remove();
            }
        }
        return userSubscriptionAppService.createSubscription(request);
    }

    @Override
    public Long createPlan(PlanDTO dto) {
        Assert.notNull(dto, "参数不能为空");
        Assert.notNull(dto.getBizType(), "业务类型不能为空");
        return subscriptionPlanAppService.createPlan(dto);
    }

    @Override
    public boolean updatePlan(PlanDTO dto) {
        Assert.notNull(dto, "参数不能为空");
        Assert.notNull(dto.getId(), "计划ID不能为空");
        return subscriptionPlanAppService.updatePlan(dto);
    }

    @Override
    public boolean offlinePlan(Long tenantId, Long planId) {
        Assert.notNull(planId, "计划ID不能为空");
        return subscriptionPlanAppService.offlinePlan(planId);
    }

    @Override
    public PlanDTO getPlan(Long planId) {
        return subscriptionPlanAppService.getPlanById(planId);
    }

    @Override
    public List<PlanDTO> listPlans(PlanQueryRequest request) {
        Assert.notNull(request, "参数不能为空");
        return subscriptionPlanAppService.listPlans(request);
    }

    @Override
    public int incrementCallCount(Long tenantId, Long subscriptionId, Integer count) {
        Assert.notNull(subscriptionId, "订阅ID不能为空");
        if (RequestContext.get() == null) {
            RequestContext.setThreadTenantId(tenantId);
            try {
                return userSubscriptionAppService.incrementCallCount(subscriptionId, count);
            } finally {
                RequestContext.remove();
            }
        }
        return userSubscriptionAppService.incrementCallCount(subscriptionId, count);
    }
}
