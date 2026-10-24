package com.xspaceagi.im.application;

import com.xspaceagi.im.application.dto.ImNotifyOauthCallbackResult;

public interface ImNotifyOauthCallbackApplicationService {

    /**
     * OAuth 回调：换码后进入 awaiting_consent，展示确认页（含平台用户标识），须用户明示同意后再回跳 App。
     */
    ImNotifyOauthCallbackResult handleCallback(String channel, String code, String state, String error);

    /**
     * 确认页同意：校验 consentToken → authorized，再 302 到域名回跳页携带 confirmToken。
     */
    ImNotifyOauthCallbackResult handleConsent(String bindId, String consentToken);
}
