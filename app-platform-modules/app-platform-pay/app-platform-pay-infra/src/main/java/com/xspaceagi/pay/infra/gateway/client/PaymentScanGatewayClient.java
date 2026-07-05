package com.xspaceagi.pay.infra.gateway.client;

import com.xspaceagi.pay.domain.gateway.PaymentScanGateway;
import com.xspaceagi.pay.infra.gateway.utils.PayGatewayClientUtils;
import com.xspaceagi.pay.sdk.dto.ApiResponse;
import com.xspaceagi.pay.sdk.dto.CashierSessionCreateRequest;
import com.xspaceagi.pay.sdk.dto.CashierSessionCreateResponse;
import com.xspaceagi.pay.sdk.dto.OrderCreateResponse;
import com.xspaceagi.pay.sdk.dto.PaymentStatusQueryRequest;
import com.xspaceagi.pay.sdk.dto.ScanOrderCreateRequest;
import com.xspaceagi.pay.sdk.dto.PaymentStatusQueryResponse;
import com.xspaceagi.pay.sdk.sign.OpenApiCashierSign;
import com.xspaceagi.pay.sdk.sign.OpenApiPaymentScanSign;
import com.xspaceagi.pay.spec.gateway.PayGatewayOutbound;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
public class PaymentScanGatewayClient implements PaymentScanGateway {

    private static final ParameterizedTypeReference<ApiResponse<OrderCreateResponse>> CREATE_TYPE =
            new ParameterizedTypeReference<ApiResponse<OrderCreateResponse>>() {};

    private static final ParameterizedTypeReference<ApiResponse<PaymentStatusQueryResponse>> STATUS_TYPE =
            new ParameterizedTypeReference<ApiResponse<PaymentStatusQueryResponse>>() {};

    private static final ParameterizedTypeReference<ApiResponse<CashierSessionCreateResponse>> CASHIER_SESSION_TYPE =
            new ParameterizedTypeReference<ApiResponse<CashierSessionCreateResponse>>() {};

    private final RestTemplate payGatewayRestTemplate;

    public PaymentScanGatewayClient(@Qualifier("payGatewayRestTemplate") RestTemplate payGatewayRestTemplate) {
        this.payGatewayRestTemplate = payGatewayRestTemplate;
    }

    @Override
    public OrderCreateResponse createOrder(
            PayGatewayOutbound outbound, String bizOrderNo, long orderAmount, String subject, String ext) {
        ScanOrderCreateRequest request = new ScanOrderCreateRequest();
        request.setClientId(outbound.clientId());
        request.setBizOrderNo(bizOrderNo);
        request.setOrderAmount(orderAmount);
        request.setSubject(subject);
        request.setExt(ext);
        byte[] body = OpenApiPaymentScanSign.signCreateOrder(request, outbound.clientSecret());
        String url = outbound.baseUrl() + OpenApiPaymentScanSign.PATH_CREATE_ORDER;
        log.info("[pay-gateway] POST {}", url);
        return PayGatewayClientUtils.postSigned(payGatewayRestTemplate, url, body, CREATE_TYPE, true);
    }

    @Override
    public PaymentStatusQueryResponse queryOrderStatus(
            PayGatewayOutbound outbound, String gatewayPaymentOrderNo, boolean syncFromChannel) {
        PaymentStatusQueryRequest request = new PaymentStatusQueryRequest();
        request.setClientId(outbound.clientId());
        request.setGatewayPaymentOrderNo(gatewayPaymentOrderNo);
        request.setSyncFromChannel(syncFromChannel);
        byte[] body = OpenApiPaymentScanSign.signQueryStatus(request, outbound.clientSecret());
        String url = outbound.baseUrl() + OpenApiPaymentScanSign.PATH_STATUS;
        log.info("[pay-gateway] POST {} syncFromChannel={}", url, syncFromChannel);
        return PayGatewayClientUtils.postSigned(payGatewayRestTemplate, url, body, STATUS_TYPE, true);
    }

    @Override
    public CashierSessionCreateResponse createCashierSession(
            PayGatewayOutbound outbound,
            String gatewayPaymentOrderNo,
            long orderAmount,
            String subject,
            String bizRedirectUrl) {
        CashierSessionCreateRequest request = new CashierSessionCreateRequest();
        request.setClientId(outbound.clientId());
        request.setGatewayPaymentOrderNo(gatewayPaymentOrderNo);
        request.setOrderAmount(orderAmount);
        request.setSubject(subject);
        if (bizRedirectUrl != null && !bizRedirectUrl.isBlank()) {
            request.setBizRedirectUrl(bizRedirectUrl.trim());
        }
        byte[] body = OpenApiCashierSign.signCreateSession(request, outbound.clientSecret());
        String url = outbound.baseUrl() + OpenApiCashierSign.PATH_SESSION;
        log.info("[pay-gateway] POST {}", url);
        return PayGatewayClientUtils.postSigned(payGatewayRestTemplate, url, body, CASHIER_SESSION_TYPE, true);
    }
}
