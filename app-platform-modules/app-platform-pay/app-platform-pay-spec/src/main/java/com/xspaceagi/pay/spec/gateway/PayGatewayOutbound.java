package com.xspaceagi.pay.spec.gateway;

/** 调用 nuwax-pay Open API 时的出站上下文 */
public record PayGatewayOutbound(String baseUrl, String clientId, String clientSecret) {}
