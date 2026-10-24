package com.xspaceagi.system.sdk.service;

public interface ISensitiveWordRpcService {

       String checkAndReturnNewText(Long tenantId, String text);

       boolean enabledSensitiveFilter();
}
