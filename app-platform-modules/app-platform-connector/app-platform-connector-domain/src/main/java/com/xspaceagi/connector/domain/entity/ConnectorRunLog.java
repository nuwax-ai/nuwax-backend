package com.xspaceagi.connector.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 执行审计（脱敏摘要；按条数上限写入时滚动删除）。
 */
@Data
@TableName("connector_run_log")
public class ConnectorRunLog {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField(value = "_tenant_id")
    private Long tenantId;

    private Long spaceId;

    private Long userId;

    private Long connectionId;

    private Long actionId;

    /** 来源 agent */
    private Long agentId;

    /** success/failed/policy_denied */
    private String status;

    /** 脱敏后的输入输出摘要（JSON 字符串） */
    private String redactedSummary;

    private String errorCode;

    private Long durationMs;

    private Date created;
}
