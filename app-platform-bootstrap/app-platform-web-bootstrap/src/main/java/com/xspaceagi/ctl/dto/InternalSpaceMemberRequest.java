package com.xspaceagi.ctl.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 内部接口：空间成员增删的请求体（添加成员用 role，移除成员忽略 role）。
 */
@Data
@Schema(description = "空间成员增删请求")
public class InternalSpaceMemberRequest {

    @Schema(description = "空间ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "123")
    private Long spaceId;

    @Schema(description = "用户ID，必须是该租户下的有效用户", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "10086")
    private Long userId;

    @Schema(description = "空间角色：Admin / User（不传视为 User）。不接受 Owner——一个空间只有一个 Owner（创建者）；"
            + "若该用户已是成员，本字段被忽略，角色保持原样", example = "User")
    private String role;
}
