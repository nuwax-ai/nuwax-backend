package com.xspaceagi.pay.infra.gateway.client;

import com.xspaceagi.pay.domain.gateway.PaymentH5Gateway;
import com.xspaceagi.pay.infra.gateway.utils.PayGatewayClientUtils;
import com.xspaceagi.pay.sdk.dto.ApiResponse;
import com.xspaceagi.pay.sdk.dto.H5OrderCreateRequest;
import com.xspaceagi.pay.sdk.dto.H5TransactionCreateRequest;
import com.xspaceagi.pay.sdk.dto.OrderAndTransactionCreateResponse;
import com.xspaceagi.pay.sdk.dto.OrderCreateResponse;
import com.xspaceagi.pay.sdk.dto.PaymentStatusQueryRequest;
import com.xspaceagi.pay.sdk.dto.PaymentStatusQueryResponse;
import com.xspaceagi.pay.sdk.enums.PayChannel;
import com.xspaceagi.pay.sdk.sign.OpenApiPaymentH5Sign;
import com.xspaceagi.pay.spec.gateway.PayGatewayOutbound;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
public class PaymentH5GatewayClient implements PaymentH5Gateway {

    private static final ParameterizedTypeReference<ApiResponse<OrderCreateResponse>> CREATE_TYPE =
            new ParameterizedTypeReference<ApiResponse<OrderCreateResponse>>() {};

    private static final ParameterizedTypeReference<ApiResponse<OrderAndTransactionCreateResponse>> TX_TYPE =
            new ParameterizedTypeReference<ApiResponse<OrderAndTransactionCreateResponse>>() {};

    private static final ParameterizedTypeReference<ApiResponse<PaymentStatusQueryResponse>> STATUS_TYPE =
            new ParameterizedTypeReference<ApiResponse<PaymentStatusQueryResponse>>() {};

    private final RestTemplate payGatewayRestTemplate;

    public PaymentH5GatewayClient(@Qualifier("payGatewayRestTemplate") RestTemplate payGatewayRestTemplate) {
        this.payGatewayRestTemplate = payGatewayRestTemplate;
    }

    @Override
    public OrderCreateResponse createOrder(
            PayGatewayOutbound outbound, String bizOrderNo, long orderAmount, String subject, String ext) {
        H5OrderCreateRequest request = new H5OrderCreateRequest();
        request.setClientId(outbound.clientId());
        request.setBizOrderNo(bizOrderNo);
        request.setOrderAmount(orderAmount);
        request.setSubject(subject);
        request.setExt(ext);
        byte[] body = OpenApiPaymentH5Sign.signCreateOrder(request, outbound.clientSecret());
        String url = outbound.baseUrl() + OpenApiPaymentH5Sign.PATH_CREATE_ORDER;
        log.info("[pay-gateway] POST {}", url);
        return PayGatewayClientUtils.postSigned(payGatewayRestTemplate, url, body, CREATE_TYPE, true);
    }

    @Override
    public OrderAndTransactionCreateResponse createTransaction(
            PayGatewayOutbound outbound,
            String gatewayPaymentOrderNo,
            PayChannel payChannel,
            String clientIp,
            String frontNotifyUrl) {
        H5TransactionCreateRequest request = new H5TransactionCreateRequest();
        request.setClientId(outbound.clientId());
        request.setGatewayPaymentOrderNo(gatewayPaymentOrderNo);
        request.setPayChannel(payChannel);
        request.setClientIp(clientIp);
        request.setFrontNotifyUrl(frontNotifyUrl);
        byte[] body = OpenApiPaymentH5Sign.signCreateTransaction(request, outbound.clientSecret());
        String url = outbound.baseUrl() + OpenApiPaymentH5Sign.PATH_CREATE_TRANSACTION;
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
        byte[] body = OpenApiPaymentH5Sign.signQueryStatus(request, outbound.clientSecret());
        String url = outbound.baseUrl() + OpenApiPaymentH5Sign.PATH_STATUS;
        log.info("[pay-gateway] POST {} syncFromChannel={}", url, syncFromChannel);
        return PayGatewayClientUtils.postSigned(payGatewayRestTemplate, url, body, STATUS_TYPE, true);
    }
}
