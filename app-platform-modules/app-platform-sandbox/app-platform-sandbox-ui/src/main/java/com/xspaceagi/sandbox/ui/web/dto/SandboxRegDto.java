package com.xspaceagi.sandbox.ui.web.dto;

import com.xspaceagi.sandbox.infra.dao.vo.SandboxConfigValue;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class SandboxRegDto {

    @Schema(description = "用户名、邮箱或手机号码")
    private String username;

    @Schema(description = "密码")
    private String password;

    @Schema(description = "曾经保存的密钥，首次登录不需要")
    private String savedKey;

    @Schema(description = "设备ID")
    private String deviceId;

    @Schema(description = "终端配置信息")
    private SandboxConfigValue sandboxConfigValue;
}
