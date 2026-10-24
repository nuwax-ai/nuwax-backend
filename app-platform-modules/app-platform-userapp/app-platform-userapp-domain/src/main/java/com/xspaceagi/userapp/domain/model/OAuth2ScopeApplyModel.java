package com.xspaceagi.userapp.domain.model;

import java.util.Date;

import com.xspaceagi.system.spec.enums.ProjectTypeEnum;
import com.xspaceagi.userapp.sdk.dto.OAuth2ScopeApplyStatus;

import lombok.Data;

/**
 * OAuth2 scope 范围变更申请
 */
@Data
public class OAuth2ScopeApplyModel {

    private Long id;

    private Long tenantId;

    private Long projectId;

    private ProjectTypeEnum projectType;

    private Long applyUserId;

    /** 申请时项目已生效的 scope（逗号分隔） */
    private String oldScopes;

    /** 申请的目标 scope（逗号分隔） */
    private String scopes;

    private OAuth2ScopeApplyStatus status;

    /** 审核人用户ID */
    private Long reviewerId;

    private String rejectReason;

    private Date reviewedAt;

    private Date created;

    private Date modified;
}
