package com.xspaceagi.userapp.web.dto;

import java.io.Serializable;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 保存数据库账号密码请求（密码加密存储）
 */
@Data
@Schema(description = "保存数据库账号密码请求")
public class UserAppDbCredentialReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "应用ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;

    @Schema(description = "环境：dev 开发环境；prod 发布环境", requiredMode = Schema.RequiredMode.REQUIRED)
    private String env;

    @Schema(description = "数据库账号", requiredMode = Schema.RequiredMode.REQUIRED)
    private String username;

    @Schema(description = "数据库密码（明文入参，服务端加密存储）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String password;
}
