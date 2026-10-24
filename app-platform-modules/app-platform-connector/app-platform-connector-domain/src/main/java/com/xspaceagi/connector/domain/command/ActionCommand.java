package com.xspaceagi.connector.domain.command;

import lombok.Data;

import java.util.List;

/**
 * 创建/更新 action 命令。
 */
@Data
public class ActionCommand {

    private String providerService;

    private String actionKey;

    private String name;

    private String description;

    private List<String> tags;

    /** 输入参数 Arg 树，JSON */
    private Object inputArgs;

    /** 输出参数 Arg 树，JSON */
    private Object outputArgs;

    /** DECLARATIVE/CUSTOM/PLUGIN/WORKFLOW */
    private String execType;

    /** 绑定型时：插件/工作流 id（CUSTOM 时对应执行器 ref） */
    private String execRef;

    /** method/path/参数映射/响应抽取，JSON */
    private Object httpSpec;

    /**
     * 是否系统级操作（导入管线/官方层）。
     * false 时禁止 CUSTOM 执行器（设计文档 §4.8 硬约束 1）。
     */
    private boolean systemLevel;
}
