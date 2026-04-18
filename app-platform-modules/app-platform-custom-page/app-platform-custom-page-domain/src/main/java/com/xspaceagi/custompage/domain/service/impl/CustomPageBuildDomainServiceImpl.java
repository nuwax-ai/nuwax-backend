package com.xspaceagi.custompage.domain.service.impl;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.xspaceagi.custompage.domain.gateway.PageFileBuildClient;
import com.xspaceagi.custompage.domain.keepalive.IKeepAliveService;
import com.xspaceagi.custompage.domain.model.CustomPageBuildModel;
import com.xspaceagi.custompage.domain.proxypath.ICustomPageProxyPathService;
import com.xspaceagi.custompage.domain.repository.ICustomPageBuildRepository;
import com.xspaceagi.custompage.domain.repository.ICustomPageConfigRepository;
import com.xspaceagi.custompage.domain.service.ICustomPageBuildDomainService;
import com.xspaceagi.custompage.domain.service.ICustomPageConfigDomainService;
import com.xspaceagi.custompage.sdk.dto.PublishTypeEnum;
import com.xspaceagi.custompage.sdk.dto.VersionInfoDto;
import com.xspaceagi.custompage.sdk.enums.CustomPageActionEnum;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.enums.YnEnum;
import com.xspaceagi.system.spec.page.SuperPage;
import com.xspaceagi.system.spec.utils.DateUtil;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class CustomPageBuildDomainServiceImpl implements ICustomPageBuildDomainService {

    @Resource
    private ICustomPageBuildRepository customPageBuildRepository;
    @Resource
    private PageFileBuildClient pageFileBuildClient;
    @Resource
    private IKeepAliveService keepAliveService;
    @Resource
    private ICustomPageConfigRepository customPageConfigRepository;
    @Resource
    private ICustomPageConfigDomainService customPageConfigDomainService;
    @Resource
    private SpacePermissionService spacePermissionService;
    @Resource
    private ICustomPageProxyPathService customPageProxyPathService;

    @Override
    public CustomPageBuildModel getByProjectId(Long projectId) {
        return customPageBuildRepository.getByProjectId(projectId);
    }

    @Override
    public ReqResult<CustomPageBuildModel> createProject(Long projectId, Long spaceId,
            UserContext userContext) throws JsonProcessingException {
        if (spaceId == null) {
            throw new IllegalArgumentException("spaceId is required");
        }

        // 不校验空间权限,因为在创建config表的时候已经校验了,这个方法执行一定跟创建config表在同一个事务里
        // spacePermissionService.checkSpaceUserPermission(spaceId);

        CustomPageBuildModel exist = customPageBuildRepository.getByProjectId(projectId);
        if (exist != null) {
            return ReqResult.error("0001", "Project already exists");
        }

        List<VersionInfoDto> versionList = List.of(VersionInfoDto.builder()
                .version(1)
                .time(DateUtil.format(new Date(), "yyyy-MM-dd HH:mm:ss"))
                .action(CustomPageActionEnum.CREATE_PROJECT.getCode())
                .build());

        CustomPageBuildModel model = new CustomPageBuildModel();
        model.setProjectId(projectId);
        model.setDevRunning(YesOrNoEnum.N.getKey());
        model.setBuildRunning(YesOrNoEnum.N.getKey());
        model.setCodeVersion(1);
        model.setVersionInfo(versionList);
        model.setCreatorId(userContext.getUserId());
        model.setCreatorName(userContext.getUserName());
        model.setTenantId(userContext.getTenantId());
        model.setSpaceId(spaceId);
        model.setYn(YnEnum.Y.getKey());

        Long id = customPageBuildRepository.add(model, userContext);
        model.setId(id);

        return ReqResult.success(model);
    }

    // 调用node创建项目
    public ReqResult<Map<String, Object>> initProject(Long projectId) {
        Map<String, Object> resp = pageFileBuildClient.createProject(projectId);
        if (resp == null) {
            return ReqResult.error("9999", "Create project failed: build server returned no response");
        }
        boolean success = Boolean.parseBoolean(String.valueOf(resp.get("success")));
        String message = resp.get("message") == null ? "" : String.valueOf(resp.get("message"));
        if (!success) {
            return ReqResult.error("9999", message);
        }
        return ReqResult.success(resp);
    }

    @Override
    public ReqResult<Map<String, Object>> uploadProject(Long projectId, MultipartFile file, boolean isInitProject,
            UserContext userContext) {
        log.info("[upload-project] project Id={},start execution", projectId);

        CustomPageBuildModel buildModel = customPageBuildRepository.getByProjectId(projectId);
        if (buildModel == null) {
            return ReqResult.error("0001", "Project build information does not exist");
        }

        // 校验空间权限
        spacePermissionService.checkSpaceUserPermission(buildModel.getSpaceId());

        // 通过projectId查询devProxyPath
        String devProxyPath = null;
        try {
            devProxyPath = customPageProxyPathService.getDevProxyPath(projectId);
        } catch (Exception e) {
            log.warn("[upload-project] project Id={},could not gettenantdefaultagent", projectId);
            // return ReqResult.error("9999", "租户没有配置默认智能体");
        }
        Integer targetVersion = isInitProject ? 1 : (buildModel.getCodeVersion() + 1);

        // 上传
        log.info("[upload-project] project Id={},startcallserver", projectId);
        Map<String, Object> resp = pageFileBuildClient.uploadProject(projectId, file, targetVersion,
                buildModel.getDevPid(), devProxyPath);
        if (resp == null) {
            log.error("[upload-project] project Id={},upload projectfailed,server returned null", projectId);
            return ReqResult.error("9999", "Upload project failed: build server returned no response");
        }

        boolean success = Boolean.parseBoolean(String.valueOf(resp.get("success")));
        String message = resp.get("message") == null ? "" : String.valueOf(resp.get("message"));
        if (!success) {
            log.error("[upload-project] project Id={},upload projectfailed,server returned message={}", projectId, message);
            return ReqResult.error("9999", message);
        }

        // 更新版本信息
        List<VersionInfoDto> versionInfo = isInitProject ? new ArrayList<>() : buildModel.getVersionInfo();
        versionInfo.add(VersionInfoDto.builder()
                .version(targetVersion)
                .time(DateUtil.format(new Date(), "yyyy-MM-dd HH:mm:ss"))
                .action(CustomPageActionEnum.UPLOAD.getCode())
                .build());
        try {
            CustomPageBuildModel updateModel = new CustomPageBuildModel();
            updateModel.setId(buildModel.getId());
            updateModel.setCodeVersion(targetVersion);
            updateModel.setVersionInfo(versionInfo);
            // updateModel.setLastChatModelId(chatModelId);
            // updateModel.setLastMultiModelId(multiModelId);
            customPageBuildRepository.updateVersionInfo(updateModel, userContext);
            log.info("[upload-project] project Id={},update version infosucceeded target Version={}", projectId, targetVersion);
        } catch (Exception e) {
            log.error("[upload-project] project Id={},update version infofailed, target Version={}", projectId, targetVersion, e);
            // 不抛出异常，因为上传已经成功
        }

        Object pidObj = resp.get("pid");
        Object portObj = resp.get("port");
        if (pidObj == null || portObj == null) {
            log.error("[upload-project] project Id={},devservice not started, message={}", projectId, resp.get("message"));
            keepAliveService.updateKeepAlive(projectId, new Date(), YesOrNoEnum.N.getKey(), null, null, userContext);
            log.warn("[upload-project] project Id={},devservice not started, update keep-alive infosucceeded,pid=null,port=null", projectId);
        } else {
            Integer pid = Integer.valueOf(String.valueOf(pidObj));
            Integer port = Integer.valueOf(String.valueOf(portObj));
            keepAliveService.updateKeepAlive(projectId, new Date(), YesOrNoEnum.Y.getKey(), pid, port, userContext);
            log.info("[upload-project] project Id={},devservice started, update keep-alive infosucceeded,pid={},port={}", projectId, pid, port);
        }

        log.info("[upload-project] project Id={},upload projectsucceeded,result={}", projectId, resp);
        return ReqResult.success(resp);

    }

    @Override
    public ReqResult<Map<String, Object>> keepAlive(Long projectId, UserContext userContext) {
        return keepAliveService.handleKeepAlive(projectId, userContext);
    }

    @Override
    public ReqResult<Map<String, Object>> startDev(Long projectId, UserContext userContext) {
        log.info("[start Dev] project Id={},start domain execution", projectId);

        ReqResult<Map<String, Object>> result = keepAliveService.handleKeepAlive(projectId, userContext);
        log.error("[start Dev] project Id={},response,result={}", projectId, result);

        return result;
    }

    @Override
    public ReqResult<Map<String, Object>> build(Long projectId, String publishType, UserContext userContext) {
        log.info("[build] project Id={},start domain execution", projectId);

        CustomPageBuildModel model = customPageBuildRepository.getByProjectId(projectId);
        if (model == null) {
            return ReqResult.error("0001", "Project does not exist");
        }
        PublishTypeEnum publishTypeEnum = PublishTypeEnum.getByValue(publishType);
        if (publishTypeEnum == null) {
            return ReqResult.error("0001", "Publish type does not exist");
        }

        // 校验空间权限
        spacePermissionService.checkSpaceUserPermission(model.getSpaceId());

        log.error("[build] project Id={},startcallserver", projectId);
        String prodProxyPath = customPageProxyPathService.getProdProxyPath(projectId);
        Map<String, Object> resp = pageFileBuildClient.build(projectId, prodProxyPath);
        if (resp == null) {
            log.error("[build] project Id={},buildfailed,server returned null", projectId);
            return ReqResult.error("9999", "Build failed: build server returned no response");
        }
        boolean success = Boolean.parseBoolean(String.valueOf(resp.get("success")));
        String message = resp.get("message") == null ? "" : String.valueOf(resp.get("message"));
        if (!success) {
            log.error("[build] project Id={},buildfailed,server returned message={}", projectId, message);
            return ReqResult.error("9999", message);
        }
        log.error("[build] project Id={},buildsucceeded", projectId);

        // 更新build表的构建状态
        customPageBuildRepository.updateBuildStatus(projectId, model.getCodeVersion(), userContext);
        log.error("[build] project Id={},buildtableupdatesucceeded", projectId);

        // 更新config表的构建状态
        customPageConfigRepository.updateBuildStatus(projectId, publishTypeEnum, userContext);
        log.error("[build] project Id={},configtableupdatesucceeded", projectId);

        return ReqResult.success(resp);
    }

    @Override
    public ReqResult<Map<String, Object>> stopDev(Long projectId, UserContext userContext) {
        log.info("[stop Dev] project Id={},start domain execution", projectId);

        CustomPageBuildModel model = customPageBuildRepository.getByProjectId(projectId);
        if (model == null) {
            return ReqResult.error("0001", "Project does not exist");
        }

        // 校验空间权限
        spacePermissionService.checkSpaceUserPermission(model.getSpaceId());

        if (model.getDevPid() == null) {
            log.info("[stop-dev] project Id={}, table queryprojectport null, handle", projectId);
            return ReqResult.success(null);
        }
        if (model.getDevPid() <= 1) {
            log.info("[stop-dev] project Id={}, table queryprojectpid {},invalid, handle", projectId, model.getDevPid());
            return ReqResult.error("Invalid process ID (pid)");
        }

        Map<String, Object> resp = pageFileBuildClient.stopDev(projectId, model.getDevPid());
        if (resp == null) {
            log.error("[stop-dev] project Id={},stop failed,server returned null", projectId);
            return ReqResult.error("9999", "Stop failed: build server returned no response");
        }
        boolean success = Boolean.parseBoolean(String.valueOf(resp.get("success")));
        String message = resp.get("message") == null ? "" : String.valueOf(resp.get("message"));
        if (!success) {
            log.error("[stop-dev] project Id={},stop failed,server returned message={}", projectId, message);
            return ReqResult.error("9999", message);
        }
        log.info("[stop-dev] project Id={},stop succeeded", projectId);
        keepAliveService.updateKeepAlive(projectId, new Date(), YesOrNoEnum.N.getKey(), null, null, userContext);
        log.info("[stop-dev] project Id={},keep-alive infoupdatesucceeded", projectId);

        return ReqResult.success(resp);
    }

    @Override
    public ReqResult<Map<String, Object>> restartDev(Long projectId, UserContext userContext) {
        log.info("[restart Dev] project Id={},start domain execution", projectId);

        CustomPageBuildModel model = customPageBuildRepository.getByProjectId(projectId);
        if (model == null) {
            return ReqResult.error("0001", "Project does not exist");
        }

        // 校验空间权限
        spacePermissionService.checkSpaceUserPermission(model.getSpaceId());

        log.info("[restart-dev] project Id={},startcallserver", projectId);
        Integer pid = model.getDevPid();

        String devProxyPath = customPageProxyPathService.getDevProxyPath(projectId);
        Map<String, Object> resp = pageFileBuildClient.restartDev(projectId, pid, devProxyPath);
        if (resp == null) {
            log.error("[restart-dev] project Id={},restart failed,server returned null", projectId);
            return ReqResult.error("9999", "Restart failed: build server returned no response");
        }
        boolean success = Boolean.parseBoolean(String.valueOf(resp.get("success")));
        String message = resp.get("message") == null ? "" : String.valueOf(resp.get("message"));
        if (!success) {
            log.error("[restart-dev] project Id={},restart failed,server returned message={}", projectId, message);
            String code = resp.get("code") == null ? "" : String.valueOf(resp.get("code"));
            if ("PROJECT_STARTING".equals(code)) {
                return ReqResult.error(ErrorCodeEnum.PROJECT_STARTING.getCode(), ErrorCodeEnum.PROJECT_STARTING.getMsg());
            }
            return ReqResult.error("9999", message);
        }

        // 重启成功后，更新 dev 运行信息（有则更新，无则插入）
        Integer newPid = null;
        Integer newPort = null;
        try {
            Object pidObj = resp.get("pid");
            Object portObj = resp.get("port");
            newPid = Integer.valueOf(String.valueOf(pidObj));
            newPort = Integer.valueOf(String.valueOf(portObj));
        } catch (Exception e) {
            keepAliveService.updateKeepAlive(projectId, new Date(), YesOrNoEnum.N.getKey(), null, null, userContext);
            log.error("[restart-dev] project Id={},get service port pidexception,update keep-alive info,pid=null,port=null", projectId);

            return ReqResult.error("9999", "Failed to obtain dev server port and process ID");
        }
        log.info("[restart-dev] project Id={},restart succeeded", projectId);
        keepAliveService.updateKeepAlive(projectId, new Date(), 1, newPid, newPort, userContext);
        log.info("[restart-dev] project Id={},update keep-alive infosucceeded,pid={},port={}", projectId, newPid, newPort);

        return ReqResult.success(resp);
    }

    @Override
    public SuperPage<CustomPageBuildModel> pageQuery(CustomPageBuildModel model, Long current,
            Long pageSize) {
        return customPageBuildRepository.pageQuery(model, current, pageSize);
    }

    @Override
    public List<CustomPageBuildModel> list(CustomPageBuildModel model) {
        return customPageBuildRepository.list(model);
    }

    @Override
    public List<CustomPageBuildModel> listByProjectIds(List<Long> projectIdList) {
        return customPageBuildRepository.listByProjectIds(projectIdList);
    }

    @Override
    public ReqResult<Map<String, Object>> deleteProjectFiles(CustomPageBuildModel model, UserContext userContext) {
        Long projectId = model.getProjectId();
        log.info("[delete-project-files] project Id={},start domain execution", projectId);

        // 校验空间权限
        spacePermissionService.checkSpaceUserPermission(model.getSpaceId());

        log.info("[delete-project-files] project Id={},startcallserver", projectId);
        Integer pid = model.getDevPid();

        Map<String, Object> resp = pageFileBuildClient.deleteProject(projectId, pid);
        if (resp == null) {
            log.error("[delete-project-files] project Id={},deletefailed,server returned null", projectId);
            return ReqResult.error("9999", "Delete failed: build server returned no response");
        }
        boolean success = Boolean.parseBoolean(String.valueOf(resp.get("success")));
        String message = resp.get("message") == null ? "" : String.valueOf(resp.get("message"));
        if (!success) {
            log.error("[delete-project-files] project Id={},deletefailed,server returned message={}", projectId, message);
            return ReqResult.error("9999", message);
        }

        log.info("[delete-project-files] project Id={},deletesucceeded", projectId);
        return ReqResult.success(resp);
    }

    @Override
    public ReqResult<Map<String, Object>> getDevLog(Long projectId, Integer startIndex, String logType, UserContext userContext) {
        log.debug("[get Dev Log] project Id={}, start Index={}, start domain execution", projectId, startIndex);

        CustomPageBuildModel model = customPageBuildRepository.getByProjectId(projectId);
        if (model == null) {
            return ReqResult.error("0001", "Project does not exist");
        }

        // 校验空间权限
        spacePermissionService.checkSpaceUserPermission(model.getSpaceId());

        log.debug("[get Dev Log] project Id={}, startcallserver", projectId);

        Map<String, Object> resp = pageFileBuildClient.getDevLog(projectId, startIndex, logType);
        if (resp == null) {
            log.error("[get Dev Log] project Id={}, query logsfailed, server returned null", projectId);
            return ReqResult.error("9999", "Query logs failed: build server returned no response");
        }
        boolean success = Boolean.parseBoolean(String.valueOf(resp.get("success")));
        String message = resp.get("message") == null ? "" : String.valueOf(resp.get("message"));
        if (!success) {
            log.error("[get Dev Log] project Id={}, query logsfailed, server returned message={}", projectId, message);
            return ReqResult.error("9999", message);
        }
        log.debug("[get Dev Log] project Id={}, query logssucceeded", projectId);

        return ReqResult.success(resp);
    }

    @Override
    public ReqResult<Map<String, Object>> copyProject(Long sourceProjectId, Long targetProjectId) {
        Map<String, Object> resp = pageFileBuildClient.copyProject(sourceProjectId, targetProjectId);
        if (resp == null) {
            return ReqResult.error("9999", "Copy project failed: build server returned no response");
        }
        boolean success = Boolean.parseBoolean(String.valueOf(resp.get("success")));
        String message = resp.get("message") == null ? "" : String.valueOf(resp.get("message"));
        if (!success) {
            return ReqResult.error("9999", message);
        }
        return ReqResult.success(resp);
    }

    @Override
    public ReqResult<Map<String, Object>> getLogCacheStats() {
        log.info("[Domain] getlogcachestats");
        Map<String, Object> resp = pageFileBuildClient.getLogCacheStats();
        if (resp == null) {
            return ReqResult.error("9999", "Get log cache stats failed: build server returned no response");
        }
        boolean success = Boolean.parseBoolean(String.valueOf(resp.get("success")));
        String message = resp.get("message") == null ? "" : String.valueOf(resp.get("message"));
        if (!success) {
            return ReqResult.error("9999", message);
        }
        log.info("[Domain] getlogcachestatssucceeded");
        return ReqResult.success(resp);
    }

    @Override
    public ReqResult<Map<String, Object>> clearAllLogCache() {
        log.info("[Domain] clear all log cache");
        Map<String, Object> resp = pageFileBuildClient.clearAllLogCache();
        if (resp == null) {
            return ReqResult.error("9999", "Clear log cache failed: build server returned no response");
        }
        boolean success = Boolean.parseBoolean(String.valueOf(resp.get("success")));
        String message = resp.get("message") == null ? "" : String.valueOf(resp.get("message"));
        if (!success) {
            return ReqResult.error("9999", message);
        }
        log.info("[Domain] clear all log cachesucceeded");
        return ReqResult.success(resp);
    }
}