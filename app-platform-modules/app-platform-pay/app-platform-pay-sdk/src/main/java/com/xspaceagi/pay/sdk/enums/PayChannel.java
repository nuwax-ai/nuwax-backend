package com.xspaceagi.pay.sdk.enums;

public enum PayChannel {
    WxPay("微信支付"),
    AliPay("支付宝"),
    UnionPay("银联支付");

    private final String name;

    PayChannel(String name) {
        this.name = name;
    }

    /** 管理台/对外展示用中文名 */
    public String getName() {
        return name;
    }

    /** API / 管理台入参：枚举名，大小写不敏感 */
    public static PayChannel parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("payChannel is blank");
        }
        String s = raw.trim();
        for (PayChannel pc : values()) {
            if (pc.name().equalsIgnoreCase(s)) {
                return pc;
            }
        }
        throw new IllegalArgumentException("Unknown pay channel: " + raw);
    }
}
