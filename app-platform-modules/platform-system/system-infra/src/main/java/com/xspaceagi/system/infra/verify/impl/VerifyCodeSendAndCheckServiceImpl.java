package com.xspaceagi.system.infra.verify.impl;

import com.aliyun.captcha20230305.models.VerifyCaptchaRequest;
import com.aliyun.captcha20230305.models.VerifyCaptchaResponse;
import com.aliyun.tea.TeaException;
import com.xspaceagi.system.infra.verify.VerifyCodeSendAndCheckService;
import com.xspaceagi.system.infra.verify.captcha.CaptchaConfig;
import com.xspaceagi.system.infra.verify.email.SmtpConfig;
import com.xspaceagi.system.infra.verify.sms.SmsConfig;
import com.xspaceagi.system.infra.verify.sms.SmsRpcService;
import com.xspaceagi.system.spec.enums.CodeTypeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.mail.MailSender;
import com.xspaceagi.system.spec.utils.I18nUtil;
import com.xspaceagi.system.spec.utils.RedisUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class VerifyCodeSendAndCheckServiceImpl implements VerifyCodeSendAndCheckService {

    @Resource
    private SmsRpcService smsRpcService;

    @Resource
    private RedisUtil redisUtil;

    @Override
    public String sendPhoneCode(SmsConfig smsConfig, CodeTypeEnum type, String phoneNumber) {
        //验证手机号码正确性
        if (phoneNumber == null || !phoneNumber.startsWith("1") || phoneNumber.length() != 11) {
            throw new BizException(I18nUtil.systemMessage("Backend.Verify.Phone.Invalid"));
        }
        //检查缓存中存在手机号码
        Object c = redisUtil.get("code-phone-" + type.name() + ":" + phoneNumber);
        if (c != null) {
            return c.toString();
        }
        //生成随机的六位数
        String code = String.valueOf((int) ((Math.random() * 9 + 1) * 100000));
        //缓存code 5分钟
        redisUtil.set("code-phone-" + type.name() + ":" + phoneNumber, code, 60 * 5);
        //发送短信验证码
        if (StringUtils.isBlank(smsConfig.getSmsAccessKeyId())){
            return code;
        }
        try {
            smsRpcService.sendSms(smsConfig, phoneNumber, "{\"code\":\"" + code + "\"}");
        } catch (Exception e) {
            redisUtil.expire("code-phone-" + type.name() + ":" + phoneNumber, 0);
            throw e;
        }

        return code;
    }

    @Override
    public void checkPhoneCode(CodeTypeEnum type, String phoneNumber, String code) {
        Object redisCode = redisUtil.get("code-phone-" + type.name() + ":" + phoneNumber);
        if (redisCode == null || !redisCode.equals(code)) {
            //失败3次清除缓存
            Long count = redisUtil.increment("code-phone-count-" + phoneNumber, 1);
            if (count >= 3) {
                redisUtil.expire("code-phone-" + phoneNumber, 0);
                redisUtil.expire("code-phone-count-" + type.name() + ":" + phoneNumber, 0);
            }
            throw new BizException(I18nUtil.systemMessage("Backend.Verify.Code.Invalid"));
        }
        redisUtil.expire("code-phone-" + type.name() + ":" + phoneNumber, 0);   //验证成功后删除缓存
    }

    @Override
    public String sendEmailCode(SmtpConfig stmpConfig, CodeTypeEnum type, String email) {
        //验证邮箱格式，邮箱用户名支持 .
        if (!email.matches("^[a-zA-Z0-9._-]+@[a-zA-Z0-9_-]+(\\.[a-zA-Z0-9_-]+)+$")) {
            throw new BizException(I18nUtil.systemMessage("Backend.User.Email.InvalidFormat"));
        }
        //检查缓存中存在手机号码
        Object c = redisUtil.get("code-email-" + type.name() + ":" + email);
        if (c != null) {
            return c.toString();
        }
        //生成随机的六位数
        String code = String.valueOf((int) ((Math.random() * 9 + 1) * 100000));
        //缓存code 5分钟
        redisUtil.set("code-email-" + type.name() + ":" + email, code, 60 * 5);
        if (StringUtils.isBlank(stmpConfig.getUsername())){
            return code;
        }
        MailSender.sendEmail(stmpConfig.getHost(), stmpConfig.getPort().toString(), stmpConfig.getUsername(), stmpConfig.getPassword(),
                email,
                I18nUtil.systemMessage("Backend.Mail.Verification.Subject", stmpConfig.getSiteName()),
                I18nUtil.systemMessage("Backend.Mail.Verification.Body", stmpConfig.getSiteName(), code));
        return code;
    }

    @Override
    public void checkEmailCode(CodeTypeEnum type, String email, String code) {
        Object redisCode = redisUtil.get("code-email-" + type.name() + ":" + email);
        if (redisCode == null || !redisCode.equals(code)) {
            //失败3次清除缓存
            Long count = redisUtil.increment("code-email-count-" + email, 1);
            if (count >= 3) {
                redisUtil.expire("code-email-" + email, 0);
                redisUtil.expire("code-email-count-" + type.name() + ":" + email, 0);
            }
            throw new BizException(I18nUtil.systemMessage("Backend.Verify.Code.Invalid"));
        }
        redisUtil.expire("code-email-" + type.name() + ":" + email, 0);
    }

    @Override
    public void checkCaptchaVerifyParam(CaptchaConfig captchaConfig, String param) {
        if (captchaConfig.getOpenCaptcha() == null || captchaConfig.getOpenCaptcha() == 0) {
            return;
        }
        try {
            com.aliyun.teaopenapi.models.Config config = new com.aliyun.teaopenapi.models.Config()
                    .setAccessKeyId(captchaConfig.getCaptchaAccessKeyId()).setAccessKeySecret(captchaConfig.getCaptchaAccessKeySecret());
            config.endpoint = "captcha.cn-shanghai.aliyuncs.com";
            com.aliyun.captcha20230305.Client client = new com.aliyun.captcha20230305.Client(config);
            VerifyCaptchaRequest verifyCaptchaRequest = new VerifyCaptchaRequest()
                    .setCaptchaVerifyParam(param);
            VerifyCaptchaResponse verifyCaptchaResponse = client.verifyCaptchaWithOptions(verifyCaptchaRequest, new com.aliyun.teautil.models.RuntimeOptions());
            if (!verifyCaptchaResponse.getBody().getResult().getVerifyResult()) {
                throw new BizException(I18nUtil.systemMessage("Backend.Verify.Captcha.ParamError"));
            }
        } catch (TeaException error) {
            log.warn("验证码校验失败", error);
            throw new BizException(I18nUtil.systemMessage("Backend.Verify.Captcha.Failed"));
        } catch (Exception _error) {
            if (_error instanceof BizException) {
                throw (BizException) _error;
            }
            log.error("验证码校验失败", _error);
        }
    }
}
