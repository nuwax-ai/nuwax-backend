package com.xspaceagi.im.wechat.ilink.dto;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SendTypingBody {
    @JSONField(name = "ilink_user_id")
    private String ilinkUserId;
    @JSONField(name = "typing_ticket")
    private String typingTicket;
    private Integer status;
    @JSONField(name = "base_info")
    private BaseInfo baseInfo;
}
