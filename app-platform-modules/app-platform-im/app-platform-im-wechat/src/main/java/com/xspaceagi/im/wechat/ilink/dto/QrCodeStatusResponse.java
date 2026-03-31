package com.xspaceagi.im.wechat.ilink.dto;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

@Data
public class QrCodeStatusResponse {
    private String status;
    @JSONField(name = "bot_token")
    private String botToken;
    @JSONField(name = "ilink_bot_id")
    private String ilinkBotId;
    private String baseurl;
    @JSONField(name = "ilink_user_id")
    private String ilinkUserId;
}
