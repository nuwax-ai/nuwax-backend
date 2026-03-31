package com.xspaceagi.im.wechat.ilink.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * POST ilink/bot/sendmessage 的请求体：msg + base_info
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SendMessageEnvelope {
    private WeixinMessage msg;
    @com.alibaba.fastjson2.annotation.JSONField(name = "base_info")
    private BaseInfo baseInfo;
}
