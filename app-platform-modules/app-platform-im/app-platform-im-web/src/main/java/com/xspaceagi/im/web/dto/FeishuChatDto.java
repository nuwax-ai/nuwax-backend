package com.xspaceagi.im.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 飞书群组信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeishuChatDto {

    @Schema(description = "群组 ID，用于发送消息")
    private String chatId;

    @Schema(description = "群名称")
    private String name;

    @Schema(description = "群头像 URL")
    private String avatar;

    @Schema(description = "群描述")
    private String description;

    @Schema(description = "群主 ID")
    private String ownerId;
}
