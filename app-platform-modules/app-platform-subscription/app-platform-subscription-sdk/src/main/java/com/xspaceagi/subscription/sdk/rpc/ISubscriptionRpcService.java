package com.xspaceagi.subscription.sdk.rpc;

import com.xspaceagi.subscription.sdk.dto.*;

import java.util.List;

public interface ISubscriptionRpcService {

    /**
     * 查询用户当前订阅信息（可指定业务类型）
     */
    List<UserSubscriptionDTO> getUserSubscriptions(SubscriptionQueryRequest request);

    /**
     * 获取用户当前系统订阅
     */
    UserSubscriptionDTO getUserCurrentSystemSubscription(Long userId);

    /**
     * 创建用户订阅（内部判断新建、续期、升级、变更计划）
     */
    UserSubscriptionDTO createSubscription(CreateSubscriptionRequest request);

    /**
     * 创建订阅计划
     */
    Long createPlan(PlanDTO dto);

    /**
     * 修改订阅计划
     */
    boolean updatePlan(PlanDTO dto);

    /**
     * 下架订阅计划
     */
    boolean offlinePlan(Long tenantId, Long planId);

    PlanDTO getPlan(Long planId);

    /**
     * 查询订阅计划列表
     */
    List<PlanDTO> listPlans(PlanQueryRequest request);

    /**
     * 增加订阅调用次数（自动判断是否需要月度重置）
     */
    int incrementCallCount(Long tenantId, Long subscriptionId, Integer count);
}
