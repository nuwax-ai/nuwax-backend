package com.xspaceagi.knowledge.domain.knowledgeimageanalysis;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档(含图片)解析结果。
 *
 * <p>{@link #content} 为解析后的正文, 其中图片已按原文档位置以
 * {@code ![](url)} 的 Markdown 形式插入; {@link #images} 为抽取到的图片元信息列表。
 */
@Data
@Builder
public class DocumentImageParseResult {

    /** 解析后的正文(图片已替换为 Markdown 标记) */
    private String content;

    /** 抽取到的图片总数 */
    private int totalImages;

    /** 图片元信息列表 */
    @Builder.Default
    private List<ImageInfo> images = new ArrayList<>();

    /** 解析是否成功 */
    private boolean success;

    /** 解析失败时的错误信息 */
    private String errorMessage;

    /**
     * 单张图片的元信息。
     */
    @Data
    @Builder
    public static class ImageInfo {

        /** 图片在文档中的序号(从 1 开始, 按文档阅读顺序) */
        private int index;

        /** 建议的文件名 */
        private String fileName;

        /** 图片二进制数据大小(字节) */
        private long size;

        /** 图片上传后的可访问 URL */
        private String uploadedUrl;
    }
}
