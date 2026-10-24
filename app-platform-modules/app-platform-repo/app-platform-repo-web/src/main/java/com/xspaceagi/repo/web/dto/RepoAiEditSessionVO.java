package com.xspaceagi.repo.web.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档 AI 编辑会话返回对象：会话 ID + 内嵌聊天应用地址。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoAiEditSessionVO {

    /** AI 编辑会话 ID（agent conversation.id）。 */
    private Long conversationId;

    /** AI 编辑聊天应用内嵌地址（站内根相对路径，前端拼 origin）。 */
    private String url;
}
