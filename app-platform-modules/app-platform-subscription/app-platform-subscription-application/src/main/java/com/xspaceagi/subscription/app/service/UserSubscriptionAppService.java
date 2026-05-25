package com.xspaceagi.subscription.app.service;

import com.xspaceagi.subscription.sdk.dto.*;
import com.xspaceagi.subscription.spec.enums.BizTypeEnum;

import java.util.List;

public interface UserSubscriptionAppService {

    List<UserSubscriptionDTO> getUserSubscriptions(SubscriptionQueryRequest query);

    /**
     * 获取用户当前指定业务（智能体）订阅
     */
    UserSubscriptionDTO getUserCurrentSubscription(Long userId, BizTypeEnum bizType, String bizId);

    /**
     * 获取用户当前系统订阅
     */
    UserSubscriptionDTO getUserCurrentSystemSubscription(Long userId, boolean showPlanDescItems);

    UserSubscriptionDTO createSubscription(CreateSubscriptionRequest request);

    SubscriptionStatsDTO getSubscriptionStats(SubscriptionStatsQueryRequest request);

    void reset(UserSubscriptionDTO subscriptionDTO);

    int incrementCallCount(Long subscriptionId, Integer count);

    List<UserSubscriptionDTO> getNeedResetSubscriptions();

    List<UserSubscriptionDTO> getExpiredSubscriptions();
}
