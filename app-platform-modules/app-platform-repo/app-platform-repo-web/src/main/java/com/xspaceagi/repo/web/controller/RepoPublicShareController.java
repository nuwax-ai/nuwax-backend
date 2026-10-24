package com.xspaceagi.repo.web.controller;

import com.xspaceagi.file.application.service.FileManagementService;
import com.xspaceagi.file.domain.model.FileRecordDomain;
import com.xspaceagi.repo.application.service.RepoPublicShareService;
import com.xspaceagi.repo.application.service.dto.RepoPublicShareView;
import com.xspaceagi.system.spec.dto.ReqResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 匿名分享链接控制器（需求4决策②：必须支持未登录只读查看）。
 * <p>
 * 路径在 bootstrap auth.excludePath 中豁免登录（/api/repo/public/**），
 * 安全边界完全由 token 承载：
 * <ul>
 *   <li>内容接口：public 链接任何人可访问；login 链接未登录访问返回登录提示，已登录放行；</li>
 *   <li>文件接口：校验 fileKey 出现在该页正文或附件中，防有效 token 越权读任意文件。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/repo/public")
@RequiredArgsConstructor
@Tag(name = "Repo Public Share", description = "文档协作：匿名分享只读访问")
public class RepoPublicShareController {

    private static final String FILE_PATH_PREFIX = "/api/repo/public/share/";

    private final RepoPublicShareService repoPublicShareService;

    private final FileManagementService fileManagementService;

    @GetMapping("/share/{token}")
    @Operation(summary = "获取分享内容（public 链接任何人可访问；login 链接未登录访问返回登录提示）")
    public ReqResult<RepoPublicShareView> getPublicShare(@PathVariable String token, HttpServletRequest request) {
        return ReqResult.success(repoPublicShareService.getPublicShare(token, requestBaseUrl(request)));
    }

    /**
     * 请求来源绝对基址（补文件 ak 用）：优先代理头，兜底直连地址
     */
    private String requestBaseUrl(HttpServletRequest request) {
        String forwardedHost = request.getHeader("X-Forwarded-Host");
        String host = forwardedHost != null && !forwardedHost.isBlank()
                ? forwardedHost.split(",")[0].trim() : request.getHeader("Host");
        String scheme = request.getHeader("X-Forwarded-Proto");
        if (scheme == null || scheme.isBlank()) {
            scheme = request.getScheme();
        }
        if (host == null || host.isBlank()) {
            host = request.getServerName() + ":" + request.getServerPort();
        }
        return scheme + "://" + host;
    }

    /**
     * 匿名访问分享页内引用的文件（图片/附件）。
     * fileKey 含多级路径（/storageType/businessType/date/uuid.ext），故用 /file/** 提取。
     */
    @GetMapping("/share/{token}/file/**")
    @Operation(summary = "匿名获取分享页内文件（校验 fileKey 属于该页后经文件服务返回）")
    public ResponseEntity<?> getPublicShareFile(@PathVariable String token, HttpServletRequest request) {
        String fullPath = request.getRequestURI();
        int fileIndex = fullPath.indexOf("/file/", fullPath.indexOf(FILE_PATH_PREFIX));
        if (fileIndex < 0 || fullPath.length() <= fileIndex + "/file/".length()) {
            return ResponseEntity.badRequest().build();
        }
        String fileKey = fullPath.substring(fileIndex + "/file/".length());
        // 越权校验：fileKey 必须出现在该页正文或附件中（失败抛 resourceDataNotFound）
        repoPublicShareService.checkFileAccessible(token, fileKey);
        return streamFile(fileKey);
    }

    /**
     * 与平台 /api/f/** 同一取数模式：本地存储直出流，云存储 302 预签名。
     */
    private ResponseEntity<?> streamFile(String fileKey) {
        try {
            String storageType = extractStorageType(fileKey);
            if (storageType == null) {
                return ResponseEntity.badRequest().build();
            }
            if ("local".equals(storageType)) {
                FileRecordDomain fileRecord = fileManagementService.getFileByKey(fileKey);
                if (fileRecord == null) {
                    return ResponseEntity.notFound().build();
                }
                InputStream inputStream = fileManagementService.downloadFile(fileKey);
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.parseMediaType(
                        fileRecord.getFileType() != null ? fileRecord.getFileType() : "application/octet-stream"));
                String fileName = fileRecord.getFileName() == null ? "file" : fileRecord.getFileName();
                headers.add(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + URLEncoder.encode(fileName, StandardCharsets.UTF_8) + "\"");
                headers.add(HttpHeaders.CACHE_CONTROL, "public, max-age=3600");
                return ResponseEntity.ok().headers(headers).body(new InputStreamResource(inputStream));
            }
            String signedUrl = fileManagementService.generatePresignedUrlByType(fileKey, storageType, 3600, null);
            if (signedUrl == null) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(signedUrl)).build();
        } catch (Exception e) {
            log.error("public share file access failed: {}", fileKey, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * fileKey 格式 /storageType/businessType/date/uuid.ext → 取首段
     */
    private String extractStorageType(String fileKey) {
        if (fileKey == null || fileKey.isEmpty()) {
            return null;
        }
        String key = fileKey.startsWith("/") ? fileKey.substring(1) : fileKey;
        int firstSlash = key.indexOf('/');
        if (firstSlash > 0) {
            return key.substring(0, firstSlash);
        }
        return null;
    }
}
