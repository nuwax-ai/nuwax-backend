package com.xspaceagi.pay.sdk.service;

import com.xspaceagi.pay.sdk.dto.DeveloperAccountInfoResponse;

public interface IDeveloperAccountInfoRpcService {

    DeveloperAccountInfoResponse getDeveloperAccountInfo(Long tenantId, Long userId);
}
