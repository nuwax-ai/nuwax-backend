package com.xspaceagi.credit.web.controller;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.bill.sdk.dto.CreateOrderRequest;
import com.xspaceagi.bill.sdk.dto.OrderDTO;
import com.xspaceagi.bill.sdk.rpc.IBillRpcService;
import com.xspaceagi.bill.spec.enums.BizTypeEnum;
import com.xspaceagi.bill.spec.enums.TargetTypeEnum;
import com.xspaceagi.credit.app.service.CreditPackageService;
import com.xspaceagi.credit.app.service.CreditService;
import com.xspaceagi.credit.sdk.dto.*;
import com.xspaceagi.credit.spec.enums.CreditTypeEnum;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.utils.I18nUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/credit")
@Tag(name = "用户积分相关接口")
public class UserCreditController {

    @Resource
    private CreditService creditService;

    @Resource
    private CreditPackageService creditPackageService;

    @Resource
    private IBillRpcService iBillRpcService;

    @GetMapping("/summary")
    @Operation(summary = "查询当前登录用户总积分")
    public ReqResult<UserCreditSummary> getUserCreditSummary() {
        TenantConfigDto tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
        int dailyGiftCredit = 0;
        if (tenantConfigDto.getEnableDailyGiftCredit() != null && tenantConfigDto.getEnableDailyGiftCredit() == 1) {
            dailyGiftCredit = tenantConfigDto.getDailyGiftCreditAmount() == null ? 0 : tenantConfigDto.getDailyGiftCreditAmount();
        }
        //每日积分发放，bizNo一样，只会发放一次
        if (dailyGiftCredit > 0) {
            String bizNo = "DGC" + RequestContext.get().getUserId() + "-" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            CreditAddRequest request = new CreditAddRequest();
            request.setCreditType(CreditTypeEnum.ACTIVITY);
            request.setUserId(RequestContext.get().getUserId());
            request.setAmount(BigDecimal.valueOf(dailyGiftCredit));
            request.setTenantId(RequestContext.get().getTenantId());
            request.setBizNo(bizNo);
            request.setExpireTime(Date.from(LocalDate.now().atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant()));
            request.setRemark(I18nUtil.systemMessage("Backend.Credit.DailyGift.Remark"));
            creditService.addCredit(request);
        }
        UserCreditSummary userCreditSummary = creditService.getUserCreditSummary(RequestContext.get().getUserId());
        userCreditSummary.setDailyGiftCredit(BigDecimal.valueOf(dailyGiftCredit));
        return ReqResult.success(userCreditSummary);
    }

    @GetMapping("/batches")
    @Operation(summary = "查询用户积分批次列表")
    public ReqResult<List<UserCreditBatchDTO>> getUserCreditBatches(
            @RequestParam(required = false) CreditTypeEnum creditType,
            @RequestParam(required = false) Boolean expired) {
        return ReqResult.success(creditService.getUserCreditBatches(RequestContext.get().getUserId(), creditType, expired));
    }

    @GetMapping("/flows")
    @Operation(summary = "查询用户积分流水明细")
    public ReqResult<List<CreditFlowDTO>> getCreditFlows(
            @RequestParam(required = false) CreditTypeEnum creditType,
            @RequestParam(required = false) Long lastId,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        return ReqResult.success(creditService.getCreditFlows(RequestContext.get().getUserId(), creditType, lastId == null ? Long.MAX_VALUE : lastId, pageSize));
    }

    @GetMapping("/package/list")
    @Operation(summary = "查询可增购的积分套餐列表")
    public ReqResult<List<CreditPackageDTO>> getPackageList() {
        return ReqResult.success(creditPackageService.getPackageList(1));
    }

    @PostMapping("/order/create")
    @Operation(summary = "创建积分增购订单")
    public ReqResult<OrderDTO> createOrder(@RequestParam Long packageId, HttpServletRequest httpServletRequest) {
        CreditPackageDTO packageById = creditPackageService.getPackageById(packageId);
        if (packageById == null) {
            return ReqResult.error(I18nUtil.systemMessage("Backend.Credit.Package.Error.NotFound"));
        }
        CreateOrderRequest request = new CreateOrderRequest();
        request.setTenantId(RequestContext.get().getTenantId());
        request.setUserId(RequestContext.get().getUserId());
        request.setDescription(I18nUtil.systemMessage("Backend.Credit.Order.Description", packageById.getPackageName()));
        request.setBizType(BizTypeEnum.CREDIT_PURCHASE);
        CreateOrderRequest.CreateOrderItem item = new CreateOrderRequest.CreateOrderItem();
        item.setTargetName(packageById.getPackageName());
        item.setTargetType(TargetTypeEnum.CREDIT_PACKAGE.getCode());
        item.setTargetId(packageById.getId());
        item.setPrice(packageById.getPrice());
        item.setCount(1);
        item.setSnapshot(JSON.parseObject(JSON.toJSONString(packageById)));
        request.setItems(List.of(item));
        OrderDTO order = iBillRpcService.createOrder(request);
        return ReqResult.success(order);
    }


    private Date addMonths(Date from, int months) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(from);
        cal.add(Calendar.MONTH, months);
        return cal.getTime();
    }
}
