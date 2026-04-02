package com.xspaceagi.im.web.service;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.region.Region;
import com.xspaceagi.im.web.dto.ImUploadResultDto;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.spec.utils.FileAkUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * 文件上传服务
 */
@Slf4j
@Service
public class ImFileUploadService {

    @Value("${cos.baseUrl:}")
    private String cosBaseUrl;

    @Value("${cos.secretId:}")
    private String cosSecretId;

    @Value("${cos.secretKey:}")
    private String cosSecretKey;

    @Value("${file.uploadFolder:}")
    private String uploadFolder;

    @Value("${file.baseUrl:}")
    private String fileBaseUrl;

    @Value("${storage.type:file}")
    private String storageType;

    @Resource
    private FileAkUtil fileAkUtil;

    /**
     * 上传文件到项目存储。
     *
     * @param file         MultipartFile
     * @param type         存储类型：tmp 临时；store 永久
     * @param tenantConfig 租户配置，用于 file 存储时获取 siteUrl；可为 null
     * @return 上传结果，失败返回 null
     */
    public ImUploadResultDto upload(MultipartFile file, String type, TenantConfigDto tenantConfig) {
        if (file == null || file.isEmpty()) {
            return null;
        }
        try {
            return uploadBytes(file.getBytes(), file.getOriginalFilename(), file.getContentType(),
                    type, null, tenantConfig, file.getInputStream());
        } catch (IOException e) {
            log.error("文件上传失败", e);
            return null;
        }
    }

    /**
     * 上传文件字节到项目存储（无 MultipartFile 的场景调用）。
     *
     * @param bytes            文件字节
     * @param originalFilename 原始文件名
     * @param contentType      文件 MIME 类型
     * @param type             存储类型：tmp 临时；store 永久
     * @param tenantConfig     租户配置，可为 null
     * @return 上传结果，失败返回 null
     */
    public ImUploadResultDto uploadBytes(byte[] bytes, String originalFilename, String contentType,
                                         String type, TenantConfigDto tenantConfig) {
        return uploadBytes(bytes, originalFilename, contentType, type, null, tenantConfig, new ByteArrayInputStream(bytes));
    }

    /**
     * 上传文件字节到项目存储（带子路径）。
     *
     * @param bytes            文件字节
     * @param originalFilename 原始文件名
     * @param contentType      文件 MIME 类型
     * @param type             存储类型：tmp 临时；store 永久
     * @param subPath          子路径（如 "wework/attachments"），可为 null
     * @param tenantConfig     租户配置，可为 null
     * @return 上传结果，失败返回 null
     */
    public ImUploadResultDto uploadBytes(byte[] bytes, String originalFilename, String contentType,
                                         String type, String subPath, TenantConfigDto tenantConfig) {
        return uploadBytes(bytes, originalFilename, contentType, type, subPath, tenantConfig, new ByteArrayInputStream(bytes));
    }

    private ImUploadResultDto uploadBytes(byte[] bytes, String originalFilename, String contentType,
                                          String type, String subPath, TenantConfigDto tenantConfig, InputStream inputStream) {
        try {
            String fileExtension = "";
            int i = originalFilename != null ? originalFilename.lastIndexOf('.') : -1;
            if (i > 0 && i < originalFilename.length() - 1) {
                fileExtension = originalFilename.substring(i + 1).toLowerCase();
            }

            // 构建路径前缀：type + subPath（如 "tmp/wework/attachments"）
            String pathPrefix = type;
            if (StringUtils.isNotBlank(subPath)) {
                pathPrefix = type.endsWith("/") ? type + subPath : type + "/" + subPath;
            }
            String newFileName = pathPrefix + "/" + UUID.randomUUID().toString().replace("-", "") + "." + (StringUtils.isNotBlank(fileExtension) ? fileExtension : "bin");
            String safeContentType = StringUtils.isNotBlank(contentType) ? contentType : "application/octet-stream";

            String url;
            if ("file".equals(storageType)) {
                String path0 = uploadFolder.endsWith("/") ? uploadFolder + type : uploadFolder + "/" + type;
                Path dirPath = Paths.get(path0);
                if (!Files.exists(dirPath)) {
                    Files.createDirectories(dirPath);
                }
                String base = fileBaseUrl;
                if (tenantConfig != null && StringUtils.isNotBlank(tenantConfig.getSiteUrl())) {
                    base = tenantConfig.getSiteUrl().trim().endsWith("/") ? tenantConfig.getSiteUrl().trim() : tenantConfig.getSiteUrl().trim() + "/";
                    base = base + "api/file/";
                } else if (StringUtils.isBlank(base)) {
                    base = "/api/file/";
                }
                url = base.endsWith("/") ? base + newFileName : base + "/" + newFileName;
                Path path = Paths.get(uploadFolder.endsWith("/") ? uploadFolder + newFileName : uploadFolder + "/" + newFileName);
                Files.write(path, bytes);
            } else {
                url = cosBaseUrl + newFileName;
                COSCredentials cred = new BasicCOSCredentials(cosSecretId, cosSecretKey);
                Region region = new Region("ap-chengdu");
                ClientConfig clientConfig = new ClientConfig(region);
                clientConfig.setHttpProtocol(HttpProtocol.https);
                COSClient cosClient = new COSClient(cred, clientConfig);
                ObjectMetadata metadata = new ObjectMetadata();
                metadata.setContentLength(bytes.length);
                metadata.setContentType(safeContentType);
                cosClient.putObject("agent-1251073634", newFileName, inputStream, metadata);
            }
            ImUploadResultDto dto = new ImUploadResultDto();
            dto.setFileName(originalFilename);
            dto.setKey(newFileName);
            dto.setUrl(fileAkUtil.getFileUrlWithAk(url));
            dto.setMimeType(safeContentType);
            dto.setSize(bytes.length);
            return dto;
        } catch (IOException e) {
            log.error("文件上传失败", e);
            return null;
        }
    }
}
