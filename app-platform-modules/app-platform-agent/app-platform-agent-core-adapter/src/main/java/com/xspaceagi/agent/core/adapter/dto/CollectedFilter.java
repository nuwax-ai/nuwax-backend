package com.xspaceagi.agent.core.adapter.dto;

/**
 * 会话列表收藏过滤选项
 */
public enum CollectedFilter {

    ALL, // 全部（默认）
    ONLY; //ONLY 仅含收藏

    public static CollectedFilter parse(String value) {
        if (value == null || value.isBlank()) {
            return ALL;
        }
        try {
            return CollectedFilter.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ALL;
        }
    }
}
