package com.xspaceagi.im.application;

import com.xspaceagi.im.application.dto.ImChannelConfigDto;

public interface WechatIlinkPushApplicationService {

    void pushText(Long configId, String message);

    void pushText(ImChannelConfigDto dto, String message);
}
