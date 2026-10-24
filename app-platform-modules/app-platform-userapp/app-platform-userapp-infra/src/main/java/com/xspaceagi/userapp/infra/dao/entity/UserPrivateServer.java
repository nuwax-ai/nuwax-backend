package com.xspaceagi.userapp.infra.dao.entity;

import java.util.Date;

import org.apache.ibatis.type.EnumTypeHandler;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xspaceagi.userapp.sdk.dto.UserPrivateServerStatus;

import lombok.Data;

@TableName(value = "user_private_server", autoResultMap = true)
@Data
public class UserPrivateServer {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField(value = "_tenant_id")
    private Long tenantId;

    @TableField(value = "owner_user_id")
    private Long ownerUserId;

    private String name;

    private String scheme;

    private String host;

    @TableField(value = "server_info")
    private String serverInfo;

    @TableField(value = "api_key")
    private String apiKey;

    @TableField(value = "status", typeHandler = EnumTypeHandler.class)
    private UserPrivateServerStatus status;

    @TableField(value = "version")
    private String version;

    @TableField(value = "last_health_check")
    private Date lastHealthCheck;

    private Date created;

    private Date modified;
}
