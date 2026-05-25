package com.xspaceagi.pay.sdk.enums;

public enum MerchantOnboardingStatus {
    DRAFT("草稿"),
    PENDING_REVIEW("待审核"),
    UNDER_REVIEW("审核中"),
    APPROVED("已通过"),
    REJECTED("已拒绝");

    private final String name;

    MerchantOnboardingStatus(String name) {
        this.name = name;
    }

    /** 管理台/对外展示用中文名 */
    public String getName() {
        return name;
    }
}
