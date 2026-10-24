package com.xspaceagi.userapp.web.dto;

import java.util.List;

import com.xspaceagi.agent.core.adapter.dto.ConversationDto;
import com.xspaceagi.userapp.sdk.dto.UserProjectDto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 项目列表 tab 条目：项目信息 + 当前用户的开发会话。
 * UserProjectDto 在 sdk 层无法引用 agent-core 的 ConversationDto，故用 web 层子类承载会话字段。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "项目列表 tab 条目：项目信息 + 当前用户的开发会话")
public class UserProjectTabVo extends UserProjectDto {

    private static final long serialVersionUID = 1L;

    @Schema(description = "当前用户在该项目的开发会话（会话 id 倒序，最新在前）")
    private List<ConversationDto> conversations;
}
