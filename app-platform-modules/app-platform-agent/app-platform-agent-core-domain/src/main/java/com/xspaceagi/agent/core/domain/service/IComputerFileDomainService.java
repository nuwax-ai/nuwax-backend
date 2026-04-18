package com.xspaceagi.agent.core.domain.service;

import com.xspaceagi.agent.core.adapter.dto.ComputerFileInfo;
import com.xspaceagi.system.spec.common.UserContext;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 文件领域服务
 */
public interface IComputerFileDomainService {

    /**
     * 获取静态文件（流式返回）
     */
    Flux<DataBuffer> getStaticFile(Long cId, String targetPrefix, String relativePath, String logId);

    /**
     * 获取静态文件完整响应（保留状态码与响应头，支持断点续传）
     */
    ResponseEntity<Flux<DataBuffer>> getStaticFileResponse(Long cId, String targetPrefix, String relativePath, String logId, String rangeHeader);

    /**
     * 查询文件列表
     */
    Map<String, Object> getFileList(Long userId, Long cId, String proxyPath, UserContext userContext);

    /**
     * 更新文件列表
     */
    Map<String, Object> filesUpdate(Long userId, Long cId, List<ComputerFileInfo> files, UserContext userContext);

    /**
     * 上传文件
     */
    Map<String, Object> uploadFile(Long userId, Long cId, String filePath, MultipartFile file, UserContext userContext);

    /**
     * 批量上传文件
     */
    Map<String, Object> uploadFiles(Long userId, Long cId, List<String> filePaths, List<MultipartFile> files, UserContext userContext);

    /**
     * 下载全部文件（zip 流式返回）
     */
    Flux<DataBuffer> downloadAllFiles(Long userId, Long cId, String logId, UserContext userContext);

}

