package com.xspaceagi.file.web.controller;

import com.xspaceagi.file.application.service.FileManagementService;
import com.xspaceagi.file.domain.model.FileRecordDomain;
import com.xspaceagi.file.infra.storage.FileKeyGenerator;
import com.xspaceagi.file.sdk.IFileAccessService;
import com.xspaceagi.file.web.dto.FileRecordVO;
import com.xspaceagi.system.sdk.service.UserAccessKeyApiService;
import com.xspaceagi.system.sdk.service.dto.UserAccessKeyDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.enums.HttpStatusEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.utils.RedisUtil;
import com.xspaceagi.system.web.controller.ChatKeyCheck;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * File Management Controller
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "File Management", description = "File upload, download, delete operations")
public class FileManagementController {

    private final FileManagementService fileManagementService;

    private final UserAccessKeyApiService userAccessKeyApiService;

    private final IFileAccessService iFileAccessService;

    @Resource
    private RedisUtil redisUtil;

    @Value("${s3.proxy:}")
    private String proxyBaseUrl;

    @Value("${s3.shortUrlKey:}")
    private String shortUrlKey;


    @PostMapping("/file/upload")
    @Operation(summary = "Upload file")
    public ReqResult<FileRecordVO> uploadFile(HttpServletRequest request, @RequestParam("file") MultipartFile file,
                                              @RequestParam(value = "targetType", required = false) String targetType,
                                              @RequestParam(value = "targetId", required = false, defaultValue = "-1") Long targetId,
                                              @RequestParam(value = "metadata", required = false) String metadata) {
        if (!RequestContext.get().isLogin()) {
            UserAccessKeyDto userAccessKeyDto = ChatKeyCheck.check(request, userAccessKeyApiService);
            RequestContext.get().setUserId(userAccessKeyDto.getUserId());
        }
        boolean isAuthRequired = true;
        // If targetType or targetId not provided, try to parse from Referer
        if (targetType == null || targetId == null) {
            String referer = request.getHeader("Referer");
            if (referer != null && !referer.isEmpty()) {
                TargetInfo targetInfo = parseTargetFromReferer(referer);
                if (targetInfo != null) {
                    if (targetType == null) {
                        targetType = targetInfo.getType();
                    }
                    if (targetId == null) {
                        targetId = targetInfo.getId();
                    }
                } else {
                    targetType = "default";
                }

                // Check if authentication is required: match public path patterns
                isAuthRequired = !isPublicPath(referer);
            }
        }
        FileRecordDomain fileRecord = fileManagementService.uploadFile(file, RequestContext.get().getTenantId(),
                RequestContext.get().getUserId(), targetType, targetId, metadata, isAuthRequired);

        if (!RequestContext.get().isLogin()) {
            String fileUrl = iFileAccessService.getFileUrlWithAk(fileRecord.getFileUrl());
            fileRecord.setFileUrl(fileUrl);
        }

        return ReqResult.success(toVO(fileRecord));
    }

    /**
     * Check if path is public (no authentication required)
     * Public paths include:
     * - /space/{tenantId}/agent/{agentId}
     * - /system/config/setting
     * - /space/{tenantId}/app-dev/{appId}
     * - /space/{tenantId}/workflow/{workflowId}
     * - /space/{tenantId}/plugin/{pluginId}
     * - /space/{tenantId}/skill-details/{skillId}
     * - /space/{tenantId}/mcp/edit/{mcpId}
     */
    private boolean isPublicPath(String referer) {
        if (referer == null || referer.isEmpty()) {
            return false;
        }

        String path = extractPath(referer);
        if (path == null) {
            return false;
        }

        // Define public path regex patterns
        String[] publicPatterns = {
                "/space/\\d+/agent/\\d+",
                "/system/config/setting",
                "/space/\\d+/app-dev/\\d+",
                "/space/\\d+/workflow/\\d+",
                "/space/\\d+/plugin/\\d+",
                "/space/\\d+/skill-details/\\d+",
                "/space/\\d+/mcp/edit/\\d+"
        };

        for (String pattern : publicPatterns) {
            if (Pattern.matches(pattern, path)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Parse targetType and targetId from Referer URL
     * Supported URL formats:
     * - /home/chat/{targetId} -> targetType=agent
     * - /space/knowledge/{targetId} -> targetType=knowledge
     * - Other paths -> targetType=other
     */
    private TargetInfo parseTargetFromReferer(String referer) {
        if (referer == null || referer.isEmpty()) {
            return null;
        }

        try {
            // Extract path part (remove protocol, domain, query params)
            String path = extractPath(referer);
            if (path == null) {
                return null;
            }

            // Use regex to match path patterns
            TargetInfo result = matchPathPattern(path, "/home/chat/(\\d+)/(\\d+)", "agent", 2);
            if (result != null) {
                return result;
            }

            result = matchPathPattern(path, "/agent/(\\d+)", "agent", 1);
            if (result != null) {
                return result;
            }

            result = matchPathPattern(path, "/app/(\\d+)", "agent", 1);
            if (result != null) {
                return result;
            }

            result = matchPathPattern(path, "/space/(\\d+)/knowledge/(\\d+)", "knowledge", 2);
            if (result != null) {
                return result;
            }

            result = matchPathPattern(path, "/space/(\\d+)/workflow/(\\d+)", "workflow", 2);
            if (result != null) {
                return result;
            }

            result = matchPathPattern(path, "/space/(\\d+)/plugin/(\\d+)", "plugin", 2);
            if (result != null) {
                return result;
            }

            result = matchPathPattern(path, "/space/(\\d+)/skill-details/(\\d+)", "skill", 2);
            if (result != null) {
                return result;
            }

            result = matchPathPattern(path, "/space/(\\d+)/mcp/edit/(\\d+)", "mcp", 2);
            if (result != null) {
                return result;
            }

            return matchPathPattern(path, "/space/(\\d+)/app-dev/(\\d+)", "app-dev", 2);
        } catch (Exception e) {
            log.error("Failed to parse Referer: {}", referer, e);
            return null;
        }
    }

    /**
     * Extract path part from URL
     */
    private String extractPath(String url) {
        try {
            // Remove query params and anchor
            int queryIndex = url.indexOf('?');
            if (queryIndex > 0) {
                url = url.substring(0, queryIndex);
            }
            int anchorIndex = url.indexOf('#');
            if (anchorIndex > 0) {
                url = url.substring(0, anchorIndex);
            }

            // Remove protocol and domain
            int pathStart = url.indexOf("://");
            if (pathStart > 0) {
                pathStart = url.indexOf('/', pathStart + 3);
                if (pathStart > 0) {
                    return url.substring(pathStart);
                }
            }
            return url;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Match path pattern using regex
     */
    private TargetInfo matchPathPattern(String path, String regex, String targetType, int index) {
        try {
            Pattern pattern = Pattern.compile(regex);
            Matcher matcher = pattern.matcher(path);
            if (matcher.find()) {
                Long id = Long.parseLong(matcher.group(index));
                return new TargetInfo(targetType, id);
            }
        } catch (Exception e) {
            log.warn("Failed to match path pattern: pattern={}, path={}", regex, path);
        }
        return null;
    }

    /**
     * Extract last number from path as ID
     */
    private TargetInfo extractLastNumber(String path) {
        String[] segments = path.split("/");
        for (int i = segments.length - 1; i >= 0; i--) {
            try {
                Long id = Long.parseLong(segments[i]);
                return new TargetInfo("other", id);
            } catch (NumberFormatException e) {
                // Continue trying previous segment
            }
        }
        return null;
    }

    /**
     * Target info inner class
     */
    @Data
    private static class TargetInfo {
        private final String type;
        private final Long id;

        public TargetInfo(String type, Long id) {
            this.type = type;
            this.id = id;
        }

        public String getType() {
            return type;
        }

        public Long getId() {
            return id;
        }
    }

    /**
     * Access file by fileKey
     * Local storage: return file stream directly
     * Cloud storage (s3/cos/oss): 302 redirect to presigned URL
     */
    @GetMapping("/f/**")
    @Operation(summary = "Access file by fileKey")
    public ResponseEntity<?> getFileByPath(HttpServletRequest request, @RequestParam(name = "download", required = false) Integer download) {
        if (!RequestContext.get().isLogin()) {
            try {
                iFileAccessService.checkFileUrlAk0(request.getRequestURI(), request.getParameter("ak"));
            } catch (Exception e) {
                throw BizException.of(HttpStatusEnum.UNAUTHORIZED, ErrorCodeEnum.UNAUTHORIZED,
                        BizExceptionCodeEnum.systemUnauthorizedOrSessionExpired);
            }
        }
        String fullPath = request.getRequestURI();
        String fileKey = fullPath.substring("/api/f/".length());
        log.info("Accessing file: {}", fileKey);
        return response(fileKey, download);
    }

    private ResponseEntity<?> response(String fileKey, Integer download) {
        try {
            // Extract storage type from fileKey (format: /storageType/businessType/date/uuid.ext)
            String storageType = extractStorageTypeFromFileKey(fileKey);
            if (storageType == null) {
                log.error("Cannot extract storage type from fileKey: {}", fileKey);
                return ResponseEntity.badRequest().body("Invalid file key format");
            }

            // Check storage type
            if ("local".equals(storageType)) {
                // Local storage: query database for file info, then return file stream
                FileRecordDomain fileRecord = fileManagementService.getFileByKey(fileKey);
                if (fileRecord == null) {
                    return ResponseEntity.notFound().build();
                }

                InputStream inputStream = fileManagementService.downloadFile(fileKey);

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.parseMediaType(
                        fileRecord.getFileType() != null ? fileRecord.getFileType() : "application/octet-stream"));
                if (download != null && download == 1) {
                    headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + URLEncoder.encode(fileRecord.getFileName(), StandardCharsets.UTF_8) + "\"");
                } else {
                    headers.add(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + URLEncoder.encode(fileRecord.getFileName(), StandardCharsets.UTF_8) + "\"");
                }
                return ResponseEntity.ok()
                        .headers(headers)
                        .body(new InputStreamResource(inputStream));
            } else {
                // Cloud storage (s3/cos/oss): generate presigned URL and 302 redirect, no database query needed
                String signedUrl = fileManagementService.generatePresignedUrlByType(fileKey, storageType, 3600, download); // 1 hour validity
                if (signedUrl == null) {
                    log.error("Failed to generate presigned URL: storageType={}, fileKey={}", storageType, fileKey);
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body("Failed to generate presigned URL");
                }

                log.info("302 redirect to presigned URL: storageType={}, fileKey={}", storageType, fileKey);
                return ResponseEntity.status(HttpStatus.FOUND)
                        .location(URI.create(signedUrl))
                        .build();
            }
        } catch (Exception e) {
            log.error("File access failed: {}", fileKey, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/f1/{key}")
    @Operation(summary = "Access file by fileKey")
    public ResponseEntity<?> redirectOriginalUrl(@PathVariable String key) {
        Object o = redisUtil.get("s3p:" + key);
        if (o == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(o.toString()))
                .header("X-S3-Url", o.toString())
                .build();
    }

    @GetMapping("/file/url/short")
    @Operation(summary = "Get file short url")
    public ReqResult<String> getShortUrl(@RequestParam String url, @RequestParam String ak, @RequestParam(required = false) Integer expireSeconds) {
        if (StringUtils.isBlank(shortUrlKey) || shortUrlKey.equals(ak)) {
            return ReqResult.error("Invalid ak");
        }
        String decode = URLDecoder.decode(url, StandardCharsets.UTF_8);
        if (decode == null) {
            return ReqResult.error("Invalid url");
        }
        String ext = FileKeyGenerator.extractExtension(decode);
        ext = ext.contains("?") ? ext.substring(0, ext.indexOf("?")) : ext;
        String key = UUID.randomUUID().toString().replace("-", "") + ext;
        redisUtil.set("s3p:" + key, decode, expireSeconds == null ? 3600 : expireSeconds);
        return ReqResult.success(proxyBaseUrl + "/" + key);
    }


    /**
     * Extract storage type from fileKey
     * fileKey format: /storageType/businessType/date/uuid.ext
     * Example: /s3/agent/20260417/xxx.jpg -> s3
     */
    private String extractStorageTypeFromFileKey(String fileKey) {
        if (fileKey == null || fileKey.isEmpty()) {
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

    public static FileRecordVO toVO(FileRecordDomain domain) {
        return FileRecordVO.builder()
                .id(domain.getId())
                .tenantId(domain.getTenantId())
                .userId(domain.getUserId())
                .targetType(domain.getTargetType())
                .targetId(domain.getTargetId())
                .fileName(domain.getFileName())
                .size(domain.getFileSize())
                .mimeType(domain.getFileType())
                .fileExtension(domain.getFileExtension())
                .metadata(domain.getMetadata())
                .key(domain.getFileKey())
                .storageType(domain.getStorageType())
                .url(domain.getFileUrl())
                .authRequired(domain.getAuthRequired())
                .status(domain.getStatus())
                .created(domain.getCreated())
                .modified(domain.getModified())
                .build();
    }
}
