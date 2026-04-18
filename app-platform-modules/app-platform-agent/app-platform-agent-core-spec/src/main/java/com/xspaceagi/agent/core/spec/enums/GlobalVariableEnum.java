package com.xspaceagi.agent.core.spec.enums;

/**
 * 全局固定变量
 */
public enum GlobalVariableEnum {

    AGENT_ID,       // 智能体ID
    AGENT_USER_MSG, // 用户消息
    CONVERSATION_ID,   // 会话ID
    REQUEST_ID,         //请求唯一标识
    SYS_USER_ID,
    USER_UID,
    USER_NAME,
    USER_LANG,
    CHAT_CONTEXT;

    public static boolean isSystemVariable(String name) {
        for (GlobalVariableEnum value : GlobalVariableEnum.values()) {
            if (value.name().equals(name)) {
                return true;
            }
        }
        return false;
    }
}
