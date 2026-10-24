package com.xspaceagi.system.infra.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xspaceagi.system.spec.common.JsonTypeHandlerWithoutType;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 用户个人 key-value 配置（一行一 key，仅本人可读写）
 */
@Data
@TableName(value = "user_config", autoResultMap = true)
public class UserConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField(value = "_tenant_id")
    private Long tenantId;

    @TableField(value = "user_id")
    private Long userId;

    @TableField(value = "config_key")
    private String configKey;

    /**
     * 任意 JSON 值（标量/对象/数组），不带 @class 类型信息
     */
    @TableField(value = "config_value", typeHandler = JsonTypeHandlerWithoutType.class)
    private Object configValue;

    private String description;

    private Date created;

    private Date modified;
}
