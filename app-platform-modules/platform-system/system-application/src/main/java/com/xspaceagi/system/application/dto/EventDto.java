package com.xspaceagi.system.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class EventDto<E> {

    public static final String EVENT_TYPE_NEW_NOTIFY_MESSAGE = "new_notify_message";
    public static final String EVENT_TYPE_REFRESH_CHAT_MESSAGE = "refresh_chat_message";
    public static final String EVENT_TYPE_CHAT_FINISHED = "chat_finished";

    public static final String EVENT_TYPE_CHAT_START = "chat_start";

    @Schema(description = "事件类型，由具体的业务定义，和前端同步；当前已定义的类型：new_notify_message - 有新的通知消息；refresh_chat_message - 会话消息列表需要刷新")
    private String type;

    @Schema(description = "事件内容，由具体的业务定义，和前端同步")
    private E event;
}
