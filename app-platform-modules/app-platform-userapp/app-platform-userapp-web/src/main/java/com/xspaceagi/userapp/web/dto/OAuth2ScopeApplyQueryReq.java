package com.xspaceagi.userapp.web.dto;

import com.xspaceagi.userapp.sdk.dto.OAuth2ScopeApplyStatus;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "scope 变更申请分页查询条件")
public class OAuth2ScopeApplyQueryReq {

    @Schema(description = "审核状态过滤（Pending / Approved / Rejected；不传查全部）")
    private OAuth2ScopeApplyStatus status;

    @Schema(description = "统一项目 ID 过滤（不传查全部）")
    private Long projectId;
}
