package com.xspaceagi.pay.sdk.enums;

public enum PayMode {
    scan("主扫"),
    minipay("小程序支付"),
    h5("H5支付"),
    app("App原生支付");

    private final String name;

    PayMode(String name) {
        this.name = name;
    }

    /** 管理台/对外展示用中文名 */
    public String getName() {
        return name;
    }
}
