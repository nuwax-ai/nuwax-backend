package com.xspaceagi.agent.core.adapter.application;

import com.xspaceagi.agent.core.adapter.dto.ComputerFileInfo;
import com.xspaceagi.system.spec.common.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;
import java.util.Map;

/**
 * 通用文件应用服务
 */
public interface IComputerFileApplicationService {

    /**
     * 文件系统目录浏览
     */
    Map<String, Object> listFsRoots(Long sandboxId);

    /**
     * 文件系统目录浏览：列出目录下一层子项（目录+文件，目录在前；文件由前端按 isDir 置灰）
     */
    Map<String, Object> listFsChildren(Long sandboxId, String path);

    /**
     * 文件系统浏览配套：在指定目录下新建一层目录（目录选择弹窗）
     */
    Map<String, Object> createFsDirectory(Long sandboxId, String parentPath, String dirName);

    /**
     * 文件系统浏览配套：同目录重命名（目录选择弹窗；newName 仅名字）
     */
    Map<String, Object> renameFsDirectory(Long sandboxId, String path, String newName);

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
     * 更新文件
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
     * 获取静态文件
     */
    ResponseEntity<StreamingResponseBody> getStaticFile(Long cId, HttpServletRequest request, String customTargetDir);

    /**
     * 下载全部文件（zip）
     */
    ResponseEntity<StreamingResponseBody> downloadAllFiles(Long conversationUserId, Long cId, UserContext userContext, String customTargetDir);

    /**
     * 获取沙盒日志
     */
    Map<String, Object> getLogs(Long conversationUserId, Long cId, int tailLines);

}