package com.xspaceagi.bill.sdk.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

@Data
@Schema(description = "微信内 H5 JSAPI 调起支付请求")
public class WeChatJsapiInvokeRequest implements Serializable {

    @Schema(description = "Bill 订单 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long orderId;

    @Schema(description = "公众号 OAuth 回调 code（snsapi_base，一次性）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String wxOAuthCode;
}
