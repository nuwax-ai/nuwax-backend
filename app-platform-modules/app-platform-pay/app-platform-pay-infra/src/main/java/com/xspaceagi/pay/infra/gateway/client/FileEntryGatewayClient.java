package com.xspaceagi.pay.infra.gateway.client;

import com.xspaceagi.pay.domain.gateway.FileEntryGateway;
import com.xspaceagi.pay.infra.gateway.utils.PayGatewayClientUtils;
import com.xspaceagi.pay.sdk.dto.ApiResponse;
import com.xspaceagi.pay.sdk.dto.FileEntryOpenApiUploadRequest;
import com.xspaceagi.pay.sdk.dto.FileEntryUploadResponse;
import com.xspaceagi.pay.sdk.sign.OpenApiFileEntryMultipartSupport;
import com.xspaceagi.pay.sdk.sign.OpenApiFileEntryMultipartSupport.SignedUploadFields;
import com.xspaceagi.pay.sdk.sign.OpenApiFileEntrySign;
import com.xspaceagi.pay.spec.gateway.PayGatewayOutbound;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
public class FileEntryGatewayClient implements FileEntryGateway {

    private static final String BIZ_MERCHANT_ONBOARDING_IMAGE = "merchant_onboarding_image";

    private static final ParameterizedTypeReference<ApiResponse<FileEntryUploadResponse>> UPLOAD_RESPONSE_TYPE =
            new ParameterizedTypeReference<ApiResponse<FileEntryUploadResponse>>() {};

    private final RestTemplate payGatewayRestTemplate;

    public FileEntryGatewayClient(@Qualifier("payGatewayRestTemplate") RestTemplate payGatewayRestTemplate) {
        this.payGatewayRestTemplate = payGatewayRestTemplate;
    }

    @Override
    public FileEntryUploadResponse uploadMerchantOnboardingImage(
            PayGatewayOutbound outbound,
            byte[] fileBytes,
            String filename,
            String contentType,
            String replaceFileKey) {
        if (fileBytes == null || fileBytes.length == 0) {
            throw new IllegalArgumentException("请选择文件");
        }
        FileEntryOpenApiUploadRequest meta = new FileEntryOpenApiUploadRequest();
        meta.setClientId(outbound.clientId());
        meta.setBizType(BIZ_MERCHANT_ONBOARDING_IMAGE);
        if (StringUtils.hasText(replaceFileKey)) {
            meta.setReplaceFileKey(replaceFileKey.trim());
        }
        byte[] signedJson = OpenApiFileEntrySign.signUpload(meta, outbound.clientSecret());
        SignedUploadFields fields = OpenApiFileEntryMultipartSupport.parseSignedMetadata(signedJson);
        String url = outbound.baseUrl() + OpenApiFileEntrySign.PATH_UPLOAD;
        log.info("[pay-gateway] POST {} clientId={} bizType={}", url, fields.getClientId(), fields.getBizType());
        return PayGatewayClientUtils.postMultipartSigned(
                payGatewayRestTemplate,
                url,
                toMultipartBody(fields, fileBytes, filename, contentType),
                UPLOAD_RESPONSE_TYPE,
                true);
    }

    private static MultiValueMap<String, Object> toMultipartBody(
            SignedUploadFields fields, byte[] fileBytes, String filename, String contentType) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("clientId", fields.getClientId());
        parts.add("bizType", fields.getBizType());
        if (StringUtils.hasText(fields.getReplaceFileKey())) {
            parts.add("replaceFileKey", fields.getReplaceFileKey());
        }
        parts.add("timestamp", String.valueOf(fields.getTimestamp()));
        parts.add("nonce", fields.getNonce());
        parts.add("signature", fields.getSignature());
        String safeName = StringUtils.hasText(filename) ? filename : "upload";
        ByteArrayResource resource = new ByteArrayResource(fileBytes) {
            @Override
            public String getFilename() {
                return safeName;
            }
        };
        HttpHeaders fileHeaders = new HttpHeaders();
        if (StringUtils.hasText(contentType)) {
            fileHeaders.setContentType(MediaType.parseMediaType(contentType));
        }
        parts.add("file", new HttpEntity<>(resource, fileHeaders));
        return parts;
    }
}
