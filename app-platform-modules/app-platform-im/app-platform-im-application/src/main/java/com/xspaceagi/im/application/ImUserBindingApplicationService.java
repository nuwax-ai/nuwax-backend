package com.xspaceagi.im.application;

import com.xspaceagi.im.application.dto.ImBindStartResult;
import com.xspaceagi.im.application.dto.ImBindStatusResult;
import com.xspaceagi.im.application.dto.ImUserBindingDto;
import com.xspaceagi.im.application.dto.ImUserBindingsView;
import com.xspaceagi.im.infra.dao.enitity.ImUserBinding;

public interface ImUserBindingApplicationService {

    /**
     * @param appReturnUrl 可选；空则用租户 siteUrl+/api/v1/im-notify/bind/return（域名 Universal Link）
     */
    ImBindStartResult startBind(String channel, Long channelConfigId, String appReturnUrl);

    ImBindStatusResult bindStatus(String bindId);

    ImUserBindingDto getBinding(Long userId, String channel);

    ImUserBindingsView listBindings(Long userId, String channel);

    void unbind(Long userId, String channel);

    ImUserBinding requireBinding(Long tenantId, Long userId, String channel);

    void upsertBinding(ImUserBinding binding);

    /**
     * OAuth 换码成功但尚未征得 IM 用户明示同意：写入 awaiting_consent + consentToken（不落库、不发 confirmToken）。
     * @return consentToken
     */
    String stashOauthPendingConsent(String bindId, String imUserId, String displayName);

    /**
     * IM 用户在授权页点击「确认绑定到该平台账号」后：校验 consentToken，再进入 authorized + confirmToken。
     *
     * @return true 表示本次新进入 authorized；false 表示此前已 authorized（幂等，调用方勿再下发 confirmToken 重定向）
     */
    boolean acceptOauthConsent(String bindId, String consentToken);

    /**
     * App 凭深链中的 confirmToken 确认落库（须为发起绑定的同一用户）。
     */
    void confirmOauthBind(String bindId, String confirmToken);

    String getBindSessionRaw(String bindId);

    /** 组装授权成功后的 App 回跳地址（含 confirmToken） */
    String buildAppReturnRedirect(String bindId);
}
