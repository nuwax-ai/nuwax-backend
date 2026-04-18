package com.xspaceagi.agent.web.ui.controller;

import com.xspaceagi.agent.core.adapter.application.IComputerFileApplicationService;
import com.xspaceagi.agent.web.ui.controller.base.BaseController;
import com.xspaceagi.agent.web.ui.controller.dto.ComputerFileListRes;
import com.xspaceagi.agent.web.ui.controller.dto.ComputerFilesUpdateReq;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.utils.I18nUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;
import java.util.Map;

@Tag(name = "虚拟桌面文件接口", description = "虚拟桌面文件接口")
@RestController
@RequestMapping("/api/computer")
@Slf4j
@RequiredArgsConstructor
public class ComputerFileController extends BaseController {

    @Resource
    private IComputerFileApplicationService computerFileApplicationService;

    @Operation(summary = "查询文件列表", description = "查询文件列表")
    @GetMapping("/static/file-list")
    public ReqResult<ComputerFileListRes> getFileList(@RequestParam("cId") Long cId) {
        Long userId = getUser().getUserId();
        log.info("[Web] File list request, userId={}, cId={}", userId, cId);
//        UserContext userContext = getUser();
        String proxyPath = String.format("/api/computer/static/%s", cId);
        Map<String, Object> result = computerFileApplicationService.getFileList(userId, cId, proxyPath, null);
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

        ComputerFileListRes res = new ComputerFileListRes();
        res.setFiles(result.get("files"));
        return ReqResult.success(res);
    }

    @Operation(summary = "文件修改", description = "文件修改")
    @PostMapping(value = "/static/files-update", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Map<String, Object>> filesUpdate(@RequestBody ComputerFilesUpdateReq req) {
        Long userId = getUser().getUserId();
        log.info("[Web] File update request, userId={}, cId={}", userId, req.getCId());
        UserContext userContext = getUser();
        Map<String, Object> result = computerFileApplicationService.filesUpdate(userId, req.getCId(), req.getFiles(), userContext);
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
                                                      @RequestParam("file") MultipartFile file) {
        Long userId = getUser().getUserId();
        
        // 兼容处理：优先使用 filePath，如果没有则从 filePaths 数组取第一个
        String finalFilePath = filePath;
        if (finalFilePath == null || finalFilePath.trim().isEmpty()) {
            if (filePaths != null && !filePaths.isEmpty()) {
                finalFilePath = filePaths.get(0);
                log.warn("[Web] Single-file upload received filePaths array; using first element, userId={}, cId={}, filePath={}", userId, cId, finalFilePath);
            } else {
                log.error("[Web] Invalid upload params: both filePath and filePaths are empty, userId={}, cId={}", userId, cId);
                return ReqResult.error("Param 'filePath' cannot be left blank.");
            }
        }
        
        log.info("[Web] File upload request, userId={}, cId={}, filePath={}", userId, cId, finalFilePath);
        UserContext userContext = getUser();
        Map<String, Object> result = computerFileApplicationService.uploadFile(userId, cId, finalFilePath, file, userContext);
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

    @Operation(summary = "批量文件上传", description = "批量文件上传")
    @PostMapping(value = "/static/upload-files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Map<String, Object>> uploadFiles(@RequestParam("cId") Long cId,
                                                      @RequestParam("filePaths") List<String> filePaths,
                                                      @RequestParam("files") List<MultipartFile> files) {
        Long userId = getUser().getUserId();
        
        if (filePaths == null || filePaths.isEmpty()) {
            log.error("[Web] filePaths is empty, userId={}, cId={}", userId, cId);
            return ReqResult.error("Param 'filePaths' cannot be left blank.");
        }
        
        log.info("[Web] Batch file upload request, userId={}, cId={}, filePathsSize={}, filesSize={}, filePaths={}", userId, cId,
                filePaths.size(),
                files != null ? files.size() : 0,
                filePaths);
        UserContext userContext = getUser();
        Map<String, Object> result = computerFileApplicationService.uploadFiles(userId, cId, filePaths, files, userContext);
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

    @Operation(summary = "下载全部文件", description = "下载全部文件（zip 流式输出）")
    @GetMapping("/static/download-all-files")
    public ResponseEntity<StreamingResponseBody> downloadAllFiles(@RequestParam("cId") Long cId) {
        Long userId = getUser().getUserId();
        log.info("[Web] Download all files request, userId={}, cId={}", userId, cId);
        return computerFileApplicationService.downloadAllFiles(userId, cId, getUser());
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
        return computerFileApplicationService.getStaticFile(cId, request);
    }

}