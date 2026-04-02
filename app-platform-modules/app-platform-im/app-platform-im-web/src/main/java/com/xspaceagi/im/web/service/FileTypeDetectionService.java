package com.xspaceagi.im.web.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import com.xspaceagi.im.application.util.MimeTypeUtils;

import java.net.URI;

/**
 * 文件类型检测服务：统一的文件类型识别和MIME类型推断
 * 整合了企业微信、钉钉、飞书的文件检测逻辑
 */
@Slf4j
@Service
public class FileTypeDetectionService {

    private final Tika tika = new Tika();

    /**
     * 文件检测结果
     */
    public static class FileDetectionResult {
        private String extension;      // 文件扩展名（不含点），如 "csv"
        private String mimeType;       // MIME类型，如 "text/csv"
        private String fileName;       // 推荐的文件名（可为null）
        private String detectionSource; // 检测来源，用于日志

        public FileDetectionResult(String extension, String mimeType, String detectionSource) {
            this.extension = extension;
            this.mimeType = mimeType;
            this.detectionSource = detectionSource;
        }

        public FileDetectionResult(String extension, String mimeType, String fileName, String detectionSource) {
            this.extension = extension;
            this.mimeType = mimeType;
            this.fileName = fileName;
            this.detectionSource = detectionSource;
        }

        // Getters
        public String getExtension() { return extension; }
        public String getMimeType() { return mimeType; }
        public String getFileName() { return fileName; }
        public String getDetectionSource() { return detectionSource; }

        public void setFileName(String fileName) { this.fileName = fileName; }
    }

    /**
     * 检测文件类型（综合多种检测方式）
     *
     * @param fileBytes          文件字节数组
     * @param headerFileName     HTTP响应头的文件名（Content-Disposition）
     * @param headerContentType  HTTP响应头的Content-Type
     * @param url                文件URL
     * @param originalType       原始类型提示（如 "image", "file"）
     * @return 文件检测结果
     */
    public FileDetectionResult detectFileType(byte[] fileBytes,
                                              String headerFileName,
                                              String headerContentType,
                                              String url,
                                              String originalType) {
        // 优先级1: HTTP响应头信息
        FileDetectionResult result = detectFromHttpHeaders(headerFileName, headerContentType);
        if (result != null) {
            log.info("从HTTP响应头检测到文件类型: ext={}, mime={}", result.getExtension(), result.getMimeType());
            return result;
        }

        // 优先级2: Magic Bytes检测
        result = detectFromMagicBytes(fileBytes);
        if (result != null) {
            log.info("从Magic Bytes检测到文件类型: ext={}, mime={}", result.getExtension(), result.getMimeType());
            return result;
        }

        // 优先级3: 从URL提取扩展名
        result = detectFromUrl(url);
        if (result != null) {
            log.info("从URL检测到文件类型: ext={}", result.getExtension());
            return result;
        }

        // 所有检测方式都失败，返回 null
        log.warn("所有文件类型检测方式都失败，无法确定文件类型: originalType={}", originalType);
        return null;
    }

    /**
     * 从文件名推断MIME类型（供其他服务使用）
     * 注意：如果无法推断，返回 null，而不是默认值
     */
    public String inferMimeTypeFromFileName(String fileName) {
        return MimeTypeUtils.inferMimeTypeFromFileName(fileName);
    }

    /**
     * 从 MIME 类型推断文件扩展名（含点）。
     */
    public String getExtensionFromMimeType(String mimeType) {
        return MimeTypeUtils.getExtensionFromMimeType(mimeType);
    }

    /**
     * 从文件名提取扩展名
     */
    public String extractExtensionFromFileName(String fileName) {
        if (StringUtils.isBlank(fileName)) {
            return null;
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex <= 0 || dotIndex >= fileName.length() - 1) {
            return null;
        }
        return fileName.substring(dotIndex + 1).toLowerCase();
    }

    // ==================== 私有方法 ====================

    /**
     * 从HTTP响应头检测文件类型
     */
    private FileDetectionResult detectFromHttpHeaders(String headerFileName, String headerContentType) {
        // 优先从Content-Disposition的文件名中获取扩展名
        if (StringUtils.isNotBlank(headerFileName)) {
            String ext = extractExtensionFromFileName(headerFileName);
            if (StringUtils.isNotBlank(ext)) {
                String mimeType = MimeTypeUtils.inferMimeTypeFromFileName("." + ext);
                return new FileDetectionResult(ext, mimeType, headerFileName, "Content-Disposition");
            }
        }

        // 从Content-Type映射扩展名
        if (StringUtils.isNotBlank(headerContentType)) {
            String ext = MimeTypeUtils.getExtensionFromMimeType(headerContentType);
            if (StringUtils.isNotBlank(ext) && !".bin".equals(ext)) {
                String mimeType = MimeTypeUtils.inferMimeTypeFromFileName(ext);
                return new FileDetectionResult(ext.substring(1), mimeType, "Content-Type");
            }
        }

        return null;
    }

    /**
     * 从Magic Bytes检测文件类型（使用 Tika）
     * Tika 内置了大量的 Magic Bytes 检测，比手动维护更可靠
     */
    private FileDetectionResult detectFromMagicBytes(byte[] fileBytes) {
        if (fileBytes == null || fileBytes.length < 4) {
            return null;
        }

        try {
            // 使用 Tika 检测文件类型
            // Tika 会检查文件头 Magic Bytes，支持 1000+ 种文件类型
            byte[] sample = fileBytes;
            if (fileBytes.length > 64 * 1024) {
                // 只检测前64KB，避免处理大文件
                sample = new byte[64 * 1024];
                System.arraycopy(fileBytes, 0, sample, 0, 64 * 1024);
            }

            String mimeType = tika.detect(sample);
            String extension = MimeTypeUtils.getExtensionFromMimeType(mimeType);

            if (StringUtils.isNotBlank(extension) && !".bin".equals(extension) && !"application/octet-stream".equals(mimeType)) {
                log.info("Tika检测到文件类型: ext={}, mimeType={}", extension, mimeType);
                return new FileDetectionResult(extension.substring(1), mimeType, "Tika");
            }
        } catch (Exception e) {
            log.debug("Tika检测失败: {}", e.getMessage());
        }

        return null;
    }

    /**
     * 从URL检测文件类型
     */
    private FileDetectionResult detectFromUrl(String url) {
        if (StringUtils.isBlank(url)) {
            return null;
        }
        try {
            URI uri = URI.create(url);
            String path = uri.getPath();
            if (StringUtils.isBlank(path)) {
                return null;
            }
            int lastSlash = path.lastIndexOf('/');
            String lastPart = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
            int dotIndex = lastPart.lastIndexOf('.');
            if (dotIndex > 0 && dotIndex < lastPart.length() - 1) {
                String ext = lastPart.substring(dotIndex + 1).toLowerCase();
                String mimeType = MimeTypeUtils.inferMimeTypeFromFileName("." + ext);
                return new FileDetectionResult(ext, mimeType, "URL");
            }
        } catch (Exception e) {
            // 忽略异常
        }
        return null;
    }
}
