package com.xspaceagi.custompage.ui.web.controller;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xspaceagi.custompage.application.service.ICustomPageCodingApplicationService;
import com.xspaceagi.custompage.ui.web.dto.PageFilesUpdateReq;
import com.xspaceagi.custompage.ui.web.dto.RollbackVersionReq;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.exception.SpacePermissionException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

import static com.xspaceagi.system.spec.enums.ResourceEnum.*;

@Tag(name = "Web app", description = "Custom page web app APIs")
@RestController
@RequestMapping("/api/custom-page")
@Slf4j
@RequiredArgsConstructor
public class CustomPageCodingController extends BaseController {

    @Resource
    private ICustomPageCodingApplicationService customPageCodingApplicationService;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @RequireResource(PAGE_APP_MODIFY_FILE)
    @Operation(summary = "Update specified files", description = "Update specified project files")
    @PostMapping(value = "/specified-files-update", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Map<String, Object>> specifiedFilesUpdate(@RequestBody PageFilesUpdateReq req) {
        log.info("[Web] specifiedfileupdaterequest, project Id={}", req.getProjectId());
        try {
            String filesJson = objectMapper.writeValueAsString(req.getFiles());
            log.info("[Web] File info JSON: {}", filesJson);

            UserContext userContext = getUser();
            return customPageCodingApplicationService.specifiedFilesUpdate(req.getProjectId(), req.getFiles(),
                    userContext);
        } catch (SpacePermissionException e) {
            log.error("[Web] specifiedfileupdatefailed, project Id={}, {}", req.getProjectId(), e.getMessage());
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[Web] specifiedfileupdatefailed, project Id={}", req.getProjectId(), e);
            return ReqResult.error("0001", "Update specified files failed: " + e.getMessage());
        }
    }

    @RequireResource(PAGE_APP_MODIFY_FILE)
    @Operation(summary = "Full file update", description = "Submit full project file update")
    @PostMapping(value = "/submit-files-update", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Map<String, Object>> submitFilesUpdate(@RequestBody PageFilesUpdateReq req) {
        log.info("[Web] fullfileupdaterequest, project Id={}", req.getProjectId());
        try {
            String filesJson = objectMapper.writeValueAsString(req.getFiles());
            log.info("[Web] File info JSON: {}", filesJson);

            UserContext userContext = getUser();
            return customPageCodingApplicationService.allFilesUpdate(req.getProjectId(), req.getFiles(),
                    userContext);
        } catch (SpacePermissionException e) {
            log.error("[Web] fullfileupdatefailed, project Id={}, {}", req.getProjectId(), e.getMessage());
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[Web] fullfileupdatefailed, project Id={}", req.getProjectId(), e);
            return ReqResult.error("0001", "Full file update failed: " + e.getMessage());
        }
    }

    @RequireResource(PAGE_APP_UPLOAD_FILE)
    @Operation(summary = "Upload single file", description = "Upload a single file to a path in the project")
    @PostMapping(value = "/upload-single-file", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Map<String, Object>> uploadSingleFile(
            @RequestParam("projectId") Long projectId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("filePath") String filePath) {
        log.info("[Web] upload single filerequest, project Id={}, file Path={}", projectId, filePath);
        try {
            if (file == null || file.isEmpty()) {
                return ReqResult.error("0001", "File is required");
            }
            if (filePath == null || filePath.trim().isEmpty()) {
                return ReqResult.error("0001", "File path is required");
            }

            UserContext userContext = getUser();
            return customPageCodingApplicationService.uploadSingleFile(projectId, file, filePath, userContext);
        } catch (SpacePermissionException e) {
            log.error("[Web] upload single filefailed, project Id={}, file Path={}, {}", projectId, filePath, e.getMessage());
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[Web] upload single filefailed, project Id={}, file Path={}", projectId, filePath, e);
            return ReqResult.error("0001", "Upload single file failed: " + e.getMessage());
        }
    }

    @RequireResource(PAGE_APP_UPLOAD_FILE)
    @Operation(summary = "Upload batch files", description = "Upload multiple files to paths in the project")
    @PostMapping(value = "/upload-batch-files", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Map<String, Object>> uploadBatchFiles(
            @RequestParam("projectId") Long projectId,
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam("filePaths") List<String> filePaths) {
        log.info("[Web] upload batch files request, project Id={}, fileCount={}", projectId, files != null ? files.size() : 0);
        try {
            if (files == null || files.isEmpty()) {
                return ReqResult.error("0001", "Files are required");
            }
            if (filePaths == null || filePaths.size() != files.size()) {
                return ReqResult.error("0001", "filePaths and files count mismatch");
            }

            UserContext userContext = getUser();
            return customPageCodingApplicationService.uploadBatchFiles(projectId, files, filePaths, userContext);
        } catch (SpacePermissionException e) {
            log.error("[Web] upload batch files failed, project Id={}, {}", projectId, e.getMessage());
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[Web] upload batch files failed, project Id={}", projectId, e);
            return ReqResult.error("0001", "Upload batch files failed: " + e.getMessage());
        }
    }

    @RequireResource(PAGE_APP_QUERY_DETAIL)
    @Operation(summary = "Get file proxy URL", description = "Get reverse-proxy URL for a single file")
    @GetMapping(value = "/file-proxy-url", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<String> getFileProxyUrl(
            @RequestParam("projectId") Long projectId,
            @RequestParam("filePath") String filePath) {
        log.info("[Web] getfileproxy URLrequest, project Id={}, file Path={}", projectId, filePath);
        try {
            if (projectId == null || projectId <= 0) {
                return ReqResult.error("0001", "projectId is required or invalid");
            }
            if (filePath == null || filePath.trim().isEmpty()) {
                return ReqResult.error("0001", "filePath is required");
            }

            UserContext userContext = getUser();
            return customPageCodingApplicationService.getFileProxyUrl(projectId, filePath, userContext);
        } catch (SpacePermissionException e) {
            log.error("[Web] getfileproxy URLfailed, project Id={}, file Path={}, {}", projectId, filePath, e.getMessage());
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[Web] getfileproxy URLfailed, project Id={}, file Path={}", projectId, filePath, e);
            return ReqResult.error("0001", "Failed to get file proxy URL: " + e.getMessage());
        }
    }

    @RequireResource(PAGE_APP_ROLLBACK_VERSION)
    @Operation(summary = "Rollback version", description = "Rollback project to a specified version")
    @PostMapping(value = "/rollback-version", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Map<String, Object>> rollbackVersion(@RequestBody RollbackVersionReq req) {
        log.info("[Web] rollback versionrequest, project Id={}, rollback To={}", req.getProjectId(), req.getRollbackTo());
        try {
            UserContext userContext = getUser();
            return customPageCodingApplicationService.rollbackVersion(req.getProjectId(), req.getRollbackTo(),
                    userContext);
        } catch (SpacePermissionException e) {
            log.error("[Web] rollback versionfailed, project Id={}, rollback To={}, {}", req.getProjectId(), req.getRollbackTo(), e.getMessage());
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[Web] rollback versionfailed, project Id={}, rollback To={}", req.getProjectId(), req.getRollbackTo(), e);
            return ReqResult.error("0001", "Rollback version failed: " + e.getMessage());
        }
    }

}