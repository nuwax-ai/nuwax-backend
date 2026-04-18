package com.xspaceagi.agent.core.adapter.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

@Data
public class ConversationUpdateDto implements Serializable {

    @Schema(description = "会话ID")
    private Long id;

    @Schema(description = "用户第一条消息")
    @NotNull(message = "User first message is required")
    private String firstMessage;

    @Schema(description = "会话主题，可以不传，firstMessage与topic二选一")
    private String topic;
}
