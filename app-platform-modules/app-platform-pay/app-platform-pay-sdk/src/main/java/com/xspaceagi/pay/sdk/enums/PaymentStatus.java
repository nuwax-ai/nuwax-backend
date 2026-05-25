package com.xspaceagi.pay.sdk.enums;

public enum PaymentStatus {
    INIT("待发起"),
    PENDING("待支付"),
    PAID("成功"),
    FAILED("失败"),
    CLOSED("关单");

    private final String name;

    PaymentStatus(String name) {
        this.name = name;
    }

    /** 管理台/对外展示用中文名 */
    public String getName() {
        return name;
    }

    public static PaymentStatus fromPersistedString(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("payment status blank");
        }
        String t = raw.trim();
        return valueOf(t);
    }
}
