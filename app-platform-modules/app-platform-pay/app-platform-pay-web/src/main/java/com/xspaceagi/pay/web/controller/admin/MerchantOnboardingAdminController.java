package com.xspaceagi.pay.web.controller.admin;

import com.xspaceagi.pay.application.service.FileEntryApplicationService;
import com.xspaceagi.pay.application.service.MerchantOnboardingApplicationService;
import com.xspaceagi.pay.sdk.dto.FileEntryUploadResponse;
import com.xspaceagi.pay.sdk.dto.MerchantOnboardingResponse;
import com.xspaceagi.pay.sdk.dto.MerchantOnboardingUpdateRequest;
import com.xspaceagi.pay.sdk.dto.MerchantOnboardingUpsertRequest;
import com.xspaceagi.pay.sdk.enums.MerchantOnboardingType;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.MediaType;

import static com.xspaceagi.system.spec.enums.ResourceEnum.PAY_EARNINGS_MODIFY;
import static com.xspaceagi.system.spec.enums.ResourceEnum.PAY_EARNINGS_QUERY;

@Slf4j
@RestController
@RequestMapping("/api/system/pay/merchant-onboarding")
@Tag(name = "支付-商户进件")
public class MerchantOnboardingAdminController {

    @Resource
    private MerchantOnboardingApplicationService merchantOnboardingAppService;

    @Resource
    private FileEntryApplicationService fileEntryAppService;

    @RequireResource(PAY_EARNINGS_MODIFY)
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传进件影像", description = "上传文件，返回 fileKey 与 publicUrl；提交进件请传 *FileKey，重传同字段请带 replaceFileKey")
    public ReqResult<FileEntryUploadResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "replaceFileKey", required = false) String replaceFileKey) {
        Long tenantId = RequestContext.get() == null ? null : RequestContext.get().getTenantId();
        Assert.notNull(tenantId, "tenantId must be non-null");
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择文件");
        }
        String replaceKey = StringUtils.hasText(replaceFileKey) ? replaceFileKey.trim() : null;
        log.info("api /pay/merchant-onboarding/upload-image tenantId={}, size={}", tenantId, file.getSize());
        return ReqResult.success(fileEntryAppService.uploadMerchantOnboardingImage(file, replaceKey));
    }

    @RequireResource(PAY_EARNINGS_MODIFY)
    @PostMapping("/add")
    @Operation(summary = "新增进件", description = "仅支持租户进件、用户进件；不支持平台进件。clientId 由服务端按登录租户凭证写入，请求体勿传")
    public ReqResult<MerchantOnboardingResponse> add(@Valid @RequestBody MerchantOnboardingUpsertRequest request) {
        Assert.notNull(request.getOnboardingType(), "onboardingType must be non-null");
        Assert.isTrue(request.getOnboardingType() != MerchantOnboardingType.PLATFORM, "agent 端不支持平台进件");
        Long tenantId = RequestContext.get() == null ? null : RequestContext.get().getTenantId();
        Assert.notNull(tenantId, "tenantId must be non-null");
        log.info("api /pay/merchant-onboarding/add type={}, tenantId={}", request.getOnboardingType(), tenantId);
        return ReqResult.success(merchantOnboardingAppService.add(request));
    }

    @RequireResource(PAY_EARNINGS_MODIFY)
    @PostMapping("/update")
    @Operation(summary = "更新进件", description = "仅支持租户进件、用户进件；不支持平台进件。clientId 由服务端按登录租户凭证写入，请求体勿传")
    public ReqResult<MerchantOnboardingResponse> update(@Valid @RequestBody MerchantOnboardingUpdateRequest request) {
        Assert.notNull(request.getId(), "id must be non-null");
        Assert.isTrue(request.getOnboardingType() != MerchantOnboardingType.PLATFORM, "agent 端不支持平台进件");
        Long tenantId = RequestContext.get() == null ? null : RequestContext.get().getTenantId();
        Assert.notNull(tenantId, "tenantId must be non-null");
        log.info("api /pay/merchant-onboarding/update id={}, tenantId={}", request.getId(), tenantId);
        return ReqResult.success(merchantOnboardingAppService.update(request));
    }

    @RequireResource(PAY_EARNINGS_QUERY)
    @PostMapping("/get-by-tenant-id")
    @Operation(summary = "按租户查询进件")
    public ReqResult<MerchantOnboardingResponse> getByTenantId() {
        Assert.notNull(RequestContext.get() != null ? RequestContext.get().getTenantId() : null, "tenantId must be non-null");
        return ReqResult.success(merchantOnboardingAppService.getByTenantId());
    }
}
