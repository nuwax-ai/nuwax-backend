package com.xspaceagi.knowledge.domain.knowledgeimageanalysis;

/**
 * 图片上传回调: 解析器在抽取到文档中的图片二进制数据后,
 * 通过该回调将图片上传到文件服务, 返回可访问的 URL。
 *
 * <p>设计为函数式接口, 便于在测试中用 lambda 模拟上传逻辑,
 * 在生产环境中由实际的文件服务实现。
 */
@FunctionalInterface
public interface ImageUploadCallback {

    /**
     * 上传图片并返回其访问 URL。
     *
     * @param imageBytes  图片二进制数据
     * @param fileName    建议的文件名 (如 image_1.png)
     * @param contentType MIME 类型 (如 image/png)
     * @return 图片上传后的可访问 URL; 返回 null 表示跳过该图片
     */
    String upload(byte[] imageBytes, String fileName, String contentType);
}
