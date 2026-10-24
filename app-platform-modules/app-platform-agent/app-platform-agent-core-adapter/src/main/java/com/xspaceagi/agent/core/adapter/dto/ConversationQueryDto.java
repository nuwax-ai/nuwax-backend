package com.xspaceagi.agent.core.adapter.dto;

import com.xspaceagi.agent.core.adapter.repository.entity.Conversation;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

@Data
public class ConversationQueryDto implements Serializable {

    @Schema(description = "智能体ID，可选")
    private Long agentId;

    @Schema(description = "会话ID，可选")
    private Long lastId;

    @Schema(description = "返回会话数量，可选")
    private Integer limit;

    @Schema(description = "定时会话状态，可选")
    private Conversation.ConversationTaskStatus taskStatus;

    @Schema(description = "会话主题，可选")
    private String topic;

    @Schema(description = "归档过滤：all=全部（默认）；exclude=排除归档；only=仅归档。值大小写不敏感")
    private String archivedFilter;

    @Schema(description = "收藏过滤：all=全部（默认）；only=仅含收藏。值大小写不敏感")
    private String collectedFilter;

    @Schema(description = "项目会话过滤（常规项目/全栈应用开发会话）：all=全部（默认）；exclude=排除项目会话；only=仅项目会话。值大小写不敏感")
    private String projectFilter;
}
