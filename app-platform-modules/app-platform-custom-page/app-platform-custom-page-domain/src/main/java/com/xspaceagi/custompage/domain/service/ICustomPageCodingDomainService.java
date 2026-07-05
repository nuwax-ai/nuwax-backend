package com.xspaceagi.custompage.domain.service;

import java.util.List;
import java.util.Map;

import org.springframework.web.multipart.MultipartFile;

import com.xspaceagi.custompage.domain.dto.PageFileInfo;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;

/**
 * 前端项目编码领域服务
 */
public interface ICustomPageCodingDomainService {

    /**
     * 指定文件修改
     */
    ReqResult<Map<String, Object>> specifiedFilesUpdate(Long projectId, List<PageFileInfo> files, UserContext userContext);

    /**
     * 全量文件修改
     */
    ReqResult<Map<String, Object>> allFilesUpdate(Long projectId, List<PageFileInfo> files, UserContext userContext);

    /**
     * 上传单个文件
     */
    ReqResult<Map<String, Object>> uploadSingleFile(Long projectId, MultipartFile file, String filePath,
            UserContext userContext);

    /**
     * 批量上传文件
     */
    ReqResult<Map<String, Object>> uploadBatchFiles(Long projectId, List<MultipartFile> files,
            List<String> filePaths, UserContext userContext);

    /**
     * 回滚版本
     */
    ReqResult<Map<String, Object>> rollbackVersion(Long projectId, Integer rollbackTo, UserContext userContext);

}