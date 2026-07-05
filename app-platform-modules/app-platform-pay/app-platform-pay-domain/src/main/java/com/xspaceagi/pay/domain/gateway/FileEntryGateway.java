package com.xspaceagi.pay.domain.gateway;

import com.xspaceagi.pay.sdk.dto.FileEntryUploadResponse;
import com.xspaceagi.pay.spec.gateway.PayGatewayOutbound;

/** 调用 nuwax-pay {@code /open-api/file}；出站上下文由应用层解析后传入。 */
public interface FileEntryGateway {

    FileEntryUploadResponse uploadMerchantOnboardingImage(
            PayGatewayOutbound outbound,
            byte[] fileBytes,
            String filename,
            String contentType,
            String replaceFileKey);
}
