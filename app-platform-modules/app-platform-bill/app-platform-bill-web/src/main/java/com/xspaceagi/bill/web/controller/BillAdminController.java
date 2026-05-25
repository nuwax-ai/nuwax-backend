package com.xspaceagi.bill.web.controller;

import com.xspaceagi.bill.app.service.BillOrderAppService;
import com.xspaceagi.bill.app.service.BillRevenueAppService;
import com.xspaceagi.bill.app.service.BillWithdrawAppService;
import com.xspaceagi.bill.app.service.ResourceStatAppService;
import com.xspaceagi.bill.sdk.dto.*;
import com.xspaceagi.bill.spec.enums.BizTypeEnum;
import com.xspaceagi.bill.spec.enums.ResourceStatTypeEnum;
import com.xspaceagi.bill.spec.enums.RevenueStatusEnum;
import com.xspaceagi.bill.spec.enums.WithdrawStatusEnum;
import com.xspaceagi.system.sdk.server.IUserRpcService;
import com.xspaceagi.system.sdk.service.dto.UserDetailDto;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;

import static com.xspaceagi.system.spec.enums.ResourceEnum.*;

@Slf4j
@RestController
@RequestMapping("/api/system/bill")
@Tag(name = "系统账单管理")
public class BillAdminController {

    @Resource
    private BillWithdrawAppService billWithdrawAppService;

    @Resource
    private BillRevenueAppService billRevenueAppService;

    @Resource
    private BillOrderAppService billOrderAppService;

    @Resource
    private ResourceStatAppService resourceStatAppService;

    @Resource
    private IUserRpcService iUserRpcService;

    @RequireResource(PAY_EARNINGS_QUERY)
    @GetMapping("/withdraw/list")
    @Operation(summary = "提现管理列表")
    public ReqResult<WithdrawApplicationPageDTO> getWithdrawList(
            @RequestParam(required = false) WithdrawStatusEnum status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        WithdrawQueryRequest query = new WithdrawQueryRequest();
        query.setStatus(status);
        query.setKeyword(keyword);
        query.setPageNum(pageNum);
        query.setPageSize(pageSize);
        if (StringUtils.isNotBlank(keyword)) {
            UserDetailDto userDetailDto = iUserRpcService.queryUserDetailByName(keyword);
            if (userDetailDto != null) {
                query.setUserId(userDetailDto.getId());
            }
        }
        return ReqResult.success(billWithdrawAppService.queryWithdrawApplications(query));
    }

    @RequireResource(WITHDRAW_AUDIT)
    @PostMapping("/withdraw/process")
    @Operation(summary = "提现处理（通过/驳回/打款完成）")
    public ReqResult<Boolean> processWithdraw(@RequestBody WithdrawProcessRequest request) {
        return ReqResult.success(billWithdrawAppService.processWithdraw(request));
    }

    @RequireResource(PAY_EARNINGS_QUERY)
    @GetMapping("/revenue/stats")
    @Operation(summary = "收益统计（按月过滤，按用户排行）")
    public ReqResult<RevenueStatsDTO> getRevenueStats(
            @RequestParam(required = false) String monthStart,
            @RequestParam(required = false) String monthEnd,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) RevenueStatusEnum status,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        if (monthStart == null || monthEnd == null) {
            java.time.LocalDate now = java.time.LocalDate.now();
            monthStart = now.withDayOfMonth(1).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
            monthEnd = now.withDayOfMonth(now.lengthOfMonth()).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        }
        return ReqResult.success(billRevenueAppService.getAdminRevenueStats(monthStart, monthEnd, userId, status, pageNum, pageSize));
    }

    @RequireResource(PAY_EARNINGS_QUERY)
    @GetMapping("/revenue/detail")
    @Operation(summary = "查看用户收益明细")
    public ReqResult<RevenueDetailPageDTO> getUserRevenueDetail(
            @RequestParam Long userId,
            @RequestParam(required = false) String dt,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        return ReqResult.success(billRevenueAppService.getUserRevenueDetails(userId, dt, pageNum, pageSize));
    }

    @RequireResource(PAY_EARNINGS_QUERY)
    @GetMapping("/resource-stat/detail")
    @Operation(summary = "资源统计明细（按条件查询）")
    public ReqResult<ResourceStatPageDTO> getResourceStatDetail(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) ResourceStatTypeEnum type,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) Long targetId,
            @RequestParam(required = false) String dtStart,
            @RequestParam(required = false) String dtEnd,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        return ReqResult.success(resourceStatAppService.queryResourceStats(
                null, userId, type != null ? type.getCode() : null, targetType, targetId, dtStart, dtEnd, pageNum, pageSize));
    }

    @RequireResource(PAY_EARNINGS_QUERY)
    @GetMapping("/resource-stat/summary")
    @Operation(summary = "资源统计汇总")
    public ReqResult<ResourceStatSummaryDTO> getResourceStatSummary(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String dtStart,
            @RequestParam(required = false) String dtEnd) {
        return ReqResult.success(resourceStatAppService.getResourceStatSummary(
                null, userId, dtStart, dtEnd));
    }

    @RequireResource(PAY_EARNINGS_QUERY)
    @GetMapping("/order/query")
    @Operation(summary = "订单查询")
    public ReqResult<OrderPageDTO> queryOrders(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long orderId,
            @RequestParam(required = false) String orderStatus,
            @RequestParam(required = false) String payStatus,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(required = false) BizTypeEnum bizType,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        OrderQueryRequest query = new OrderQueryRequest();
        query.setUserId(userId);
        query.setOrderId(orderId);
        query.setOrderStatus(orderStatus);
        query.setPayStatus(payStatus);
        query.setStartTime(startTime);
        query.setEndTime(endTime);
        query.setBizType(bizType);
        query.setKeyword(keyword);
        query.setPageNum(pageNum);
        query.setPageSize(pageSize);
        return ReqResult.success(billOrderAppService.queryOrders(query));
    }

    @RequireResource(PAY_EARNINGS_QUERY)
    @GetMapping("/withdraw/config")
    @Operation(summary = "查询提现配置")
    public ReqResult<WithdrawConfigDTO> getWithdrawConfig() {
        return ReqResult.success(billWithdrawAppService.getWithdrawConfig(RequestContext.get().getTenantId()));
    }

    @RequireResource(PAY_EARNINGS_MODIFY)
    @PostMapping("/withdraw/config/save")
    @Operation(summary = "保存提现配置")
    public ReqResult<Boolean> saveWithdrawConfig(@RequestBody SaveWithdrawConfigRequest request) {
        request.setTenantId(RequestContext.get().getTenantId());
        return ReqResult.success(billWithdrawAppService.saveWithdrawConfig(request));
    }
}
