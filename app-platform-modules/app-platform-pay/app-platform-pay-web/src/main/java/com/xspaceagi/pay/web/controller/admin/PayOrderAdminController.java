package com.xspaceagi.pay.web.controller.admin;

import com.xspaceagi.pay.application.service.PayOrderApplicationService;
import com.xspaceagi.pay.spec.dto.PageResult;
import com.xspaceagi.pay.spec.dto.PayOrderItemResponse;
import com.xspaceagi.pay.spec.dto.PayOrderPageQueryRequest;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.dto.ReqResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.xspaceagi.system.spec.enums.ResourceEnum.PAY_EARNINGS_QUERY;

@Slf4j
@RestController
@RequestMapping("/api/system/pay/order")
@Tag(name = "支付-订单")
public class PayOrderAdminController {

    @Resource
    private PayOrderApplicationService payOrderAppService;

    @RequireResource(PAY_EARNINGS_QUERY)
    @PostMapping("/page")
    @Operation(summary = "分页查询支付订单", description = "金额单位为元")
    public ReqResult<PageResult<PayOrderItemResponse>> page(@Valid @RequestBody PayOrderPageQueryRequest request) {
        return ReqResult.success(payOrderAppService.pagePayOrders(request));
    }
}
