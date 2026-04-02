package com.xspaceagi.im.web.service;

import com.xspaceagi.agent.core.adapter.dto.AttachmentDto;
import com.xspaceagi.im.infra.enums.ImChannelEnum;
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
    private FileUploadHelperService fileUploadHelperService;

    /**
     * 从钉钉消息中下载附件并上传到项目存储。
     *
     * @param apiClient         钉钉 API 客户端
     * @param attachmentCodes   附件信息列表（含 downloadCode、是否图片、原始文件名）
     * @param robotCode         机器人编码
     * @param robotCodeFallback 备用 robotCode，首次失败时重试，可为 null
     * @param tenantConfig      租户配置（可为 null）
     * @return 上传成功的附件列表 + 不支持的 key 列表
     */
    public FeishuAttachmentResultDto downloadAndUpload(DingtalkOpenApiClient apiClient,
                                                       List<DingtalkAttachmentCodeDto> attachmentCodes,
                                                       String robotCode, String robotCodeFallback,
                                                       TenantConfigDto tenantConfig) {
        FeishuAttachmentResultDto result = new FeishuAttachmentResultDto();
        if (attachmentCodes == null || attachmentCodes.isEmpty()) {
            return result;
        }

        for (int i = 0; i < attachmentCodes.size(); i++) {
            DingtalkAttachmentCodeDto codeInfo = attachmentCodes.get(i);
            if (codeInfo == null || StringUtils.isBlank(codeInfo.getDownloadCode())) continue;

            String code = codeInfo.getDownloadCode();
            String originalFileName = codeInfo.getOriginalFileName();

            if (StringUtils.isBlank(code)) continue;

            try {
                // 1. 从钉钉 API 下载文件
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

                // 2. 确定文件名和检测文件类型
                FileUploadHelperService.UploadResult uploadResult;

                if (StringUtils.isNotBlank(originalFileName)) {
                    // 如果有原始文件名，使用它
                    uploadResult = fileUploadHelperService.detectAndUploadByFileName(
                        bytes, originalFileName, null, ImChannelEnum.DINGTALK, tenantConfig);
                } else {
                    // 如果没有原始文件名，使用完整检测（基于内容）
                    uploadResult = fileUploadHelperService.detectAndUpload(
                        bytes,
                        null,                    // 无 HTTP 响应头文件名
                        null,                    // 无 HTTP 响应头 Content-Type
                        null,                    // 无 URL
                        "file",                  // 原始类型（钉钉附件都是 file 类型）
                        code,                    // 默认文件名（使用 downloadCode）
                        ImChannelEnum.DINGTALK, // IM 渠道类型
                        tenantConfig
                    );
                }

                if (uploadResult.isSuccess()) {
                    ImUploadResultDto imUploadResult = uploadResult.getUploadResult();
                    AttachmentDto attachment = fileUploadHelperService.createAttachmentDto(
                        code,
                        imUploadResult.getUrl(),
                        imUploadResult.getFileName(),
                        imUploadResult.getMimeType()
                    );
                    result.getAttachments().add(attachment);
                    log.info("钉钉附件处理成功: downloadCode={}, url={}", code, attachment.getFileUrl());
                } else {
                    log.warn("钉钉附件处理失败: downloadCode={}, error={}", code, uploadResult.getErrorMessage());
                    result.getUnsupportedKeys().add(code);
                }
            } catch (Exception e) {
                log.warn("钉钉附件下载或上传异常: downloadCode={}", code, e);
                result.getUnsupportedKeys().add(code);
            }
        }

        return result;
    }
}
