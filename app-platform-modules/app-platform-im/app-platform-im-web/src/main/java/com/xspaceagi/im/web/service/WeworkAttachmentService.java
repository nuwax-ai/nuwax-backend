package com.xspaceagi.im.web.service;

import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.agent.core.adapter.dto.AttachmentDto;
import com.xspaceagi.im.web.dto.ImUploadResultDto;
import com.xspaceagi.im.web.dto.WeworkAttachmentResultDto;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.tika.Tika;

/**
 * 企业微信附件服务：从企业微信消息中下载附件，通过 FileUploadService 上传到项目存储，返回可访问的 URL。
 * 参考企业微信文档：https://developer.work.weixin.qq.com/document/path/90237
 */
@Slf4j
@Service
public class WeworkAttachmentService {

    @Resource
    private ImFileUploadService fileUploadService;

    // 兜底：当 magic bytes/启发式都失败时，使用 Tika 根据内容推测 mime。
    private final Tika tika = new Tika();

    /**
     * 从企业微信消息中下载附件并上传到项目存储
     *
     * @param corpId    企业微信企业ID
     * @param corpSecret 企业微信应用密钥
     * @param mediaId   附件 media_id（图片、文件等）
     * @param type      附件类型："image" 或 "file"
     * @param tenantId  租户ID
     * @param tenantConfig 租户配置
     * @return 上传成功的附件列表 + 不支持的附件列表
     */
    public WeworkAttachmentResultDto downloadAndUpload(String corpId, String corpSecret, String mediaId,
                                                       String type, Long tenantId, TenantConfigDto tenantConfig) {
        WeworkAttachmentResultDto result = new WeworkAttachmentResultDto();

        if (StringUtils.isBlank(mediaId)) {
            return result;
        }

        try {
            // 1. 获取 access_token
            String accessToken = getAccessToken(corpId, corpSecret);
            if (StringUtils.isBlank(accessToken)) {
                log.warn("企业微信获取access_token失败: corpId={}", corpId);
                result.getUnsupportedKeys().add(mediaId);
                return result;
            }

            // 2. 下载附件
            byte[] fileBytes = downloadMedia(accessToken, mediaId);
            if (fileBytes == null || fileBytes.length == 0) {
                log.warn("企业微信附件下载失败: mediaId={}, type={}", mediaId, type);
                result.getUnsupportedKeys().add(mediaId);
                return result;
            }

            log.info("企业微信附件下载成功: mediaId={}, type={}, size={}", mediaId, type, fileBytes.length);

            // 3. 检测实际文件类型（基于文件头 Magic Bytes）
            String detectedExtension = detectImageFormat(fileBytes);
            if (StringUtils.isBlank(detectedExtension)) {
                detectedExtension = detectFileExtension(fileBytes);
            }
            if (StringUtils.isBlank(detectedExtension)) {
                // 如果无法检测，使用类型推断
                detectedExtension = switch (type) {
                    case "image" -> "jpg";
                    case "voice" -> "amr";
                    case "video" -> "mp4";
                    case "file" -> "bin";
                    default -> "dat";
                };
            }
            log.info("检测到文件格式: extension={}, originalType={}", detectedExtension, type);

            // 4. 生成文件名和 Content-Type
            String fileName = mediaId + "." + detectedExtension;
            String contentType = getContentType(detectedExtension);
            log.info("使用文件名: {}, Content-Type: {}", fileName, contentType);

            ImUploadResultDto uploadResult = fileUploadService.uploadBytes(
                    fileBytes, fileName, contentType, "wework/attachments", tenantConfig);

            if (uploadResult != null && StringUtils.isNotBlank(uploadResult.getUrl())) {
                AttachmentDto dto = new AttachmentDto();
                dto.setFileKey(mediaId);
                dto.setFileUrl(uploadResult.getUrl());
                dto.setFileName(uploadResult.getFileName());
                dto.setMimeType(uploadResult.getMimeType());
                result.getAttachments().add(dto);
                log.info("企业微信附件上传成功: mediaId={}, url={}, uploadFileName={}, uploadMimeType={}",
                        mediaId, uploadResult.getUrl(), uploadResult.getFileName(), uploadResult.getMimeType());
            } else {
                log.warn("企业微信附件上传失败: mediaId={}", mediaId);
                result.getUnsupportedKeys().add(mediaId);
            }

        } catch (Exception e) {
            log.error("企业微信附件处理异常: mediaId={}", mediaId, e);
            result.getUnsupportedKeys().add(mediaId);
        }

        return result;
    }

    /**
     * 从 URL 下载附件并上传到项目存储
     * 企业微信智能机器人直接提供附件 URL（临时签名 URL）
     *
     * @param url          附件 URL
     * @param type         附件类型："image" 或 "file"
     * @param aesKey       解密密钥（Base64 编码的 AES Key）
     * @param tenantId     租户ID
     * @param tenantConfig 租户配置
     * @return 上传成功的附件列表 + 不支持的附件列表
     */
    public WeworkAttachmentResultDto downloadAndUploadFromUrl(String url, String type, String aesKey, Long tenantId, TenantConfigDto tenantConfig) {
        WeworkAttachmentResultDto result = new WeworkAttachmentResultDto();

        if (StringUtils.isBlank(url)) {
            return result;
        }

        try {
            // 1. 从 URL 下载附件（加密数据）
            DownloadResult downloadResult = downloadFromUrl(url);
            byte[] encryptedBytes = downloadResult != null ? downloadResult.bytes : null;
            if (encryptedBytes == null || encryptedBytes.length == 0) {
                log.warn("企业微信附件下载失败: url={}, type={}", url, type);
                result.getUnsupportedKeys().add(url);
                return result;
            }

            String headerContentType = downloadResult != null ? downloadResult.contentType : null;
            String headerFileName = downloadResult != null ? downloadResult.fileName : null;
            String headerExtension = extractExtensionFromFileName(headerFileName);

            log.info("企业微信加密附件下载成功: url={}, type={}, size={}", url, type, encryptedBytes.length);
            log.info("加密数据前16字节: {}", bytesToHex(encryptedBytes, Math.min(16, encryptedBytes.length)));

            // 2. 尝试解密附件数据（如果可能）
            byte[] fileBytes = null;  // 解密后的原始内容
            boolean useOriginalUrl = false;
            try {
                // 企业微信智能机器人的图片可能是加密的，但也可能只是特殊格式
                // 先尝试解密
                fileBytes = decryptWeworkData(encryptedBytes, aesKey);
                log.info("企业微信附件解密成功: 原始大小={}, 解密后大小={}", encryptedBytes.length, fileBytes.length);
                log.info("解密后前16字节: {}", bytesToHex(fileBytes, Math.min(16, fileBytes.length)));

                // 只有图片附件才需要校验“是否真的是图片”
                if ("image".equals(type) && !isValidImageData(fileBytes)) {
                    log.warn("解密后的数据不是有效图片，将使用原始 URL: url={}", url);
                    useOriginalUrl = true;
                }
            } catch (Exception e) {
                log.warn("企业微信附件解密失败: url={}, 错误: {}", url, e.getMessage());
                // 有些文件（比如普通附件）可能本身并不需要解密；
                // 解密失败时，尝试把加密字节当成明文继续识别与上传（仅 type=file 才这样做）。
                if ("file".equals(type)) {
                    fileBytes = encryptedBytes;
                    useOriginalUrl = false;
                } else {
                    useOriginalUrl = true;
                }
            }

            if (fileBytes == null) {
                useOriginalUrl = true;
            }

            // 如果解密失败或数据无效，直接使用企业微信的原始 URL
            if (useOriginalUrl) {
                log.info("使用企业微信原始 URL（不解密）: {}", url);
                String fallbackExtension = StringUtils.isNotBlank(headerExtension) ? headerExtension : getExtensionFromUrl(url);
                if (StringUtils.isBlank(fallbackExtension)) {
                    fallbackExtension = "image".equals(type) ? "jpg" : "bin";
                }
                String fallbackFileName = generateFileName(url, fallbackExtension);
                String fallbackMimeType = getContentType(fallbackExtension);

                AttachmentDto dto = new AttachmentDto();
                dto.setFileKey(url);
                dto.setFileUrl(url);  // 直接使用企业微信的 URL
                dto.setFileName(fallbackFileName);
                dto.setMimeType(fallbackMimeType);
                result.getAttachments().add(dto);
                return result;
            }

            // 3. 检测实际文件类型（基于文件头 Magic Bytes）
            String detectedExtension = detectImageFormat(fileBytes);
            if (StringUtils.isBlank(detectedExtension)) {
                detectedExtension = detectFileExtension(fileBytes);
            }
            if (StringUtils.isBlank(detectedExtension)) {
                detectedExtension = StringUtils.isNotBlank(headerExtension) ? headerExtension : getExtensionFromUrl(url);
            }
            if (StringUtils.isBlank(detectedExtension) && StringUtils.isNotBlank(headerContentType)) {
                detectedExtension = mapMimeTypeToExtension(headerContentType);
            }
            if (StringUtils.isBlank(detectedExtension)) {
                // 如果无法检测，使用类型推断
                detectedExtension = switch (type) {
                    case "image" -> "jpg";
                    case "voice" -> "amr";
                    case "video" -> "mp4";
                    case "file" -> "bin";
                    default -> "dat";
                };
            }
            log.info("检测到文件格式: extension={}, originalType={}", detectedExtension, type);

            // 3. 生成文件名和 Content-Type
            String fileName = generateFileName(url, detectedExtension);
            String contentType = getContentType(detectedExtension);
            log.info("使用文件名: {}, Content-Type: {}", fileName, contentType);

            // 4. 上传到项目存储
            ImUploadResultDto uploadResult = fileUploadService.uploadBytes(
                    fileBytes, fileName, contentType, "wework/attachments", tenantConfig);

            if (uploadResult != null && StringUtils.isNotBlank(uploadResult.getUrl())) {
                AttachmentDto dto = new AttachmentDto();
                dto.setFileKey(url);
                dto.setFileUrl(uploadResult.getUrl());
                dto.setFileName(uploadResult.getFileName());
                dto.setMimeType(uploadResult.getMimeType());
                result.getAttachments().add(dto);
                log.info("企业微信附件上传成功: url={}, finalUrl={}, uploadFileName={}, uploadMimeType={}",
                        url, uploadResult.getUrl(), uploadResult.getFileName(), uploadResult.getMimeType());
            } else {
                log.warn("企业微信附件上传失败: url={}", url);
                result.getUnsupportedKeys().add(url);
            }

        } catch (Exception e) {
            log.error("企业微信附件处理异常: url={}", url, e);
            result.getUnsupportedKeys().add(url);
        }

        return result;
    }

    /**
     * 从 URL 下载文件内容
     */
    private static class DownloadResult {
        private byte[] bytes;
        private String contentType;
        private String fileName;
    }

    /**
     * 从 URL 下载文件内容，并尽量返回 HTTP 头信息作为辅助识别。
     */
    private DownloadResult downloadFromUrl(String url) {
        try {
            RestTemplate restTemplate = new RestTemplate();
            restTemplate.getMessageConverters().add(new org.springframework.http.converter.ByteArrayHttpMessageConverter());

            // 使用 URI 避免重复编码（企业微信URL已经包含编码的签名参数）
            java.net.URI uri = java.net.URI.create(url);

            // 设置请求头，避免被COS服务器拒绝
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            headers.set("Accept", "*/*");

            HttpEntity<?> entity = new HttpEntity<>(headers);

            ResponseEntity<byte[]> response = restTemplate.exchange(
                    uri,
                    HttpMethod.GET,
                    entity,
                    byte[].class
            );

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                DownloadResult result = new DownloadResult();
                result.bytes = response.getBody();
                result.contentType = response.getHeaders() != null ? (response.getHeaders().getContentType() != null
                        ? response.getHeaders().getContentType().toString()
                        : response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)) : null;

                String contentDisposition = response.getHeaders() != null ? response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION) : null;
                result.fileName = extractFileNameFromContentDisposition(contentDisposition);
                return result;
            }
        } catch (Exception e) {
            log.error("下载企业微信附件异常: url={}", url, e);
        }
        return null;
    }

    /**
     * 基于 Magic Bytes 检测图片格式
     * 返回文件扩展名（不带点）：jpg, png, gif, webp, bmp 等
     */
    private String detectImageFormat(byte[] fileBytes) {
        if (fileBytes == null || fileBytes.length < 8) {
            return null;
        }

        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (fileBytes.length > 8
                && (fileBytes[0] & 0xFF) == 0x89
                && fileBytes[1] == 0x50
                && fileBytes[2] == 0x4E
                && fileBytes[3] == 0x47
                && fileBytes[4] == 0x0D
                && fileBytes[5] == 0x0A
                && fileBytes[6] == 0x1A
                && fileBytes[7] == 0x0A) {
            log.info("检测到 PNG 格式");
            return "png";
        }

        // JPEG: FF D8 FF
        if (fileBytes.length > 3
                && (fileBytes[0] & 0xFF) == 0xFF
                && (fileBytes[1] & 0xFF) == 0xD8
                && (fileBytes[2] & 0xFF) == 0xFF) {
            log.info("检测到 JPEG 格式");
            return "jpg";
        }

        // GIF: GIF87a 或 GIF89a
        if (fileBytes.length > 6
                && fileBytes[0] == 0x47
                && fileBytes[1] == 0x49
                && fileBytes[2] == 0x46
                && fileBytes[3] == 0x38
                && (fileBytes[4] == 0x37 || fileBytes[4] == 0x39)
                && fileBytes[5] == 0x61) {
            log.info("检测到 GIF 格式");
            return "gif";
        }

        // WebP: RIFF....WEBP
        if (fileBytes.length > 12
                && fileBytes[0] == 0x52
                && fileBytes[1] == 0x49
                && fileBytes[2] == 0x46
                && fileBytes[3] == 0x46
                && fileBytes[8] == 0x57
                && fileBytes[9] == 0x45
                && fileBytes[10] == 0x42
                && fileBytes[11] == 0x50) {
            log.info("检测到 WebP 格式");
            return "webp";
        }

        // BMP: BM
        if (fileBytes.length > 2
                && fileBytes[0] == 0x42
                && fileBytes[1] == 0x4D) {
            log.info("检测到 BMP 格式");
            return "bmp";
        }

        log.warn("无法检测图片格式，文件头: {} {} {} {}",
                String.format("%02X", fileBytes[0]),
                String.format("%02X", fileBytes[1]),
                String.format("%02X", fileBytes[2]),
                String.format("%02X", fileBytes[3]));
        return null;
    }

    /**
     * 基于 Magic Bytes 检测常见“非图片”文件类型扩展名（如 pdf/docx/xlsx/pptx）。
     */
    private String detectFileExtension(byte[] fileBytes) {
        if (fileBytes == null || fileBytes.length < 4) {
            return null;
        }

        // PDF: %PDF-
        if (fileBytes.length > 5
                && fileBytes[0] == '%' && fileBytes[1] == 'P'
                && fileBytes[2] == 'D' && fileBytes[3] == 'F'
                && fileBytes[4] == '-') {
            return "pdf";
        }

        // =======================
        // 音频/视频（常见文件头）
        // =======================
        // MP3: ID3
        if (fileBytes.length > 3
                && fileBytes[0] == 'I' && fileBytes[1] == 'D'
                && fileBytes[2] == '3') {
            return "mp3";
        }

        // WAV: RIFF....WAVE
        if (fileBytes.length > 12
                && fileBytes[0] == 'R' && fileBytes[1] == 'I'
                && fileBytes[2] == 'F' && fileBytes[3] == 'F'
                && fileBytes[8] == 'W' && fileBytes[9] == 'A'
                && fileBytes[10] == 'V' && fileBytes[11] == 'E') {
            return "wav";
        }

        // OGG: OggS
        if (fileBytes.length > 4
                && fileBytes[0] == 'O' && fileBytes[1] == 'g'
                && fileBytes[2] == 'g' && fileBytes[3] == 'S') {
            return "ogg";
        }

        // FLAC: fLaC
        if (fileBytes.length > 4
                && fileBytes[0] == 'f' && fileBytes[1] == 'L'
                && fileBytes[2] == 'a' && fileBytes[3] == 'C') {
            return "flac";
        }

        // MP4/M4A: ....ftyp....
        if (fileBytes.length > 12
                && fileBytes[4] == 'f' && fileBytes[5] == 't'
                && fileBytes[6] == 'y' && fileBytes[7] == 'p') {
            // major brand（offset 8~11）
            if (fileBytes.length > 11
                    && fileBytes[8] == 'M' && fileBytes[9] == '4'
                    && fileBytes[10] == 'A') {
                return "m4a";
            }
            return "mp4";
        }

        // Matroska/WEBM: 1A 45 DF A3
        if (fileBytes.length > 4
                && (fileBytes[0] & 0xFF) == 0x1A
                && (fileBytes[1] & 0xFF) == 0x45
                && (fileBytes[2] & 0xFF) == 0xDF
                && (fileBytes[3] & 0xFF) == 0xA3) {
            int scanLen = Math.min(fileBytes.length, 1024 * 1024);
            try {
                String head = new String(fileBytes, 0, scanLen, StandardCharsets.ISO_8859_1).toLowerCase();
                if (head.contains("webm")) {
                    return "webm";
                }
            } catch (Exception ignored) {
                // ignore
            }
            return "mkv";
        }

        // =======================
        // 压缩/归档（常见文件头）
        // =======================
        // GZIP: 1F 8B
        if ((fileBytes[0] & 0xFF) == 0x1F && (fileBytes[1] & 0xFF) == 0x8B) {
            return "gz";
        }

        // BZIP2: 42 5A 68
        if (fileBytes.length > 3
                && fileBytes[0] == 'B' && fileBytes[1] == 'Z'
                && fileBytes[2] == 'h') {
            return "bz2";
        }

        // Zstandard: 28 B5 2F FD
        if (fileBytes.length > 3
                && (fileBytes[0] & 0xFF) == 0x28
                && (fileBytes[1] & 0xFF) == 0xB5
                && (fileBytes[2] & 0xFF) == 0x2F
                && (fileBytes[3] & 0xFF) == 0xFD) {
            return "zst";
        }

        // TAR: 257..262 == "ustar"
        if (fileBytes.length > 262) {
            if (fileBytes[257] == 'u' && fileBytes[258] == 's'
                    && fileBytes[259] == 't' && fileBytes[260] == 'a'
                    && fileBytes[261] == 'r') {
                return "tar";
            }
        }

        // 7z: 37 7A BC AF 27 1C
        if (fileBytes.length > 5
                && (fileBytes[0] & 0xFF) == 0x37
                && (fileBytes[1] & 0xFF) == 0x7A
                && (fileBytes[2] & 0xFF) == 0xBC
                && (fileBytes[3] & 0xFF) == 0xAF
                && (fileBytes[4] & 0xFF) == 0x27
                && (fileBytes[5] & 0xFF) == 0x1C) {
            return "7z";
        }

        // RAR: Rar!
        if (fileBytes.length > 4
                && fileBytes[0] == 'R' && fileBytes[1] == 'a'
                && fileBytes[2] == 'r' && fileBytes[3] == '!') {
            return "rar";
        }

        // OLE Compound File Binary Format: D0 CF 11 E0 A1 B1 1A E1
        if ((fileBytes[0] & 0xFF) == 0xD0
                && (fileBytes[1] & 0xFF) == 0xCF
                && (fileBytes[2] & 0xFF) == 0x11
                && (fileBytes[3] & 0xFF) == 0xE0
                && (fileBytes[4] & 0xFF) == 0xA1
                && (fileBytes[5] & 0xFF) == 0xB1
                && (fileBytes[6] & 0xFF) == 0x1A
                && (fileBytes[7] & 0xFF) == 0xE1) {
            return detectOleOfficeExtension(fileBytes);
        }

        // ZIP（通常是 docx/xlsx/pptx 的容器）：PK..
        if (fileBytes.length > 4 && fileBytes[0] == 'P' && fileBytes[1] == 'K') {
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(fileBytes))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (name == null) continue;
                    if (name.equals("word/document.xml") || (name.startsWith("word/") && name.endsWith("document.xml"))) {
                        return "docx";
                    }
                    if (name.equals("xl/workbook.xml") || (name.startsWith("xl/") && name.endsWith("workbook.xml"))) {
                        return "xlsx";
                    }
                    if (name.equals("ppt/presentation.xml") || (name.startsWith("ppt/") && name.endsWith("presentation.xml"))) {
                        return "pptx";
                    }
                }
            } catch (Exception e) {
                log.debug("detectFileExtension zip scan failed: {}", e.getMessage());
            }
            return "zip";
        }

        // 纯文本兜底：很多企业微信 file 回调（例如 txt）解密后就是可读文本。
        String textExt = detectTextExtension(fileBytes);
        if (textExt != null) {
            return textExt;
        }

        // 最后兜底：Tika detect（可选）
        try {
            byte[] sample = Arrays.copyOf(fileBytes, Math.min(fileBytes.length, 64 * 1024));
            String mime = tika.detect(sample);
            String ext = mapMimeTypeToExtension(mime);
            if (StringUtils.isNotBlank(ext)) {
                return ext;
            }
        } catch (Exception ignored) {
            // ignore
        }

        return null;
    }

    private String detectOleOfficeExtension(byte[] fileBytes) {
        int scanLen = Math.min(fileBytes.length, 1024 * 1024);
        String head;
        try {
            head = new String(fileBytes, 0, scanLen, StandardCharsets.ISO_8859_1).toLowerCase();
        } catch (Exception e) {
            return "doc";
        }

        if (head.contains("xl/") || head.contains("workbook") || head.contains("worksheet")) {
            return "xls";
        }
        if (head.contains("ppt/") || head.contains("powerpoint") || head.contains("slide")) {
            return "ppt";
        }
        return "doc";
    }

    private String detectTextExtension(byte[] fileBytes) {
        if (fileBytes == null || fileBytes.length == 0) {
            return null;
        }

        String s;
        try {
            s = new String(fileBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }

        // 快速排除：如果出现大量替换字符（通常表示不是有效 UTF-8），就当二进制
        int replacementCount = 0;
        int controlCount = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\uFFFD') {
                replacementCount++;
                continue;
            }
            // 允许常见换行/制表
            if (c == '\n' || c == '\r' || c == '\t') {
                continue;
            }
            // 非可打印控制字符直接认为不是文本
            if (c < 0x20 || c == 0x7F) {
                controlCount++;
            }
        }

        int len = Math.max(1, s.length());
        double replacementRatio = replacementCount / (double) len;
        double controlRatio = controlCount / (double) len;

        if (replacementRatio > 0.02 || controlRatio > 0.02) {
            return null;
        }

        String trimmed = s.trim();
        if (trimmed.isEmpty()) {
            return "txt";
        }

        // 简单代码文件启发式：只在明确为脚本入口（shebang）时才猜测后缀
        if (trimmed.startsWith("#!")) {
            String lower = trimmed.toLowerCase();
            if (lower.contains("python")) return "py";
            if (lower.contains("bash") || lower.contains("sh") || lower.contains("zsh")) return "sh";
            if (lower.contains("node") || lower.contains("deno")) return "js";
            if (lower.contains("typescript") || lower.contains("ts-node")) return "ts";
            if (lower.contains("perl")) return "pl";
            if (lower.contains("ruby")) return "rb";
            if (lower.contains("php")) return "php";
            return "txt";
        }

        // JSON
        if ((trimmed.startsWith("{") && trimmed.endsWith("}"))
                || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            return "json";
        }

        // XML
        if (trimmed.startsWith("<") && trimmed.contains(">")) {
            // 简单区分：如果看起来更像 HTML，也按 xml/xml 对待
            return "xml";
        }

        // Markdown
        if (trimmed.startsWith("```")
                || trimmed.startsWith("#")
                || trimmed.startsWith("- ")
                || trimmed.startsWith("* ")
                || trimmed.startsWith("> ")) {
            return "md";
        }

        // 默认文本
        return "txt";
    }

    private String getExtensionFromUrl(String url) {
        if (StringUtils.isBlank(url)) {
            return null;
        }
        try {
            java.net.URI uri = java.net.URI.create(url);
            String path = uri.getPath();
            if (StringUtils.isBlank(path)) {
                return null;
            }
            int lastSlash = path.lastIndexOf('/');
            String lastPart = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
            int dotIndex = lastPart.lastIndexOf('.');
            if (dotIndex <= 0 || dotIndex >= lastPart.length() - 1) {
                return null;
            }
            return lastPart.substring(dotIndex + 1).toLowerCase();
        } catch (Exception e) {
            return null;
        }
    }

    private String extractExtensionFromFileName(String fileName) {
        if (StringUtils.isBlank(fileName)) {
            return null;
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex <= 0 || dotIndex >= fileName.length() - 1) {
            return null;
        }
        return fileName.substring(dotIndex + 1).toLowerCase();
    }

    private String extractFileNameFromContentDisposition(String contentDisposition) {
        if (StringUtils.isBlank(contentDisposition)) {
            return null;
        }
        String cd = contentDisposition.trim();
        String lower = cd.toLowerCase();

        // filename*=UTF-8''xxx
        int idxStar = lower.indexOf("filename*=");
        String filePart = null;
        if (idxStar >= 0) {
            filePart = cd.substring(idxStar + "filename*=".length()).trim();
        } else {
            // filename=xxx
            int idx = lower.indexOf("filename=");
            if (idx < 0) {
                return null;
            }
            filePart = cd.substring(idx + "filename=".length()).trim();
        }

        // 截断掉后续参数
        int semicolon = filePart.indexOf(';');
        if (semicolon >= 0) {
            filePart = filePart.substring(0, semicolon).trim();
        }

        // 去掉引号
        if (filePart.startsWith("\"") && filePart.endsWith("\"") && filePart.length() >= 2) {
            filePart = filePart.substring(1, filePart.length() - 1);
        }

        // 去掉 RFC 5987 前缀：UTF-8''xxx
        int dd = filePart.indexOf("''");
        if (dd >= 0 && dd + 2 < filePart.length()) {
            filePart = filePart.substring(dd + 2);
        }

        try {
            // 尝试解码 percent-encoding
            return java.net.URLDecoder.decode(filePart, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return filePart;
        }
    }

    /**
     * 根据 Content-Type 映射常见扩展名（不依赖 Tika）。
     */
    private String mapMimeTypeToExtension(String contentType) {
        if (StringUtils.isBlank(contentType)) {
            return null;
        }
        String mime = contentType.toLowerCase();
        int semicolon = mime.indexOf(';');
        if (semicolon >= 0) {
            mime = mime.substring(0, semicolon).trim();
        }
        return switch (mime) {
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "image/bmp" -> "bmp";
            case "image/svg+xml" -> "svg";

            case "application/pdf" -> "pdf";

            case "application/msword" -> "doc";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx";

            case "application/vnd.ms-excel" -> "xls";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx";

            case "application/vnd.ms-powerpoint" -> "ppt";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx";

            case "application/zip", "application/x-zip-compressed" -> "zip";
            case "application/vnd.rar" -> "rar";
            case "application/x-7z-compressed" -> "7z";
            case "application/gzip" -> "gz";
            case "application/x-tar" -> "tar";
            case "application/x-bzip2" -> "bz2";
            case "application/zstd" -> "zst";

            case "text/plain", "text/rtf" -> "txt";
            // Code / script (常见 Content-Type -> 后缀)
            case "text/x-python" -> "py";
            case "text/x-shellscript" -> "sh";
            case "application/javascript", "text/javascript" -> "js";
            case "application/typescript", "text/typescript" -> "ts";
            case "text/x-perl" -> "pl";
            case "text/x-ruby" -> "rb";
            case "application/x-httpd-php" -> "php";
            case "text/x-java-source" -> "java";
            case "text/x-go" -> "go";
            case "text/x-rust" -> "rs";
            case "text/x-c" -> "c";
            case "text/x-c++src" -> "cpp";
            case "text/x-csharp" -> "cs";
            case "text/x-kotlin" -> "kt";
            case "text/x-swift" -> "swift";
            case "text/x-scala" -> "scala";
            case "application/sql" -> "sql";
            case "text/csv" -> "csv";
            case "application/json" -> "json";
            case "application/xml", "text/xml" -> "xml";
            case "text/html" -> "html";
            case "text/markdown" -> "md";

            case "audio/mpeg" -> "mp3";
            case "audio/wav" -> "wav";
            case "audio/ogg" -> "ogg";
            case "audio/flac" -> "flac";
            case "audio/mp4" -> "m4a";
            case "video/mp4" -> "mp4";
            case "video/webm" -> "webm";
            case "video/x-matroska" -> "mkv";

            default -> null;
        };
    }

    /**
     * 从 URL 生成文件名
     */
    private String generateFileName(String url, String extension) {
        String filename = "wework_attachment";
        try {
            String[] parts = url.split("/");
            if (parts.length > 0) {
                String lastPart = parts[parts.length - 1];
                // 移除查询参数
                int queryIndex = lastPart.indexOf('?');
                if (queryIndex > 0) {
                    lastPart = lastPart.substring(0, queryIndex);
                }
                if (StringUtils.isNotBlank(lastPart)) {
                    // 移除现有扩展名（如果有）
                    int dotIndex = lastPart.lastIndexOf('.');
                    if (dotIndex > 0) {
                        filename = lastPart.substring(0, dotIndex);
                    } else {
                        filename = lastPart;
                    }
                }
            }
        } catch (Exception e) {
            // 忽略异常，使用默认文件名
        }

        // 添加检测到的扩展名
        return filename + "." + extension;
    }

    /**
     * 根据扩展名获取 Content-Type
     */
    private String getContentType(String extension) {
        if (StringUtils.isBlank(extension)) {
            return "application/octet-stream";
        }
        return switch (extension.toLowerCase()) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            case "svg" -> "image/svg+xml";
            case "pdf" -> "application/pdf";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls" -> "application/vnd.ms-excel";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "ppt" -> "application/vnd.ms-powerpoint";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "csv" -> "text/csv";
            case "json" -> "application/json";
            case "xml" -> "application/xml";
            case "html", "htm" -> "text/html";
            case "md", "markdown" -> "text/markdown";
            // Code / script
            case "py" -> "text/x-python";
            case "sh" -> "text/x-shellscript";
            case "js" -> "application/javascript";
            case "ts" -> "application/typescript";
            case "pl" -> "text/x-perl";
            case "rb" -> "text/x-ruby";
            case "php" -> "application/x-httpd-php";
            case "java" -> "text/x-java-source";
            case "go" -> "text/x-go";
            case "rs" -> "text/x-rust";
            case "c", "h" -> "text/x-c";
            case "cc", "cpp", "hpp" -> "text/x-c++src";
            case "cs" -> "text/x-csharp";
            case "kt" -> "text/x-kotlin";
            case "swift" -> "text/x-swift";
            case "scala" -> "text/x-scala";
            case "sql" -> "application/sql";
            case "txt" -> "text/plain";
            case "log" -> "text/plain";
            case "rtf" -> "application/rtf";
            case "zip" -> "application/zip";
            case "rar" -> "application/vnd.rar";
            case "7z" -> "application/x-7z-compressed";
            case "gz" -> "application/gzip";
            case "tar" -> "application/x-tar";
            case "bz2" -> "application/x-bzip2";
            case "zst" -> "application/zstd";
            case "ogg" -> "audio/ogg";
            case "flac" -> "audio/flac";
            case "m4a" -> "audio/mp4";
            case "webm" -> "video/webm";
            case "mkv" -> "video/x-matroska";
            case "mp4" -> "video/mp4";
            case "mp3" -> "audio/mpeg";
            case "amr" -> "audio/amr";
            case "wav" -> "audio/wav";
            default -> "application/octet-stream";
        };
    }

    /**
     * 获取企业微信 access_token
     */
    private String getAccessToken(String corpId, String corpSecret) {
        try {
            String url = "https://qyapi.weixin.qq.com/cgi-bin/gettoken?corpid=" + corpId + "&corpsecret=" + corpSecret;
            RestTemplate restTemplate = new RestTemplate();
            String response = restTemplate.getForObject(url, String.class);

            if (StringUtils.isNotBlank(response)) {
                // 解析 JSON 响应：{"errcode":0,"errmsg":"ok","access_token":"xxx","expires_in":7200}
                JSONObject json = parseJson(response);
                if (json != null && json.getIntValue("errcode") == 0) {
                    return json.getString("access_token");
                }
            }
        } catch (Exception e) {
            log.error("获取企业微信access_token异常", e);
        }
        return null;
    }

    /**
     * 下载企业微信临时素材
     */
    private byte[] downloadMedia(String accessToken, String mediaId) {
        try {
            String url = "https://qyapi.weixin.qq.com/cgi-bin/media/get?access_token=" + accessToken + "&media_id=" + mediaId;

            RestTemplate restTemplate = new RestTemplate();
            restTemplate.getMessageConverters().add(new org.springframework.http.converter.ByteArrayHttpMessageConverter());

            ResponseEntity<byte[]> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    null,
                    byte[].class
            );

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return response.getBody();
            }
        } catch (Exception e) {
            log.error("下载企业微信附件异常: mediaId={}", mediaId, e);
        }
        return null;
    }

    /**
     * 简单的 JSON 解析（避免引入额外依赖）
     */
    private JSONObject parseJson(String json) {
        try {
            return com.alibaba.fastjson2.JSON.parseObject(json);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解密企业微信附件数据
     * 使用 AES-256-CBC 模式
     * 根据官方文档：IV 取 AESKey 的前16字节，填充方式为 PKCS#7
     */
    private byte[] decryptWeworkData(byte[] encryptedData, String aesKey) throws Exception {
        // 1. Base64 解码 AESKey（企业微信的 AESKey 长度为 43 字符，需要添加 = 填充）
        String paddedAesKey = aesKey;
        if (paddedAesKey.length() % 4 != 0) {
            paddedAesKey = paddedAesKey + "=".repeat(4 - (paddedAesKey.length() % 4));
        }
        byte[] keyBytes = Base64.getDecoder().decode(paddedAesKey);
        log.info("AES Key 解码成功，原始长度: {}, 解码后长度: {} 字节", aesKey.length(), keyBytes.length);
        log.info("AES Key (Hex): {}", bytesToHex(keyBytes, keyBytes.length));

        // 2. 提取 IV：**取 AESKey 的前16字节**（不是加密数据的前16字节！）
        byte[] iv = new byte[16];
        System.arraycopy(keyBytes, 0, iv, 0, 16);
        log.info("IV（取AESKey前16字节）: {}", bytesToHex(iv, 16));

        // 3. 所有加密数据都是密文（不需要跳过前16字节）
        byte[] cipherText = encryptedData;
        log.info("密文长度: {} 字节", cipherText.length);

        // 4. 先尝试 PKCS5Padding 解密
        try {
            byte[] result = decryptWithPadding(keyBytes, iv, cipherText, "AES/CBC/PKCS5Padding");
            log.info("PKCS5Padding 解密成功，解密后长度: {} 字节", result.length);
            log.info("解密后前16字节: {}", bytesToHex(result, Math.min(16, result.length)));
            return result;
        } catch (Exception e) {
            log.warn("PKCS5Padding 解密失败: {}，尝试 NoPadding 解密", e.getMessage());
            // 如果 PKCS5Padding 失败，尝试 NoPadding
            try {
                byte[] result = decryptWithNoPadding(keyBytes, iv, cipherText);
                log.info("NoPadding 解密成功，解密后长度: {} 字节", result.length);
                log.info("解密后前16字节: {}", bytesToHex(result, Math.min(16, result.length)));
                return result;
            } catch (Exception e2) {
                log.error("NoPadding 解密也失败: {}", e2.getMessage());
                throw e2;
            }
        }
    }

    /**
     * 使用指定填充方式解密
     */
    private byte[] decryptWithPadding(byte[] keyBytes, byte[] iv, byte[] cipherText, String transformation) throws Exception {
        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(iv);

        Cipher cipher = Cipher.getInstance(transformation);
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);

        return cipher.doFinal(cipherText);
    }

    /**
     * 备用解密方法：使用 NoPadding，避免填充错误
     * 解密后需要手动去除 PKCS#7 填充
     */
    private byte[] decryptWithNoPadding(byte[] keyBytes, byte[] iv, byte[] cipherText) throws Exception {
        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(iv);

        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);

        byte[] decrypted = cipher.doFinal(cipherText);

        // 手动去除 PKCS#7 填充
        // PKCS#7 填充：每个填充字节的值等于填充字节数
        if (decrypted.length > 0) {
            int padLength = decrypted[decrypted.length - 1] & 0xFF;
            if (padLength > 0 && padLength <= 16 && decrypted.length >= padLength) {
                // 验证填充是否正确
                boolean validPadding = true;
                for (int i = decrypted.length - padLength; i < decrypted.length; i++) {
                    if ((decrypted[i] & 0xFF) != padLength) {
                        validPadding = false;
                        break;
                    }
                }
                if (validPadding) {
                    byte[] unpadded = new byte[decrypted.length - padLength];
                    System.arraycopy(decrypted, 0, unpadded, 0, unpadded.length);
                    log.info("手动去除 PKCS#7 填充: {} 字节", padLength);
                    return unpadded;
                }
            }
        }

        return decrypted;
    }

    /**
     * 字节数组转十六进制字符串（用于调试）
     */
    private String bytesToHex(byte[] bytes, int length) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        int len = Math.min(length, bytes.length);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02X ", bytes[i]));
        }
        return sb.toString().trim();
    }

    /**
     * 检查数据是否是有效的图片格式
     */
    private boolean isValidImageData(byte[] data) {
        if (data == null || data.length < 8) {
            return false;
        }

        // 检查常见图片格式的 Magic Bytes
        // JPEG: FF D8 FF
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
            return true;
        }

        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (data.length > 8
                && (data[0] & 0xFF) == 0x89
                && data[1] == 0x50
                && data[2] == 0x4E
                && data[3] == 0x47
                && data[4] == 0x0D
                && data[5] == 0x0A
                && data[6] == 0x1A
                && data[7] == 0x0A) {
            return true;
        }

        // GIF: GIF87a 或 GIF89a
        if (data.length > 6
                && data[0] == 0x47
                && data[1] == 0x49
                && data[2] == 0x46
                && data[3] == 0x38
                && (data[4] == 0x37 || data[4] == 0x39)
                && data[5] == 0x61) {
            return true;
        }

        // WebP: RIFF....WEBP
        if (data.length > 12
                && data[0] == 0x52
                && data[1] == 0x49
                && data[2] == 0x46
                && data[3] == 0x46
                && data[8] == 0x57
                && data[9] == 0x45
                && data[10] == 0x42
                && data[11] == 0x50) {
            return true;
        }

        // BMP: BM
        if (data.length > 2
                && data[0] == 0x42
                && data[1] == 0x4D) {
            return true;
        }

        return false;
    }
}
