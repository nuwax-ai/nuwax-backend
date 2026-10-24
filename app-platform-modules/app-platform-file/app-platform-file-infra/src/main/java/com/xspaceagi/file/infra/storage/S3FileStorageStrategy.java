package com.xspaceagi.file.infra.storage;

import com.xspaceagi.file.domain.storage.FileStorageStrategy;
import com.xspaceagi.system.spec.utils.RedisUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * S3 Protocol Storage Implementation
 */
@Slf4j
@Component
//@ConditionalOnProperty(name = "storage.type", havingValue = "s3")
public class S3FileStorageStrategy implements FileStorageStrategy {

    /**
     * 代理缓存 Redis key 前缀，与 FileManagementController /f1/{key} 端点保持一致
     */
    private static final String PROXY_REDIS_PREFIX = "s3p:";

    /**
     * 代理条目剩余有效期低于该阈值（秒）时不再复用，重新 presign 并覆盖，
     * 避免把即将失效的代理 URL 返回给前端
     */
    private static final long PROXY_MIN_REUSE_TTL_SECONDS = 60L;

    @Value("${s3.endpoint:}")
    private String endpoint;

    @Value("${s3.access-key:}")
    private String accessKey;

    @Value("${s3.secret-key:}")
    private String secretKey;

    @Value("${s3.bucket-name:}")
    private String bucketName;

    @Value("${s3.region:us-east-1}")
    private String region;

    @Value("${s3.proxy:}")
    private String proxyBaseUrl;

    @Resource
    private RedisUtil redisUtil;

    private volatile S3Client s3Client;
    private volatile S3Presigner s3Presigner;

    private S3Client getS3Client() {
        if (s3Client == null) {
            synchronized (this) {
                if (s3Client == null) {
                    AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKey, secretKey);
                    s3Client = S3Client.builder()
                            .endpointOverride(URI.create(endpoint))
                            .credentialsProvider(StaticCredentialsProvider.create(credentials))
                            .region(Region.of(region))
                            .forcePathStyle(true)
                            .build();
                }
            }
        }
        return s3Client;
    }

    private S3Presigner getS3Presigner() {
        if (s3Presigner == null) {
            synchronized (this) {
                if (s3Presigner == null) {
                    AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKey, secretKey);

                    software.amazon.awssdk.services.s3.presigner.S3Presigner.Builder builder = S3Presigner.builder()
                            .endpointOverride(URI.create(endpoint))
                            .credentialsProvider(StaticCredentialsProvider.create(credentials))
                            .region(Region.of(region));

                    // Configure path-style access for presigner
                    builder.serviceConfiguration(
                            software.amazon.awssdk.services.s3.S3Configuration.builder()
                                    .pathStyleAccessEnabled(true)
                                    .build()
                    );

                    s3Presigner = builder.build();
                }
            }
        }
        return s3Presigner;
    }

    @Override
    public String upload(InputStream inputStream, String fileName, String contentType, String targetType) {
        try {
            S3Client client = getS3Client();
            String fileKey = FileKeyGenerator.generate(fileName, targetType, getStorageType());

            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(fileKey)
                    .contentType(contentType)
                    .build();

            client.putObject(putObjectRequest, RequestBody.fromInputStream(inputStream, inputStream.available()));

            log.info("File uploaded to S3 successfully: {}", fileKey);
            return fileKey;
        } catch (Exception e) {
            log.error("Failed to upload file to S3", e);
            throw new RuntimeException("File upload failed", e);
        }
    }

    @Override
    public InputStream download(String fileKey) {
        try {
            S3Client client = getS3Client();
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(fileKey)
                    .build();
            return client.getObject(getObjectRequest);
        } catch (Exception e) {
            log.error("Failed to download file from S3: {}", fileKey, e);
            throw new RuntimeException("File download failed", e);
        }
    }

    @Override
    public boolean delete(String fileKey) {
        try {
            S3Client client = getS3Client();
            DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(fileKey)
                    .build();
            client.deleteObject(deleteObjectRequest);
            log.info("File deleted from S3 successfully: {}", fileKey);
            return true;
        } catch (Exception e) {
            log.error("Failed to delete file from S3: {}", fileKey, e);
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
            // 开启代理时先派生稳定代理 key，并检查是否有未到期的缓存条目可复用
            String proxyKey = null;
            if (StringUtils.isNotBlank(proxyBaseUrl) && expireSeconds > 0 && expireSeconds < 86400 * 30) {
                proxyKey = buildProxyKey(fileKey, fileName, expireSeconds);
                try {
                    String redisKey = PROXY_REDIS_PREFIX + proxyKey;
                    Object cached = redisUtil.get(redisKey);
                    Long ttl = redisUtil.getTime(redisKey);
                    if (cached != null && StringUtils.isNotBlank(cached.toString())
                            && ttl != null && ttl > PROXY_MIN_REUSE_TTL_SECONDS) {
                        // 未到期条目直接复用：跳过 presign，且返回的 URL 保持稳定，前端缓存不失效
                        log.debug("S3 presigned URL reused from proxy cache: {}", fileKey);
                        return proxyBaseUrl + "/" + proxyKey;
                    }
                } catch (Exception e) {
                    // 缓存读取失败时降级为直接 presign，不影响主流程
                    log.warn("Failed to check proxy cache, fallback to presign: {}", fileKey, e);
                    proxyKey = null;
                }
            }

            S3Presigner presigner = getS3Presigner();

            // Remove leading slash from fileKey if present
            String objectKey = fileKey.startsWith("/") ? fileKey.substring(1) : fileKey;

            GetObjectRequest.Builder requestBuilder = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey);

            // Add Content-Disposition header if fileName is provided
            if (fileName != null && !fileName.isEmpty()) {
                requestBuilder.responseContentDisposition("attachment; filename=\"" + URLEncoder.encode(fileName, StandardCharsets.UTF_8) + "\"");
            }

            GetObjectRequest getObjectRequest = requestBuilder.build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofSeconds(expireSeconds))
                    .getObjectRequest(getObjectRequest)
                    .build();

            PresignedGetObjectRequest presignedRequest = presigner.presignGetObject(presignRequest);
            String url = presignedRequest.url().toString();
            if (proxyKey != null) {
                redisUtil.set(PROXY_REDIS_PREFIX + proxyKey, url, expireSeconds);
                url = proxyBaseUrl + "/" + proxyKey;
            }
            log.info("S3 presigned URL generated successfully: {}", fileKey);
            return url;
        } catch (Exception e) {
            log.error("Failed to generate S3 presigned URL: {}", fileKey, e);
            return null;
        }
    }

    /**
     * 派生稳定的代理 key：同一 (fileKey + fileName + expireSeconds) 组合始终得到同一个 key，
     * 返回给前端的代理 URL 因此稳定、可缓存。
     * 形态与旧的随机 key 保持一致（32 位十六进制 + 原文件扩展名，如 .jpg），
     * /f1/{key} 端点按路径段读 Redis，不感知 key 的生成规则。
     */
    private static String buildProxyKey(String fileKey, String fileName, int expireSeconds) {
        String raw = fileKey + "|" + StringUtils.defaultString(fileName) + "|" + expireSeconds;
        String hex = DigestUtils.md5DigestAsHex(raw.getBytes(StandardCharsets.UTF_8));
        return hex + FileKeyGenerator.extractExtension(fileKey);
    }

    @Override
    public String getStorageType() {
        return "s3";
    }
}
