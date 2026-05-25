package com.xspaceagi.pay.spec.enums;

public enum PayBizNotifyStatus {
    /** 轮询中 */
    POLLING,
    /** 已通知（含支付成功、失败/关闭等终态，已通过进程内事件投递） */
    NOTIFIED,
    /** 轮询耗尽仍未拿到明确终态（业务宜主动查单） */
    TIMEOUT
}
