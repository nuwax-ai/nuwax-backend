package com.xspaceagi.agent.core.domain.service.impl;

import com.xspaceagi.agent.core.domain.service.IComputerFileDomainService;
import com.xspaceagi.agent.core.infra.rpc.ComputerFileClient;
import com.xspaceagi.agent.core.adapter.dto.ComputerFileInfo;
import com.xspaceagi.system.spec.common.UserContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
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
    public Flux<DataBuffer> getStaticFile(Long cId, String targetPrefix, String relativePath, String logId) {
        log.info("[Domain] logId={}, 获取静态文件, targetPrefix={}, relativePath={}", logId, targetPrefix, relativePath);
        return computerFileClient.getStaticFile(cId, targetPrefix, relativePath, logId)
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
    public Map<String, Object> getFileList(Long userId, Long cId, String proxyPath, UserContext userContext) {
        log.info("[Domain] 查询文件列表, userId={}, cId={}", userId, cId);
        return computerFileClient.getFileList(userId, cId, proxyPath);
    }

    @Override
    public Map<String, Object> filesUpdate(Long userId, Long cId, List<ComputerFileInfo> files, UserContext userContext) {
        log.info("[Domain] 更新文件列表, userId={}, cId={}", userId, cId);
        return computerFileClient.filesUpdate(userId, cId, files);
    }

    @Override
    public Map<String, Object> uploadFile(Long userId, Long cId, String filePath, MultipartFile file, UserContext userContext) {
        log.info("[Domain] 上传文件, userId={}, cId={}, filePath={}", userId, cId, filePath);
        return computerFileClient.uploadFile(userId, cId, filePath, file);
    }

    @Override
    public Map<String, Object> uploadFiles(Long userId, Long cId, List<String> filePaths, List<MultipartFile> files, UserContext userContext) {
        log.info("[Domain] 批量上传文件, userId={}, cId={}, fileCount={}", userId, cId, files != null ? files.size() : 0);
        return computerFileClient.uploadFiles(userId, cId, filePaths, files);
    }

    @Override
    public Flux<DataBuffer> downloadAllFiles(Long userId, Long cId, String logId, UserContext userContext) {
        log.info("[Domain] 下载全部文件, logId={}, userId={}, cId={}", logId, userId, cId);
        return computerFileClient.downloadAllFiles(userId, cId, logId)
                .doOnError(WebClientResponseException.NotFound.class, e -> {
                    handleDownloadFileNotFound(logId, userId, cId, e);
                })
                .doOnError(WebClientResponseException.class, e -> {
                    handleDownloadHttpError(logId, userId, cId, e);
                })
                .doOnError(Throwable.class, e -> {
                    handleDownloadGenericError(logId, userId, cId, e);
                });
    }

    /**
     * 处理下载文件未找到错误（404）
     */
    private void handleDownloadFileNotFound(String logId, Long userId, Long cId, WebClientResponseException.NotFound e) {
        String errorMessage = extractErrorMessage(e.getResponseBodyAsString());
        log.warn("[Domain] logId={}, 下载文件不存在, userId={}, cId={}, errorDetail={}",
                logId, userId, cId, errorMessage);

        log.debug("[Domain] logId={}, 下载404错误详情, status={}, responseBody={}",
                logId, e.getStatusCode(), e.getResponseBodyAsString());
    }

    /**
     * 处理下载HTTP错误
     */
    private void handleDownloadHttpError(String logId, Long userId, Long cId, WebClientResponseException e) {
        String errorMessage = extractErrorMessage(e.getResponseBodyAsString());
        log.error("[Domain] logId={}, 下载文件HTTP错误, userId={}, cId={}, status={}, errorMessage={}",
                logId, userId, cId, e.getStatusCode(), errorMessage);
    }

    /**
     * 处理下载通用异常
     */
    private void handleDownloadGenericError(String logId, Long userId, Long cId, Throwable e) {
        log.error("[Domain] logId={}, 下载文件异常, userId={}, cId={}, errorType={}, errorMessage={}",
                logId, userId, cId, e.getClass().getSimpleName(), e.getMessage(), e);
    }
}

