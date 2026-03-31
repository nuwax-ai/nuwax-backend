package com.xspaceagi.im.web.service;

import com.xspaceagi.agent.core.adapter.dto.AttachmentDto;
import com.xspaceagi.im.web.dto.ImUploadResultDto;
import com.xspaceagi.im.application.wechat.WechatIlinkAttachmentUploader;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.service.TenantConfigApplicationService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * iLink 入站附件：上传到 IM 统一存储，返回智能体可用的 URL。
 */
@Slf4j
@Service
public class WechatIlinkAttachmentUploaderImpl implements WechatIlinkAttachmentUploader {

    @Resource
    private ImFileUploadService imFileUploadService;
    @Resource
    private TenantConfigApplicationService tenantConfigApplicationService;

    @Override
    public AttachmentDto upload(byte[] bytes, String originalFilename, String contentType, Long tenantId) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        TenantConfigDto tenantConfig = tenantId != null ? tenantConfigApplicationService.getTenantConfig(tenantId) : null;
        String name = StringUtils.isNotBlank(originalFilename) ? originalFilename : "ilink.bin";
        String mime = StringUtils.isNotBlank(contentType) ? contentType : "application/octet-stream";
        ImUploadResultDto r = imFileUploadService.uploadBytes(bytes, name, mime, "wechat_ilink/inbound", tenantConfig);
        if (r == null || StringUtils.isBlank(r.getUrl())) {
            log.warn("wechat ilink inbound upload failed, name={}", name);
            return null;
        }
        AttachmentDto dto = new AttachmentDto();
        dto.setFileKey(r.getKey());
        dto.setFileUrl(r.getUrl());
        dto.setFileName(StringUtils.isNotBlank(r.getFileName()) ? r.getFileName() : name);
        dto.setMimeType(StringUtils.isNotBlank(r.getMimeType()) ? r.getMimeType() : mime);
        return dto;
    }
}
