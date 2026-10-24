package com.xspaceagi.connector.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 连接器动作目录。
 */
@Data
@TableName("connector_action")
public class ConnectorAction {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属 provider service */
    private String providerService;

    /** 动作标识，如 create_issue */
    private String actionKey;

    private String name;

    /** 动作描述（供检索与 LLM 指南） */
    private String description;

    /** action 能力域分组（JSON 数组字符串） */
    private String tags;

    /** 输入参数 Arg 树（JSON 字符串） */
    private String inputArgs;

    /** 输出参数 Arg 树（JSON 字符串） */
    private String outputArgs;

    /** DECLARATIVE/CUSTOM */
    private String execType;

    /** CUSTOM 时的实现类名（懒加载） */
    private String execRef;

    /** method/path/参数映射/响应抽取规则（JSON 字符串） */
    private String httpSpec;

    /** system/user（user=手工保护区） */
    private String managedBy;

    /** 弃用标记：不删、停新绑定、执行带告警 */
    private Integer deprecated;

    /** enabled/disabled */
    private String status;

    private Date modified;

    private Date created;
}
