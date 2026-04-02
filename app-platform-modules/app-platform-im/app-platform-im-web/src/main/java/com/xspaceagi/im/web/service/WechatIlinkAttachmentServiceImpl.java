package com.xspaceagi.im.web.service;

import com.xspaceagi.agent.core.adapter.dto.AttachmentDto;
import com.xspaceagi.im.application.wechat.WechatIlinkAttachmentService;
import com.xspaceagi.im.infra.enums.ImChannelEnum;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.service.TenantConfigApplicationService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * 微信 iLink 入站附件服务：上传到 IM 统一存储，返回智能体可用的 URL。
 */
@Slf4j
@Service
public class WechatIlinkAttachmentServiceImpl implements WechatIlinkAttachmentService {

    @Resource
    private FileUploadHelperService fileUploadHelperService;

    @Resource
    private TenantConfigApplicationService tenantConfigApplicationService;

    /**
     * 上传附件字节到存储
     *
     * @param bytes 文件字节数组
     * @param originalFilename 原始文件名
     * @param contentType 文件MIME类型（当前未使用，保留以兼容接口）
     * @param tenantId 租户ID
     * @return 上传失败时返回 null
     */
    public AttachmentDto upload(byte[] bytes, String originalFilename, String contentType, Long tenantId) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        TenantConfigDto tenantConfig = tenantId != null ? tenantConfigApplicationService.getTenantConfig(tenantId) : null;

        FileUploadHelperService.UploadResult result;
        if (StringUtils.isNotBlank(originalFilename)) {
            // 如果有文件名，优先保留文件名，同时允许上游显式覆盖 MIME。
            result = fileUploadHelperService.detectAndUploadByFileName(
                bytes, originalFilename, contentType, ImChannelEnum.WECHAT_ILINK, tenantConfig);
        } else if (StringUtils.isNotBlank(contentType)) {
            // 对于语音、视频等没有文件名的场景，使用 MIME 生成扩展名并上传。
            result = fileUploadHelperService.uploadWithMimeOverride(
                bytes, contentType, inferTypePrefix(contentType), ImChannelEnum.WECHAT_ILINK, tenantConfig);
        } else {
            // 否则使用完整检测（基于内容）
            result = fileUploadHelperService.detectAndUpload(
                bytes, null, null, null, null, null, ImChannelEnum.WECHAT_ILINK, tenantConfig);
        }

        if (result.isSuccess()) {
            return fileUploadHelperService.createAttachmentDto(
                result.getUploadResult().getKey(),
                result.getUploadResult().getUrl(),
                result.getUploadResult().getFileName(),
                result.getUploadResult().getMimeType()
            );
        } else {
            log.warn("wechat ilink inbound upload failed, filename={}, error={}", originalFilename, result.getErrorMessage());
            return null;
        }
    }

    private String inferTypePrefix(String contentType) {
        if (StringUtils.isBlank(contentType)) {
            return "wechat_ilink";
        }
        if (contentType.startsWith("audio/")) {
            return "voice";
        }
        if (contentType.startsWith("video/")) {
            return "video";
        }
        if (contentType.startsWith("image/")) {
            return "image";
        }
        return "wechat_ilink";
    }
}
