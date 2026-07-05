package com.xspaceagi.pay.sdk.dto;

import com.xspaceagi.pay.sdk.enums.PayChannel;
import com.xspaceagi.pay.sdk.enums.PayClientScene;
import lombok.Data;

/** 对已有 App 支付单调渠道下单，返回原生 SDK 调起参数。 */
@Data
public class AppTransactionRpcCreateRequest {
    private String bizOrderNo;
    private PayChannel payChannel;
    private String clientIp;
    private PayClientScene payClientScene;
}
