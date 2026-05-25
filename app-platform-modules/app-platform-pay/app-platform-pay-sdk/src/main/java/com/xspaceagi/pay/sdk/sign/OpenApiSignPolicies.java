package com.xspaceagi.pay.sdk.sign;

import java.util.List;
import java.util.Map;

/**
 * 各 Open API 路径参与 body 哈希的字段（不含 {@code timestamp}/{@code nonce}/{@code signature}）。
 * <p>path 与各 {@code OpenApi*Sign#PATH_*} 保持一致，为单一来源。</p>
 */
public final class OpenApiSignPolicies {

    private OpenApiSignPolicies() {}

    private static final Map<String, List<String>> BY_PATH = Map.ofEntries(
            Map.entry(
                    OpenApiPaymentScanSign.PATH_CREATE_ORDER_AND_TRANSACTION,
                    List.of("bizOrderNo", "clientId", "orderAmount", "payChannel")),
            Map.entry(
                    OpenApiPaymentScanSign.PATH_CREATE_ORDER,
                    List.of("bizOrderNo", "clientId", "orderAmount")),
            Map.entry(
                    OpenApiPaymentScanSign.PATH_CREATE_TRANSACTION,
                    List.of("clientId", "gatewayPaymentOrderNo", "payChannel")),
            Map.entry(
                    OpenApiPaymentScanSign.PATH_STATUS,
                    List.of("clientId", "gatewayPaymentOrderNo", "syncFromChannel")),
            Map.entry(OpenApiPaymentConfigSign.PATH_QUERY, List.of("clientId")),
            Map.entry(
                    OpenApiMerchantOnboardingSign.PATH_ADD,
                    List.of("clientId", "creditCode", "merchantName", "onboardingType", "userId")),
            Map.entry(
                    OpenApiMerchantOnboardingSign.PATH_UPDATE,
                    List.of("clientId", "creditCode", "id", "merchantName")),
            Map.entry(OpenApiMerchantOnboardingSign.PATH_GET_BY_CLIENT_ID, List.of("clientId")),
            Map.entry(OpenApiMerchantOnboardingSign.PATH_LIST_BY_CLIENT_ID, List.of("clientId")),
            Map.entry(
                    OpenApiMerchantOnboardingSign.PATH_PAGE,
                    List.of("clientId", "onboardingType", "page", "pageSize", "status")),
            Map.entry(
                    OpenApiCashierSign.PATH_SESSION,
                    List.of("clientId", "gatewayPaymentOrderNo", "orderAmount")));

    public static List<String> signFieldsForPath(String path) {
        String normalized = normalizePath(path);
        List<String> fields = BY_PATH.get(normalized);
        if (fields == null) {
            throw new IllegalArgumentException("No OpenAPI sign field policy for path: " + normalized);
        }
        return fields;
    }

    static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String p = path.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        return p;
    }
}
