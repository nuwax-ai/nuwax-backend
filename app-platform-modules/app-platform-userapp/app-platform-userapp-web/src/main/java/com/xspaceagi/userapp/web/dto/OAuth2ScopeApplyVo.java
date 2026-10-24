package com.xspaceagi.userapp.web.dto;

import java.util.Date;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * scope 变更申请（审核列表行，含所属应用信息）
 */
@Data
@Schema(description = "OAuth2 scope 变更申请")
public class OAuth2ScopeApplyVo {

    @Schema(description = "申请 ID")
    private Long id;

    @Schema(description = "统一项目 ID（user_project 主键）")
    private Long projectId;

    @Schema(description = "项目类型：ThirdApp / UserApp")
    private String projectType;

    @Schema(description = "应用名称")
    private String projectName;

    @Schema(description = "OAuth2 Client ID")
    private String clientId;

    @Schema(description = "申请人用户 ID")
    private Long applyUserId;

    @Schema(description = "申请时已生效的 scope（空数组=平台默认 profile）")
    private List<String> oldScopes;

    @Schema(description = "申请的目标 scope（空数组=清空回落平台默认）")
    private List<String> scopes;

    @Schema(description = "审核状态：Pending 待审核 / Approved 已通过 / Rejected 已拒绝")
    private String status;

    @Schema(description = "审核人用户 ID")
    private Long reviewerId;

    @Schema(description = "拒绝原因（仅 Rejected）")
    private String rejectReason;

    @Schema(description = "审核时间")
    private Date reviewedAt;

    @Schema(description = "申请时间")
    private Date created;
}
