package com.xspaceagi.agent.core.application.service;

import com.xspaceagi.agent.core.adapter.application.IComputerFileApplicationService;
import com.xspaceagi.agent.core.adapter.dto.ComputerFileInfo;
import com.xspaceagi.agent.core.adapter.dto.ConversationDto;
import com.xspaceagi.agent.core.domain.service.IComputerFileDomainService;
import com.xspaceagi.agent.core.infra.rpc.ComputerFileClient;
import com.xspaceagi.agent.core.infra.rpc.SandboxServerConfigService;
import com.xspaceagi.agent.core.infra.rpc.UserShareRpcService;
import com.xspaceagi.agent.core.infra.rpc.dto.SandboxServerConfig;
import com.xspaceagi.agent.core.spec.utils.FileTypeUtils;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.AuthService;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.sdk.service.dto.UserShareDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.sandbox.sdk.server.ISandboxConfigRpcService;
import com.xspaceagi.sandbox.sdk.service.dto.SandboxConfigRpcDto;
import com.xspaceagi.sandbox.spec.enums.SandboxScopeEnum;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.SpacePermissionException;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Signal;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.Objects;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@Service
public class ComputerFileApplicationServiceImpl implements IComputerFileApplicationService {

    @Resource
    private IComputerFileDomainService computerFileDomainService;
    @Resource
    private SandboxServerConfigService sandboxServerConfigService;
    @Resource
    private UserShareRpcService userShareRpcService;
    @Resource
    private AuthService authService;
    @Resource
    private SpacePermissionService spacePermissionService;
    @Resource
    private ConversationPermissionChecker conversationPermissionChecker;
    @Resource
    private ComputerFileClient computerFileClient;
    @Resource
    private ISandboxConfigRpcService iSandboxConfigRpcService;

    private static final int SEARCH_MIN_KW_LENGTH = 1;  //搜索关键词最短长度（按 codePoint 计数，单个汉字/字母可搜）
    private static final int SEARCH_DEFAULT_LIMIT = 100; // 返回文件数量上限（前端不传时）
    private static final int SEARCH_MAX_LIMIT = 200; // 返回文件数量最大上限
    private static final int SEARCH_DEFAULT_MAX_VISIT = 100_000; // 搜索文件数量上限（前端不传时）
    private static final int SEARCH_MAX_MAX_VISIT = 200_000; // 搜索文件数量最大上限
    private static final int SEARCH_DEFAULT_TIMEOUT_MS = 3_000; // 搜索耗时上限，超过不再搜索，直接返回（前端不传时）
    private static final int SEARCH_MAX_TIMEOUT_MS = 15_000; // 搜索耗时最大上限

    @Override
    public Map<String, Object> listFsRoots(Long sandboxId) {
        SandboxConfigRpcDto config = requireSandboxBrowsePermission(sandboxId);
        return computerFileClient.listFsRoots(config);
    }

    @Override
    public Map<String, Object> listFsChildren(Long sandboxId, String path) {
        requireText(path, "path");
        SandboxConfigRpcDto config = requireSandboxBrowsePermission(sandboxId);
        return computerFileClient.listFsChildren(config, path);
    }

    @Override
    public Map<String, Object> createFsDirectory(Long sandboxId, String parentPath, String dirName) {
        requireText(parentPath, "parentPath");
        requireText(dirName, "dirName");
        SandboxConfigRpcDto config = requireSandboxBrowsePermission(sandboxId);
        return computerFileClient.createFsDirectory(config, parentPath, dirName);
    }

    @Override
    public Map<String, Object> renameFsDirectory(Long sandboxId, String path, String newName) {
        requireText(path, "path");
        requireText(newName, "newName");
        SandboxConfigRpcDto config = requireSandboxBrowsePermission(sandboxId);
        return computerFileClient.renameFsDirectory(config, path, newName);
    }

    /** 空白参数校验：统一 "X is required" 文案 */
    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    /**
     * 浏览权限：仅用户本人个人沙箱支持选目录；返回已加载的沙箱配置供下游复用（免二次查询）。
     * 查询走租户上下文（queryByIdInTenant）：跨租户/不存在统一报 Sandbox not found（不暴露可区分探针），
     * 缺租户上下文由租户处理器直接报错，不做 ignoreCheck 兜底。
     */
    private SandboxConfigRpcDto requireSandboxBrowsePermission(Long sandboxId) {
        if (sandboxId == null || sandboxId <= 0) {
            throw new IllegalArgumentException("sandboxId is required");
        }
        SandboxConfigRpcDto config = iSandboxConfigRpcService.queryByIdInTenant(sandboxId);
        if (config == null) {
            throw new IllegalArgumentException("Sandbox not found");
        }
        if (SandboxScopeEnum.USER != config.getScope()) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(),
                    "Directory selection is only supported on personal sandboxes");
        }
        if (!Objects.equals(config.getUserId(), RequestContext.get().getUserId())) {
            throw new BizException(ErrorCodeEnum.PERMISSION_DENIED.getCode(), "No permission to browse this sandbox");
        }
        return config;
    }

    private static int resolveSearchBound(Integer value, int defaultValue, int max) {
        if (value == null || value <= 0) {
            return defaultValue;
        }
        return Math.min(value, max);
    }

    @Override
    public Map<String, Object> getFileList(Long conversationUserId, Long cId, String proxyPath, UserContext userContext, String customTargetDir, String relativePath, Boolean recursive, Integer depth, String type, Integer limit) {
        if (conversationUserId == null || conversationUserId <= 0) {
            log.error("[Web] Invalid conversationUserId, conversationUserId={}", conversationUserId);
            return buildError("conversationUserId is invalid");
        }
        if (cId == null || cId <= 0) {
            log.error("[Web] Invalid cId, cId={}", cId);
            return buildError("cId is invalid");
        }
        // type/limit 归一化
        String normalizedType = normalizeListType(type);
        if (normalizedType == null && StringUtils.isNotBlank(type)) {
            log.warn("[Web] Unknown file list type '{}', fallback to all, cId={}", type, cId);
        }
        Integer safeLimit = limit != null && limit >= 0 ? limit : null;
        if (limit != null && limit < 0) {
            log.warn("[Web] Invalid file list limit {}, fallback to unlimited, cId={}", limit, cId);
        }
        // depth 归一化：仅单层模式（recursive=false）下生效的受限展开层级数，1-10；null=不展开。
        // 递归模式下 depth 是无操作参数：忽略（不校验、不下发），与 file-server 口径一致。
        Integer safeDepth = null;
        if (Boolean.FALSE.equals(recursive) && depth != null) {
            if (depth < 1 || depth > 10) {
                return buildError("depth must be between 1 and 10");
            }
            safeDepth = depth;
        }
        try {
            Map<String, Object> result = computerFileDomainService.getFileList(
                    conversationUserId, cId, proxyPath, userContext, customTargetDir, relativePath, recursive, safeDepth, normalizedType, safeLimit);
            // 混部窗口：旧版 file-server（无 depth 能力）会静默丢弃 depth 参数——响应缺 depth 回显键时告警，
            if (safeDepth != null && result != null && !result.containsKey("depth")) {
                log.warn("[Web] file-server does not support depth (no echo), depth={} silently ignored, cId={}", safeDepth, cId);
            }
            // 混部窗口：旧版 file-server（< 1.3.6 无单层能力）会静默丢弃 recursive 参数——响应缺 recursive 回显键时告警，
            // 此时"单层请求"实际返回全量扁平列表（且非空目录无条目），前端按路径推导才正确
            if (Boolean.FALSE.equals(recursive) && result != null && !result.containsKey("recursive")) {
                log.warn("[Web] file-server does not support single-layer list (no recursive echo), recursive=false silently ignored, cId={}", cId);
            }
            return applyLegacyFileListFallback(result, normalizedType, safeLimit);
        } catch (BizException e) {
            // 语义化业务错误（沙箱不在线/被禁用/会话不存在等）直接抛出返回原始错误，不吞掉成通用错误
            throw e;
        } catch (Exception e) {
            log.error("[Web] Exception querying file list, conversationUserId={}, cId={}", conversationUserId, cId, e);
            return buildError("Failed to query file list");
        }
    }

    /**
     * type 归一为规范字符串：file→file、dir/directory→dir、其他→null（按 all 处理）
     */
    private static String normalizeListType(String type) {
        if (StringUtils.isBlank(type)) {
            return null;
        }
        return switch (type.trim().toLowerCase()) {
            case "file" -> "file";
            case "dir", "directory" -> "dir";
            default -> null;
        };
    }

    /**
     * 旧版 file-server（不识别 type/limit、响应无生效参数回显）时的平台侧兜底过滤，保证新旧 FS 对两个入口行为一致。
     * 新版 FS 扫描时已应用并回显 type/limit，直接返回。
     */
    private Map<String, Object> applyLegacyFileListFallback(Map<String, Object> result, String type, Integer limit) {
        // 无过滤需求，或新版 FS 已应用（type/limit 两键同时回显；即使存在只回显其一的中间版本，
        // 兜底的过滤/截断对已应用的结果幂等，宁可重做不可漏做）——都无需处理
        if (result == null || (type == null && limit == null)
                || (result.containsKey("type") && result.containsKey("limit"))) {
            return result;
        }
        Object filesObj = result.get("files");
        if (!(filesObj instanceof List<?> files) || files.isEmpty()) {
            return result;
        }
        List<Object> filtered = new ArrayList<>(files);
        if (type != null) {
            boolean wantDir = "dir".equals(type);
            filtered.removeIf(entry -> wantDir != isFileListDirectoryEntry(entry));
        }
        if (limit != null && filtered.size() > limit) {
            filtered.subList(limit, filtered.size()).clear();
        }
        result.put("files", filtered);
        return result;
    }

    /**
     * 判断文件列表条目是否目录：file-server 条目为 Map（isDir 字段，同 file-meta/fs 接口），兼容字符串路径按尾随 / 判断
     */
    private static boolean isFileListDirectoryEntry(Object entry) {
        if (entry instanceof Map<?, ?> map) {
            return Boolean.TRUE.equals(map.get("isDir"));
        }
        return entry instanceof String path && path.endsWith("/");
    }

    @Override
    public Map<String, Object> resolveExistingFile(Long conversationUserId, Long cId, String proxyPath, String filePath, String customTargetDir) {
        if (conversationUserId == null || conversationUserId <= 0) {
            log.error("[Web] Invalid conversationUserId, conversationUserId={}", conversationUserId);
            return buildError("conversationUserId is invalid");
        }
        if (cId == null || cId <= 0) {
            log.error("[Web] Invalid cId, cId={}", cId);
            return buildError("cId is invalid");
        }
        if (filePath == null || filePath.isBlank()) {
            return buildError("filePath is invalid");
        }
        try {
            return computerFileDomainService.resolveExistingFile(conversationUserId, cId, proxyPath, filePath, customTargetDir);
        } catch (BizException e) {
            // 语义化业务错误（沙箱不在线/被禁用/会话不存在等）直接抛出返回原始错误，不吞掉成通用错误
            throw e;
        } catch (Exception e) {
            log.error("[Web] Exception resolving file, conversationUserId={}, cId={}, filePath={}", conversationUserId, cId, filePath, e);
            return buildError("Failed to resolve file");
        }
    }

    @Override
    public Map<String, Object> getFileMeta(Long conversationUserId, Long cId, List<String> filePaths, String customTargetDir) {
        if (conversationUserId == null || conversationUserId <= 0) {
            log.error("[Web] Invalid conversationUserId, conversationUserId={}", conversationUserId);
            return buildError("conversationUserId is invalid");
        }
        if (cId == null || cId <= 0) {
            log.error("[Web] Invalid cId, cId={}", cId);
            return buildError("cId is invalid");
        }
        if (filePaths == null || filePaths.isEmpty()) {
            return buildError("filePaths is invalid");
        }
        try {
            return computerFileDomainService.getFileMeta(conversationUserId, cId, filePaths, customTargetDir);
        } catch (BizException e) {
            // 语义化业务错误（沙箱不在线/被禁用/会话不存在等）直接抛出返回原始错误，不吞掉成通用错误
            throw e;
        } catch (Exception e) {
            log.error("[Web] Exception querying file meta, conversationUserId={}, cId={}", conversationUserId, cId, e);
            return buildError("Failed to query file meta");
        }
    }

    @Override
    public Map<String, Object> searchFiles(Long conversationUserId, Long cId, String proxyPath, String kw, UserContext userContext, String customTargetDir, String relativePath, String type, Integer limit, Integer maxVisit, Integer timeoutMs) {
        if (conversationUserId == null || conversationUserId <= 0) {
            log.error("[Web] Invalid conversationUserId, conversationUserId={}", conversationUserId);
            return buildError("conversationUserId is invalid");
        }
        if (cId == null || cId <= 0) {
            log.error("[Web] Invalid cId, cId={}", cId);
            return buildError("cId is invalid");
        }
        if (kw == null || kw.isBlank()) {
            return buildError("kw is invalid");
        }
        String trimmedKw = kw.trim();
        if (trimmedKw.codePointCount(0, trimmedKw.length()) < SEARCH_MIN_KW_LENGTH) {
            return buildError("kw must be at least " + SEARCH_MIN_KW_LENGTH + " characters");
        }
        // type 归一（与 file-list 的 normalizeListType 同口径）：file/dir/其他按 all
        String normalizedType = normalizeListType(type);
        if (normalizedType == null && StringUtils.isNotBlank(type)) {
            log.warn("[Web] Unknown file search type '{}', fallback to all, cId={}", type, cId);
        }
        int safeLimit = resolveSearchBound(limit, SEARCH_DEFAULT_LIMIT, SEARCH_MAX_LIMIT);
        int safeMaxVisit = resolveSearchBound(maxVisit, SEARCH_DEFAULT_MAX_VISIT, SEARCH_MAX_MAX_VISIT);
        int safeTimeoutMs = resolveSearchBound(timeoutMs, SEARCH_DEFAULT_TIMEOUT_MS, SEARCH_MAX_TIMEOUT_MS);
        try {
            return computerFileDomainService.searchFiles(
                    conversationUserId, cId, proxyPath, trimmedKw, userContext, customTargetDir, relativePath, normalizedType,
                    safeLimit, safeMaxVisit, safeTimeoutMs);
        } catch (BizException e) {
            // 语义化业务错误（沙箱不在线/被禁用/会话不存在等）直接抛出返回原始错误，不吞掉成通用错误
            throw e;
        } catch (Exception e) {
            log.error("[Web] Exception searching files, conversationUserId={}, cId={}, kw={}", conversationUserId, cId, kw, e);
            return buildError("Failed to search files");
        }
    }

    @Override
    public Map<String, Object> getLogs(Long conversationUserId, Long cId, int tailLines) {
        if (conversationUserId == null || conversationUserId <= 0) {
            log.error("[Web] Invalid conversationUserId, conversationUserId={}", conversationUserId);
            return buildError("conversationUserId is invalid");
        }
        if (cId == null || cId <= 0) {
            log.error("[Web] Invalid cId, cId={}", cId);
            return buildError("cId is invalid");
        }
        int safeTailLines = Math.max(1, tailLines);
        try {
            return computerFileDomainService.getLogs(conversationUserId, cId, safeTailLines, null);
        } catch (BizException e) {
            // 语义化业务错误（沙箱不在线/被禁用/会话不存在等）直接抛出返回原始错误，不吞掉成通用错误
            throw e;
        } catch (Exception e) {
            log.error("[Web] Exception querying logs, conversationUserId={}, cId={}", conversationUserId, cId, e);
            return buildError("Failed to query logs");
        }
    }

    @Override
    public Map<String, Object> filesUpdate(Long conversationUserId, Long cId, List<ComputerFileInfo> files, UserContext userContext, String customTargetDir) {
        if (conversationUserId == null || conversationUserId <= 0) {
            log.error("[Web] Invalid conversationUserId, conversationUserId={}", conversationUserId);
            return buildError("conversationUserId is invalid");
        }
        if (cId == null || cId <= 0) {
            log.error("[Web] Invalid cId, cId={}", cId);
            return buildError("cId is invalid");
        }
        if (files == null || files.isEmpty()) {
            log.error("[Web] files is invalid, files is empty");
            return buildError("files is invalid");
        }
        try {
            return computerFileDomainService.filesUpdate(conversationUserId, cId, files, userContext, customTargetDir);
        } catch (BizException e) {
            // 语义化业务错误（沙箱不在线/被禁用/会话不存在等）直接抛出返回原始错误，不吞掉成通用错误
            throw e;
        } catch (Exception e) {
            log.error("[Web] Exception updating user file list, conversationUserId={}, cId={}", conversationUserId, cId, e);
            return buildError("Failed to update user file list, please check if filename is duplicated");
        }
    }

    @Override
    public Map<String, Object> uploadFile(Long conversationUserId, Long cId, String filePath, MultipartFile file, UserContext userContext, String customTargetDir) {
        if (conversationUserId == null || conversationUserId <= 0) {
            log.error("[Web] Invalid conversationUserId, conversationUserId={}", conversationUserId);
            return buildError("conversationUserId is invalid");
        }
        if (cId == null || cId <= 0) {
            log.error("[Web] Invalid cId, cId={}", cId);
            return buildError("cId is invalid");
        }
        if (filePath == null || filePath.trim().isEmpty()) {
            log.error("[Web] filePath is invalid, filePath={}", filePath);
            return buildError("filePath is invalid");
        }
        if (file == null || file.isEmpty()) {
            log.error("[Web] file is invalid, file is empty");
            return buildError("file is invalid");
        }
        try {
            return computerFileDomainService.uploadFile(conversationUserId, cId, filePath, file, userContext, customTargetDir);
        } catch (BizException e) {
            // 语义化业务错误（沙箱不在线/被禁用/会话不存在等）直接抛出返回原始错误，不吞掉成通用错误
            throw e;
        } catch (Exception e) {
            log.error("[Web] Exception uploading user file, conversationUserId={}, cId={}, filePath={}", conversationUserId, cId, filePath, e);
            return buildError("Failed to upload user file");
        }
    }

    @Override
    public Map<String, Object> generateFile(Long conversationUserId, Long cId, String fileName, String content, UserContext userContext, String customTargetDir) {
        if (conversationUserId == null || conversationUserId <= 0) {
            log.error("[Web] Invalid conversationUserId, conversationUserId={}", conversationUserId);
            return buildError("conversationUserId is invalid");
        }
        if (cId == null || cId <= 0) {
            log.error("[Web] Invalid cId, cId={}", cId);
            return buildError("cId is invalid");
        }
        if (fileName == null || fileName.trim().isEmpty()) {
            log.error("[Web] fileName is invalid, fileName={}", fileName);
            return buildError("fileName is invalid");
        }
        try {
            return computerFileDomainService.generateFile(conversationUserId, cId, fileName.trim(), content, userContext, customTargetDir);
        } catch (BizException e) {
            // 语义化业务错误（沙箱不在线/被禁用/会话不存在等）直接抛出返回原始错误，不吞掉成通用错误
            throw e;
        } catch (Exception e) {
            log.error("[Web] Exception generating user file, conversationUserId={}, cId={}, fileName={}", conversationUserId, cId, fileName, e);
            return buildError("Failed to generate user file");
        }
    }

    @Override
    public Map<String, Object> uploadFiles(Long conversationUserId, Long cId, List<String> filePaths, List<MultipartFile> files, UserContext userContext, String customTargetDir) {
        if (conversationUserId == null || conversationUserId <= 0) {
            log.error("[Web] Invalid conversationUserId, conversationUserId={}", conversationUserId);
            return buildError("conversationUserId is invalid");
        }
        if (cId == null || cId <= 0) {
            log.error("[Web] Invalid cId, cId={}", cId);
            return buildError("cId is invalid");
        }
        if (filePaths == null || filePaths.isEmpty()) {
            log.error("[Web] filePaths is invalid, filePaths is empty");
            return buildError("filePaths is invalid");
        }
        if (files == null || files.isEmpty()) {
            log.error("[Web] files is invalid, files is empty");
            return buildError("files is invalid");
        }
        if (filePaths.size() != files.size()) {
            log.error("[Web] filePaths and files count mismatch, filePathsSize={}, filesSize={}", filePaths.size(), files.size());
            return buildError("filePaths and files count mismatch");
        }
        try {
            return computerFileDomainService.uploadFiles(conversationUserId, cId, filePaths, files, userContext, customTargetDir);
        } catch (BizException e) {
            // 语义化业务错误（沙箱不在线/被禁用/会话不存在等）直接抛出返回原始错误，不吞掉成通用错误
            throw e;
        } catch (Exception e) {
            log.error("[Web] Exception batch uploading user files, conversationUserId={}, cId={}", conversationUserId, cId, e);
            return buildError("Failed to batch upload user files");
        }
    }

    @Override
    public Map<String, Object> importProject(Long conversationUserId, Long cId, MultipartFile file, UserContext userContext, String customTargetDir) {
        if (conversationUserId == null || conversationUserId <= 0) {
            log.error("[Web] Invalid conversationUserId, conversationUserId={}", conversationUserId);
            return buildError("conversationUserId is invalid");
        }
        if (cId == null || cId <= 0) {
            log.error("[Web] Invalid cId, cId={}", cId);
            return buildError("cId is invalid");
        }
        if (file == null || file.isEmpty()) {
            log.error("[Web] file is invalid, file is empty");
            return buildError("file is invalid");
        }
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".zip")) {
            log.error("[Web] file is invalid, only zip is supported, fileName={}", originalFilename);
            return buildError("Only zip files are supported");
        }
        try {
            return computerFileDomainService.importProject(conversationUserId, cId, file, userContext, customTargetDir);
        } catch (BizException e) {
            // 语义化业务错误（沙箱不在线/被禁用/会话不存在等）直接抛出返回原始错误，不吞掉成通用错误
            throw e;
        } catch (Exception e) {
            log.error("[Web] Exception importing project, conversationUserId={}, cId={}, fileName={}", conversationUserId, cId, originalFilename, e);
            return buildError("Failed to import project");
        }
    }

    @Override
    public ResponseEntity<StreamingResponseBody> downloadAllFiles(Long conversationUserId, Long cId, UserContext userContext, String customTargetDir) {
        if (conversationUserId == null || conversationUserId <= 0) {
            log.error("[Web] Invalid conversationUserId, conversationUserId={}", conversationUserId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        if (cId == null || cId <= 0) {
            log.error("[Web] Invalid cId, cId={}", cId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        String logId = conversationUserId + "_" + cId;
        log.info("[Web] Download all files, logId={}, conversationUserId={}, cId={}", logId, conversationUserId, cId);

        try {
            Flux<DataBuffer> fileFlux = computerFileDomainService.downloadAllFiles(conversationUserId, cId, logId, userContext, customTargetDir);

            // Check the first signal before starting to write to detect errors early
            // Use share() to share Flux, then use materialize() to check the first signal
            // If the first signal is an error, return an error response; otherwise continue with the original streaming method
            Flux<DataBuffer> sharedFlux = fileFlux.share();
            try {
                Signal<DataBuffer> firstSignal = sharedFlux.materialize().blockFirst();
                if (firstSignal != null && firstSignal.isOnError()) {
                    Throwable error = firstSignal.getThrowable();
                    if (error instanceof WebClientResponseException) {
                        WebClientResponseException e = (WebClientResponseException) error;
                        log.error("[Web] Failed to download all files, logId={}, status={}", logId, e.getStatusCode());
                        HttpStatus status = e.getStatusCode() == org.springframework.http.HttpStatus.NOT_FOUND
                                ? HttpStatus.NOT_FOUND
                                : HttpStatus.INTERNAL_SERVER_ERROR;
                        return buildErrorResponse(status, "Failed to download all files: " + e.getMessage(), logId);
                    } else if (error instanceof SpacePermissionException) {
                        SpacePermissionException e = (SpacePermissionException) error;
                        log.error("[Web] Insufficient permission to download all files, logId={}, {}", logId, e.getMessage());
                        return buildErrorResponse(HttpStatus.FORBIDDEN, e.getMessage(), logId, e.getCode());
                    } else {
                        log.error("[Web] Exception downloading all files, logId={}", logId, error);
                        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to download all files: " + error.getMessage(), logId);
                    }
                }
            } catch (Exception e) {
                // materialize() itself may throw exceptions, in which case also return an error response
                log.error("[Web] Exception while checking download all files, logId={}", logId, e);
                if (e instanceof WebClientResponseException) {
                    WebClientResponseException webEx = (WebClientResponseException) e;
                    HttpStatus status = webEx.getStatusCode() == org.springframework.http.HttpStatus.NOT_FOUND
                            ? HttpStatus.NOT_FOUND
                            : HttpStatus.INTERNAL_SERVER_ERROR;
                    return buildErrorResponse(status, "Failed to download all files: " + webEx.getMessage(), logId);
                } else if (e instanceof SpacePermissionException) {
                    SpacePermissionException permEx = (SpacePermissionException) e;
                    return buildErrorResponse(HttpStatus.FORBIDDEN, permEx.getMessage(), logId, permEx.getCode());
                } else {
                    return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to download all files: " + e.getMessage(), logId);
                }
            }

            // If the first signal is not an error, use the shared Flux to continue streaming
            // share() ensures that multiple subscribers can share the same Flux without losing data
            final Flux<DataBuffer> finalFileFlux = sharedFlux;

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            String zipFileName = "files-" + conversationUserId + "-" + cId + ".zip";
            String encodedName = URLEncoder.encode(zipFileName, StandardCharsets.UTF_8);
            headers.set(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + encodedName + "\"; filename*=UTF-8''" + encodedName);

            StreamingResponseBody streamingResponseBody = outputStream -> {
                try {
                    finalFileFlux
                            // 写出切离 reactor-netty event loop：阻塞写会钉住共享 IO 线程（慢客户端最坏钉满整个下载期）；
                            // publishOn 自带 prefetch，同时补上端到端背压（写不动 → 停 request → 下游自动暂停）
                            .publishOn(Schedulers.boundedElastic())
                            .doOnError(WebClientResponseException.class, e -> {
                                log.error("[Web] Failed to download all files, logId={}, status={}, responseBody={}",
                                        logId, e.getStatusCode(), e.getResponseBodyAsString());
                            })
                            .doOnError(SpacePermissionException.class, e -> {
                                log.error("[Web] Insufficient permission to download all files, logId={}, {}", logId, e.getMessage());
                            })
                            .doOnError(Throwable.class, e -> {
                                log.error("[Web] Exception downloading all files, logId={}", logId, e);
                            })
                            .doOnNext(dataBuffer -> {
                                try {
                                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                                    dataBuffer.read(bytes);
                                    outputStream.write(bytes);
                                    outputStream.flush();
                                } catch (IOException e) {
                                    log.error("[Web] Failed to write zip output stream, logId={}", logId, e);
                                    throw new RuntimeException("Failed to write output stream", e);
                                } finally {
                                    DataBufferUtils.release(dataBuffer);
                                }
                            })
                            .doOnComplete(() -> log.info("[Web] All files zip streaming completed, logId={}", logId))
                            .blockLast();
                } catch (Exception e) {
                    log.error("[Web] Streaming all files zip failed, logId={}", logId, e);
                    // No longer throw exception, because the response has already started writing and cannot change the status code
                    // Errors have been checked and handled before starting to write
                }
            };

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(streamingResponseBody);
        } catch (SpacePermissionException e) {
            log.error("[Web] Insufficient permission to download all files, logId={}, {}", logId, e.getMessage());
            return buildErrorResponse(HttpStatus.FORBIDDEN, e.getMessage(), logId, e.getCode());
        } catch (Exception e) {
            log.error("[Web] Failed to download all files, logId={}", logId, e);
            return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to download all files: " + e.getMessage(), logId);
        }
    }

    @Override
    public ResponseEntity<StreamingResponseBody> getStaticFile(Long cId, HttpServletRequest request, String customTargetDir) {
        ConversationDto currentConversation = getConversation(cId);
        AuthResult authResult = staticFileAuth(currentConversation, cId, request, customTargetDir);
        if (authResult.getRedirectUrl() != null) {
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(authResult.getRedirectUrl())).build();
        }
        Long conversationUserId = authResult.getConversationUserId();

        log.info("[Web] Access static file, conversationUserId={}, cId={}, ", conversationUserId, cId);
        String staticPrefix = "/api/computer/static/" + cId + "/";
        String staticPrefixForApi = "/api/v1/chat/" + cId + "/file/";
        String targetPrefix = "/computer/static/" + conversationUserId + "/" + cId + "/";
        String logId = conversationUserId + "_" + cId;

        String requestPath = request.getRequestURI();
        String relativePath = "";

        int prefixIndex = requestPath.startsWith(staticPrefixForApi) ? requestPath.indexOf(staticPrefixForApi) : requestPath.indexOf(staticPrefix);
        int prefixLength = requestPath.startsWith(staticPrefixForApi) ? staticPrefixForApi.length() : staticPrefix.length();
        if (prefixIndex != -1) {
            relativePath = requestPath.substring(prefixIndex + prefixLength);
        } else {
            log.error("[Web] Cannot extract relativePath from request, requestPath={}, logId={}", requestPath, logId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        if (relativePath.trim().isEmpty()) {
            log.error("[Web] relativePath is empty, requestPath={}, logId={}", requestPath, logId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        // URL decode
        String decodedPath = relativePath;
        try {
            decodedPath = URLDecoder.decode(relativePath, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[Web] URL decode failed, relativePath={}, using raw path", relativePath, e);
        }
        final String finalRelativePath = decodedPath;

        log.info("[Web] Extracted logId={}, relativePath={}", logId, finalRelativePath);

        try {
            String rangeHeader = request.getHeader(HttpHeaders.RANGE);
            if (StringUtils.isNotBlank(rangeHeader)) {
                log.info("[Web] Client range request, logId={}, range={}", logId, rangeHeader);
            }
            ResponseEntity<Flux<DataBuffer>> downstreamResponse = computerFileDomainService.getStaticFileResponse(
                    cId, targetPrefix, finalRelativePath, logId, rangeHeader, customTargetDir
            );
            if (downstreamResponse == null || downstreamResponse.getBody() == null) {
                log.error("[Web] Downstream static file response is empty, logId={}", logId);
                return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to access static file: empty downstream response", logId);
            }

            final Flux<DataBuffer> finalFileFlux = downstreamResponse.getBody();
            HttpHeaders downstreamHeaders = downstreamResponse.getHeaders();

            HttpHeaders headers = new HttpHeaders();
            MediaType downstreamContentType = downstreamHeaders.getContentType();
            if (downstreamContentType != null) {
                headers.setContentType(downstreamContentType);
            } else {
                headers.setContentType(FileTypeUtils.getContentTypeByFileName(finalRelativePath));
            }
            if (downstreamHeaders.getFirst(HttpHeaders.ACCEPT_RANGES) != null) {
                headers.set(HttpHeaders.ACCEPT_RANGES, downstreamHeaders.getFirst(HttpHeaders.ACCEPT_RANGES));
            }
            if (downstreamHeaders.getFirst(HttpHeaders.CONTENT_RANGE) != null) {
                headers.set(HttpHeaders.CONTENT_RANGE, downstreamHeaders.getFirst(HttpHeaders.CONTENT_RANGE));
            }
            if (downstreamHeaders.getContentLength() >= 0) {
                headers.setContentLength(downstreamHeaders.getContentLength());
            }
            if (downstreamHeaders.getFirst(HttpHeaders.ETAG) != null) {
                headers.set(HttpHeaders.ETAG, downstreamHeaders.getFirst(HttpHeaders.ETAG));
            }
            if (downstreamHeaders.getFirst(HttpHeaders.LAST_MODIFIED) != null) {
                headers.set(HttpHeaders.LAST_MODIFIED, downstreamHeaders.getFirst(HttpHeaders.LAST_MODIFIED));
            }
            // 自定义header
            if (downstreamHeaders.getFirst("X-File-Size") != null) {
                headers.set("X-File-Size", downstreamHeaders.getFirst("X-File-Size"));
            }

            // If token is obtained from ticket, set Cookie for subsequent requests
            if (authResult.getToken() != null) {
                headers.add("Set-Cookie", "ticket=" + authResult.getToken() + "; Path=/; HttpOnly; SameSite=None; Secure");
                log.info("Set Cookie ticket, logId={}", logId);
            }
            if (authResult.getUserShare() != null) {
                String skCookieKey = getSkCookieKey(conversationUserId, cId);
                UserShareDto userShare = authResult.getUserShare();
                String cookieValue = skCookieKey + userShare.getShareKey() + "; Path=/; SameSite=None; Secure";
                Date expire = userShare.getExpire();
                if (expire != null) {
                    // Calculate the number of seconds from current time to expiration time
                    long maxAge = (expire.getTime() - System.currentTimeMillis()) / 1000;
                    if (maxAge > 0) {
                        cookieValue += "; Max-Age=" + maxAge;
                    } else {
                        // If already expired, set Max-Age=0 to delete cookie immediately
                        cookieValue += "; Max-Age=0";
                    }
                }
                headers.add("Set-Cookie", cookieValue);
            }

            // Create StreamingResponseBody to implement streaming
            StreamingResponseBody streamingResponseBody = outputStream -> {
                try {
                    finalFileFlux
                            // 写出切离 reactor-netty event loop：阻塞写会钉住共享 IO 线程（慢客户端最坏钉满整个下载期）；
                            // publishOn 自带 prefetch，同时补上端到端背压（写不动 → 停 request → 下游自动暂停）
                            .publishOn(Schedulers.boundedElastic())
                            .doOnError(WebClientResponseException.class, e -> {
                                log.error("[Web] Failed to get static file, logId={}, status={}, responseBody={}",
                                        logId, e.getStatusCode(), e.getResponseBodyAsString());
                            })
                            .doOnError(SpacePermissionException.class, e -> {
                                log.error("[Web] Insufficient permission for static file, logId={}, {}", logId, e.getMessage());
                            })
                            .doOnError(Throwable.class, e -> {
                                log.error("[Web] Exception while accessing static file, logId={}", logId, e);
                            })
                            .doOnNext(dataBuffer -> {
                                try {
                                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                                    dataBuffer.read(bytes);
                                    outputStream.write(bytes);
                                    outputStream.flush();
                                } catch (IOException e) {
                                    log.error("[Web] Failed to write output stream, logId={}", logId, e);
                                    throw new RuntimeException("Failed to write output stream", e);
                                } finally {
                                    DataBufferUtils.release(dataBuffer);
                                }
                            })
                            .doOnComplete(() -> {
                                log.info("[Web] File streaming completed, logId={}, relativePath={}", logId, finalRelativePath);
                            })
                            .blockLast(); // Blocking in StreamingResponseBody callback is normal
                } catch (Exception e) {
                    log.error("[Web] File streaming failed, logId={}", logId, e);
                    // No longer throw exception, because the response has already started writing and cannot change the status code
                    // Errors have been checked and handled before starting to write
                }
            };

            HttpStatus status = HttpStatus.resolve(downstreamResponse.getStatusCode().value());
            if (status == null) {
                status = HttpStatus.OK;
            }
            return ResponseEntity.status(status).headers(headers).body(streamingResponseBody);
        } catch (SpacePermissionException e) {
            log.error("[Web] Insufficient permission for static file, logId={}, {}", logId, e.getMessage());
            return buildErrorResponse(HttpStatus.FORBIDDEN, e.getMessage(), logId, e.getCode());
        } catch (Exception e) {
            log.error("[Web] Failed to access static file, logId={}", logId, e);
            return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to access static file: " + e.getMessage(), logId);
        }
    }

    private ConversationDto getConversation(Long cId) {
        SandboxServerConfig.SandboxServer sandboxServer = null;
        try {
            sandboxServer = sandboxServerConfigService.selectServer(cId);
        } catch (Exception e) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail, e.getMessage());
        }
        if (sandboxServer == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSandboxNotFound);
        }
        ConversationDto currentConversation = sandboxServer.getCurrentConversation();
        if (currentConversation == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSessionNotFound);
        }
        return currentConversation;
    }

    /**
     * Authentication result, containing conversationUserId and token (if obtained from ticket).
     * When redirectUrl is not empty, it means need to redirect to login page, conversationUserId is null at this time.
     */
    @Getter
    @AllArgsConstructor
    private static class AuthResult {
        private final Long conversationUserId;
        private final String token;
        private final UserShareDto userShare;
        private final String redirectUrl;
    }

    // sk authentication
    private UserShareDto authWithSk(ConversationDto currentConversation, Long cId, HttpServletRequest request) {
        Long conversationUserId = currentConversation.getUserId();
        if (conversationUserId == null) {
            return null;
        }

        // 1. Priority to get from request parameter sk
        String sk = request.getParameter("sk");

        if (StringUtils.isBlank(sk)) {
            // 2. Parse sk from Cookie
            String cookieHeader = request.getHeader("Cookie");
            if (StringUtils.isBlank(cookieHeader)) {
                return null;
            }
            String skCookieKey = getSkCookieKey(conversationUserId, cId);
            int start = cookieHeader.indexOf(skCookieKey);
            if (start < 0) {
                return null;
            }
            int valueStart = start + skCookieKey.length();
            int end = cookieHeader.indexOf(";", valueStart);
            sk = end > 0 ? cookieHeader.substring(valueStart, end) : cookieHeader.substring(valueStart);

        }
        if (StringUtils.isBlank(sk)) {
            return null;
        }

        UserShareDto userShare = userShareRpcService.getUserShare(sk, true);
        if (userShare != null
                && userShare.getType() == UserShareDto.UserShareType.CONVERSATION
                && conversationUserId.equals(userShare.getUserId())
                && userShare.getTargetId().equals(cId.toString())) {
            return userShare;
        }
        return null;
    }

    private AuthResult staticFileAuth(ConversationDto currentConversation, Long cId, HttpServletRequest request, String customTargetDir) {
        Long conversationUserId = currentConversation.getUserId();

        UserShareDto userShare = authWithSk(currentConversation, cId, request);
        // 分享放行不适用于自定义目录：customTargetDir 非空时不认分享（含 sk Cookie 命中），落回登录态鉴权——
        // 属主本人经登录态正常使用；匿名分享访客无登录态被拦，协作者由控制器层 checkCustomTargetDir 属主校验拦截
        if (userShare != null && StringUtils.isBlank(customTargetDir)) {
            return new AuthResult(conversationUserId, null, userShare, null);
        }

        Long currentUserId = RequestContext.get().getUserId();
        if (currentUserId == null) {
            String ticket = request.getParameter("_ticket");
            if (StringUtils.isNotBlank(ticket) && authService != null) {
                try {
                    String token = authService.getTokenByTicket(ticket);
                    if (StringUtils.isNotBlank(token)) {
                        UserDto userDto = authService.getLoginUserInfo(token);
                        if (userDto != null) {
                            log.info("Successfully obtained user info from URI ticket, userId={}", userDto.getId());
                            currentUserId = userDto.getId();
                            if (!currentUserId.equals(conversationUserId)) {
                                throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
                            }
                            return new AuthResult(conversationUserId, token, null, null);
                        } else {
                            log.warn("Failed to obtain user info from URI ticket, token={}", token);
                        }
                    }
                } catch (BizException e) {
                    // Business exception (e.g., insufficient permission) is thrown directly, not caught
                    throw e;
                } catch (Exception e) {
                    log.warn("Failed to obtain user info from URI ticket, ticket={}", ticket, e);
                }
            }
            // Unable to get user info, redirect to login page
            StringBuffer requestURL = request.getRequestURL();
            String queryString = request.getQueryString();
            String referer = requestURL.toString();
            if (queryString != null && !queryString.isEmpty()) {
                referer += "?" + queryString;
            }
            String redirectUrl = "/login?redirect=" + URLEncoder.encode(referer, StandardCharsets.UTF_8);
            return new AuthResult(null, null, null, redirectUrl);
        }

        conversationPermissionChecker.checkUserConversationPermission(currentUserId, currentConversation);

        return new AuthResult(conversationUserId, null, null, null);
    }

    private Map<String, Object> buildError(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("code", "0001");
        result.put("message", message);
        return result;
    }

    // Build error response (JSON format)
    private ResponseEntity<StreamingResponseBody> buildErrorResponse(HttpStatus status, String message, String logId) {
        return buildErrorResponse(status, message, logId, "0001");
    }

    // Build error response (JSON format)
    private ResponseEntity<StreamingResponseBody> buildErrorResponse(HttpStatus status, String message, String logId, String code) {
        String errorMessage = message != null ? message : "Failed to access static file";

        // Remove sensitive information in IP:port format (e.g., 192.168.1.34:60000)
        // Match IPv4 address:port format, including possible http:// or https:// prefix
        Pattern ipPortPattern = Pattern.compile("(https?://)?\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}:\\d+");
        errorMessage = ipPortPattern.matcher(errorMessage).replaceAll("");
        // Clean up extra spaces
        errorMessage = errorMessage.trim().replaceAll("\\s+", " ");
        // Escape special characters in JSON string
        String escapedMessage = errorMessage.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        String errorJson = "{\"code\":\"" + code + "\",\"message\":\"" + escapedMessage + "\"}";
        HttpHeaders errorHeaders = new HttpHeaders();
        errorHeaders.setContentType(MediaType.APPLICATION_JSON);
        StreamingResponseBody errorBody = outputStream -> {
            try {
                outputStream.write(errorJson.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            } catch (IOException ioException) {
                log.error("[Web] Failed to write error response, logId={}", logId, ioException);
            }
        };
        return ResponseEntity.status(status).headers(errorHeaders).body(errorBody);
    }

    private String getSkCookieKey(Long conversationUserId, Long cId) {
        return "static_sk_" + conversationUserId + "_" + cId + "=";
    }
}