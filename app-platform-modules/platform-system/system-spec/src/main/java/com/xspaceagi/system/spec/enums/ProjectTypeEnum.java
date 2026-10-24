package com.xspaceagi.system.spec.enums;

/**
 * 项目类型
 */
public enum ProjectTypeEnum {

    /** 智能体 */
    Agent,
    /** 插件 */
    Plugin,
    /** 技能 */
    Skill,
    /** 网页应用 */
    PageApp,
    /** 全栈应用 */
    UserApp,
    /** 常规项目 */
    NormalProject,
    /** 连接器 **/
    Connector,
    /** 三方应用 **/
    ThirdApp,

    Workflow,
    Knowledge,
    Table,
    Model,
    Mcp;

    /**
     * 是否有底层引用表。
     * 无引用表的类型（常规项目、三方应用）以 user_project 主键本身作为项目 id，refId 恒为空；
     * 其余类型（UserApp/PageApp 等）refId 指向各自源表主键。
     */
    public boolean hasRefTable() {
        return this != NormalProject && this != ThirdApp;
    }

    /** 按名称解析（大小写不敏感），未匹配返回 null */
    public static ProjectTypeEnum fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (ProjectTypeEnum type : values()) {
            if (type.name().equalsIgnoreCase(value)) {
                return type;
            }
        }
        return null;
    }
}
