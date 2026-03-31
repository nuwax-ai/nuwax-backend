package com.xspaceagi.im.wechat.ilink.dto;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

@Data
public class GetConfigResp {
    private Integer ret;
    private String errmsg;
    @JSONField(name = "typing_ticket")
    private String typingTicket;
}
