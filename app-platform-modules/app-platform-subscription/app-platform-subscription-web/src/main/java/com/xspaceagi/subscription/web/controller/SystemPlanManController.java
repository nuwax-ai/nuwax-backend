package com.xspaceagi.subscription.web.controller;

import com.xspaceagi.subscription.app.service.SubscriptionPlanAppService;
import com.xspaceagi.subscription.app.service.UserSubscriptionAppService;
import com.xspaceagi.subscription.sdk.dto.PlanDTO;
import com.xspaceagi.subscription.sdk.dto.PlanQueryRequest;
import com.xspaceagi.subscription.sdk.dto.SubscriptionStatsDTO;
import com.xspaceagi.subscription.sdk.dto.SubscriptionStatsQueryRequest;
import com.xspaceagi.subscription.spec.enums.BizTypeEnum;
import com.xspaceagi.subscription.web.controller.dto.SortUpdateDTO;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.dto.ReqResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.xspaceagi.system.spec.enums.ResourceEnum.*;

@Slf4j
@RestController
@RequestMapping("/api/system/plan")
@Tag(name = "系统订阅计划管理")
public class SystemPlanManController {

    @Resource
    private SubscriptionPlanAppService subscriptionPlanAppService;

    @Resource
    private UserSubscriptionAppService userSubscriptionAppService;

    @RequireResource(SUBSCRIPTION_POINTS_QUERY)
    @GetMapping("/subscription/stats")
    @Operation(summary = "查询指定对象的订阅统计")
    public ReqResult<SubscriptionStatsDTO> getSubscriptionStats(
            @RequestParam BizTypeEnum bizType,
            @RequestParam String bizId,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        SubscriptionStatsQueryRequest request = new SubscriptionStatsQueryRequest();
        request.setBizType(bizType);
        request.setBizId(bizId);
        request.setPageNum(pageNum);
        request.setPageSize(pageSize);
        return ReqResult.success(userSubscriptionAppService.getSubscriptionStats(request));
    }

    @RequireResource(SUBSCRIPTION_POINTS_MODIFY)
    @PostMapping(value = "/create")
    @Operation(summary = "添加订阅计划")
    public ReqResult<Long> createPlan(@RequestBody PlanDTO dto) {
        dto.setBizType(BizTypeEnum.SYSTEM);
        return ReqResult.success(subscriptionPlanAppService.createPlan(dto));
    }

    @RequireResource(SUBSCRIPTION_POINTS_MODIFY)
    @PostMapping(value = "/update")
    @Operation(summary = "修改订阅计划")
    public ReqResult<Boolean> updatePlan(@RequestBody PlanDTO dto) {
        return ReqResult.success(subscriptionPlanAppService.updatePlan(dto));
    }

    @RequireResource(SUBSCRIPTION_POINTS_MODIFY)
    @PostMapping(value = "/sort/update")
    @Operation(summary = "修改订阅计划排序")
    public ReqResult<Void> updatePlanSort(@RequestBody List<SortUpdateDTO> updateDTOS) {
        updateDTOS.forEach(updateDTO -> {
            PlanDTO dto = new PlanDTO();
            dto.setId(updateDTO.getId());
            dto.setSort(updateDTO.getSort());
            subscriptionPlanAppService.updatePlan(dto);
        });
        return ReqResult.success();
    }

    @RequireResource(SUBSCRIPTION_POINTS_QUERY)
    @GetMapping("/list")
    @Operation(summary = "查询订阅计划列表")
    public ReqResult<List<PlanDTO>> listPlans(
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword) {
        PlanQueryRequest query = new PlanQueryRequest();
        query.setBizType(BizTypeEnum.SYSTEM);
        query.setStatus(status);
        query.setKeyword(keyword);
        return ReqResult.success(subscriptionPlanAppService.listPlans(query));
    }

    @RequireResource(SUBSCRIPTION_POINTS_MODIFY)
    @PostMapping("/{id}/offline")
    @Operation(summary = "下架订阅计划")
    public ReqResult<Boolean> offlinePlan(@PathVariable Long id) {
        return ReqResult.success(subscriptionPlanAppService.offlinePlan(id));
    }

    @RequireResource(SUBSCRIPTION_POINTS_DELETE)
    @PostMapping("/{id}/delete")
    @Operation(summary = "删除订阅计划")
    public ReqResult<Boolean> deletePlan(@PathVariable Long id) {
        return ReqResult.success(subscriptionPlanAppService.deletePlan(id));
    }

}
