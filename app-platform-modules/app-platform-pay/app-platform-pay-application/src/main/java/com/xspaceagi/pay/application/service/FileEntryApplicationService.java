package com.xspaceagi.pay.application.service;

import com.xspaceagi.pay.sdk.dto.FileEntryUploadResponse;
import org.springframework.web.multipart.MultipartFile;

public interface FileEntryApplicationService {

    FileEntryUploadResponse uploadMerchantOnboardingImage(MultipartFile file, String replaceFileKey);
}
