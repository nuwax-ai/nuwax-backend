package com.xspaceagi.pay.sdk.dto;

import com.xspaceagi.pay.sdk.enums.PayChannel;
import lombok.Data;

/** 对已有 h5 支付单调渠道下单，返回前端调起参数。 */
@Data
public class H5TransactionRpcCreateRequest {
    private String bizOrderNo;
    private PayChannel payChannel;
    private String clientIp;
    private String frontNotifyUrl;
}
