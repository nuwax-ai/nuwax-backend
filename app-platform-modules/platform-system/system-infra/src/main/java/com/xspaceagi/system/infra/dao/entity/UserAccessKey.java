package com.xspaceagi.system.infra.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xspaceagi.system.sdk.service.dto.UserAccessKeyDto;
import com.xspaceagi.system.spec.common.JsonTypeHandler;
import lombok.Data;

import java.util.Date;

@Data
@TableName(value = "user_access_key", autoResultMap = true)
public class UserAccessKey {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField(value = "_tenant_id")
    private Long tenantId;

    @TableField(value = "user_id")
    private Long userId;

    private String name;

    private UserAccessKeyDto.AKTargetType targetType;

    private String targetId;

    private String accessKey;

    @TableField(value = "config", typeHandler = JsonTypeHandler.class)
    private UserAccessKeyDto.UserAccessKeyConfig config;

    private Integer status;

    private Date expire;

    private Date created;
}
