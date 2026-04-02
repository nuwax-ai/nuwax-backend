package com.xspaceagi.im.application.util;

import org.apache.commons.lang3.StringUtils;

/**
 * MIME 类型工具类
 * 提供文件扩展名与 MIME 类型的映射
 */
public final class MimeTypeUtils {

    private MimeTypeUtils() {
    }

    /**
     * 从文件名推断 MIME 类型
     *
     * @param fileName 文件名（可以是完整文件名或带点的扩展名，如 ".csv"）
     * @return MIME 类型，如果无法推断返回 null
     */
    public static String inferMimeTypeFromFileName(String fileName) {
        if (StringUtils.isBlank(fileName)) {
            return null;
        }

        // 如果已经是扩展名格式（如 ".csv"），直接使用
        String ext = fileName.startsWith(".") ? fileName.toLowerCase() : "";

        // 否则从完整文件名提取扩展名
        if (ext.isEmpty()) {
            int lastDot = fileName.lastIndexOf('.');
            if (lastDot < 0 || lastDot >= fileName.length() - 1) {
                return null;
            }
            ext = fileName.substring(lastDot).toLowerCase();
        }

        return switch (ext) {
            // 图片
            case ".jpg", ".jpeg" -> "image/jpeg";
            case ".png" -> "image/png";
            case ".gif" -> "image/gif";
            case ".webp" -> "image/webp";
            case ".bmp" -> "image/bmp";
            case ".svg" -> "image/svg+xml";

            // 文档
            case ".pdf" -> "application/pdf";
            case ".doc" -> "application/msword";
            case ".docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case ".xls" -> "application/vnd.ms-excel";
            case ".xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case ".ppt" -> "application/vnd.ms-powerpoint";
            case ".pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case ".txt" -> "text/plain";
            case ".csv" -> "text/csv";
            case ".tsv" -> "text/tab-separated-values";
            case ".json" -> "application/json";
            case ".xml" -> "application/xml";
            case ".html", ".htm" -> "text/html";
            case ".md" -> "text/markdown";

            // 压缩文件
            case ".zip" -> "application/zip";
            case ".tar" -> "application/x-tar";
            case ".gz" -> "application/gzip";
            case ".rar" -> "application/vnd.rar";
            case ".7z" -> "application/x-7z-compressed";

            // 音频
            case ".mp3" -> "audio/mpeg";
            case ".ogg" -> "audio/ogg";
            case ".wav" -> "audio/wav";
            case ".flac" -> "audio/flac";
            case ".m4a" -> "audio/mp4";
            case ".aac" -> "audio/aac";

            // 视频
            case ".mp4" -> "video/mp4";
            case ".mov" -> "video/quicktime";
            case ".webm" -> "video/webm";
            case ".mkv" -> "video/x-matroska";
            case ".avi" -> "video/x-msvideo";

            default -> null;
        };
    }

    /**
     * 从 MIME 类型获取文件扩展名
     *
     * @param mimeType MIME 类型
     * @return 文件扩展名（含点），如果无法推断返回 ".bin"
     */
    public static String getExtensionFromMimeType(String mimeType) {
        if (StringUtils.isBlank(mimeType)) {
            return ".bin";
        }
        String mime = mimeType.toLowerCase();
        int semicolon = mime.indexOf(';');
        if (semicolon >= 0) {
            mime = mime.substring(0, semicolon).trim();
        }
        return switch (mime) {
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/bmp" -> ".bmp";
            case "image/svg+xml" -> ".svg";

            case "application/pdf" -> ".pdf";
            case "application/msword" -> ".doc";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx";
            case "application/vnd.ms-excel" -> ".xls";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> ".xlsx";
            case "application/vnd.ms-powerpoint" -> ".ppt";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> ".pptx";
            case "text/plain" -> ".txt";
            case "text/csv" -> ".csv";
            case "application/json" -> ".json";
            case "application/xml" -> ".xml";
            case "text/html" -> ".html";
            case "text/markdown" -> ".md";

            case "application/zip" -> ".zip";
            case "application/x-tar" -> ".tar";
            case "application/gzip" -> ".gz";
            case "application/vnd.rar" -> ".rar";
            case "application/x-7z-compressed" -> ".7z";

            case "audio/mpeg" -> ".mp3";
            case "audio/ogg" -> ".ogg";
            case "audio/wav" -> ".wav";
            case "audio/flac" -> ".flac";
            case "audio/mp4" -> ".m4a";

            case "video/mp4" -> ".mp4";
            case "video/quicktime" -> ".mov";
            case "video/webm" -> ".webm";
            case "video/x-matroska" -> ".mkv";
            case "video/x-msvideo" -> ".avi";

            default -> ".bin";
        };
    }
}
