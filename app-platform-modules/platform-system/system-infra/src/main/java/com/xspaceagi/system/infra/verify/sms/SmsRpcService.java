package com.xspaceagi.system.infra.verify.sms;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.dysmsapi20170525.models.SendSmsResponse;
import com.aliyun.teaopenapi.models.Config;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SmsRpcService {

    private static Client createClient(SmsConfig smsConfig) throws Exception {
        //暂时写死，后续替换成租户自己的配置
        Config config = new Config()
                // 配置 AccessKey ID，请确保代码运行环境配置了环境变量 ALIBABA_CLOUD_ACCESS_KEY_ID。
                .setAccessKeyId(smsConfig.getSmsAccessKeyId())
                // 配置 AccessKey Secret，请确保代码运行环境配置了环境变量 ALIBABA_CLOUD_ACCESS_KEY_SECRET。
                .setAccessKeySecret(smsConfig.getSmsAccessKeySecret());

        // 配置 Endpoint
        config.endpoint = "dysmsapi.aliyuncs.com";

        return new Client(config);
    }

    public void sendSms(SmsConfig smsConfig, String phoneNumber, String templateParam) {
        // 构造请求对象，请替换请求参数值
        SendSmsRequest sendSmsRequest = new SendSmsRequest()
                .setPhoneNumbers(phoneNumber)
                .setSignName(smsConfig.getSmsSignName())
                .setTemplateCode(smsConfig.getSmsTemplateCode())
                .setTemplateParam(templateParam); // TemplateParam为序列化后的JSON字符串

        // 获取响应对象
        SendSmsResponse sendSmsResponse;
        try {
            sendSmsResponse = createClient(smsConfig).sendSms(sendSmsRequest);
        } catch (Exception e) {
            if (StringUtils.isBlank(smsConfig.getSmsAccessKeyId())) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemSmsServiceNotConfigured);
            }
            throw new RuntimeException(e);
        }
        if (!"OK".equals(sendSmsResponse.getBody().code)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                    sendSmsResponse.getBody().message);
        }
    }
}
