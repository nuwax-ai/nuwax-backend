package com.xspaceagi.pay.application.service.impl;

import com.xspaceagi.pay.application.service.FileEntryApplicationService;
import com.xspaceagi.pay.application.support.PayGatewayOutboundExecutor;
import com.xspaceagi.pay.domain.gateway.FileEntryGateway;
import com.xspaceagi.pay.sdk.dto.FileEntryUploadResponse;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import jakarta.annotation.Resource;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
public class FileEntryApplicationServiceImpl implements FileEntryApplicationService {

    @Resource
    private FileEntryGateway payFileEntryGateway;

    @Resource
    private PayGatewayOutboundExecutor payGatewayOutboundExecutor;

    @Override
    public FileEntryUploadResponse uploadMerchantOnboardingImage(MultipartFile file, String replaceFileKey) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择文件");
        }
        final byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (IOException e) {
            throw new IllegalStateException("read upload file failed", e);
        }
        String filename = file.getOriginalFilename();
        String contentType = file.getContentType();
        return payGatewayOutboundExecutor.invoke(
                resolveTenantId(),
                outbound -> payFileEntryGateway.uploadMerchantOnboardingImage(
                        outbound, fileBytes, filename, contentType, replaceFileKey));
    }

    private static long resolveTenantId() {
        RequestContext<?> ctx = RequestContext.get();
        if (ctx != null && ctx.getTenantId() != null) {
            return ctx.getTenantId();
        }
        throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "无法确定租户：请登录后重试");
    }
}
