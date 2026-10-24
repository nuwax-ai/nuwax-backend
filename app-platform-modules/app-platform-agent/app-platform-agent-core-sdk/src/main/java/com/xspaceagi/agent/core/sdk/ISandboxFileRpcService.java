package com.xspaceagi.agent.core.sdk;

import com.xspaceagi.agent.core.sdk.dto.SandboxFileGenerateRequest;
import com.xspaceagi.agent.core.sdk.dto.SandboxFileListRequest;
import com.xspaceagi.agent.core.sdk.dto.SandboxFileUploadRequest;
import com.xspaceagi.agent.core.sdk.dto.SandboxFilesUpdateRequest;
import com.xspaceagi.agent.core.sdk.dto.SandboxFilesUploadRequest;
import com.xspaceagi.agent.core.sdk.dto.SandboxImportProjectRequest;

import java.util.Map;

/**
 * 沙箱文件 RPC 服务
 */
public interface ISandboxFileRpcService {

    /**
     * 查询文件列表
     */
    Map<String, Object> getFileList(SandboxFileListRequest request);

    /**
     * 更新文件
     */
    Map<String, Object> filesUpdate(SandboxFilesUpdateRequest request);

    /**
     * 根据文本内容生成文件
     */
    Map<String, Object> generateFile(SandboxFileGenerateRequest request);

    /**
     * 批量上传文件
     */
    Map<String, Object> uploadFiles(SandboxFilesUploadRequest request);

    /**
     * 导入项目（zip 替换工作空间）
     */
    Map<String, Object> importProject(SandboxImportProjectRequest request);
}
