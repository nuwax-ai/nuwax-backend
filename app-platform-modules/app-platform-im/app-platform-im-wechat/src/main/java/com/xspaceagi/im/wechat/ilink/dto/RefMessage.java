package com.xspaceagi.im.wechat.ilink.dto;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

@Data
public class RefMessage {
    @JSONField(name = "message_item")
    private MessageItem messageItem;
    private String title;
}
