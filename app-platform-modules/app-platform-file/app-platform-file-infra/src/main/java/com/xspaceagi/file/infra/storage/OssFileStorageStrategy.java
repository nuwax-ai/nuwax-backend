package com.xspaceagi.file.infra.storage;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.CredentialsProvider;
import com.aliyun.oss.common.auth.CredentialsProviderFactory;
import com.aliyun.oss.common.comm.SignVersion;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import com.xspaceagi.file.domain.storage.FileStorageStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Alibaba Cloud OSS Storage Implementation
 */
@Slf4j
@Component
//@ConditionalOnProperty(name = "storage.type", havingValue = "oss")
public class OssFileStorageStrategy implements FileStorageStrategy {

    @Value("${oss.endpoint:}")
    private String endpoint;

    @Value("${oss.access-key-id:}")
    private String accessKeyId;

    @Value("${oss.access-key-secret:}")
    private String accessKeySecret;

    @Value("${oss.region-name:cn-hangzhou}")
    private String regionName;

    @Value("${oss.bucket-name:}")
    private String bucketName;

    private volatile OSS ossClient;

    private OSS getOSSClient() {
        if (ossClient == null) {
            synchronized (this) {
                if (ossClient == null) {
                    CredentialsProvider credentialsProvider = CredentialsProviderFactory.newDefaultCredentialProvider(accessKeyId, accessKeySecret);
                    ClientBuilderConfiguration clientBuilderConfiguration = new ClientBuilderConfiguration();
                    clientBuilderConfiguration.setSupportCname(true);
                    clientBuilderConfiguration.setSignatureVersion(SignVersion.V4);
                    ossClient = OSSClientBuilder.create()
                            .endpoint(endpoint)
                            .credentialsProvider(credentialsProvider)
                            .clientConfiguration(clientBuilderConfiguration)
                            .region(regionName)
                            .build();
                }
            }
        }
        return ossClient;
    }

    @Override
    public String upload(InputStream inputStream, String fileName, String contentType, String targetType) {
        try {
            OSS client = getOSSClient();
            String fileKey = FileKeyGenerator.generate(fileName, targetType, getStorageType());

            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType(contentType);

            PutObjectRequest putObjectRequest = new PutObjectRequest(bucketName, fileKey, inputStream, metadata);
            client.putObject(putObjectRequest);

            log.info("File uploaded to OSS successfully: {}", fileKey);
            return fileKey;
        } catch (Exception e) {
            log.error("Failed to upload file to OSS", e);
            throw new RuntimeException("File upload failed", e);
        }
    }

    @Override
    public InputStream download(String fileKey) {
        try {
            OSS client = getOSSClient();
            return client.getObject(bucketName, fileKey).getObjectContent();
        } catch (Exception e) {
            log.error("Failed to download file from OSS: {}", fileKey, e);
            throw new RuntimeException("File download failed", e);
        }
    }

    @Override
    public boolean delete(String fileKey) {
        try {
            OSS client = getOSSClient();
            client.deleteObject(bucketName, fileKey);
            log.info("File deleted from OSS successfully: {}", fileKey);
            return true;
        } catch (Exception e) {
            log.error("Failed to delete file from OSS: {}", fileKey, e);
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
            OSS client = getOSSClient();

            // Remove leading slash from fileKey if present
            String objectKey = fileKey.startsWith("/") ? fileKey.substring(1) : fileKey;

            // Generate presigned URL
            java.util.Date expirationDate = new java.util.Date(System.currentTimeMillis() + expireSeconds * 1000L);

            // Create GeneratePresignedUrlRequest
            com.aliyun.oss.model.GeneratePresignedUrlRequest request =
                    new com.aliyun.oss.model.GeneratePresignedUrlRequest(bucketName, objectKey);
            request.setExpiration(expirationDate);

            // Add Content-Disposition header if fileName is provided
            if (fileName != null && !fileName.isEmpty()) {
                com.aliyun.oss.model.ResponseHeaderOverrides responseHeaders =
                        new com.aliyun.oss.model.ResponseHeaderOverrides();
                responseHeaders.setContentDisposition("attachment; filename=\"" + URLEncoder.encode(fileName, StandardCharsets.UTF_8) + "\"");
                request.setResponseHeaders(responseHeaders);
            }

            java.net.URL url = client.generatePresignedUrl(request);

            log.info("OSS presigned URL generated successfully: {}", fileKey);
            return url.toString();
        } catch (Exception e) {
            log.error("Failed to generate OSS presigned URL: {}", fileKey, e);
            return null;
        }
    }

    @Override
    public String getStorageType() {
        return "oss";
    }
}
