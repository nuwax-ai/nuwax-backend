package com.xspaceagi.file.infra.storage;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.region.Region;
import com.xspaceagi.file.domain.storage.FileStorageStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

/**
 * Tencent Cloud COS Storage Implementation
 */
@Slf4j
@Component
//@ConditionalOnProperty(name = "storage.type", havingValue = "cos")
public class CosFileStorageStrategy implements FileStorageStrategy {

    @Value("${cos.secret-id:}")
    private String secretId;

    @Value("${cos.secret-key:}")
    private String secretKey;

    @Value("${cos.region:ap-guangzhou}")
    private String region;

    @Value("${cos.bucket-name:}")
    private String bucketName;

    @Value("${cos.base-url:}")
    private String baseUrl;

    @Value("${cos.endpoint:}")
    private String endpoint;

    private volatile COSClient cosClient;

    private COSClient getCOSClient() {
        if (cosClient == null) {
            synchronized (this) {
                if (cosClient == null) {
                    COSCredentials cred = new BasicCOSCredentials(secretId, secretKey);

                    // If custom endpoint is configured, use UserSpecifiedEndpointBuilder
                    if (endpoint != null && !endpoint.isEmpty()) {
                        com.qcloud.cos.endpoint.UserSpecifiedEndpointBuilder endpointBuilder =
                                new com.qcloud.cos.endpoint.UserSpecifiedEndpointBuilder(endpoint, endpoint);
                        ClientConfig clientConfig = new ClientConfig(new Region(region));
                        clientConfig.setEndpointBuilder(endpointBuilder);
                        cosClient = new COSClient(cred, clientConfig);
                    } else {
                        ClientConfig clientConfig = new ClientConfig(new Region(region));
                        cosClient = new COSClient(cred, clientConfig);
                    }
                }
            }
        }
        return cosClient;
    }

    @Override
    public String upload(InputStream inputStream, String fileName, String contentType, String targetType) {
        try {
            COSClient client = getCOSClient();
            String fileKey = FileKeyGenerator.generate(fileName, targetType, getStorageType());

            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType(contentType);

            PutObjectRequest putObjectRequest = new PutObjectRequest(bucketName, fileKey, inputStream, metadata);
            client.putObject(putObjectRequest);

            log.info("File uploaded to COS successfully: {}", fileKey);
            return fileKey;
        } catch (Exception e) {
            log.error("Failed to upload file to COS", e);
            throw new RuntimeException("File upload failed", e);
        }
    }

    @Override
    public InputStream download(String fileKey) {
        try {
            COSClient client = getCOSClient();
            return client.getObject(bucketName, fileKey).getObjectContent();
        } catch (Exception e) {
            log.error("Failed to download file from COS: {}", fileKey, e);
            throw new RuntimeException("File download failed", e);
        }
    }

    @Override
    public boolean delete(String fileKey) {
        try {
            COSClient client = getCOSClient();
            client.deleteObject(bucketName, fileKey);
            log.info("File deleted from COS successfully: {}", fileKey);
            return true;
        } catch (Exception e) {
            log.error("Failed to delete file from COS: {}", fileKey, e);
            return false;
        }
    }

    @Override
    public String getFileUrl(String fileKey) {
        return fileKey;
    }

    @Override
    public String generatePresignedUrl(String fileKey, int expireSeconds) {
        return generatePresignedUrl(fileKey, expireSeconds, null);
    }

    @Override
    public String generatePresignedUrl(String fileKey, int expireSeconds, String fileName) {
        try {
            COSClient client = getCOSClient();

            // Remove leading slash from fileKey if present
            String objectKey = fileKey.startsWith("/") ? fileKey.substring(1) : fileKey;

            // Generate presigned URL
            java.util.Date expirationDate = new java.util.Date(System.currentTimeMillis() + expireSeconds * 1000L);

            // Set response headers if fileName is provided
            java.util.Map<String, String> responseHeaders = new java.util.HashMap<>();
            if (fileName != null && !fileName.isEmpty()) {
                responseHeaders.put("Content-Disposition", "attachment; filename=\"" + URLEncoder.encode(fileName, StandardCharsets.UTF_8) + "\"");
            }

            java.net.URL url = client.generatePresignedUrl(bucketName, objectKey, expirationDate, HttpMethodName.GET, new HashMap<>(), responseHeaders, false, false);

            String signedUrl = url.toString();

            // If custom baseUrl is configured, replace default COS domain
            if (baseUrl != null && !baseUrl.isEmpty()) {
                try {
                    java.net.URL originalUrl = new java.net.URL(signedUrl);
                    String path = originalUrl.getPath();
                    String query = originalUrl.getQuery();

                    // Remove trailing slash from baseUrl if present
                    String normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;

                    // Build new URL: baseUrl + path + query
                    signedUrl = normalizedBaseUrl + path + (query != null ? "?" + query : "");
                } catch (Exception e) {
                    log.warn("Failed to replace baseUrl, using original URL: {}", e.getMessage());
                }
            }

            log.info("COS presigned URL generated successfully: {}", fileKey);
            return signedUrl;
        } catch (Exception e) {
            log.error("Failed to generate COS presigned URL: {}", fileKey, e);
            return null;
        }
    }

    @Override
    public String getStorageType() {
        return "cos";
    }
}
