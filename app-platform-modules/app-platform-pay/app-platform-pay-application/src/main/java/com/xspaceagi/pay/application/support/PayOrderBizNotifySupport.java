package com.xspaceagi.pay.application.support;

import com.xspaceagi.pay.domain.model.PayOrderModel;
import com.xspaceagi.pay.sdk.enums.PaymentStatus;
import com.xspaceagi.pay.spec.enums.PayBizNotifyStatus;

/** 支付单业务通知（Bill）状态判断，与轮询/补偿任务共用。 */
public final class PayOrderBizNotifySupport {

    private PayOrderBizNotifySupport() {}

    /**
     * 网关已终态时是否应通知 Bill。
     * TIMEOUT：阶段 1 可能已按关单通知，仅补偿发现 PAID 时补发 SUCCESS。
     */
    public static boolean shouldNotifyBizOnTerminal(PayOrderModel row, PaymentStatus gatewayStatus) {
        PayBizNotifyStatus biz = row.getBizNotifyStatus();
        if (biz == PayBizNotifyStatus.NOTIFIED) {
            return false;
        }
        if (biz == null || biz == PayBizNotifyStatus.POLLING) {
            return true;
        }
        if (biz == PayBizNotifyStatus.TIMEOUT) {
            return gatewayStatus == PaymentStatus.PAID;
        }
        return false;
    }

    /** CAS 时期望的当前 {@link PayBizNotifyStatus}；null 视为 POLLING。 */
    public static PayBizNotifyStatus expectedCasCurrent(PayOrderModel row) {
        PayBizNotifyStatus biz = row.getBizNotifyStatus();
        return biz == null ? PayBizNotifyStatus.POLLING : biz;
    }
}
