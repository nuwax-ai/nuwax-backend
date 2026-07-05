package com.xspaceagi.pay.sdk.enums;

/** 调起场景 */
public enum PayClientScene {
    MINI_PROGRAM("微信小程序"), //渠道 pay_mode 均为 minipay
    WECHAT_JSAPI("微信JSAPI"), //渠道 pay_mode 均为 minipay
    H5_WEB("系统浏览器H5"),
    NATIVE_APP("App原生SDK"); // gateway_back：微信 common / 支付宝 scan

    private final String name;

    PayClientScene(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
