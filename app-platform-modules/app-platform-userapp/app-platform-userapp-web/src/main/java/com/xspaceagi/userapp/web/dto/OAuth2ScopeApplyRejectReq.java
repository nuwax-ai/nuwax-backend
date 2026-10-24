package com.xspaceagi.userapp.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * scope 变更申请审核拒绝请求
 */
@Data
@Schema(description = "scope 变更申请审核拒绝请求")
public class OAuth2ScopeApplyRejectReq {

    @Schema(description = "拒绝原因（回传给申请人，便于调整后重新提交）")
    private String reason;
}
