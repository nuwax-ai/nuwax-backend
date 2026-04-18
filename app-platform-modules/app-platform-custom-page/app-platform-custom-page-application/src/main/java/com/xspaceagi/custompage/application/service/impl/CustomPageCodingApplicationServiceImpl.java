package com.xspaceagi.custompage.application.service.impl;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.xspaceagi.custompage.application.service.ICustomPageCodingApplicationService;
import com.xspaceagi.custompage.domain.dto.PageFileInfo;
import com.xspaceagi.custompage.domain.proxypath.ICustomPageProxyPathService;
import com.xspaceagi.custompage.domain.service.ICustomPageChatDomainService;
import com.xspaceagi.custompage.domain.service.ICustomPageCodingDomainService;
import com.xspaceagi.custompage.domain.service.ICustomPageConversationDomainService;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class CustomPageCodingApplicationServiceImpl implements ICustomPageCodingApplicationService {

    @Resource
    private ICustomPageChatDomainService customPageChatDomainService;
    @Resource
    private ICustomPageCodingDomainService customPageCodingDomainService;
    @Resource
    private ICustomPageProxyPathService customPageProxyPathApplicationService;
    @Resource
    private ICustomPageConversationDomainService customPageConversationDomainService;

    @Override
    public ReqResult<Map<String, Object>> specifiedFilesUpdate(Long projectId, List<PageFileInfo> files, UserContext userContext) {
        log.info("[Application] project Id={},specifiedfileupdate", projectId);
        Optional.ofNullable(projectId).filter(x -> x > 0)
                .orElseThrow(() -> new IllegalArgumentException("projectId is required or invalid"));
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("files cannot be empty");
        }

        ReqResult<Map<String, Object>> result = customPageCodingDomainService.specifiedFilesUpdate(projectId, files,
                userContext);

        log.info("[Application] project Id={},specifiedfileupdatecompleted,result={}", projectId, result);
        return result;
    }

    // 全量文件修改,不开启事务,如果保活信息更新失败,不要影响版本信息
    // @Transactional(rollbackFor = Exception.class)
    @Override
    public ReqResult<Map<String, Object>> allFilesUpdate(Long projectId, List<PageFileInfo> files,
                                                         UserContext userContext) {
        log.info("[Application] project Id={},fullfileupdate", projectId);
        Optional.ofNullable(projectId).filter(x -> x > 0)
                .orElseThrow(() -> new IllegalArgumentException("projectId is required or invalid"));
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("files cannot be empty");
        }

        ReqResult<Map<String, Object>> result = customPageCodingDomainService.allFilesUpdate(projectId, files,
                userContext);

        log.info("[Application] project Id={},fullfileupdatecompleted,result={}", projectId, result);
        return result;
    }

    // 上传文件,不开启事务,如果保活信息更新失败,不要影响版本信息
    // @Transactional(rollbackFor = Exception.class)
    @Override
    public ReqResult<Map<String, Object>> uploadSingleFile(Long projectId, MultipartFile file, String filePath,
                                                           UserContext userContext) {
        log.info("[Application] project Id={},upload single file,file Path={}", projectId, filePath);
        Optional.ofNullable(projectId).filter(x -> x > 0)
                .orElseThrow(() -> new IllegalArgumentException("projectId is required or invalid"));
        Optional.ofNullable(file).orElseThrow(() -> new IllegalArgumentException("file is required"));
        Optional.ofNullable(filePath).filter(x -> !x.trim().isEmpty())
                .orElseThrow(() -> new IllegalArgumentException("filePath is required"));

        ReqResult<Map<String, Object>> result = customPageCodingDomainService.uploadSingleFile(projectId, file,
                filePath, userContext);
        log.info("[Application] project Id={},upload single filecompleted,result={}", projectId, result);
        return result;
    }

    @Override
    public ReqResult<String> getFileProxyUrl(Long projectId, String filePath, UserContext userContext) {
        log.info("[Application] project Id={},getfileproxy URL,file Path={}", projectId, filePath);
        Optional.ofNullable(projectId).filter(x -> x > 0)
                .orElseThrow(() -> new IllegalArgumentException("projectId is required or invalid"));
        Optional.ofNullable(filePath).filter(x -> !x.trim().isEmpty())
                .orElseThrow(() -> new IllegalArgumentException("filePath is required"));

        try {
            // 获取开发环境代理路径
            String devProxyPath = customPageProxyPathApplicationService.getDevProxyPath(projectId);
            // 去掉filePath开头的所有/
            String normalizedFilePath = filePath.replaceAll("^/+", "");
            String devProxyUrl = devProxyPath + normalizedFilePath;

            log.info("[Application] project Id={},getfileproxy URLresponse,proxy Url={}", devProxyUrl);
            return ReqResult.success(devProxyUrl);
        } catch (Exception e) {
            log.error("[Application] project Id={},getfileproxy URLexception,file Path={}", projectId, filePath, e);
            return ReqResult.error("0001", "Failed to get file proxy URL: " + e.getMessage());
        }
    }

    @Override
    public ReqResult<Map<String, Object>> rollbackVersion(Long projectId, Integer rollbackTo,
            UserContext userContext) {
        log.info("[Application] project Id={},rollback version,rollback To={}", projectId, rollbackTo);
        Optional.ofNullable(projectId).filter(x -> x > 0)
                .orElseThrow(() -> new IllegalArgumentException("projectId is required or invalid"));
        Optional.ofNullable(rollbackTo).filter(x -> x >= 0)
                .orElseThrow(() -> new IllegalArgumentException("rollbackTo is required or invalid"));

        ReqResult<Map<String, Object>> result = customPageCodingDomainService.rollbackVersion(projectId,
                rollbackTo, userContext);

        log.info("[Application] project Id={},rollback versioncompleted,result={}", projectId, result);
        return result;
    }

}