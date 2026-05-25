package com.xspaceagi.subscription.web.controller;

import com.xspaceagi.agent.core.sdk.IAgentRpcService;
import com.xspaceagi.agent.core.sdk.dto.AgentInfoDto;
import com.xspaceagi.subscription.app.service.SubscriptionPlanAppService;
import com.xspaceagi.subscription.app.service.UserSubscriptionAppService;
import com.xspaceagi.subscription.sdk.dto.PlanDTO;
import com.xspaceagi.subscription.sdk.dto.PlanQueryRequest;
import com.xspaceagi.subscription.sdk.dto.SubscriptionStatsDTO;
import com.xspaceagi.subscription.sdk.dto.SubscriptionStatsQueryRequest;
import com.xspaceagi.subscription.spec.enums.BizTypeEnum;
import com.xspaceagi.subscription.web.controller.dto.SortUpdateDTO;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.dto.ReqResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/agent/plan")
@Tag(name = "智能体订阅计划管理")
public class AgentPlanManController {

    @Resource
    private SubscriptionPlanAppService subscriptionPlanAppService;

    @Resource
    private UserSubscriptionAppService userSubscriptionAppService;

    @Resource
    private IAgentRpcService iAgentRpcService;

    @Resource
    private SpacePermissionService spacePermissionService;

    @GetMapping("/subscription/stats")
    @Operation(summary = "查询智能体订阅统计")
    public ReqResult<SubscriptionStatsDTO> getSubscriptionStats(
            @RequestParam Long agentId,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        checkAgentPermission(agentId);
        SubscriptionStatsQueryRequest request = new SubscriptionStatsQueryRequest();
        request.setBizType(BizTypeEnum.AGENT);
        request.setBizId(agentId.toString());
        request.setPageNum(pageNum);
        request.setPageSize(pageSize);
        return ReqResult.success(userSubscriptionAppService.getSubscriptionStats(request));
    }

    @PostMapping(value = "/create")
    @Operation(summary = "添加订阅计划")
    public ReqResult<Long> createPlan(@RequestBody PlanDTO dto) {
        Assert.notNull(dto.getBizId(), "智能体ID不能为空");
        dto.setBizType(BizTypeEnum.AGENT);
        checkAgentPermission(Long.parseLong(dto.getBizId()));
        return ReqResult.success(subscriptionPlanAppService.createPlan(dto));
    }

    @PostMapping(value = "/update")
    @Operation(summary = "修改订阅计划")
    public ReqResult<Boolean> updatePlan(@RequestBody PlanDTO dto) {
        Assert.notNull(dto.getId(), "计划ID不能为空");
        dto.setBizId(null);
        dto.setBizType(BizTypeEnum.AGENT);
        PlanDTO planById = subscriptionPlanAppService.getPlanById(dto.getId());
        Assert.isTrue(planById != null && planById.getBizType() == BizTypeEnum.AGENT, "计划不存在");
        checkAgentPermission(Long.parseLong(planById.getBizId()));
        return ReqResult.success(subscriptionPlanAppService.updatePlan(dto));
    }

    @PostMapping(value = "/sort/update")
    @Operation(summary = "修改订阅计划排序")
    public ReqResult<Void> updatePlanSort(@RequestBody List<SortUpdateDTO> updateDTOS) {
        updateDTOS.forEach(updateDTO -> {
            PlanDTO planById = subscriptionPlanAppService.getPlanById(updateDTO.getId());
            Assert.isTrue(planById != null && planById.getBizType() == BizTypeEnum.AGENT, "计划不存在");
            checkAgentPermission(Long.parseLong(planById.getBizId()));
            PlanDTO dto = new PlanDTO();
            dto.setId(updateDTO.getId());
            dto.setSort(updateDTO.getSort());
            subscriptionPlanAppService.updatePlan(dto);
        });
        return ReqResult.success();
    }

    @GetMapping("/list")
    @Operation(summary = "查询订阅计划列表")
    public ReqResult<List<PlanDTO>> listPlans(
            @RequestParam Long agentId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword) {
        PlanQueryRequest query = new PlanQueryRequest();
        query.setBizType(BizTypeEnum.AGENT);
        query.setBizId(agentId.toString());
        query.setStatus(status);
        query.setKeyword(keyword);
        return ReqResult.success(subscriptionPlanAppService.listPlans(query));
    }

    @PostMapping("/{id}/offline")
    @Operation(summary = "下架订阅计划")
    public ReqResult<Boolean> offlinePlan(@PathVariable Long id) {
        PlanDTO planById = subscriptionPlanAppService.getPlanById(id);
        Assert.isTrue(planById != null && planById.getBizType() == BizTypeEnum.AGENT, "计划不存在");
        checkAgentPermission(Long.parseLong(planById.getBizId()));
        return ReqResult.success(subscriptionPlanAppService.offlinePlan(id));
    }

    @PostMapping("/{id}/delete")
    @Operation(summary = "删除订阅计划")
    public ReqResult<Boolean> deletePlan(@PathVariable Long id) {
        PlanDTO planById = subscriptionPlanAppService.getPlanById(id);
        Assert.isTrue(planById != null && planById.getBizType() == BizTypeEnum.AGENT, "计划不存在");
        checkAgentPermission(Long.parseLong(planById.getBizId()));
        return ReqResult.success(subscriptionPlanAppService.deletePlan(id));
    }

    private void checkAgentPermission(Long agentId) {
        com.xspaceagi.agent.core.sdk.dto.ReqResult<List<AgentInfoDto>> listReqResult = iAgentRpcService.queryAgentInfoList(List.of(agentId));
        if (listReqResult.getData() == null || listReqResult.getData().isEmpty()) {
            throw new IllegalArgumentException("错误的智能体ID");
        }
        spacePermissionService.checkSpaceUserPermission(listReqResult.getData().get(0).getSpaceId());
    }
}
