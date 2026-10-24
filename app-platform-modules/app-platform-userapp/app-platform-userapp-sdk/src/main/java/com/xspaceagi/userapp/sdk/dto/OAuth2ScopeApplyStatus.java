package com.xspaceagi.userapp.sdk.dto;

/**
 * OAuth2 scope 范围变更申请状态
 */
public enum OAuth2ScopeApplyStatus {
    /** 待审核 */
    Pending,
    /** 已通过 */
    Approved,
    /** 已拒绝 */
    Rejected
}
