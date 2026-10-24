package com.xspaceagi.userapp.infra.dao.entity;

import java.util.Date;

import org.apache.ibatis.type.EnumTypeHandler;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xspaceagi.system.spec.enums.ProjectTypeEnum;
import com.xspaceagi.userapp.sdk.dto.OAuth2ScopeApplyStatus;

import lombok.Data;

@TableName(value = "oauth2_scope_apply", autoResultMap = true)
@Data
public class OAuth2ScopeApply {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField(value = "_tenant_id")
    private Long tenantId;

    @TableField(value = "project_id")
    private Long projectId;

    @TableField(value = "project_type", typeHandler = EnumTypeHandler.class)
    private ProjectTypeEnum projectType;

    @TableField(value = "apply_user_id")
    private Long applyUserId;

    @TableField(value = "old_scopes")
    private String oldScopes;

    /** 申请的目标 scope（逗号分隔，归一化后；空串=清空回落平台默认） */
    @TableField(value = "scopes")
    private String scopes;

    @TableField(value = "status", typeHandler = EnumTypeHandler.class)
    private OAuth2ScopeApplyStatus status;

    /** 审核人用户ID */
    @TableField(value = "reviewer_id")
    private Long reviewerId;

    @TableField(value = "reject_reason")
    private String rejectReason;

    @TableField(value = "reviewed_at")
    private Date reviewedAt;

    private Date created;

    private Date modified;
}
