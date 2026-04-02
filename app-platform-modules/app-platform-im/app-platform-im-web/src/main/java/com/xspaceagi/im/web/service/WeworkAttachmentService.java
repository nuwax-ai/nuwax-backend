package com.xspaceagi.im.web.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.agent.core.adapter.dto.AttachmentDto;
import com.xspaceagi.im.infra.enums.ImChannelEnum;
import com.xspaceagi.im.web.dto.ImUploadResultDto;
import com.xspaceagi.im.web.dto.WeworkAttachmentResultDto;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.*;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

/**
 * 企业微信附件服务：从企业微信消息中下载附件，通过 FileUploadService 上传到项目存储，返回可访问的 URL。
 * 参考企业微信文档：https://developer.work.weixin.qq.com/document/path/90237
 */
@Slf4j
@Service
public class WeworkAttachmentService {

    @Resource
    private FileUploadHelperService fileUploadHelperService;

    private final RestTemplate restTemplate;

    public WeworkAttachmentService() {
        this.restTemplate = new RestTemplate();
        this.restTemplate.getMessageConverters().add(new ByteArrayHttpMessageConverter());
    }

    /**
     * 从企业微信消息中下载附件并上传到项目存储
     *
     * @param corpId    企业微信企业ID
     * @param corpSecret 企业微信应用密钥
     * @param mediaId   附件 media_id（图片、文件等）
     * @param type      附件类型："image" 或 "file"
     * @param tenantConfig 租户配置
     * @return 上传成功的附件列表 + 不支持的附件列表
     */
    public WeworkAttachmentResultDto downloadAndUpload(String corpId, String corpSecret, String mediaId,
                                                       String type, TenantConfigDto tenantConfig) {
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

            // 3. 使用统一服务检测文件类型并上传
            FileUploadHelperService.UploadResult uploadResult = fileUploadHelperService.detectAndUpload(
                    fileBytes,
                    null,                    // 无HTTP响应头文件名
                    null,                    // 无HTTP响应头Content-Type
                    null,                    // 无URL
                    type,                    // 原始类型
                    mediaId,                 // 默认文件名
                    ImChannelEnum.WEWORK,     // IM 渠道类型
                    tenantConfig
            );

            if (uploadResult.isSuccess()) {
                ImUploadResultDto imUploadResult = uploadResult.getUploadResult();
                AttachmentDto dto = fileUploadHelperService.createAttachmentDto(
                        mediaId,
                        imUploadResult.getUrl(),
                        imUploadResult.getFileName(),
                        imUploadResult.getMimeType()
                );
                result.getAttachments().add(dto);
                log.info("企业微信附件处理成功: mediaId={}, url={}, detectionSource={}",
                        mediaId, dto.getFileUrl(), uploadResult.getDetection().getDetectionSource());
            } else {
                log.warn("企业微信附件处理失败: mediaId={}, error={}", mediaId, uploadResult.getErrorMessage());
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
     * @param tenantConfig 租户配置
     * @return 上传成功的附件列表 + 不支持的附件列表
     */
    public WeworkAttachmentResultDto downloadAndUploadFromUrl(String url, String type, String aesKey, TenantConfigDto tenantConfig) {
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

            log.info("企业微信加密附件下载成功: url={}, type={}, size={}", url, type, encryptedBytes.length);
            log.info("加密数据前16字节: {}", bytesToHex(encryptedBytes, Math.min(16, encryptedBytes.length)));

            // 2. 尝试解密附件数据（如果可能）
            byte[] fileBytes = null;
            try {
                // 企业微信智能机器人的图片可能是加密的，但也可能只是特殊格式
                // 先尝试解密
                fileBytes = decryptWeworkData(encryptedBytes, aesKey);
                log.info("企业微信附件解密成功: 原始大小={}, 解密后大小={}", encryptedBytes.length, fileBytes.length);
                log.info("解密后前16字节: {}", bytesToHex(fileBytes, Math.min(16, fileBytes.length)));
            } catch (Exception e) {
                log.warn("企业微信附件解密失败: url={}, 错误: {}", url, e.getMessage());
                // 有些文件（比如普通附件）可能本身并不需要解密；
                // 解密失败时，尝试把加密字节当成明文继续识别与上传（仅 type=file 才这样做）。
                if ("file".equals(type)) {
                    fileBytes = encryptedBytes;
                }
            }

            // 如果解密失败或数据为空，直接返回失败
            if (fileBytes == null || fileBytes.length == 0) {
                log.warn("企业微信附件解密后数据为空: url={}", url);
                result.getUnsupportedKeys().add(url);
                return result;
            }

            // 3. 使用统一服务检测文件类型并上传
            FileUploadHelperService.UploadResult uploadResult = fileUploadHelperService.detectAndUpload(
                    fileBytes,
                    headerFileName,       // Content-Disposition中的文件名
                    headerContentType,    // Content-Type
                    url,                  // 文件URL
                    type,                 // 原始类型
                    "wework_attachment",  // 默认文件名
                    ImChannelEnum.WEWORK,  // IM 渠道类型
                    tenantConfig
            );

            if (uploadResult.isSuccess()) {
                ImUploadResultDto imUploadResult = uploadResult.getUploadResult();
                AttachmentDto dto = fileUploadHelperService.createAttachmentDto(
                        url,
                        imUploadResult.getUrl(),
                        imUploadResult.getFileName(),
                        imUploadResult.getMimeType()
                );
                result.getAttachments().add(dto);
                log.info("企业微信附件处理成功: url={}, finalUrl={}, detectionSource={}",
                        url, dto.getFileUrl(), uploadResult.getDetection().getDetectionSource());
            } else {
                log.warn("企业微信附件处理失败: url={}, error={}", url, uploadResult.getErrorMessage());
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

            // 使用 URI 避免重复编码（企业微信URL已经包含编码的签名参数）
            URI uri = URI.create(url);

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
     * 获取企业微信 access_token
     */
    private String getAccessToken(String corpId, String corpSecret) {
        try {
            String url = "https://qyapi.weixin.qq.com/cgi-bin/gettoken?corpid=" + corpId + "&corpsecret=" + corpSecret;
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
            return JSON.parseObject(json);
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

}