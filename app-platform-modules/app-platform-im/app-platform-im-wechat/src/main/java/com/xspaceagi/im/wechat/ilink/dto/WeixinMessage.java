package com.xspaceagi.im.wechat.ilink.dto;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

import java.util.List;

@Data
public class WeixinMessage {
    private Long seq;
    @JSONField(name = "message_id")
    private Long messageId;
    @JSONField(name = "from_user_id")
    private String fromUserId;
    @JSONField(name = "to_user_id")
    private String toUserId;
    @JSONField(name = "client_id")
    private String clientId;
    @JSONField(name = "create_time_ms")
    private Long createTimeMs;
    @JSONField(name = "update_time_ms")
    private Long updateTimeMs;
    @JSONField(name = "delete_time_ms")
    private Long deleteTimeMs;
    @JSONField(name = "session_id")
    private String sessionId;
    @JSONField(name = "group_id")
    private String groupId;
    @JSONField(name = "message_type")
    private Integer messageType;
    @JSONField(name = "message_state")
    private Integer messageState;
    @JSONField(name = "item_list")
    private List<MessageItem> itemList;
    @JSONField(name = "context_token")
    private String contextToken;
}
