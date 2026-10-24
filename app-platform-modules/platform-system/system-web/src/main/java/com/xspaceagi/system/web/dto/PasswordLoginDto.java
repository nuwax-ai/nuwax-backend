package com.xspaceagi.system.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

@Schema(description = "密码登录")
@Data
public class PasswordLoginDto implements Serializable {

    @Schema(description = "完整的手机号码，例如 8613888888888", requiredMode = Schema.RequiredMode.REQUIRED)
    private String phone;

    @Schema(description = "完整的邮箱地址，与手机号码二选一，例如 xxx@xx.com")
    private String phoneOrEmail;

    @NotNull
    @Schema(description = "密码", requiredMode = Schema.RequiredMode.REQUIRED)
    private String password;

    @Schema(description = "验证码参数")
    private String captchaVerifyParam;

    @Schema(description = "图形验证码ID，开启图形验证码时必传")
    private String captchaId;

    @Schema(description = "图形验证码内容，开启图形验证码时必传")
    private String captchaCode;
}
