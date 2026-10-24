package com.xspaceagi.agent.core.adapter.dto;

/**
 * 会话列表项目会话过滤选项（项目会话 = 常规项目/全栈应用开发会话）
 */
public enum ProjectConversationFilter {

    ALL, // 全部（默认）
    EXCLUDE, // 排除项目会话
    ONLY; // 仅项目会话

    public static ProjectConversationFilter parse(String value) {
        if (value == null || value.isBlank()) {
            return ALL;
        }
        try {
            return ProjectConversationFilter.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ALL;
        }
    }
}
