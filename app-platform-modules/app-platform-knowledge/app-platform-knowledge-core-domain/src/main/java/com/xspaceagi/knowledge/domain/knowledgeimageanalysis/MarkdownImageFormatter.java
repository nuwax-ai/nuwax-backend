package com.xspaceagi.knowledge.domain.knowledgeimageanalysis;

/**
 * 将图片访问 URL 包装为 Markdown 图片语法 {@code ![](url)}。
 */
public final class MarkdownImageFormatter {

    private static final String PREFIX = "![](";
    private static final String SUFFIX = ")";

    private MarkdownImageFormatter() {
    }

    /**
     * 将裸 URL 格式化为 Markdown 图片标记。
     *
     * @param url 图片 URL, 为 null 或空时原样返回
     * @return {@code ![](url)} 形式的字符串
     */
    public static String format(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        return PREFIX + url + SUFFIX;
    }
}
