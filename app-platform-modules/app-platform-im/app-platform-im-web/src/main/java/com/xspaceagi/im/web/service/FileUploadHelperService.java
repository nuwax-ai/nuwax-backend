package com.xspaceagi.im.web.service;

import com.xspaceagi.agent.core.adapter.dto.AttachmentDto;
import com.xspaceagi.im.infra.enums.ImChannelEnum;
import com.xspaceagi.im.web.dto.ImUploadResultDto;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * 文件上传辅助服务：统一的文件类型检测和上传流程
 * 供企业微信、钉钉、飞书等平台服务使用
 */
@Slf4j
@Service
public class FileUploadHelperService {

    @Resource
    private FileTypeDetectionService fileTypeDetectionService;

    @Resource
    private ImFileUploadService fileUploadService;

    /**
     * 检测文件类型并上传
     *
     * @param fileBytes          文件字节数组
     * @param headerFileName     HTTP响应头的文件名（Content-Disposition）
     * @param headerContentType  HTTP响应头的Content-Type
     * @param url                文件URL
     * @param originalType       原始类型提示（如 "image", "file"）
     * @param defaultFileName    默认文件名（当无法从其他来源获取时使用）
     * @param imType             IM 渠道类型
     * @param tenantConfig       租户配置
     * @return 上传结果
     */
    public UploadResult detectAndUpload(byte[] fileBytes,
                                        String headerFileName,
                                        String headerContentType,
                                        String url,
                                        String originalType,
                                        String defaultFileName,
                                        ImChannelEnum imType,
                                        TenantConfigDto tenantConfig) {
        if (fileBytes == null || fileBytes.length == 0) {
            log.warn("文件字节数组为空，无法上传");
            return UploadResult.failed("文件字节数组为空");
        }

        // 统一使用 tmp 作为存储类型，子路径为 im/{imType.code}
        String storageType = "tmp";
        String subPath = imType != null ? "im/" + imType.getCode() : null;

        try {
            // 1. 检测文件类型
            FileTypeDetectionService.FileDetectionResult detection =
                    fileTypeDetectionService.detectFileType(fileBytes, headerFileName, headerContentType, url, originalType);

            // 如果所有检测方式都失败，但有默认文件名，就使用默认文件名
            if (detection == null) {
                if (StringUtils.isNotBlank(defaultFileName)) {
                    // 使用默认文件名，Content-Type 使用通用类型
                    String fileName = defaultFileName;
                    String contentType = "application/octet-stream";

                    log.info("文件类型检测失败，使用默认文件名: fileName={}", fileName);

                    ImUploadResultDto uploadResult = fileUploadService.uploadBytes(
                            fileBytes, fileName, contentType, storageType, subPath, tenantConfig);

                    if (uploadResult != null && StringUtils.isNotBlank(uploadResult.getUrl())) {
                        // 创建一个空的 detection 对象（不包含类型信息）
                        return UploadResult.success(uploadResult, null);
                    } else {
                        return UploadResult.failed("文件上传失败");
                    }
                } else {
                    log.warn("文件类型检测失败，且无默认文件名，无法上传: originalType={}", originalType);
                    return UploadResult.failed("文件类型检测失败，且无默认文件名");
                }
            }

            // 2. 确定文件名
            String fileName = determineFileName(detection, defaultFileName, originalType);

            // 3. 确定Content-Type
            String contentType = detection.getMimeType();
            if (StringUtils.isBlank(contentType)) {
                contentType = "application/octet-stream";  // 最后的兜底
            }

            log.info("准备上传文件: fileName={}, contentType={}, source={}", fileName, contentType, detection.getDetectionSource());

            // 4. 上传文件
            ImUploadResultDto uploadResult = fileUploadService.uploadBytes(
                    fileBytes, fileName, contentType, storageType, subPath, tenantConfig);

            if (uploadResult != null && StringUtils.isNotBlank(uploadResult.getUrl())) {
                log.info("文件上传成功: url={}, uploadFileName={}", uploadResult.getUrl(), uploadResult.getFileName());
                return UploadResult.success(uploadResult, detection);
            } else {
                log.warn("文件上传失败");
                return UploadResult.failed("文件上传失败");
            }

        } catch (Exception e) {
            log.error("文件检测或上传异常", e);
            return UploadResult.failed("文件检测或上传异常: " + e.getMessage());
        }
    }

    /**
     * 基于文件名上传，并允许调用方显式覆盖 MIME 类型。
     * 适用于已知文件名，且上游协议能够提供更准确 MIME 的场景。
     *
     * @param fileBytes       文件字节数组
     * @param originalFileName 原始文件名
     * @param mimeOverride    MIME 类型覆盖（可为 null）
     * @param imType          IM 渠道类型
     * @param tenantConfig    租户配置
     * @return 上传结果
     */
    public UploadResult detectAndUploadByFileName(byte[] fileBytes,
                                                  String originalFileName,
                                                  String mimeOverride,
                                                  ImChannelEnum imType,
                                                  TenantConfigDto tenantConfig) {
        if (fileBytes == null || fileBytes.length == 0) {
            return UploadResult.failed("文件字节数组为空");
        }

        // 统一使用 tmp 作为存储类型，子路径为 im/{imType.code}
        String storageType = "tmp";
        String subPath = imType != null ? "im/" + imType.getCode() : null;

        try {
            // 从文件名推断MIME类型
            String contentType = StringUtils.isNotBlank(mimeOverride)
                    ? mimeOverride
                    : fileTypeDetectionService.inferMimeTypeFromFileName(originalFileName);

            // 如果无法推断，使用默认值
            if (StringUtils.isBlank(contentType)) {
                contentType = "application/octet-stream";
            }

            log.info("从文件名推断MIME类型: fileName={}, contentType={}", originalFileName, contentType);

            // 上传文件
            ImUploadResultDto uploadResult = fileUploadService.uploadBytes(
                    fileBytes, originalFileName, contentType, storageType, subPath, tenantConfig);

            if (uploadResult != null && StringUtils.isNotBlank(uploadResult.getUrl())) {
                return UploadResult.success(uploadResult, null);
            } else {
                return UploadResult.failed("文件上传失败");
            }

        } catch (Exception e) {
            log.error("文件上传异常: fileName={}", originalFileName, e);
            return UploadResult.failed("文件上传异常: " + e.getMessage());
        }
    }

    /**
     * 基于 MIME 类型生成默认文件名并上传。
     * 适用于没有原始文件名，但已知 MIME 类型的场景。
     *
     * @param fileBytes     文件字节数组
     * @param mimeOverride  MIME 类型
     * @param originalType  原始类型提示（如 "image", "file"）
     * @param imType        IM 渠道类型
     * @param tenantConfig  租户配置
     * @return 上传结果
     */
    public UploadResult uploadWithMimeOverride(byte[] fileBytes,
                                               String mimeOverride,
                                               String originalType,
                                               ImChannelEnum imType,
                                               TenantConfigDto tenantConfig) {
        if (fileBytes == null || fileBytes.length == 0) {
            return UploadResult.failed("文件字节数组为空");
        }

        // 统一使用 tmp 作为存储类型，子路径为 im/{imType.code}
        String storageType = "tmp";
        String subPath = imType != null ? "im/" + imType.getCode() : null;

        String contentType = StringUtils.defaultIfBlank(mimeOverride, "application/octet-stream");
        String extension = fileTypeDetectionService.getExtensionFromMimeType(contentType);
        String typePrefix = StringUtils.defaultIfBlank(originalType, "file");
        String fileName = typePrefix + "_" + System.currentTimeMillis() + extension;

        try {
            log.info("使用 MIME 覆盖上传文件: fileName={}, contentType={}", fileName, contentType);
            ImUploadResultDto uploadResult = fileUploadService.uploadBytes(
                    fileBytes, fileName, contentType, storageType, subPath, tenantConfig);

            if (uploadResult != null && StringUtils.isNotBlank(uploadResult.getUrl())) {
                return UploadResult.success(uploadResult, null);
            } else {
                return UploadResult.failed("文件上传失败");
            }
        } catch (Exception e) {
            log.error("使用 MIME 覆盖上传文件异常: fileName={}", fileName, e);
            return UploadResult.failed("文件上传异常: " + e.getMessage());
        }
    }

    /**
     * 创建AttachmentDto
     */
    public AttachmentDto createAttachmentDto(String fileKey, String fileUrl, String fileName, String mimeType) {
        AttachmentDto dto = new AttachmentDto();
        dto.setFileKey(fileKey);
        dto.setFileUrl(fileUrl);
        dto.setFileName(fileName);
        dto.setMimeType(mimeType);
        return dto;
    }

    /**
     * 确定文件名
     * 优先级：检测结果文件名 > 默认文件名 > 生成唯一文件名
     */
    private String determineFileName(FileTypeDetectionService.FileDetectionResult detection,
                                     String defaultFileName,
                                     String originalType) {
        // 优先使用检测结果中的文件名（来自 HTTP 响应头的 Content-Disposition）
        if (StringUtils.isNotBlank(detection.getFileName())) {
            return detection.getFileName();
        }

        // 使用默认文件名（调用者提供的文件名）
        if (StringUtils.isNotBlank(defaultFileName)) {
            return defaultFileName;
        }

        // 兜底：生成唯一的文件名（基于时间戳和类型）
        String timestamp = String.valueOf(System.currentTimeMillis());
        String typePrefix = StringUtils.isNotBlank(originalType) ? originalType : "file";
        return typePrefix + "_" + timestamp + "." + detection.getExtension();
    }

    /**
     * 上传结果封装类
     */
    public static class UploadResult {
        private boolean success;
        private String errorMessage;
        private ImUploadResultDto uploadResult;
        private FileTypeDetectionService.FileDetectionResult detection;

        private UploadResult(boolean success, String errorMessage, ImUploadResultDto uploadResult, FileTypeDetectionService.FileDetectionResult detection) {
            this.success = success;
            this.errorMessage = errorMessage;
            this.uploadResult = uploadResult;
            this.detection = detection;
        }

        public static UploadResult success(ImUploadResultDto uploadResult, FileTypeDetectionService.FileDetectionResult detection) {
            return new UploadResult(true, null, uploadResult, detection);
        }

        public static UploadResult failed(String errorMessage) {
            return new UploadResult(false, errorMessage, null, null);
        }

        // Getters
        public boolean isSuccess() { return success; }
        public String getErrorMessage() { return errorMessage; }
        public ImUploadResultDto getUploadResult() { return uploadResult; }
        public FileTypeDetectionService.FileDetectionResult getDetection() { return detection; }
    }
}
