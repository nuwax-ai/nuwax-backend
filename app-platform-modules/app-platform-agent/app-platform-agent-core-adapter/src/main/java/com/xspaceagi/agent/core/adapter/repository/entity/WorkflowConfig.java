package com.xspaceagi.agent.core.adapter.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@TableName("workflow_config")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class WorkflowConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("_tenant_id")
    private Long tenantId;

    @TableField("space_id")
    private Long spaceId;

    @TableField("creator_id")
    private Long creatorId;

    private String name;

    private String description;

    private String icon;

    private String type;

    private Long agentId;

    @TableField("start_node_id")
    private Long startNodeId;

    @TableField("end_node_id")
    private Long endNodeId;

    @TableField("publish_status")
    private Published.PublishStatus publishStatus;

    private String ext;

    private Date modified;

    private Date created;
}
