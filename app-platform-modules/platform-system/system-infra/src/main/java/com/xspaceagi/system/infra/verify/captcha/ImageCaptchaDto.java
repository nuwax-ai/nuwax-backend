package com.xspaceagi.system.infra.verify.captcha;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

@Schema(description = "图形验证码")
@Data
public class ImageCaptchaDto implements Serializable {

    @Schema(description = "验证码ID，校验时原样传回")
    private String captchaId;

    @Schema(description = "验证码图片，base64 data uri，可直接作为img标签的src使用")
    private String image;
}
