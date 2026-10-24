package com.xspaceagi.system.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

@Data
public class CodeSendDto implements Serializable {

    @Schema(description = "验证码类型", requiredMode = Schema.RequiredMode.REQUIRED)
    private Type type;

    @Schema(description = "完整的手机号码，例如 8613888888888")
    private String phone;

    @Schema(description = "邮箱地址，与手机号码二选一")
    private String email;

    @Schema(description = "验证码参数")
    private String captchaVerifyParam;

    @Schema(description = "图形验证码ID，开启图形验证码时必传")
    private String captchaId;

    @Schema(description = "图形验证码内容，开启图形验证码时必传")
    private String captchaCode;

    public enum Type {
        LOGIN_OR_REGISTER,
        RESET_PASSWORD,
        BIND_EMAIL
    }
}
