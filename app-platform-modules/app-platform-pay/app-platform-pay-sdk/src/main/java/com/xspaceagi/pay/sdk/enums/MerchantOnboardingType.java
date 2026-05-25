package com.xspaceagi.pay.sdk.enums;

public enum MerchantOnboardingType {
    PLATFORM("平台进件"),
    TENANT("租户进件"),
    USER("用户进件");

    private final String name;

    MerchantOnboardingType(String name) {
        this.name = name;
    }

    /** 管理台/对外展示用中文名 */
    public String getName() {
        return name;
    }
}
