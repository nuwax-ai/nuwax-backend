package com.xspaceagi.userapp.infra.dao.entity;

import java.util.Date;

import org.apache.ibatis.type.EnumTypeHandler;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xspaceagi.userapp.sdk.dto.UserAppDomainType;

import lombok.Data;

@TableName(value = "userapp_domain", autoResultMap = true)
@Data
public class UserAppDomain {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField(value = "_tenant_id")
    private Long tenantId;

    @TableField(value = "app_id")
    private Long appId;

    private String domain;

    @TableField(value = "domain_type", typeHandler = EnumTypeHandler.class)
    private UserAppDomainType domainType;

    private Date created;

    private Date modified;
}
