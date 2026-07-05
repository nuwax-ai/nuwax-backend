package com.xspaceagi.pay.infra.gateway.client;

import com.xspaceagi.pay.domain.gateway.PaymentAppGateway;
import com.xspaceagi.pay.infra.gateway.utils.PayGatewayClientUtils;
import com.xspaceagi.pay.sdk.dto.ApiResponse;
import com.xspaceagi.pay.sdk.dto.AppOrderCreateRequest;
import com.xspaceagi.pay.sdk.dto.AppTransactionCreateRequest;
import com.xspaceagi.pay.sdk.dto.OrderAndTransactionCreateResponse;
import com.xspaceagi.pay.sdk.dto.OrderCreateResponse;
import com.xspaceagi.pay.sdk.dto.PaymentStatusQueryRequest;
import com.xspaceagi.pay.sdk.dto.PaymentStatusQueryResponse;
import com.xspaceagi.pay.sdk.enums.PayChannel;
import com.xspaceagi.pay.sdk.enums.PayClientScene;
import com.xspaceagi.pay.sdk.sign.OpenApiPaymentAppSign;
import com.xspaceagi.pay.spec.gateway.PayGatewayOutbound;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
public class PaymentAppGatewayClient implements PaymentAppGateway {

    private static final ParameterizedTypeReference<ApiResponse<OrderCreateResponse>> CREATE_TYPE =
            new ParameterizedTypeReference<ApiResponse<OrderCreateResponse>>() {};

    private static final ParameterizedTypeReference<ApiResponse<OrderAndTransactionCreateResponse>> TX_TYPE =
            new ParameterizedTypeReference<ApiResponse<OrderAndTransactionCreateResponse>>() {};

    private static final ParameterizedTypeReference<ApiResponse<PaymentStatusQueryResponse>> STATUS_TYPE =
            new ParameterizedTypeReference<ApiResponse<PaymentStatusQueryResponse>>() {};

    private final RestTemplate payGatewayRestTemplate;

    public PaymentAppGatewayClient(@Qualifier("payGatewayRestTemplate") RestTemplate payGatewayRestTemplate) {
        this.payGatewayRestTemplate = payGatewayRestTemplate;
    }

    @Override
    public OrderCreateResponse createOrder(
            PayGatewayOutbound outbound, String bizOrderNo, long orderAmount, String subject, String ext) {
        AppOrderCreateRequest request = new AppOrderCreateRequest();
        request.setClientId(outbound.clientId());
        request.setBizOrderNo(bizOrderNo);
        request.setOrderAmount(orderAmount);
        request.setSubject(subject);
        request.setExt(ext);
        byte[] body = OpenApiPaymentAppSign.signCreateOrder(request, outbound.clientSecret());
        String url = outbound.baseUrl() + OpenApiPaymentAppSign.PATH_CREATE_ORDER;
        log.info("[pay-gateway] POST {}", url);
        return PayGatewayClientUtils.postSigned(payGatewayRestTemplate, url, body, CREATE_TYPE, true);
    }

    @Override
    public OrderAndTransactionCreateResponse createTransaction(
            PayGatewayOutbound outbound,
            String gatewayPaymentOrderNo,
            PayChannel payChannel,
            String clientIp,
            PayClientScene payClientScene) {
        AppTransactionCreateRequest request = new AppTransactionCreateRequest();
        request.setClientId(outbound.clientId());
        request.setGatewayPaymentOrderNo(gatewayPaymentOrderNo);
        request.setPayChannel(payChannel);
        request.setClientIp(clientIp);
        request.setPayClientScene(payClientScene);
        byte[] body = OpenApiPaymentAppSign.signCreateTransaction(request, outbound.clientSecret());
        String url = outbound.baseUrl() + OpenApiPaymentAppSign.PATH_CREATE_TRANSACTION;
        log.info("[pay-gateway] POST {} payChannel={}", url, payChannel);
        return PayGatewayClientUtils.postSigned(payGatewayRestTemplate, url, body, TX_TYPE, true);
    }

    @Override
    public PaymentStatusQueryResponse queryOrderStatus(
            PayGatewayOutbound outbound, String gatewayPaymentOrderNo, boolean syncFromChannel) {
        PaymentStatusQueryRequest request = new PaymentStatusQueryRequest();
        request.setClientId(outbound.clientId());
        request.setGatewayPaymentOrderNo(gatewayPaymentOrderNo);
        request.setSyncFromChannel(syncFromChannel);
        byte[] body = OpenApiPaymentAppSign.signQueryStatus(request, outbound.clientSecret());
        String url = outbound.baseUrl() + OpenApiPaymentAppSign.PATH_STATUS;
        log.info("[pay-gateway] POST {} syncFromChannel={}", url, syncFromChannel);
        return PayGatewayClientUtils.postSigned(payGatewayRestTemplate, url, body, STATUS_TYPE, true);
    }
}
