package com.xspaceagi.custompage.domain.service.impl;

import com.xspaceagi.custompage.domain.dto.PageFileInfo;
import com.xspaceagi.custompage.domain.gateway.PageAppFileClient;
import com.xspaceagi.custompage.domain.keepalive.IKeepAliveService;
import com.xspaceagi.custompage.domain.model.CustomPageBuildModel;
import com.xspaceagi.custompage.domain.proxypath.ICustomPageProxyPathService;
import com.xspaceagi.custompage.domain.repository.ICustomPageBuildRepository;
import com.xspaceagi.custompage.domain.service.ICustomPageCodingDomainService;
import com.xspaceagi.custompage.sdk.dto.VersionInfoDto;
import com.xspaceagi.custompage.sdk.enums.CustomPageActionEnum;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.utils.DateUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.Date;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class CustomPageCodingDomainServiceImpl implements ICustomPageCodingDomainService {

    @Resource
    private IKeepAliveService keepAliveService;
    @Resource
    private PageAppFileClient pageAppFileClient;
    @Resource
    private SpacePermissionService spacePermissionService;
    @Resource
    private ICustomPageBuildRepository customPageBuildRepository;
    @Resource
    private ICustomPageProxyPathService customPageProxyPathService;

    @Override
    public ReqResult<Map<String, Object>> specifiedFilesUpdate(Long projectId, List<PageFileInfo> files,
                                                               UserContext userContext) {
        log.info("[specified Files Update] project Id={},start domain execution", projectId);
        CustomPageBuildModel buildModel = customPageBuildRepository.getByProjectId(projectId);
        if (buildModel == null) {
            return ReqResult.error("0001", "Project does not exist");
        }

        // 校验空间权限
        spacePermissionService.checkSpaceUserPermission(buildModel.getSpaceId());


        String devProxyPath = customPageProxyPathService.getDevProxyPath(projectId);
        Map<String, Object> resp = pageAppFileClient.specifiedFilesUpdate(projectId, files, buildModel.getCodeVersion(), devProxyPath,
                buildModel.getDevPid());
        if (resp == null) {
            return ReqResult.error("9999", "Update specified files failed: build server returned no response");
        }
        boolean success = Boolean.parseBoolean(String.valueOf(resp.get("success")));
        String message = resp.get("message") == null ? "" : String.valueOf(resp.get("message"));
        if (!success) {
            return ReqResult.error("9999", message);
        }

        // 更新版本信息
//        VersionInfoDto newVersionInfo = VersionInfoDto.builder()
//                .action(CustomPageActionEnum.SUBMIT_FILES_UPDATE.getCode())
//                .build();
//        updateVersion(buildModel, newVersionInfo, userContext);

        return ReqResult.success(resp);
    }

    @Override
    public ReqResult<Map<String, Object>> allFilesUpdate(Long projectId, List<PageFileInfo> files,
                                                         UserContext userContext) {
        log.info("[all Files Update] project Id={},start domain execution", projectId);
        CustomPageBuildModel buildModel = customPageBuildRepository.getByProjectId(projectId);
        if (buildModel == null) {
            return ReqResult.error("0001", "Project does not exist");
        }

        // 校验空间权限
        spacePermissionService.checkSpaceUserPermission(buildModel.getSpaceId());

        String devProxyPath = customPageProxyPathService.getDevProxyPath(projectId);
        Map<String, Object> resp = pageAppFileClient.allFilesUpdate(projectId, files, buildModel.getCodeVersion(), devProxyPath,
                buildModel.getDevPid());
        if (resp == null) {
            return ReqResult.error("9999", "Full file update failed: build server returned no response");
        }
        boolean success = Boolean.parseBoolean(String.valueOf(resp.get("success")));
        String message = resp.get("message") == null ? "" : String.valueOf(resp.get("message"));
        if (!success) {
            return ReqResult.error("9999", message);
        }

        // 更新版本信息
//        VersionInfoDto newVersionInfo = VersionInfoDto.builder()
//                .action(CustomPageActionEnum.SUBMIT_FILES_UPDATE.getCode())
//                .build();
//        updateVersion(buildModel, newVersionInfo, userContext);

        // 开发服务器可能重启,如果重启则更新
        Object pidObj = resp.get("pid");
        Object portObj = resp.get("port");
        if (pidObj instanceof Integer && portObj instanceof Integer) {
            Integer pid = Integer.valueOf(String.valueOf(pidObj));
            Integer port = Integer.valueOf(String.valueOf(portObj));
            keepAliveService.updateKeepAlive(projectId, new Date(), 1, pid, port, userContext);
        }

        return ReqResult.success(resp);
    }

    @Override
    public ReqResult<Map<String, Object>> uploadSingleFile(Long projectId, MultipartFile file, String filePath,
                                                           UserContext userContext) {
        log.info("[upload Single File] project Id={},start domain execution", projectId);

        CustomPageBuildModel buildModel = customPageBuildRepository.getByProjectId(projectId);
        if (buildModel == null) {
            return ReqResult.error("0001", "Project does not exist");
        }

        // 校验空间权限
        spacePermissionService.checkSpaceUserPermission(buildModel.getSpaceId());

        Integer currentVersion = buildModel.getCodeVersion() == null ? 0 : buildModel.getCodeVersion();

        Map<String, Object> resp = pageAppFileClient.uploadSingleFile(projectId, file, filePath, currentVersion);
        if (resp == null) {
            return ReqResult.error("9999", "Upload file failed: build server returned no response");
        }
        boolean success = Boolean.parseBoolean(String.valueOf(resp.get("success")));
        String message = resp.get("message") == null ? "" : String.valueOf(resp.get("message"));
        if (!success) {
            return ReqResult.error("9999", message);
        }

        // 更新版本信息
//        VersionInfoDto newVersionInfo = VersionInfoDto.builder()
//                .action(CustomPageActionEnum.UPLOAD_SINGLE_FILE.getCode())
//                .ext(Map.of("filePath", filePath))
//                .build();
//        updateVersion(buildModel, newVersionInfo, userContext);

        // 开发服务器可能重启,如果重启则更新updateKeepAlive
        Object pidObj = resp.get("pid");
        Object portObj = resp.get("port");
        if (pidObj instanceof Integer && portObj instanceof Integer) {
            Integer pid = Integer.valueOf(String.valueOf(pidObj));
            Integer port = Integer.valueOf(String.valueOf(portObj));
            keepAliveService.updateKeepAlive(projectId, new Date(), 1, pid, port, userContext);
        }

        return ReqResult.success(resp);
    }

    @Override
    public ReqResult<Map<String, Object>> uploadBatchFiles(Long projectId, List<MultipartFile> files,
                                                            List<String> filePaths, UserContext userContext) {
        log.info("[upload Batch Files] project Id={}, start domain execution, fileCount={}", projectId, files.size());

        CustomPageBuildModel buildModel = customPageBuildRepository.getByProjectId(projectId);
        if (buildModel == null) {
            return ReqResult.error("0001", "Project does not exist");
        }

        // 校验空间权限
        spacePermissionService.checkSpaceUserPermission(buildModel.getSpaceId());

        Integer currentVersion = buildModel.getCodeVersion() == null ? 0 : buildModel.getCodeVersion();

        Map<String, Object> resp = pageAppFileClient.uploadBatchFiles(projectId, files, filePaths, currentVersion);
        if (resp == null) {
            return ReqResult.error("9999", "Upload batch files failed: build server returned no response");
        }
        boolean success = Boolean.parseBoolean(String.valueOf(resp.get("success")));
        String message = resp.get("message") == null ? "" : String.valueOf(resp.get("message"));
        if (!success) {
            return ReqResult.error("9999", message);
        }

        // 更新版本信息
//        VersionInfoDto newVersionInfo = VersionInfoDto.builder()
//                .action(CustomPageActionEnum.UPLOAD_BATCH_FILES.getCode())
//                .ext(Map.of("fileCount", String.valueOf(files.size())))
//                .build();
//        updateVersion(buildModel, newVersionInfo, userContext);

        // 开发服务器可能重启,如果重启则更新updateKeepAlive
        Object pidObj = resp.get("pid");
        Object portObj = resp.get("port");
        if (pidObj instanceof Integer && portObj instanceof Integer) {
            Integer pid = Integer.valueOf(String.valueOf(pidObj));
            Integer port = Integer.valueOf(String.valueOf(portObj));
            keepAliveService.updateKeepAlive(projectId, new Date(), 1, pid, port, userContext);
        }

        return ReqResult.success(resp);
    }

    @Override
    public ReqResult<Map<String, Object>> rollbackVersion(Long projectId, Integer rollbackTo,
                                                          UserContext userContext) {
        log.info("[rollback To Version] project Id={},start domain execution,rollback To Version={}", projectId, rollbackTo);
        CustomPageBuildModel buildModel = customPageBuildRepository.getByProjectId(projectId);
        if (buildModel == null) {
            return ReqResult.error("0001", "Project does not exist");
        }

        // 校验空间权限
        spacePermissionService.checkSpaceUserPermission(buildModel.getSpaceId());

        // 校验版本号
        Integer currentVersion = buildModel.getCodeVersion() == null ? 0 : buildModel.getCodeVersion();
        if (rollbackTo >= currentVersion) {
            return ReqResult.error("0002", "Rollback version must be less than the current version");
        }
        if (rollbackTo < 1) {
            return ReqResult.error("0002", "Rollback version cannot be less than 1");
        }

        String devProxyPath = customPageProxyPathService.getDevProxyPath(projectId);
        Map<String, Object> resp = pageAppFileClient.rollbackVersion(projectId, rollbackTo, buildModel.getCodeVersion(), devProxyPath,
                buildModel.getDevPid());
        if (resp == null) {
            return ReqResult.error("9999", "Rollback version failed: build server returned no response");
        }
        boolean success = Boolean.parseBoolean(String.valueOf(resp.get("success")));
        String message = resp.get("message") == null ? "" : String.valueOf(resp.get("message"));
        if (!success) {
            return ReqResult.error("9999", message);
        }

        // 更新版本信息
//        VersionInfoDto newVersionInfo = VersionInfoDto.builder()
//                .action(CustomPageActionEnum.ROLLBACK_VERSION.getCode())
//                .ext(Map.of("rollbackTo", String.valueOf(rollbackTo)))
//                .build();
//        updateVersion(buildModel, newVersionInfo, userContext);

        // 开发服务器可能重启,如果重启则更新
        Object pidObj = resp.get("pid");
        Object portObj = resp.get("port");
        if (pidObj instanceof Integer && portObj instanceof Integer) {
            Integer pid = Integer.valueOf(String.valueOf(pidObj));
            Integer port = Integer.valueOf(String.valueOf(portObj));
            keepAliveService.updateKeepAlive(projectId, new Date(), 1, pid, port, userContext);
        }

        return ReqResult.success(resp);
    }

    // 更新版本信息
    // 已改为用git 版本管理，不再记录库表
//    private void updateVersion(CustomPageBuildModel buildModel, VersionInfoDto newVersionInfo, UserContext userContext) {
//        Integer nextVersion = buildModel.getCodeVersion() + 1;
//
//        List<VersionInfoDto> versionInfo = buildModel.getVersionInfo();
//        newVersionInfo.setVersion(nextVersion);
//        newVersionInfo.setTime(DateUtil.format(new Date(), "yyyy-MM-dd HH:mm:ss"));
//        versionInfo.add(newVersionInfo);
//
//        CustomPageBuildModel updateModel = new CustomPageBuildModel();
//        updateModel.setId(buildModel.getId());
//        updateModel.setCodeVersion(nextVersion);
//        updateModel.setVersionInfo(versionInfo);
//        customPageBuildRepository.updateVersionInfo(updateModel, userContext);
//    }

}
