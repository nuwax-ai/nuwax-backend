package com.xspaceagi.repo.web.controller;

import com.xspaceagi.file.application.service.FileManagementService;
import com.xspaceagi.file.domain.model.FileRecordDomain;
import com.xspaceagi.repo.application.service.RepoAccessService;
import com.xspaceagi.repo.application.service.RepoDocConvertService;
import com.xspaceagi.repo.application.service.RepoDocExportService;
import com.xspaceagi.repo.application.service.RepoPageApplicationService;
import com.xspaceagi.repo.domain.model.RepoPageDomain;
import com.xspaceagi.repo.web.dto.RepoMarkdownConvertRequest;
import com.xspaceagi.repo.web.dto.RepoPageExportVO;
import com.xspaceagi.system.spec.dto.ReqResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * 文档导入导出控制器（flexmark md/html 双向；Word 后置）。
 */
@Slf4j
@RestController
@RequestMapping("/api/repo")
@RequiredArgsConstructor
@Tag(name = "Repo Doc Convert", description = "文档协作：导入导出")
public class RepoDocConvertController {

    private final RepoDocConvertService repoDocConvertService;

    private final RepoDocExportService repoDocExportService;

    private final RepoPageApplicationService repoPageApplicationService;

    private final RepoAccessService repoAccessService;

    private final FileManagementService fileManagementService;

    @GetMapping("/pages/{pageId}/export/markdown")
    @Operation(summary = "导出页面为 Markdown（基于 html_snapshot 转换；shareToken 可选，本轮需求4）")
    public ReqResult<RepoPageExportVO> exportMarkdown(@PathVariable Long pageId,
                                                      @RequestParam(required = false) String shareToken) {
        RepoPageDomain page = repoPageApplicationService.getPage(pageId, shareToken);
        if (page == null) {
            return ReqResult.error("page not found");
        }
        repoAccessService.requireExportable(page, shareToken);
        String markdown = repoDocConvertService.htmlToMarkdown(page.getHtmlSnapshot());
        return ReqResult.success(RepoPageExportVO.builder()
                .title(page.getTitle())
                .markdown(markdown)
                .build());
    }

    @GetMapping("/pages/{pageId}/export/docx")
    @Operation(summary = "导出页面为 Word 文档（后端转换，二进制流直接下载；shareToken 可选，本轮需求4）")
    public ResponseEntity<byte[]> exportDocx(@PathVariable Long pageId,
                                             @RequestParam(required = false) String shareToken) {
        RepoPageDomain page = repoPageApplicationService.getPage(pageId, shareToken);
        if (page == null) {
            return ResponseEntity.notFound().build();
        }
        repoAccessService.requireExportable(page, shareToken);
        byte[] bytes = repoDocExportService.exportDocx(page);
        return download(bytes, page.getTitle() + ".docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    @GetMapping("/pages/{pageId}/export/pdf")
    @Operation(summary = "导出页面为 PDF（后端转换，二进制流直接下载；shareToken 可选，本轮需求4）")
    public ResponseEntity<byte[]> exportPdf(@PathVariable Long pageId,
                                            @RequestParam(required = false) String shareToken) {
        RepoPageDomain page = repoPageApplicationService.getPage(pageId, shareToken);
        if (page == null) {
            return ResponseEntity.notFound().build();
        }
        repoAccessService.requireExportable(page, shareToken);
        byte[] bytes = repoDocExportService.exportPdf(page);
        return download(bytes, page.getTitle() + ".pdf", MediaType.APPLICATION_PDF_VALUE);
    }

    @GetMapping("/pages/{pageId}/export/original")
    @Operation(summary = "下载原始文件（导入原件；无原件的 html/htm 导入直接下载快照为 .html；shareToken 可选，本轮需求4）")
    public ResponseEntity<byte[]> exportOriginal(@PathVariable Long pageId,
                                                 @RequestParam(required = false) String shareToken) {
        RepoPageDomain page = repoPageApplicationService.getPage(pageId, shareToken);
        if (page == null) {
            return ResponseEntity.notFound().build();
        }
        repoAccessService.requireExportable(page, shareToken);
        String fileKey = page.getOriginalFileKey();
        if (fileKey != null && !fileKey.isBlank()) {
            try (InputStream in = fileManagementService.downloadFile(fileKey)) {
                byte[] bytes = in == null ? null : in.readAllBytes();
                if (bytes != null && bytes.length > 0) {
                    String fileName = page.getTitle();
                    String contentType = guessContentType(fileKey);
                    try {
                        FileRecordDomain record = fileManagementService.getFileByKey(fileKey);
                        if (record != null && record.getFileName() != null && !record.getFileName().isBlank()) {
                            fileName = record.getFileName();
                            contentType = guessContentType(record.getFileName());
                        }
                    } catch (Exception e) {
                        log.debug("export original getFileByKey failed, fileKey={}", fileKey);
                    }
                    return download(bytes, fileName, contentType);
                }
            } catch (Exception e) {
                log.warn("export original download failed, pageId={}, fileKey={}, error={}",
                        pageId, fileKey, e.getMessage());
            }
        }
        // 无原件兜底：html/htm 导入的内容即快照本身，直接下载为 .html。
        // 历史导入文档 source_ext 可能为空，此时按标题扩展名兜底（与前端对历史文档的标题判定一致）。
        String ext = page.getSourceExt() == null ? "" : page.getSourceExt().toLowerCase(Locale.ROOT);
        String titleLower = page.getTitle() == null ? "" : page.getTitle().toLowerCase(Locale.ROOT);
        boolean isHtmlDoc = "html".equals(ext) || "htm".equals(ext)
                || titleLower.endsWith(".html") || titleLower.endsWith(".htm");
        if (isHtmlDoc) {
            byte[] bytes = (page.getHtmlSnapshot() == null ? "" : page.getHtmlSnapshot())
                    .getBytes(StandardCharsets.UTF_8);
            // 历史文档标题常含 .html/.htm（如 import-test-html.html），已带扩展名则不再追加，避免双后缀
            String baseTitle = page.getTitle() == null ? "page" : page.getTitle();
            String fileName = titleLower.endsWith(".html") || titleLower.endsWith(".htm")
                    ? baseTitle : baseTitle + ".html";
            return download(bytes, fileName, "text/html; charset=UTF-8");
        }
        return ResponseEntity.badRequest().build();
    }

    /**
     * 按文件名后缀推断 Content-Type，未知类型按二进制流
     */
    private String guessContentType(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pptx")) {
            return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        }
        if (lower.endsWith(".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if (lower.endsWith(".xlsx")) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }
        if (lower.endsWith(".pdf")) {
            return MediaType.APPLICATION_PDF_VALUE;
        }
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "text/html; charset=UTF-8";
        }
        if (lower.endsWith(".md") || lower.endsWith(".txt")) {
            return "text/plain; charset=UTF-8";
        }
        if (lower.endsWith(".csv")) {
            return "text/csv; charset=UTF-8";
        }
        if (lower.endsWith(".json")) {
            return "application/json; charset=UTF-8";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        return MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }

    /**
     * 附件下载响应：文件名走 RFC 5987 filename*（UTF-8），兼容中文标题
     */
    private ResponseEntity<byte[]> download(byte[] bytes, String fileName, String contentType) {
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"download\"; filename*=UTF-8''" + encoded)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(bytes.length)
                .body(bytes);
    }

    @PostMapping("/convert/markdown-to-html")
    @Operation(summary = "Markdown 转 HTML（导入：前端将 html setContent 进编辑器）")
    public ReqResult<Map<String, String>> convertMarkdownToHtml(@RequestBody RepoMarkdownConvertRequest request) {
        String html = repoDocConvertService.markdownToHtml(request.getMarkdown());
        return ReqResult.success(Map.of("html", html));
    }
}
