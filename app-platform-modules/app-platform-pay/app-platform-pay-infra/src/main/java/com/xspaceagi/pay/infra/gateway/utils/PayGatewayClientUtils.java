package com.xspaceagi.pay.infra.gateway.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xspaceagi.pay.sdk.OpenApiCodes;
import com.xspaceagi.pay.sdk.dto.ApiResponse;
import com.xspaceagi.pay.spec.exception.PayGatewayClientException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

public final class PayGatewayClientUtils {

    private static final Logger log = LoggerFactory.getLogger(PayGatewayClientUtils.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private PayGatewayClientUtils() {}

    /**
     * @param requireNonNullData true 时在业务成功但 data 为空时抛 {@link PayGatewayClientException}；false 时允许返回 null data（如查询无记录）
     */
    public static <T> T get(
            RestTemplate restTemplate,
            String url,
            ParameterizedTypeReference<ApiResponse<T>> typeRef,
            boolean requireNonNullData) {
        try {
            ResponseEntity<ApiResponse<T>> response =
                    restTemplate.exchange(url, HttpMethod.GET, HttpEntity.EMPTY, typeRef);
            return unwrap(response.getBody(), url, requireNonNullData);
        } catch (HttpStatusCodeException e) {
            log.warn("[pay-gateway] GET failed url={} status={} body={}", url, e.getStatusCode(), e.getResponseBodyAsString());
            throw toHttpErrorException(e);
        } catch (ResourceAccessException e) {
            log.warn("[pay-gateway] GET network error url={} msg={}", url, e.getMessage(), e);
            throw new PayGatewayClientException();
        }
    }

    public static <T> T postSigned(
            RestTemplate restTemplate,
            String url,
            byte[] signedBody,
            ParameterizedTypeReference<ApiResponse<T>> typeRef,
            boolean requireNonNullData) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<byte[]> entity = new HttpEntity<>(signedBody, headers);
            ResponseEntity<ApiResponse<T>> response = restTemplate.exchange(url, HttpMethod.POST, entity, typeRef);
            return unwrap(response.getBody(), url, requireNonNullData);
        } catch (HttpStatusCodeException e) {
            log.warn("[pay-gateway] POST failed url={} status={} body={}", url, e.getStatusCode(), e.getResponseBodyAsString());
            throw toHttpErrorException(e);
        } catch (ResourceAccessException e) {
            log.warn("[pay-gateway] POST network error url={} msg={}", url, e.getMessage(), e);
            throw new PayGatewayClientException();
        }
    }

    private static <T> T unwrap(ApiResponse<T> resp, String url, boolean requireNonNullData) {
        if (resp == null) {
            log.warn("[pay-gateway] empty response url={}", url);
            throw new PayGatewayClientException();
        }
        if (!OpenApiCodes.SUCCESS.equals(resp.getCode())) {
            throw toGatewayApiException(resp.getCode(), resp.getMessage());
        }
        if (requireNonNullData && resp.getData() == null) {
            log.warn("[pay-gateway] response data null url={}", url);
            throw new PayGatewayClientException();
        }
        return resp.getData();
    }

    private static PayGatewayClientException toHttpErrorException(HttpStatusCodeException e) {
        GatewayApiError parsed = parseGatewayApiError(e.getResponseBodyAsString());
        if (parsed != null) {
            return toGatewayApiException(parsed.code(), parsed.message());
        }
        log.warn(
                "[pay-gateway] HTTP error status={} body={}",
                e.getStatusCode().value(),
                abbreviate(e.getResponseBodyAsString()));
        return new PayGatewayClientException();
    }

    private static PayGatewayClientException toGatewayApiException(String code, String message) {
        String msg = Optional.ofNullable(message).filter(s -> !s.isBlank()).orElse(null);
        if (msg == null) {
            log.warn("[pay-gateway] api error without message code={}", code);
            return new PayGatewayClientException();
        }
        String codeKey = (code != null && !code.isBlank()) ? code.trim() : "GATEWAY";
        return new PayGatewayClientException(codeKey, msg);
    }

    /**
     * 从网关响应体或「HTTP xxx {json}」类文案中解析 {@code ApiResponse} 的 code / message。
     */
    private static GatewayApiError parseGatewayApiError(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String json = raw.trim();
        int brace = json.indexOf('{');
        if (brace > 0) {
            json = json.substring(brace);
        }
        if (!json.startsWith("{")) {
            return null;
        }
        try {
            ApiResponse<?> resp = OBJECT_MAPPER.readValue(json, ApiResponse.class);
            if (resp == null) {
                return null;
            }
            String code = resp.getCode();
            String message = resp.getMessage();
            if ((code != null && !code.isBlank()) || (message != null && !message.isBlank())) {
                return new GatewayApiError(code, message);
            }
        } catch (Exception ignored) {
            // 非标准 ApiResponse JSON
        }
        return null;
    }

    private record GatewayApiError(String code, String message) {}

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }
}
