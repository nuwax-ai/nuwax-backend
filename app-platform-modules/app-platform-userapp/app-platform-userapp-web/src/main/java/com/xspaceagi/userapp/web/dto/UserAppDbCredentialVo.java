package com.xspaceagi.userapp.web.dto;

import java.io.Serializable;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "数据库账号密码（明文）")
public class UserAppDbCredentialVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "数据库账号")
    private String username;

    @Schema(description = "数据库密码（明文）")
    private String password;
}
