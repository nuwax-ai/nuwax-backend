package com.xspaceagi.im.web.service;

import com.lark.oapi.Client;
import com.lark.oapi.service.im.v1.model.GetMessageResourceReq;
import com.lark.oapi.service.im.v1.model.GetMessageResourceResp;
import com.xspaceagi.agent.core.adapter.dto.AttachmentDto;
import com.xspaceagi.im.infra.enums.ImChannelEnum;
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
    private FileUploadHelperService fileUploadHelperService;

    /**
     * 从飞书消息中下载附件并上传到项目存储。
     *
     * @param appId     飞书 appId
     * @param appSecret 飞书 appSecret
     * @param messageId 消息 ID
     * @param fileKeys  附件 key 列表（image_key 或 file_key）
     * @param types     对应资源类型："image" 或 "file"
     * @param tenantConfig 租户配置（可为 null，用于 file 存储时获取 siteUrl）
     * @return 上传成功的附件列表 + 不支持的附件 key 列表（如文件夹）
     */
    public FeishuAttachmentResultDto downloadAndUpload(String appId, String appSecret, String messageId,
                                                       List<String> fileKeys, List<String> types,
                                                       TenantConfigDto tenantConfig) {
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
                // 1. 从飞书 SDK 下载文件
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

                // 2. 确定文件名和检测文件类型
                String fileName = resp.getFileName();
                FileUploadHelperService.UploadResult uploadResult;

                if (StringUtils.isNotBlank(fileName)) {
                    // 如果飞书返回了文件名，使用它
                    uploadResult = fileUploadHelperService.detectAndUploadByFileName(
                        bytes, fileName, null, ImChannelEnum.FEISHU, tenantConfig);
                } else {
                    // 如果没有文件名，使用完整检测（不依赖文件名）
                    uploadResult = fileUploadHelperService.detectAndUpload(
                        bytes,
                        null,                    // 无 HTTP 响应头文件名
                        null,                    // 无 HTTP 响应头 Content-Type
                        null,                    // 无 URL
                        type,                    // 原始类型（image/file）
                        fileKey,                 // 默认文件名
                        ImChannelEnum.FEISHU,     // IM 渠道类型
                        tenantConfig
                    );
                }

                if (uploadResult.isSuccess()) {
                    ImUploadResultDto imUploadResult = uploadResult.getUploadResult();
                    AttachmentDto dto = fileUploadHelperService.createAttachmentDto(
                        fileKey,
                        imUploadResult.getUrl(),
                        imUploadResult.getFileName(),
                        imUploadResult.getMimeType()
                    );
                    result.getAttachments().add(dto);
                    log.info("飞书附件处理成功: fileKey={}, url={}", fileKey, dto.getFileUrl());
                } else {
                    log.warn("飞书附件处理失败: fileKey={}, error={}", fileKey, uploadResult.getErrorMessage());
                    result.getUnsupportedKeys().add(fileKey);
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
}
