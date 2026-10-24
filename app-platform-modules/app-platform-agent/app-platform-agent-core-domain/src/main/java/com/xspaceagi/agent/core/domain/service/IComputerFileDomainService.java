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
    Flux<DataBuffer> getStaticFile(Long cId, String targetPrefix, String relativePath, String logId, String customTargetDir);

    /**
     * 获取静态文件完整响应（保留状态码与响应头，支持断点续传）
     */
    ResponseEntity<Flux<DataBuffer>> getStaticFileResponse(Long cId, String targetPrefix, String relativePath, String logId, String rangeHeader, String customTargetDir);

    /**
     * 查询文件列表（recursive 默认 true=全量扁平原逻辑；false 时按 relativePath 单层列出；
     */
    Map<String, Object> getFileList(Long conversationUserId, Long cId, String proxyPath, UserContext userContext, String customTargetDir, String relativePath, Boolean recursive, Integer depth, String type, Integer limit);

    /**
     * 解析工作区内文件是否存在，存在则返回相对 name 与 fileProxyUrl
     */
    Map<String, Object> resolveExistingFile(Long conversationUserId, Long cId, String proxyPath, String filePath, String customTargetDir);

    /**
     * 批量查询文件元数据；单条失败仅该条带 error
     */
    Map<String, Object> getFileMeta(Long conversationUserId, Long cId, List<String> filePaths, String customTargetDir);

    /**
     * 有界文件名搜索；返回命中文件及路径上各目录一层列表
     * @param type 命中类型过滤：file-仅文件、dir-仅目录、空/非法-全部（默认）
     */
    Map<String, Object> searchFiles(Long conversationUserId, Long cId, String proxyPath, String kw, UserContext userContext, String customTargetDir, String relativePath, String type, Integer limit, Integer maxVisit, Integer timeoutMs);

    /**
     * 更新文件列表
     */
    Map<String, Object> filesUpdate(Long conversationUserId, Long cId, List<ComputerFileInfo> files, UserContext userContext, String customTargetDir);

    /**
     * 上传文件
     */
    Map<String, Object> uploadFile(Long conversationUserId, Long cId, String filePath, MultipartFile file, UserContext userContext, String customTargetDir);

    /**
     * 根据文本内容生成文件
     */
    Map<String, Object> generateFile(Long conversationUserId, Long cId, String fileName, String content, UserContext userContext, String customTargetDir);

    /**
     * 批量上传文件
     */
    Map<String, Object> uploadFiles(Long conversationUserId, Long cId, List<String> filePaths, List<MultipartFile> files, UserContext userContext, String customTargetDir);

    /**
     * 导入项目（zip 替换工作空间，保留白名单目录）
     */
    Map<String, Object> importProject(Long conversationUserId, Long cId, MultipartFile file, UserContext userContext, String customTargetDir);

    /**
     * 下载全部文件（zip 流式返回）
     */
    Flux<DataBuffer> downloadAllFiles(Long conversationUserId, Long cId, String logId, UserContext userContext, String customTargetDir);

    /**
     * 获取沙盒日志
     */
    Map<String, Object> getLogs(Long conversationUserId, Long cId, int tailLines, UserContext userContext);

}

