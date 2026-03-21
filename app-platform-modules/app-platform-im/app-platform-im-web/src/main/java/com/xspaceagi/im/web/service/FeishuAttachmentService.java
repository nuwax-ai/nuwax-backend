package com.xspaceagi.im.web.service;

import com.lark.oapi.Client;
import com.lark.oapi.service.im.v1.model.GetMessageResourceReq;
import com.lark.oapi.service.im.v1.model.GetMessageResourceResp;
import com.xspaceagi.agent.core.adapter.dto.AttachmentDto;
import com.xspaceagi.im.web.dto.FeishuAttachmentResultDto;
import com.xspaceagi.im.web.dto.ImUploadResultDto;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.google.gson.JsonIOException;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * 飞书附件服务：从飞书消息中下载附件，通过 FileUploadService 上传到项目存储，返回可访问的 URL。
 * 参考飞书文档：https://open.feishu.cn/document/server-docs/im-v1/message/get-2
 */
@Slf4j
@Service
public class FeishuAttachmentService {

    @Resource
    private ImFileUploadService fileUploadService;

    /**
     * 从飞书消息中下载附件并上传到项目存储。
     *
     * @param appId     飞书 appId
     * @param appSecret 飞书 appSecret
     * @param messageId 消息 ID
     * @param fileKeys  附件 key 列表（image_key 或 file_key）
     * @param types     对应资源类型："image" 或 "file"
     * @param tenantId  租户 ID（用于获取 siteUrl）
     * @param tenantConfig 租户配置（可为 null，用于 file 存储时获取 siteUrl）
     * @return 上传成功的附件列表 + 不支持的附件 key 列表（如文件夹）
     */
    public FeishuAttachmentResultDto downloadAndUpload(String appId, String appSecret, String messageId,
                                                       List<String> fileKeys, List<String> types,
                                                       Long tenantId, TenantConfigDto tenantConfig) {
        FeishuAttachmentResultDto result = new FeishuAttachmentResultDto();
        if (fileKeys == null || fileKeys.isEmpty()) {
            return result;
        }
        Client client = Client.newBuilder(appId, appSecret).build();

        for (int i = 0; i < fileKeys.size(); i++) {
            String fileKey = fileKeys.get(i);
            String type = (types != null && i < types.size()) ? types.get(i) : "file";
            if (StringUtils.isBlank(fileKey)) {
                continue;
            }
            try {
                GetMessageResourceReq req = GetMessageResourceReq.newBuilder()
                        .messageId(messageId)
                        .fileKey(fileKey)
                        .type(type)
                        .build();
                GetMessageResourceResp resp = client.im().v1().messageResource().get(req);
                if (resp.getData() == null) {
                    log.warn("飞书附件下载失败: messageId={}, fileKey={}, type={}", messageId, fileKey, type);
                    result.getUnsupportedKeys().add(fileKey);
                    continue;
                }
                ByteArrayOutputStream data = resp.getData();
                byte[] bytes = data.toByteArray();
                String fileName = resp.getFileName();
                if (StringUtils.isBlank(fileName)) {
                    fileName = inferFileName(fileKey, type);
                }
                String contentType = inferContentType(fileName, type);
                ImUploadResultDto uploadResult = fileUploadService.uploadBytes(bytes, fileName, contentType, "tmp", tenantConfig);
                if (uploadResult != null && StringUtils.isNotBlank(uploadResult.getUrl())) {
                    AttachmentDto dto = new AttachmentDto();
                    dto.setFileKey(fileKey);
                    dto.setFileUrl(uploadResult.getUrl());
                    dto.setFileName(uploadResult.getFileName());
                    dto.setMimeType(uploadResult.getMimeType());
                    result.getAttachments().add(dto);
                }
            } catch (JsonIOException e) {
                // 飞书 SDK 在解析错误响应时可能抛出（如文件夹等不支持的类型）
                log.warn("飞书附件跳过（可能为文件夹或不支持的类型）: messageId={}, fileKey={}, type={}", messageId, fileKey, type);
                result.getUnsupportedKeys().add(fileKey);
            } catch (Exception e) {
                log.warn("飞书附件下载或上传异常: messageId={}, fileKey={}", messageId, fileKey, e);
                result.getUnsupportedKeys().add(fileKey);
            }
        }
        return result;
    }

    private String inferFileName(String fileKey, String type) {
        String ext = "image".equals(type) ? "png" : "bin";
        return fileKey + "." + ext;
    }

    private String inferContentType(String fileName, String type) {
        if ("image".equals(type)) {
            return "image/png";
        }
        int i = fileName != null ? fileName.lastIndexOf('.') : -1;
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
