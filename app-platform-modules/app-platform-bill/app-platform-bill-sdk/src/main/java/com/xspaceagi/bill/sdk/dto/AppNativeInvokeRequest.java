package com.xspaceagi.bill.sdk.dto;

import com.xspaceagi.pay.sdk.enums.PayChannel;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

@Data
@Schema(description = "App 原生 SDK 调起支付请求")
public class AppNativeInvokeRequest implements Serializable {

    @Schema(description = "Bill 订单 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long orderId;

    @Schema(description = "支付渠道：WxPay / AliPay", requiredMode = Schema.RequiredMode.REQUIRED)
    private PayChannel payChannel;
}
