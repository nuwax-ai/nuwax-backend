package com.xspaceagi.system.infra.verify.captcha;

import lombok.Data;

@Data
public class CaptchaConfig {

    private Integer openCaptcha;
    private String captchaAccessKeyId;
    private String captchaAccessKeySecret;
    private Integer openImageCaptcha;
}
