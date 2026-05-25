package com.xspaceagi.pricing.spec.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum TargetTypeEnum {

    AGENT("AGENT", "智能体"),
    SKILL("SKILL", "技能"),
    PLUGIN("PLUGIN", "插件"),
    WORKFLOW("WORKFLOW", "工作流"),
    MCP("MCP", "MCP"),
    MODEL("MODEL", "模型");

    private final String code;
    private final String desc;

    public static TargetTypeEnum fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (TargetTypeEnum e : values()) {
            if (e.getCode().equals(code)) {
                return e;
            }
        }
        return null;
    }
}
