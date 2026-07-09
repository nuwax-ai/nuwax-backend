package com.xspaceagi.file.application.service.impl;

import com.xspaceagi.file.application.service.FileManagementService;
import com.xspaceagi.file.domain.model.FileRecordDomain;
import com.xspaceagi.file.sdk.IFileAccessService;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.utils.RedisUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
public class FileAccessServiceImpl implements IFileAccessService {

    @Resource
    private RedisUtil redisUtil;

    @Value("${storage.type}")
    private String storageType;

    @Resource
    private FileManagementService fileManagementService;

    public String getFileUrlWithAk(String fileUrl) {
        return getFileUrlWithAk(fileUrl, false);
    }

    @Override
    public String getFileUrlWithAk(String siteUrl, String fileUrl) {
        return getRealFileUrl(siteUrl, fileUrl, false, false);
    }

    public String getFileUrlWithAk(String fileUrl, boolean returnOriginalUrl) {
        if (fileUrl == null) {
            return null;
        }
        if (RequestContext.get() != null && RequestContext.get().getTenantConfig() != null) {
            TenantConfigDto tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
            fileUrl = getRealFileUrl(tenantConfigDto.getSiteUrl(), fileUrl, false, false);
        }
        String path;
        try {
            path = getUrlPath(fileUrl);
        } catch (Exception e) {
            return fileUrl;
        }
        if (!path.startsWith("/api/f/") && !path.startsWith("/api/file/")) {
            return fileUrl;
        }

        // 从 fileUrl 中提取 storageType 和 fileKey
        // URL 格式：http://127.0.0.1:8081/api/f/{fileKey}
        // fileKey 格式：{storageType}/{businessType}/{date}/{uuid}.{ext}
        String extractedStorageType = null;
        String fileKey = null;
        try {
            int apiIndex = fileUrl.indexOf("/api/f/");
            if (apiIndex != -1) {
                fileKey = fileUrl.substring(apiIndex + "/api/f/".length());
                // 移除查询参数
                int queryIndex = fileKey.indexOf('?');
                if (queryIndex != -1) {
                    fileKey = fileKey.substring(0, queryIndex);
                }
                // 提取存储类型（fileKey 的第一段）
                int slashIndex = fileKey.indexOf('/');
                if (slashIndex != -1) {
                    extractedStorageType = fileKey.substring(0, slashIndex);
                }
            }
        } catch (Exception e) {
            // 提取失败，使用配置的 storageType
        }

        boolean isCurrentSiteFile = true;
        TenantConfigDto tenantConfig = RequestContext.get() != null ? (TenantConfigDto) RequestContext.get().getTenantConfig() : null;
        if (tenantConfig != null && tenantConfig.getSiteUrl() != null) {
            try {
                URL siteUrl = new URL(tenantConfig.getSiteUrl());
                URL fileUrlObj = new URL(fileUrl);

                // Compare host (domain) between siteUrl and fileUrl
                String siteHost = siteUrl.getHost();
                String fileHost = fileUrlObj.getHost();

                isCurrentSiteFile = siteHost != null && siteHost.equalsIgnoreCase(fileHost);
            } catch (MalformedURLException e) {
                // If URL parsing fails, assume it's current site file
            }
        }

        log.debug("fileUrl {}, isCurrentSiteFile {}", fileUrl, isCurrentSiteFile);
        if (!isCurrentSiteFile && fileKey != null) {
            FileRecordDomain fileByKey = fileManagementService.getFileByKey(fileKey);
            if (fileByKey == null) {
                return fileUrl;
            }
        }

        // 移除 URL 中的 ak 参数
        if (fileUrl.contains("ak=")) {
            int akIndex = fileUrl.indexOf("ak=");
            int ampIndex = fileUrl.indexOf('&', akIndex);
            if (akIndex > 0 && fileUrl.charAt(akIndex - 1) == '?') {
                // ak 是第一个参数：?ak=xxx 或 ?ak=xxx&other=yyy
                if (ampIndex != -1) {
                    fileUrl = fileUrl.substring(0, akIndex) + fileUrl.substring(ampIndex + 1);
                } else {
                    fileUrl = fileUrl.substring(0, akIndex - 1);
                }
            } else if (akIndex > 0 && fileUrl.charAt(akIndex - 1) == '&') {
                // ak 是中间或最后的参数：&ak=xxx 或 &ak=xxx&other=yyy
                if (ampIndex != -1) {
                    fileUrl = fileUrl.substring(0, akIndex - 1) + fileUrl.substring(ampIndex);
                } else {
                    fileUrl = fileUrl.substring(0, akIndex - 1);
                }
            }
        }

        boolean isLocalFile = "local".equals(extractedStorageType) || fileUrl.contains("/api/file/");
        if (!returnOriginalUrl || isLocalFile) {
            Object ak = redisUtil.get("file.ak:" + path);
            if (Objects.nonNull(ak)) {
                return fileUrl + "?ak=" + ak;
            }
            ak = UUID.randomUUID().toString().replace("-", "");
            redisUtil.set("file.ak:" + path, ak.toString(), 60 * 60 * 24);
            return fileUrl + "?ak=" + ak;
        }
        return fileManagementService.generatePresignedUrlByType(fileKey, extractStorageTypeFromFileKey(fileKey), 60 * 60 * 24, 0);
    }

    public void checkFileUrlAk(String uri, String ak) {
        if (!"file".equals(storageType) && uri.contains("/api/file")) {
            return;
        }

        Object ak0 = redisUtil.get("file.ak:" + uri);
        if (ak0 == null || !ak0.equals(ak)) {
            throw new IllegalArgumentException("Invalid file URL");
        }
    }

    public void checkFileUrlAk0(String uri, String ak) {
        Object ak0 = redisUtil.get("file.ak:" + uri);
        if (ak0 == null || !ak0.equals(ak)) {
            throw new IllegalArgumentException("Invalid file URL");
        }
    }

    @Override
    public String getRealFileUrl(String siteUrl, String fileUrl, boolean withAk, boolean returnOriginalUrl) {
        fileUrl = replaceUrl(fileUrl, siteUrl);
        if (withAk || returnOriginalUrl) {
            return getFileUrlWithAk(fileUrl, returnOriginalUrl);
        }
        return fileUrl;
    }

    @Override
    public String getRealFileUrl(String fileUrl) {
        if (RequestContext.get() == null) {
            return fileUrl;
        }
        TenantConfigDto tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
        return getRealFileUrl(tenantConfigDto.getSiteUrl(), fileUrl, false, false);
    }

    private static String replaceUrl(String url, String newBase) {
        if (url == null || url.isEmpty()) return url;

        // 只匹配包含 /api/f/ 的完整 URL
        int idx = url.indexOf("/api/f/");
        if (idx == -1) return url;

        String path = url.substring(idx);           // /api/f/xxx
        return newBase.replaceAll("/+$", "") + path;
    }

    private static String getUrlPath(String fileUrl) throws MalformedURLException {
        URL url;
        url = new URL(fileUrl);
        return url.getPath();
    }

    private String extractStorageTypeFromFileKey(String fileKey) {
        if (StringUtils.isBlank(fileKey)) {
            return null;
        }

        // Remove leading slash
        String key = fileKey.startsWith("/") ? fileKey.substring(1) : fileKey;

        // Extract first segment as storage type
        int firstSlash = key.indexOf('/');
        if (firstSlash > 0) {
            return key.substring(0, firstSlash);
        }

        return null;
    }
}
