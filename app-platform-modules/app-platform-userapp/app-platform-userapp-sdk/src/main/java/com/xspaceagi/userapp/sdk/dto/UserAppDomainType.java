package com.xspaceagi.userapp.sdk.dto;

/**
 * 域名绑定类型：Dev 平台分配的开发域名（创建时写入）；Prod 平台分配的生产域名（发布时写入）；
 * Custom 用户自行绑定。Dev/Prod 均不可修改、不可解绑。
 */
public enum UserAppDomainType {
    Dev,
    Prod,
    Custom
}
