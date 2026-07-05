package com.xspaceagi.agent.core.adapter.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xspaceagi.agent.core.spec.handler.JsonTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@TableName(value = "agent_component_config", autoResultMap = true)
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AgentComponentConfig {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id; // 组件配置ID

    @TableField(value = "_tenant_id")
    private Long tenantId; // 商户ID

    private String name; // 节点名称

    private String icon;

    private String description;

    private Long agentId; // AgentID

    private Type type; // 组件类型，可选值：Plugin, Workflow, Trigger, Knowledge, Variable, Database

    private Long targetId; // 关联的组件ID

    @TableField(value = "bind_config", typeHandler = JsonTypeHandler.class)
    private Object bindConfig; // 组件详细配置

    private Integer exceptionOut; // 异常是否抛出，中断主要流程

    private String fallbackMsg; // 异常时兜底内容

    private Date modified; // 更新时间

    private Date created; // 创建时间

    public enum Type {
        Plugin, Workflow, Trigger, Knowledge, Variable, Database, Model, Agent, Table, Mcp, Page, Event, Skill, SubAgent, Hook
    }
}
