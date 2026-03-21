package com.xspaceagi.im.web.service;

import com.xspaceagi.agent.core.adapter.dto.AttachmentDto;
import com.xspaceagi.im.web.dto.DingtalkAttachmentCodeDto;
import com.xspaceagi.im.web.dto.FeishuAttachmentResultDto;
import com.xspaceagi.im.web.dto.ImUploadResultDto;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 钉钉附件服务：从钉钉消息中下载附件（通过 downloadCode），上传到项目存储，返回可访问的 URL。
 * 参考：<a href="https://open.dingtalk.com/document/orgapp/download-files-received-by-robot">下载机器人接收消息的文件内容</a>
 */
@Slf4j
@Service
public class DingtalkAttachmentService {

    @Resource
    private ImFileUploadService fileUploadService;

    /**
     * 从钉钉消息中下载附件并上传到项目存储。
     *
     * @param apiClient         钉钉 API 客户端
     * @param attachmentCodes   附件信息列表（含 downloadCode、是否图片、原始文件名）
     * @param robotCode         机器人编码
     * @param robotCodeFallback 备用 robotCode，首次失败时重试，可为 null
     * @param tenantId          租户 ID
     * @param tenantConfig      租户配置（可为 null）
     * @return 上传成功的附件列表 + 不支持的 key 列表
     */
    public FeishuAttachmentResultDto downloadAndUpload(DingtalkOpenApiClient apiClient,
                                                       List<DingtalkAttachmentCodeDto> attachmentCodes,
                                                       String robotCode, String robotCodeFallback,
                                                       Long tenantId, TenantConfigDto tenantConfig) {
        FeishuAttachmentResultDto result = new FeishuAttachmentResultDto();
        if (attachmentCodes == null || attachmentCodes.isEmpty()) {
            return result;
        }
        for (int i = 0; i < attachmentCodes.size(); i++) {
            DingtalkAttachmentCodeDto codeInfo = attachmentCodes.get(i);
            if (codeInfo == null || StringUtils.isBlank(codeInfo.getDownloadCode())) continue;
            String code = codeInfo.getDownloadCode();
            boolean isPicture = codeInfo.isPicture();
            String originalFileName = codeInfo.getOriginalFileName();
            if (StringUtils.isBlank(code)) continue;
            try {
                byte[] bytes = apiClient.downloadMessageFile(code, robotCode);
                if (bytes == null && StringUtils.isNotBlank(robotCodeFallback)) {
                    log.info("钉钉附件首次下载失败，尝试 robotCodeFallback={}", robotCodeFallback);
                    bytes = apiClient.downloadMessageFile(code, robotCodeFallback);
                }
                if (bytes == null || bytes.length == 0) {
                    log.warn("钉钉附件下载失败: downloadCode={}", code);
                    result.getUnsupportedKeys().add(code);
                    continue;
                }
                String fileName = inferFileName(code, i, isPicture, originalFileName, bytes);
                String contentType = inferContentType(fileName);
                ImUploadResultDto uploadResult = fileUploadService.uploadBytes(bytes, fileName, contentType, "tmp", tenantConfig);
                if (uploadResult != null && StringUtils.isNotBlank(uploadResult.getUrl())) {
                    AttachmentDto attachment = new AttachmentDto();
                    attachment.setFileKey(code);
                    attachment.setFileUrl(uploadResult.getUrl());
                    attachment.setFileName(uploadResult.getFileName());
                    attachment.setMimeType(uploadResult.getMimeType());
                    result.getAttachments().add(attachment);
                } else {
                    result.getUnsupportedKeys().add(code);
                }
            } catch (Exception e) {
                log.warn("钉钉附件下载或上传异常: downloadCode={}", code, e);
                result.getUnsupportedKeys().add(code);
            }
        }
        return result;
    }

    /**
     * 推断文件名：优先用原始文件名后缀；如果没有后缀，则 picture 用 .png，file 按内容或 .bin。
     */
    private String inferFileName(String downloadCode, int index, boolean isPicture, String originalFileName, byte[] bytes) {
        String ext = extractFileExtension(originalFileName);
        if (ext == null) {
            ext = isPicture ? "png" : (isPdf(bytes) ? "pdf" : "bin");
        }
        String baseName;
        if (StringUtils.isNotBlank(originalFileName) && originalFileName.contains(".")) {
            int dot = originalFileName.lastIndexOf('.');
            baseName = (dot > 0 ? originalFileName.substring(0, dot) : originalFileName)
                    .replaceAll("[\\\\/:*?\"<>|]", "_");
        } else {
            String safeCode = downloadCode != null && downloadCode.length() > 8 ? downloadCode.substring(0, 8) : (downloadCode != null ? downloadCode : "");
            baseName = "dingtalk_attach_" + index + "_" + safeCode.replaceAll("[^a-zA-Z0-9]", "_");
        }
        return baseName + "." + ext;
    }

    private String extractFileExtension(String originalFileName) {
        if (StringUtils.isBlank(originalFileName) || !originalFileName.contains(".")) return null;
        String ext = originalFileName.substring(originalFileName.lastIndexOf('.') + 1).toLowerCase();
        if (StringUtils.isBlank(ext)) return null;
        // 后缀也需要做文件名安全处理，避免出现非法字符
        ext = ext.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (StringUtils.isBlank(ext)) return null;
        return ext;
    }

    private boolean isPdf(byte[] bytes) {
        if (bytes == null || bytes.length < 5) return false;
        return bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F';
    }

    private String inferContentType(String fileName) {
        if (fileName == null) return "application/octet-stream";
        int i = fileName.lastIndexOf('.');
        if (i > 0 && i < fileName.length() - 1) {
            String ext = fileName.substring(i + 1).toLowerCase();
            return switch (ext) {
                case "pdf" -> "application/pdf";
                case "doc" -> "application/msword";
                case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                case "xls" -> "application/vnd.ms-excel";
                case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                case "ppt" -> "application/vnd.ms-powerpoint";
                case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
                // Code / script
                case "py" -> "text/x-python";
                case "sh" -> "text/x-shellscript";
                case "js" -> "application/javascript";
                case "ts" -> "application/typescript";
                case "pl" -> "text/x-perl";
                case "rb" -> "text/x-ruby";
                case "php" -> "application/x-httpd-php";
                case "java" -> "text/x-java-source";
                case "go" -> "text/x-go";
                case "rs" -> "text/x-rust";
                case "c", "h" -> "text/x-c";
                case "cc", "cpp", "hpp" -> "text/x-c++src";
                case "cs" -> "text/x-csharp";
                case "kt" -> "text/x-kotlin";
                case "swift" -> "text/x-swift";
                case "scala" -> "text/x-scala";
                case "sql" -> "application/sql";
                case "mp4" -> "video/mp4";
                case "mp3" -> "audio/mpeg";
                case "jpg", "jpeg" -> "image/jpeg";
                case "png" -> "image/png";
                case "gif" -> "image/gif";
                default -> "application/octet-stream";
            };
        }
        return "application/octet-stream";
    }
}
