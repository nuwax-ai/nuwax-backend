package com.xspaceagi.agent.core.domain.service.impl;

import com.xspaceagi.agent.core.domain.service.IComputerFileDomainService;
import com.xspaceagi.agent.core.infra.rpc.ComputerFileClient;
import com.xspaceagi.agent.core.adapter.dto.ComputerFileInfo;
import com.xspaceagi.system.spec.common.UserContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 文件领域服务实现
 */
@Slf4j
@Service
public class ComputerFileDomainServiceImpl implements IComputerFileDomainService {

    @Resource
    private ComputerFileClient computerFileClient;

    @Override
    public Flux<DataBuffer> getStaticFile(Long cId, String targetPrefix, String relativePath, String logId, String customTargetDir) {
        log.info("[Domain] logId={}, 获取静态文件, targetPrefix={}, relativePath={}", logId, targetPrefix, relativePath);
            return computerFileClient.getStaticFile(cId, targetPrefix, relativePath, logId, customTargetDir)
                .doOnError(WebClientResponseException.NotFound.class, e -> {
                    handleFileNotFound(logId, targetPrefix, relativePath, e);
                })
                .doOnError(WebClientResponseException.class, e -> {
                    handleHttpError(logId, targetPrefix, relativePath, e);
                })
                .doOnError(Throwable.class, e -> {
                    handleGenericError(logId, targetPrefix, relativePath, e);
                });
    }

    @Override
    public ResponseEntity<Flux<DataBuffer>> getStaticFileResponse(Long cId, String targetPrefix, String relativePath, String logId, String rangeHeader, String customTargetDir) {
        log.info("[Domain] logId={}, 获取静态文件完整响应, targetPrefix={}, relativePath={}, range={}",
                logId, targetPrefix, relativePath, rangeHeader);
        return computerFileClient.getStaticFile(cId, targetPrefix, relativePath, logId, rangeHeader, customTargetDir);
    }

    /**
     * 处理文件未找到错误（404）
     */
    private void handleFileNotFound(String logId, String targetPrefix, String relativePath, WebClientResponseException.NotFound e) {
        String fullFilePath = targetPrefix + relativePath;
        String errorMessage = extractErrorMessage(e.getResponseBodyAsString());

        log.warn("[Domain] logId={}, 文件不存在, fullPath={}, errorDetail={}",
                logId, fullFilePath, errorMessage);

        log.debug("[Domain] logId={}, 404错误详情, status={}, responseBody={}",
                logId, e.getStatusCode(), e.getResponseBodyAsString());
    }

    /**
     * 处理HTTP错误（4xx, 5xx）
     */
    private void handleHttpError(String logId, String targetPrefix, String relativePath, WebClientResponseException e) {
        String fullFilePath = targetPrefix + relativePath;
        String errorMessage = extractErrorMessage(e.getResponseBodyAsString());

        log.error("[Domain] logId={}, 获取静态文件HTTP错误, fullPath={}, status={}, errorMessage={}",
                logId, fullFilePath, e.getStatusCode(), errorMessage);
    }

    /**
     * 处理通用异常
     */
    private void handleGenericError(String logId, String targetPrefix, String relativePath, Throwable e) {
        String fullFilePath = targetPrefix + relativePath;
        log.error("[Domain] logId={}, 获取静态文件异常, fullPath={}, errorType={}, errorMessage={}",
                logId, fullFilePath, e.getClass().getSimpleName(), e.getMessage(), e);
    }

    /**
     * 从错误响应体中提取错误信息
     */
    private String extractErrorMessage(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) {
            return "无详细错误信息";
        }

        try {
            if (responseBody.contains("message")) {
                int messageIndex = responseBody.indexOf("\"message\"");
                if (messageIndex != -1) {
                    int startIndex = responseBody.indexOf(":\"", messageIndex);
                    int endIndex = responseBody.indexOf("\"", startIndex + 2);
                    if (startIndex != -1 && endIndex != -1) {
                        return responseBody.substring(startIndex + 2, endIndex);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[Domain] 解析错误消息失败, 使用原始响应", e);
        }

        return responseBody.length() > 100 ? responseBody.substring(0, 100) + "..." : responseBody;
    }

    @Override
    public Map<String, Object> getFileList(Long conversationUserId, Long cId, String proxyPath, UserContext userContext, String customTargetDir, String relativePath, Boolean recursive, Integer depth, String type, Integer limit) {
        log.info("[Domain] 查询文件列表, conversationUserId={}, cId={}, relativePath={}, recursive={}, depth={}, type={}, limit={}", conversationUserId, cId, relativePath, recursive, depth, type, limit);
        return computerFileClient.getFileList(conversationUserId, cId, proxyPath, customTargetDir, relativePath, recursive, depth, type, limit);
    }

    @Override
    public Map<String, Object> resolveExistingFile(Long conversationUserId, Long cId, String proxyPath, String filePath, String customTargetDir) {
        log.info("[Domain] 解析文件是否存在, conversationUserId={}, cId={}, filePath={}", conversationUserId, cId, filePath);
        return computerFileClient.resolveExistingFile(conversationUserId, cId, proxyPath, filePath, customTargetDir);
    }

    @Override
    public Map<String, Object> getFileMeta(Long conversationUserId, Long cId, List<String> filePaths, String customTargetDir) {
        log.info("[Domain] 查询文件元数据, conversationUserId={}, cId={}, count={}", conversationUserId, cId, filePaths != null ? filePaths.size() : 0);
        return computerFileClient.getFileMeta(conversationUserId, cId, filePaths, customTargetDir);
    }

    @Override
    public Map<String, Object> searchFiles(Long conversationUserId, Long cId, String proxyPath, String kw, UserContext userContext, String customTargetDir, String relativePath, String type, Integer limit, Integer maxVisit, Integer timeoutMs) {
        log.info("[Domain] 搜索文件, conversationUserId={}, cId={}, kw={}, relativePath={}, type={}", conversationUserId, cId, kw, relativePath, type);
        return computerFileClient.searchFiles(conversationUserId, cId, proxyPath, kw, customTargetDir, relativePath, type, limit, maxVisit, timeoutMs);
    }

    @Override
    public Map<String, Object> filesUpdate(Long conversationUserId, Long cId, List<ComputerFileInfo> files, UserContext userContext, String customTargetDir) {
        log.info("[Domain] 更新文件列表, conversationUserId={}, cId={}", conversationUserId, cId);
        return computerFileClient.filesUpdate(conversationUserId, cId, files, customTargetDir);
    }

    @Override
    public Map<String, Object> uploadFile(Long conversationUserId, Long cId, String filePath, MultipartFile file, UserContext userContext, String customTargetDir) {
        log.info("[Domain] 上传文件, conversationUserId={}, cId={}, filePath={}", conversationUserId, cId, filePath);
        return computerFileClient.uploadFile(conversationUserId, cId, filePath, file, customTargetDir);
    }

    @Override
    public Map<String, Object> generateFile(Long conversationUserId, Long cId, String fileName, String content, UserContext userContext, String customTargetDir) {
        log.info("[Domain] 生成文件, conversationUserId={}, cId={}, fileName={}", conversationUserId, cId, fileName);
        return computerFileClient.generateFile(conversationUserId, cId, fileName, content, customTargetDir);
    }

    @Override
    public Map<String, Object> uploadFiles(Long conversationUserId, Long cId, List<String> filePaths, List<MultipartFile> files, UserContext userContext, String customTargetDir) {
        log.info("[Domain] 批量上传文件, conversationUserId={}, cId={}, fileCount={}", conversationUserId, cId, files != null ? files.size() : 0);
        return computerFileClient.uploadFiles(conversationUserId, cId, filePaths, files, customTargetDir);
    }

    @Override
    public Map<String, Object> importProject(Long conversationUserId, Long cId, MultipartFile file, UserContext userContext, String customTargetDir) {
        log.info("[Domain] 导入项目, conversationUserId={}, cId={}, fileName={}", conversationUserId, cId, file != null ? file.getOriginalFilename() : null);
        return computerFileClient.importProject(conversationUserId, cId, file, customTargetDir);
    }

    @Override
    public Flux<DataBuffer> downloadAllFiles(Long conversationUserId, Long cId, String logId, UserContext userContext, String customTargetDir) {
        log.info("[Domain] 下载全部文件, logId={}, conversationUserId={}, cId={}", logId, conversationUserId, cId);
        return computerFileClient.downloadAllFiles(conversationUserId, cId, logId, customTargetDir)
                .doOnError(WebClientResponseException.NotFound.class, e -> {
                    handleDownloadFileNotFound(logId, conversationUserId, cId, e);
                })
                .doOnError(WebClientResponseException.class, e -> {
                    handleDownloadHttpError(logId, conversationUserId, cId, e);
                })
                .doOnError(Throwable.class, e -> {
                    handleDownloadGenericError(logId, conversationUserId, cId, e);
                });
    }

    @Override
    public Map<String, Object> getLogs(Long conversationUserId, Long cId, int tailLines, UserContext userContext) {
        log.info("[Domain] 获取沙盒日志, conversationUserId={}, cId={}, tailLines={}", conversationUserId, cId, tailLines);
        return computerFileClient.getLogs(conversationUserId, cId, tailLines);
    }

    /**
     * 处理下载文件未找到错误（404）
     */
    private void handleDownloadFileNotFound(String logId, Long conversationUserId, Long cId, WebClientResponseException.NotFound e) {
        String errorMessage = extractErrorMessage(e.getResponseBodyAsString());
        log.warn("[Domain] logId={}, 下载文件不存在, conversationUserId={}, cId={}, errorDetail={}",
                logId, conversationUserId, cId, errorMessage);

        log.debug("[Domain] logId={}, 下载404错误详情, status={}, responseBody={}",
                logId, e.getStatusCode(), e.getResponseBodyAsString());
    }

    /**
     * 处理下载HTTP错误
     */
    private void handleDownloadHttpError(String logId, Long conversationUserId, Long cId, WebClientResponseException e) {
        String errorMessage = extractErrorMessage(e.getResponseBodyAsString());
        log.error("[Domain] logId={}, 下载文件HTTP错误, conversationUserId={}, cId={}, status={}, errorMessage={}",
                logId, conversationUserId, cId, e.getStatusCode(), errorMessage);
    }

    /**
     * 处理下载通用异常
     */
    private void handleDownloadGenericError(String logId, Long conversationUserId, Long cId, Throwable e) {
        log.error("[Domain] logId={}, 下载文件异常, conversationUserId={}, cId={}, errorType={}, errorMessage={}",
                logId, conversationUserId, cId, e.getClass().getSimpleName(), e.getMessage(), e);
    }
}

