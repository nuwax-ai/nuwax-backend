package com.xspaceagi.sandbox.infra.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xspaceagi.sandbox.spec.enums.SandboxScopeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;

/**
 * 沙盒配置实体类
 */
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Data
@TableName(value = "sandbox_config", autoResultMap = true)
public class SandboxConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField(value = "scope")
    private SandboxScopeEnum scope;

    @TableField(value = "_tenant_id")
    private Long tenantId;

    @TableField(value = "user_id")
    private Long userId;

    @TableField(value = "device_id")
    private String deviceId;

    @TableField(value = "name")
    private String name;

    @TableField(value = "config_key")
    private String configKey;

    @TableField(value = "config_value")
    private String configValue;

    @TableField(value = "description")
    private String description;

    @TableField(value = "is_active")
    private Boolean isActive;

    @TableField(value = "server_info")
    private String serverInfo;

    @TableField(value = "agent_id")
    private Long agentId;

    @TableField(value = "max_agent")
    private Integer maxAgentCount;

    @TableField(value = "bind_info")
    private String bindInfo;

    @TableField(value = "type")
    private String type;

    @TableField(value = "isolation")
    private String isolation;

    @TableField(value = "created")
    private Date created;

    @TableField(value = "modified")
    private Date modified;
}
