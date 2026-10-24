package com.xspaceagi.knowledge.domain.knowledgeimageanalysis;

/**
 * 文档图片上传器 SPI。
 *
 * <p>由基础设施层实现并通过 Spring {@code ObjectProvider} 注入到
 * {@link DocumentImageParseService} 中, 作为默认的上传实现。
 * 方法签名与 {@link ImageUploadCallback} 一致, 便于适配。
 */
public interface DocumentImageUploader {

    /**
     * 上传图片并返回其访问 URL。
     *
     * @param imageBytes  图片二进制数据
     * @param fileName    建议的文件名
     * @param contentType MIME 类型
     * @return 图片上传后的可访问 URL
     */
    String upload(byte[] imageBytes, String fileName, String contentType);
}
