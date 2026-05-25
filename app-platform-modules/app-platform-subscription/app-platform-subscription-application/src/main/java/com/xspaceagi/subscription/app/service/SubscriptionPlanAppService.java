package com.xspaceagi.subscription.app.service;

import com.xspaceagi.subscription.sdk.dto.PlanDTO;
import com.xspaceagi.subscription.sdk.dto.PlanQueryRequest;
import com.xspaceagi.subscription.spec.enums.BizTypeEnum;

import java.util.List;

public interface SubscriptionPlanAppService {

    Long createPlan(PlanDTO dto);

    boolean updatePlan(PlanDTO dto);

    boolean offlinePlan(Long id);

    boolean deletePlan(Long id);

    PlanDTO getPlanById(Long id);

    PlanDTO getPlanById(Long id, boolean showPlanDescItems);

    List<PlanDTO> listPlans(PlanQueryRequest query);

    PlanDTO getFreePlan(BizTypeEnum bizType, String bizId, boolean showPlanDescItems);
}
