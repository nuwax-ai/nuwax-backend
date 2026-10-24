package com.xspaceagi.userapp.web.dto;

import com.xspaceagi.agent.core.adapter.dto.ConversationDto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 项目会话：会话信息 + 会话所属用户信息。
 * ConversationDto 在 adapter 层被多处共用，用户名仅项目会话列表需要，所以用子类。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "项目会话条目：会话信息 + 会话所属用户信息")
public class UserProjectConversationVo extends ConversationDto {

    @Schema(description = "会话所属用户名")
    private String userName;
}
