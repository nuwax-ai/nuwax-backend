package com.xspaceagi.im.application;

public interface WechatIlinkPushApplicationService {

    void pushText(Long imChannelConfigId, String text);
}
