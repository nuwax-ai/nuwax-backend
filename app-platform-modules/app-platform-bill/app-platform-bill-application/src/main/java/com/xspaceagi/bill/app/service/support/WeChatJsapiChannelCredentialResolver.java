package com.xspaceagi.bill.app.service.support;

import com.xspaceagi.bill.sdk.dto.WeChatJsapiInvokeRequest;
import com.xspaceagi.pay.sdk.dto.MiniPayTransactionRpcCreateRequest;
import com.xspaceagi.pay.sdk.enums.PayChannel;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.service.TenantConfigApplicationService;
import com.xspaceagi.system.infra.rpc.WeChatOaService;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 将公众号 OAuth 凭证解析为 minipay（JSAPI）网关下单参数。 */
@Component
public class WeChatJsapiChannelCredentialResolver {

    @Resource
    private TenantConfigApplicationService tenantConfigApplicationService;

    @Resource
    private WeChatOaService weChatOaService;

    public void applyChannelParams(long tenantId, WeChatJsapiInvokeRequest request, MiniPayTransactionRpcCreateRequest tx) {
        if (!StringUtils.hasText(request.getWxOAuthCode())) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "wxOAuthCode 不能为空");
        }
        TenantConfigDto config = tenantConfigApplicationService.getTenantConfig(tenantId);
        if (config == null || !StringUtils.hasText(config.getOaAppId())) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemWeChatOaNotConfigured);
        }
        String openId = weChatOaService.getOpenIdByOAuthCode(request.getWxOAuthCode().trim(), tenantId);
        tx.setPayChannel(PayChannel.WxPay);
        tx.setSubAppid(config.getOaAppId().trim());
        tx.setOpenId(openId);
    }
}
