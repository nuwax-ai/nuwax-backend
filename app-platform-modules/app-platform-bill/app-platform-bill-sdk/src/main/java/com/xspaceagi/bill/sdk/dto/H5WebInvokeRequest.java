package com.xspaceagi.bill.sdk.dto;

import com.xspaceagi.pay.sdk.enums.PayChannel;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

@Data
@Schema(description = "系统浏览器 H5 调起支付请求（h5）")
public class H5WebInvokeRequest implements Serializable {

    @Schema(description = "Bill 订单 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long orderId;

    @Schema(description = "支付渠道：WxPay / AliPay", requiredMode = Schema.RequiredMode.REQUIRED)
    private PayChannel payChannel;

    @Schema(description = "前端结算页地址（后端将封装为渠道可回调的中转地址）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String frontNotifyUrl;
}
