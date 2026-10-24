package com.xspaceagi.system.infra.verify;

import com.xspaceagi.system.infra.verify.captcha.CaptchaConfig;
import com.xspaceagi.system.infra.verify.captcha.ImageCaptchaDto;
import com.xspaceagi.system.infra.verify.email.SmtpConfig;
import com.xspaceagi.system.infra.verify.sms.SmsConfig;
import com.xspaceagi.system.spec.enums.CodeTypeEnum;

public interface VerifyCodeSendAndCheckService {

    String sendPhoneCode(SmsConfig smsConfig, CodeTypeEnum type, String phoneNumber);

    void checkPhoneCode(CodeTypeEnum type, String phoneNumber, String code);

    String sendEmailCode(SmtpConfig stmpConfig, CodeTypeEnum type, String email);

    void checkEmailCode(CodeTypeEnum type, String email, String code);

    void checkCaptchaVerifyParam(CaptchaConfig captchaConfig, String param);

    ImageCaptchaDto createImageCaptcha(CaptchaConfig captchaConfig, String clientIp);

    void checkImageCaptcha(CaptchaConfig captchaConfig, String captchaId, String captchaCode);
}
