package com.xspaceagi.pay.spec;

import java.time.Duration;

/** 支付调度相关时间常量（建单宽限期、补偿扫描等共用）。 */
public final class PayOrderScheduleConstants {

    private PayOrderScheduleConstants() {}

    /** 本地 {@code gatewaySyncStatus=PENDING} 且无网关单号超过该时间视为可重试/可补偿关单。 */
    public static final Duration STALE_GATEWAY_SYNC_PENDING = Duration.ofMinutes(1);
}
