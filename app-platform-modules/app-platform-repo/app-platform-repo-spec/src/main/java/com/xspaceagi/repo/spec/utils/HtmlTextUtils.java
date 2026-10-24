package com.xspaceagi.repo.spec.utils;

/**
 * HTML 纯文本提取工具（用于搜索索引）。
 * <p>
 * 轻量实现：去除标签、解码常见实体、压缩空白；不引入额外依赖。
 */
public final class HtmlTextUtils {

    /**
     * 索引正文最大长度，超长文档截断保护 ES 索引体积
     */
    private static final int MAX_TEXT_LENGTH = 100_000;

    private HtmlTextUtils() {
    }

    /**
     * 从 HTML 提取纯文本；入参为 null 返回空串。
     */
    public static String extractText(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        // script/style 块整体移除
        String cleaned = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");
        // 块级标签边界补空格，避免相邻文字粘连
        cleaned = cleaned.replaceAll("(?i)</(p|div|h[1-6]|li|tr|br|td|th|section|article)>", " ");
        cleaned = cleaned.replaceAll("(?i)<br\\s*/?>", " ");
        // 去除其余标签
        cleaned = cleaned.replaceAll("<[^>]+>", " ");
        cleaned = decodeEntities(cleaned);
        // 压缩空白
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        if (cleaned.length() > MAX_TEXT_LENGTH) {
            cleaned = cleaned.substring(0, MAX_TEXT_LENGTH);
        }
        return cleaned;
    }

    private static String decodeEntities(String text) {
        return text
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
    }
}
