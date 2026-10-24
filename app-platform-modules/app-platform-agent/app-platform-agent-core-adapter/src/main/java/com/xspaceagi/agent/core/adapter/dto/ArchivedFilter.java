package com.xspaceagi.agent.core.adapter.dto;

/**
 * 会话列表归档过滤选项
 */
public enum ArchivedFilter {

    ALL, // 全部（默认）
    EXCLUDE, //EXCLUDE 排除归档
    ONLY; //ONLY 仅归档

    public static ArchivedFilter parse(String value) {
        if (value == null || value.isBlank()) {
            return ALL;
        }
        try {
            return ArchivedFilter.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ALL;
        }
    }
}
