package com.xspaceagi.pay.spec.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayGatewayConnectivityResponse {

    /** 是否成功联通 */
    private boolean reachable;

    /** 不可达时的原因说明 */
    private String message;

    /** 当前租户配置的支付网关基址 */
    private String gatewayBaseUrl;

    /** 网关返回的服务器时间戳（毫秒） */
    private Long gatewayServerTimeMillis;

    /** 本次探测往返耗时（毫秒） */
    private Long latencyMillis;
}
