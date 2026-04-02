package com.xspaceagi.im.application.wechat;

import com.xspaceagi.agent.core.adapter.dto.AttachmentDto;

/**
 * 将 iLink 入站媒体字节上传到业务存储并转为 {@link AttachmentDto}，供智能体使用。
 * 由 web 模块提供实现（COS/本地等）；无 Bean 时入站附件仅记录日志、不阻断文本消息。
 */
public interface WechatIlinkAttachmentService {

    /**
     * 上传附件字节到存储
     *
     * @param bytes 文件字节数组
     * @param originalFilename 原始文件名
     * @param contentType 文件MIME类型
     * @param tenantId 租户ID（用于解析存储配置）
     * @return 上传失败时返回 null
     */
    AttachmentDto upload(byte[] bytes, String originalFilename, String contentType, Long tenantId);
}
