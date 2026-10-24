package com.xspaceagi.agent.web.ui.controller;

import com.xspaceagi.agent.core.adapter.application.IComputerFileApplicationService;
import com.xspaceagi.agent.web.ui.controller.base.BaseController;
import com.xspaceagi.agent.core.application.service.ConversationPermissionChecker;
import com.xspaceagi.agent.web.ui.controller.dto.ComputerFileGenerateReq;
import com.xspaceagi.agent.web.ui.controller.dto.ComputerFileListRes;
import com.xspaceagi.agent.web.ui.controller.dto.ComputerFileMetaReq;
import com.xspaceagi.agent.web.ui.controller.dto.ComputerFileMetaRes;
import com.xspaceagi.agent.web.ui.controller.dto.ComputerFileSearchRes;
import com.xspaceagi.agent.web.ui.controller.dto.ComputerFsChildrenRes;
import com.xspaceagi.agent.web.ui.controller.dto.ComputerFsEntry;
import com.xspaceagi.agent.web.ui.controller.dto.ComputerFsMkdirReq;
import com.xspaceagi.agent.web.ui.controller.dto.ComputerFsRenameReq;
import com.xspaceagi.agent.web.ui.controller.dto.ComputerFsRootsRes;
import com.xspaceagi.agent.web.ui.controller.dto.ComputerFilesUpdateReq;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.utils.I18nUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Tag(name = "沙箱文件接口", description = "沙箱文件接口")
@RestController
@RequestMapping("/api/computer")
@Slf4j
@RequiredArgsConstructor
public class ComputerFileController extends BaseController {

    @Resource
    private IComputerFileApplicationService computerFileApplicationService;
    @Resource
    private ConversationPermissionChecker conversationPermissionChecker;

    @Operation(summary = "文件系统浏览：根列表（包含根目录和用户home）",
            description = "按沙箱定位目标机器。仅用户个人沙箱可用，云端沙箱不支持选目录")
    @GetMapping("/static/fs/roots")
    public ReqResult<ComputerFsRootsRes> fsRoots(@RequestParam("sandboxId") Long sandboxId) {
        Map<String, Object> result = computerFileApplicationService.listFsRoots(sandboxId);
        return fsBrowseResponse(result, ComputerFsRootsRes::from);
    }

    @Operation(summary = "文件系统浏览：列出一层子项（点父目录获取子文件）",
            description = "目录+文件都返回、目录在前名称自然排序，文件由前端按 isDir 置灰；path 为目标机器绝对路径（分隔符统一 /），隐藏文件默认返回")
    @GetMapping("/static/fs/children")
    public ReqResult<ComputerFsChildrenRes> fsChildren(@RequestParam("sandboxId") Long sandboxId,
            @RequestParam("path") String path) {
        Map<String, Object> result = computerFileApplicationService.listFsChildren(sandboxId, path);
        return fsBrowseResponse(result, ComputerFsChildrenRes::from);
    }

    @Operation(summary = "文件系统浏览：新建目录",
            description = "在 parentPath 下创建一层新目录（目录选择弹窗用）")
    @PostMapping("/static/fs/mkdir")
    public ReqResult<ComputerFsEntry> fsMkdir(@RequestBody ComputerFsMkdirReq req) {
        Map<String, Object> result = computerFileApplicationService.createFsDirectory(
                req.getSandboxId(), req.getParentPath(), req.getDirName());
        return fsBrowseResponse(result, ComputerFsEntry::from);
    }

    @Operation(summary = "文件系统浏览：重命名目录",
            description = "同目录重命名，newName 仅名字（不支持跨目录移动）")
    @PostMapping("/static/fs/rename")
    public ReqResult<ComputerFsEntry> fsRename(@RequestBody ComputerFsRenameReq req) {
        Map<String, Object> result = computerFileApplicationService.renameFsDirectory(
                req.getSandboxId(), req.getPath(), req.getNewName());
        return fsBrowseResponse(result, ComputerFsEntry::from);
    }

    /**
     * fs 家族统一响应包装：失败透出下游 message 并保留 code
     */
    private <T> ReqResult<T> fsBrowseResponse(Map<String, Object> result, Function<Map<String, Object>, T> mapper) {
        if (result == null) {
            return ReqResult.error("File server error");
        }
        Object successObj = result.get("success");
        Object codeObj = result.get("code");
        boolean success;
        if (successObj instanceof Boolean) {
            success = (Boolean) successObj;
        } else {
            String code = codeObj != null ? codeObj.toString() : ReqResult.SUCCESS;
            success = ReqResult.SUCCESS.equals(code);
        }
        if (!success) {
            String message = result.getOrDefault("message", "File server error").toString();
            return ReqResult.create(codeObj != null ? codeObj.toString() : "9999", message, null);
        }
        return ReqResult.success(mapper.apply(result));
    }

    @Operation(summary = "查询文件列表", description = "默认 recursive=true 全量扁平（原逻辑）；recursive=false 时按 relativePath 只列当前一层，"
            + "可选 depth（1-10）在单层模式下受限展开：1=当前层（同原单层），N=目录条目向下再展开 N-1 级（目录本身始终作为条目返回，type=file 时仍会下钻取深层文件）；"
            + "可选 type=file/dir/all 过滤返回条目类型（默认 all，非法值按 all 处理），limit 限制返回数量（类型过滤后截取，不传或负数不限制）；"
            + "type/limit 由 file-server 扫描时应用，旧版 file-server 由平台侧兜底过滤")
    @GetMapping("/static/file-list")
    public ReqResult<ComputerFileListRes> getFileList(@RequestParam("cId") Long cId,
                                                       @RequestParam(value = "customTargetDir", required = false) String customTargetDir,
                                                       @RequestParam(value = "relativePath", required = false) String relativePath,
                                                       @Parameter(description = "是否递归全量列出。不传默认 true（原逻辑）；false 为单层浏览")
                                                       @RequestParam(value = "recursive", required = false) Boolean recursive,
                                                       @Parameter(description = "单层模式下的受限展开层级（1-10）：1=仅当前层，N=目录向下再展开 N-1 级；仅在 recursive=false 时生效")
                                                       @RequestParam(value = "depth", required = false) Integer depth,
                                                       @Parameter(description = "返回条目类型：file-仅文件、dir-仅目录、all-全部（默认，非法值按 all 处理）")
                                                       @RequestParam(value = "type", required = false) String type,
                                                       @Parameter(description = "最多返回条目数量（类型过滤后截取）。不传不限制")
                                                       @RequestParam(value = "limit", required = false) Integer limit) {

        Long conversationUserId = conversationPermissionChecker.check(cId, customTargetDir);
        // 前端不传 recursive → 默认 true，与旧全量 list 一致；type/limit 归一与旧版 FS 兜底在应用层统一处理（RPC 入口共用）
        Boolean effectiveRecursive = recursive == null ? Boolean.TRUE : recursive;
        log.info("[Web] File list request, cId={}, conversationUserId={}, loginUserId={}, relativePath={}, recursive={}, depth={}, type={}, limit={}",
                cId, conversationUserId, getUser().getUserId(), relativePath, effectiveRecursive, depth, type, limit);

        String proxyPath = String.format("/api/computer/static/%s", cId);
        Map<String, Object> result = computerFileApplicationService.getFileList(
                conversationUserId, cId, proxyPath, null, customTargetDir, relativePath, effectiveRecursive, depth, type, limit);
        if (result == null) {
            return ReqResult.error(I18nUtil.systemMessage("File.GetFileList.Error"));
        }

        Object successObj = result.get("success");
        Object codeObj = result.get("code");

        boolean success;
        if (successObj instanceof Boolean) {
            success = (Boolean) successObj;
        } else {
            String code = codeObj != null ? codeObj.toString() : ReqResult.SUCCESS;
            success = ReqResult.SUCCESS.equals(code);
        }

        if (!success) {
            String message = result.getOrDefault("message", I18nUtil.systemMessage("File.GetFileList.Error")).toString();
            String code = codeObj != null ? codeObj.toString() : "9999";
            return ReqResult.create(code, message, null);
        }

        return ReqResult.success(ComputerFileListRes.from(result));
    }

    @Operation(summary = "批量查询文件元数据", description = "按需补查大小/mtime/MIME/扩展名/软链目标/目录子项数；单次最多 100 条，单条失败仅该条带 error，不影响整批")
    @PostMapping(value = "/static/file-meta", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<ComputerFileMetaRes> getFileMeta(@RequestBody ComputerFileMetaReq req,
                                                      @RequestParam(value = "customTargetDir", required = false) String customTargetDir) {
        Assert.notNull(req, "Request body is required.");
        Assert.notNull(req.getCId(), "Parameter cId is required.");
        Assert.isTrue(req.getFilePaths() != null && !req.getFilePaths().isEmpty(), "Parameter filePaths is required.");

        Long conversationUserId = conversationPermissionChecker.check(req.getCId(), customTargetDir);
        log.info("[Web] File meta request, cId={}, conversationUserId={}, loginUserId={}, count={}",
                req.getCId(), conversationUserId, getUser().getUserId(), req.getFilePaths().size());

        Map<String, Object> result = computerFileApplicationService.getFileMeta(conversationUserId, req.getCId(), req.getFilePaths(), customTargetDir);
        if (result == null) {
            return ReqResult.error(I18nUtil.systemMessage("File.GetFileList.Error"));
        }

        Object successObj = result.get("success");
        Object codeObj = result.get("code");

        boolean success;
        if (successObj instanceof Boolean) {
            success = (Boolean) successObj;
        } else {
            String code = codeObj != null ? codeObj.toString() : ReqResult.SUCCESS;
            success = ReqResult.SUCCESS.equals(code);
        }

        if (!success) {
            String message = result.getOrDefault("message", I18nUtil.systemMessage("File.GetFileList.Error")).toString();
            String code = codeObj != null ? codeObj.toString() : "9999";
            return ReqResult.create(code, message, null);
        }

        return ReqResult.success(ComputerFileMetaRes.from(result));
    }

    @Operation(summary = "搜索文件")
    @GetMapping("/static/search-files")
    public ReqResult<ComputerFileSearchRes> searchFiles(
            @RequestParam("cId") Long cId,
            @Parameter(description = "搜索关键词（至少 1 个字符；目录名/相对路径子串也可命中）", required = true)
            @RequestParam("kw") String kw,
            @Parameter(description = "自定义根目录（绝对路径）")
            @RequestParam(value = "customTargetDir", required = false) String customTargetDir,
            @Parameter(description = "相对路径")
            @RequestParam(value = "relativePath", required = false) String relativePath,
            @Parameter(description = "命中类型过滤：file-仅文件（目录仍会遍历下钻）、dir-仅目录、不传-全部（默认）")
            @RequestParam(value = "type", required = false) String type,
            @Parameter(description = "最多返回命中条数。未传时默认 100，上限 200")
            @RequestParam(value = "limit", required = false) Integer limit,
            @Parameter(description = "最多访问节点数。未传时100000，上限 200000")
            @RequestParam(value = "maxVisit", required = false) Integer maxVisit,
            @Parameter(description = "搜索超时（毫秒）。未传时 默认 3000，上限 15000；到期后停止扫描并可能返回已截断")
            @RequestParam(value = "timeoutMs", required = false) Integer timeoutMs) {

        Long conversationUserId = conversationPermissionChecker.check(cId, customTargetDir);
        log.info("[Web] File search request, cId={}, conversationUserId={}, loginUserId={}, kw={}, relativePath={}, type={}",
                cId, conversationUserId, getUser().getUserId(), kw, relativePath, type);

        String proxyPath = String.format("/api/computer/static/%s", cId);
        Map<String, Object> result = computerFileApplicationService.searchFiles(
                conversationUserId, cId, proxyPath, kw, null, customTargetDir, relativePath, type, limit, maxVisit, timeoutMs);
        if (result == null) {
            return ReqResult.error(I18nUtil.systemMessage("File.GetFileList.Error"));
        }

        Object successObj = result.get("success");
        Object codeObj = result.get("code");

        boolean success;
        if (successObj instanceof Boolean) {
            success = (Boolean) successObj;
        } else {
            String code = codeObj != null ? codeObj.toString() : ReqResult.SUCCESS;
            success = ReqResult.SUCCESS.equals(code);
        }

        if (!success) {
            String message = result.getOrDefault("message", I18nUtil.systemMessage("File.GetFileList.Error")).toString();
            String code = codeObj != null ? codeObj.toString() : "9999";
            return ReqResult.create(code, message, null);
        }

        ComputerFileSearchRes res = new ComputerFileSearchRes();
        res.setFiles(result.get("files"));
        res.setTruncated(result.get("truncated"));
        res.setVisited(result.get("visited"));
        return ReqResult.success(res);
    }

    @Operation(summary = "文件修改", description = "文件修改")
    @PostMapping(value = "/static/files-update", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Map<String, Object>> filesUpdate(@RequestBody ComputerFilesUpdateReq req,
                                                       @RequestParam(value = "customTargetDir", required = false) String customTargetDir) {
        Long conversationUserId = conversationPermissionChecker.check(req.getCId(), customTargetDir);
        log.info("[Web] File update request, cId={}, conversationUserId={}, loginUserId={}", req.getCId(), conversationUserId, getUser().getUserId());

        Map<String, Object> result = computerFileApplicationService.filesUpdate(conversationUserId, req.getCId(), req.getFiles(), getUser(), customTargetDir);
        if (result == null) {
            return ReqResult.error("File modification failed.");
        }

        Object successObj = result.get("success");
        Object codeObj = result.get("code");

        boolean success;
        if (successObj instanceof Boolean) {
            success = (Boolean) successObj;
        } else {
            String code = codeObj != null ? codeObj.toString() : ReqResult.SUCCESS;
            success = ReqResult.SUCCESS.equals(code);
        }

        if (!success) {
            String message = result.getOrDefault("message", "File modification failed.").toString();
            String code = codeObj != null ? codeObj.toString() : "9999";
            return ReqResult.create(code, message, null);
        }

        return ReqResult.success(result);
    }

    @Operation(summary = "文件上传", description = "文件上传")
    @PostMapping(value = "/static/upload-file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Map<String, Object>> uploadFile(@RequestParam("cId") Long cId,
                                                      @RequestParam(value = "filePath", required = false) String filePath,
                                                      @RequestParam(value = "filePaths", required = false) List<String> filePaths,
                                                      @RequestParam("file") MultipartFile file,
                                                      @RequestParam(value = "customTargetDir", required = false) String customTargetDir) {
        Long conversationUserId = conversationPermissionChecker.check(cId, customTargetDir);

        // 兼容处理：优先使用 filePath，如果没有则从 filePaths 数组取第一个
        String finalFilePath = filePath;
        if (finalFilePath == null || finalFilePath.trim().isEmpty()) {
            if (filePaths != null && !filePaths.isEmpty()) {
                finalFilePath = filePaths.get(0);
                log.warn("[Web] Single-file upload received filePaths array; using first element, cId={}, conversationUserId={}, loginUserId={}, filePath={}", cId, conversationUserId, getUser().getUserId(), finalFilePath);
            } else {
                log.error("[Web] Invalid upload params: both filePath and filePaths are empty, cId={}, conversationUserId={}, loginUserId={}", cId, conversationUserId, getUser().getUserId());
                return ReqResult.error("Param 'filePath' cannot be left blank.");
            }
        }
        
        log.info("[Web] File upload request, cId={}, conversationUserId={}, loginUserId={}, filePath={}", cId, conversationUserId, getUser().getUserId(), finalFilePath);
        UserContext userContext = getUser();
        Map<String, Object> result = computerFileApplicationService.uploadFile(conversationUserId, cId, finalFilePath, file, userContext, customTargetDir);
        if (result == null) {
            return ReqResult.error("File upload failed.");
        }

        Object successObj = result.get("success");
        Object codeObj = result.get("code");

        boolean success;
        if (successObj instanceof Boolean) {
            success = (Boolean) successObj;
        } else {
            String code = codeObj != null ? codeObj.toString() : ReqResult.SUCCESS;
            success = ReqResult.SUCCESS.equals(code);
        }

        if (!success) {
            String message = result.getOrDefault("message", "File upload failed.").toString();
            String code = codeObj != null ? codeObj.toString() : "9999";
            return ReqResult.create(code, message, null);
        }

        return ReqResult.success(result);
    }

    @Operation(summary = "生成文件", description = "根据文本内容生成文件")
    @PostMapping(value = "/static/generate-file", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Map<String, Object>> generateFile(@RequestBody ComputerFileGenerateReq req,
                                                       @RequestParam(value = "customTargetDir", required = false) String customTargetDir) {
        Assert.notNull(req, "Request body is required.");
        Assert.notNull(req.getCId(), "Parameter cId is required.");
        Assert.isTrue(StringUtils.hasText(req.getFileName()), "Parameter fileName is required.");

        Long conversationUserId = conversationPermissionChecker.check(req.getCId(), customTargetDir);
        log.info("[Web] Generate file request, cId={}, conversationUserId={}, loginUserId={}, fileName={}",
                req.getCId(), conversationUserId, getUser().getUserId(), req.getFileName());

        UserContext userContext = getUser();
        Map<String, Object> result = computerFileApplicationService.generateFile(
                conversationUserId, req.getCId(), req.getFileName(), req.getContent(), userContext, customTargetDir);
        if (result == null) {
            return ReqResult.error("File generation failed.");
        }

        Object successObj = result.get("success");
        Object codeObj = result.get("code");

        boolean success;
        if (successObj instanceof Boolean) {
            success = (Boolean) successObj;
        } else {
            String code = codeObj != null ? codeObj.toString() : ReqResult.SUCCESS;
            success = ReqResult.SUCCESS.equals(code);
        }

        if (!success) {
            String message = result.getOrDefault("message", "File generation failed.").toString();
            String code = codeObj != null ? codeObj.toString() : "9999";
            return ReqResult.create(code, message, null);
        }

        return ReqResult.success(result);
    }

    @Operation(summary = "批量文件上传", description = "批量文件上传")
    @PostMapping(value = "/static/upload-files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Map<String, Object>> uploadFiles(@RequestParam("cId") Long cId,
                                                      @RequestParam("filePaths") List<String> filePaths,
                                                      @RequestParam("files") List<MultipartFile> files,
                                                      @RequestParam(value = "customTargetDir", required = false) String customTargetDir) {
        Long conversationUserId = conversationPermissionChecker.check(cId, customTargetDir);

        if (filePaths == null || filePaths.isEmpty()) {
            log.error("[Web] filePaths is empty, cId={}, conversationUserId={}, loginUserId={}", cId, conversationUserId, getUser().getUserId());
            return ReqResult.error("Param 'filePaths' cannot be left blank.");
        }
        
        log.info("[Web] Batch file upload request, cId={}, conversationUserId={}, loginUserId={}, filePathsSize={}, filesSize={}, filePaths={}",
                cId, conversationUserId, getUser().getUserId(),
                filePaths.size(),
                files != null ? files.size() : 0,
                filePaths);
        UserContext userContext = getUser();
        Map<String, Object> result = computerFileApplicationService.uploadFiles(conversationUserId, cId, filePaths, files, userContext, customTargetDir);
        if (result == null) {
            return ReqResult.error("Batch file upload failed");
        }

        Object successObj = result.get("success");
        Object codeObj = result.get("code");

        boolean success;
        if (successObj instanceof Boolean) {
            success = (Boolean) successObj;
        } else {
            String code = codeObj != null ? codeObj.toString() : ReqResult.SUCCESS;
            success = ReqResult.SUCCESS.equals(code);
        }

        if (!success) {
            String message = result.getOrDefault("message", "Batch file upload failed").toString();
            String code = codeObj != null ? codeObj.toString() : "9999";
            return ReqResult.create(code, message, null);
        }

        return ReqResult.success(result);
    }

    @Operation(summary = "导入项目", description = "上传 zip 包替换工作空间文件，保留 .git/.agents/.claude/.codex/.opencode/.tmp/.logs")
    @PostMapping(value = "/static/import-project", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Map<String, Object>> importProject(@RequestParam("cId") Long cId,
                                                        @RequestParam("file") MultipartFile file,
                                                        @RequestParam(value = "customTargetDir", required = false) String customTargetDir) {
        Long conversationUserId = conversationPermissionChecker.check(cId, customTargetDir);

        if (file == null || file.isEmpty()) {
            log.error("[Web] Invalid import project params: file is empty, cId={}, loginUserId={}", cId, getUser().getUserId());
            return ReqResult.error("Param 'file' cannot be left blank.");
        }
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".zip")) {
            log.error("[Web] Invalid import project params: only zip is supported, cId={}, fileName={}", cId, originalFilename);
            return ReqResult.error("Only zip files are supported.");
        }

        log.info("[Web] Import project request, cId={}, conversationUserId={}, loginUserId={}, fileName={}",
                cId, conversationUserId, getUser().getUserId(), originalFilename);

        UserContext userContext = getUser();
        Map<String, Object> result = computerFileApplicationService.importProject(conversationUserId, cId, file, userContext, customTargetDir);
        if (result == null) {
            return ReqResult.error("Project import failed.");
        }

        Object successObj = result.get("success");
        Object codeObj = result.get("code");

        boolean success;
        if (successObj instanceof Boolean) {
            success = (Boolean) successObj;
        } else {
            String code = codeObj != null ? codeObj.toString() : ReqResult.SUCCESS;
            success = ReqResult.SUCCESS.equals(code);
        }

        if (!success) {
            String message = result.getOrDefault("message", "Project import failed.").toString();
            String code = codeObj != null ? codeObj.toString() : "9999";
            return ReqResult.create(code, message, null);
        }

        return ReqResult.success(result);
    }

    @Operation(summary = "下载全部文件", description = "下载全部文件（zip 流式输出）")
    @GetMapping("/static/download-all-files")
    public ResponseEntity<StreamingResponseBody> downloadAllFiles(@RequestParam("cId") Long cId,
                                                                    @RequestParam(value = "customTargetDir", required = false) String customTargetDir) {
        Long conversationUserId = conversationPermissionChecker.check(cId, customTargetDir);
        log.info("[Web] Download all files request, cId={}, conversationUserId={}, loginUserId={}", cId, conversationUserId, getUser().getUserId());

        return computerFileApplicationService.downloadAllFiles(conversationUserId, cId, getUser(), customTargetDir);
    }

    @Operation(summary = "静态文件访问", description = "静态文件访问，返回二进制流（图片、文件等）")
    @CrossOrigin // 确保 OPTIONS 预检请求被正确处理
    @GetMapping(value = "/static/{cId}/**")
    public ResponseEntity<StreamingResponseBody> getUserStaticFile(@PathVariable("cId") Long cId, HttpServletRequest request) {
        log.info("[Web] Static file access request, cId={}", cId);
        if (cId == null) {
            log.error("[Web] Invalid cId, cId={}", cId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        String customTargetDir = request.getParameter("customTargetDir");
        conversationPermissionChecker.checkCustomTargetDir(cId, customTargetDir);

        return computerFileApplicationService.getStaticFile(cId, request, customTargetDir);
    }

    @Operation(summary = "获取沙盒日志", description = "获取沙盒最新日志，取最后修改的日志文件的最后N行")
    @GetMapping("/logs")
    public ReqResult<Map<String, Object>> getLogs(@RequestParam("cId") Long cId,
                                                  @RequestParam(value = "tailLines", defaultValue = "200") int tailLines) {
        Long conversationUserId = conversationPermissionChecker.check(cId);
        log.info("[Web] Log request, cId={}, conversationUserId={}, loginUserId={}, tailLines={}", cId, conversationUserId, getUser().getUserId(), tailLines);

        Map<String, Object> result = computerFileApplicationService.getLogs(conversationUserId, cId, tailLines);
        if (result == null) {
            return ReqResult.error("Failed to get logs.");
        }

        Object successObj = result.get("success");
        Object codeObj = result.get("code");

        boolean success;
        if (successObj instanceof Boolean) {
            success = (Boolean) successObj;
        } else {
            String code = codeObj != null ? codeObj.toString() : ReqResult.SUCCESS;
            success = ReqResult.SUCCESS.equals(code);
        }

        if (!success) {
            String message = result.getOrDefault("message", "Failed to get logs.").toString();
            String code = codeObj != null ? codeObj.toString() : "9999";
            return ReqResult.create(code, message, null);
        }

        return ReqResult.success(result);
    }
}