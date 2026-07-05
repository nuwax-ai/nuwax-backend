package com.xspaceagi.bill.spec.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum RevenueTargetTypeEnum {
    AGENT("Agent", "智能体"),
    SKILL("Skill", "技能"),
    MODEL("Model", "模型"),
    PLUGIN("Plugin", "插件"),
    MCP("Mcp", "MCP"),
    WORKFLOW("Workflow", "工作流"),
    PROJECT("Project", "项目");

    private final String code;
    private final String desc;

    public static RevenueTargetTypeEnum fromCode(String code) {
        if (code == null) return null;
        for (RevenueTargetTypeEnum e : values()) {
            if (e.getCode().equals(code)) return e;
        }
        return null;
    }
}
