package com.xspaceagi.bill.app.service.support;

import com.xspaceagi.bill.sdk.dto.MiniPayInvokeRequest;
import com.xspaceagi.pay.sdk.dto.MiniPayTransactionRpcCreateRequest;
import com.xspaceagi.pay.sdk.enums.PayChannel;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.service.TenantConfigApplicationService;
import com.xspaceagi.system.infra.rpc.WeChatMpService;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 将小程序支付渠道凭证解析为网关下单参数 */
@Component
public class MiniPayChannelCredentialResolver {

    @Resource
    private TenantConfigApplicationService tenantConfigApplicationService;

    @Resource
    private WeChatMpService weChatMpService;

    public void applyChannelParams(long tenantId, MiniPayInvokeRequest request, MiniPayTransactionRpcCreateRequest tx) {
        PayChannel channel = request.getPayChannel();
        if (channel == PayChannel.WxPay) {
            applyWxPay(tenantId, request, tx);
            return;
        }
        if (channel == PayChannel.AliPay) {
            applyAliPay(request, tx);
            return;
        }
        throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "小程序支付仅支持 WxPay 与 AliPay");
    }

    private void applyWxPay(long tenantId, MiniPayInvokeRequest request, MiniPayTransactionRpcCreateRequest tx) {
        if (!StringUtils.hasText(request.getWxLoginCode())) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "wxLoginCode 不能为空");
        }
        TenantConfigDto config = tenantConfigApplicationService.getTenantConfig(tenantId);
        if (config == null || !StringUtils.hasText(config.getMpAppId())) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemWeChatMpNotConfigured);
        }
        String openId = weChatMpService.getOpenIdByLoginCode(request.getWxLoginCode().trim());
        tx.setSubAppid(config.getMpAppId().trim());
        tx.setOpenId(openId);
    }

    private void applyAliPay(MiniPayInvokeRequest request, MiniPayTransactionRpcCreateRequest tx) {
        if (!StringUtils.hasText(request.getAlipayAuthCode())) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "alipayAuthCode 不能为空");
        }
        // 支付宝 oauth 换 buyer_id 依赖租户密钥配置，待接入 AliPayMpService 后实现
        throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemAlipayMiniPayNotConfigured);
    }
}
