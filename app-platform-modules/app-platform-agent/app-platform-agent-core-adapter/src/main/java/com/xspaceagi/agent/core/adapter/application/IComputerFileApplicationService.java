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
     * 查询文件列表
     */
    Map<String, Object> getFileList(Long userId, Long cId, String proxyPath, UserContext userContext, String customTargetDir);

    /**
     * 更新文件
     */
    Map<String, Object> filesUpdate(Long userId, Long cId, List<ComputerFileInfo> files, UserContext userContext, String customTargetDir);

    /**
     * 上传文件
     */
    Map<String, Object> uploadFile(Long userId, Long cId, String filePath, MultipartFile file, UserContext userContext, String customTargetDir);

    /**
     * 批量上传文件
     */
    Map<String, Object> uploadFiles(Long userId, Long cId, List<String> filePaths, List<MultipartFile> files, UserContext userContext, String customTargetDir);

    /**
     * 导入项目（zip 替换工作空间，保留白名单目录）
     */
    Map<String, Object> importProject(Long userId, Long cId, MultipartFile file, UserContext userContext, String customTargetDir);

    /**
     * 获取静态文件
     */
    ResponseEntity<StreamingResponseBody> getStaticFile(Long cId, HttpServletRequest request, String customTargetDir);

    /**
     * 下载全部文件（zip）
     */
    ResponseEntity<StreamingResponseBody> downloadAllFiles(Long userId, Long cId, UserContext userContext, String customTargetDir);

    /**
     * 获取沙盒日志
     */
    Map<String, Object> getLogs(Long userId, Long cId, int tailLines);

}