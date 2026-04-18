package com.xspaceagi.custompage.domain.service.impl;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.xspaceagi.custompage.sdk.dto.*;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.enums.YnEnum;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.id.IdGenerator;
import com.xspaceagi.system.spec.page.SuperPage;
import com.xspaceagi.system.spec.utils.IdGeneratorRetryUtil;
import com.xspaceagi.system.spec.utils.RedisUtil;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.agent.core.sdk.IAgentRpcService;
import com.xspaceagi.agent.core.sdk.dto.PluginEnableOrUpdateDto;
import com.xspaceagi.agent.core.sdk.dto.PluginInfoDto;
import com.xspaceagi.agent.core.sdk.dto.TemplateEnableOrUpdateDto;
import com.xspaceagi.agent.core.sdk.dto.WorkflowInfoDto;
import com.xspaceagi.agent.core.sdk.enums.TargetTypeEnum;
import com.xspaceagi.custompage.domain.dto.PageFileInfo;
import com.xspaceagi.custompage.domain.gateway.PageFileBuildClient;
import com.xspaceagi.custompage.domain.keepalive.IKeepAliveService;
import com.xspaceagi.custompage.domain.model.CustomPageBuildModel;
import com.xspaceagi.custompage.domain.model.CustomPageConfigModel;
import com.xspaceagi.custompage.domain.proxypath.ICustomPageProxyPathService;
import com.xspaceagi.custompage.domain.repository.ICustomPageBuildRepository;
import com.xspaceagi.custompage.domain.repository.ICustomPageConfigRepository;
import com.xspaceagi.custompage.domain.service.ICustomPageConfigDomainService;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class CustomPageConfigDomainServiceImpl implements ICustomPageConfigDomainService {

    @Resource
    private RedisUtil redisUtil;
    @Resource
    private IdGenerator idGenerator;
    @Resource
    private IAgentRpcService agentRpcService;
    @Resource
    private IKeepAliveService keepAliveService;
    @Resource
    private PageFileBuildClient pageFileBuildClient;
    @Resource
    private SpacePermissionService spacePermissionService;
    @Resource
    private ICustomPageConfigRepository customPageConfigRepository;
    @Resource
    private ICustomPageBuildRepository customPageBuildRepository;
    @Resource
    private ICustomPageProxyPathService customPageProxyPathService;

    @Override
    public ReqResult<CustomPageConfigModel> create(CustomPageConfigModel model, UserContext userContext) {
        // 校验空间权限
        spacePermissionService.checkSpaceUserPermission(model.getSpaceId());

        model.setBuildRunning(YesOrNoEnum.N.getKey());
        model.setTenantId(userContext.getTenantId());
        model.setCreatorId(userContext.getUserId());
        model.setCreatorName(userContext.getUserName());
        model.setYn(YnEnum.Y.getKey());

        // 使用重试工具，自动处理ID重复的情况
        CustomPageConfigModel result = IdGeneratorRetryUtil.executeWithRetry(
                idGenerator,
                16,
                (projectId) -> {
                    model.setId(projectId);
                    model.setBasePath("/" + projectId);
                    Long id = customPageConfigRepository.add(model, userContext);
                    model.setId(id);
                    return model;
                },
                "创建用户页面",
                3 // 最大重试3次
        );

        return ReqResult.success(result);
    }

    @Override
    public List<CustomPageConfigModel> list(CustomPageConfigModel model) {
        return customPageConfigRepository.list(model);
    }

    @Override
    public SuperPage<CustomPageConfigModel> pageQuery(CustomPageConfigModel configModel, Long current, Long pageSize) {
        return customPageConfigRepository.pageQuery(configModel, current, pageSize);
    }

    @Override
    public CustomPageConfigModel getById(Long id) {
        return customPageConfigRepository.getById(id);
    }

    @Override
    public CustomPageConfigModel getByAgentId(Long agentId) {
        return customPageConfigRepository.getByAgentId(agentId);
    }

    @Override
    public List<CustomPageConfigModel> listByIds(List<Long> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return List.of();
        }
        return customPageConfigRepository.listByIds(ids);
    }

    @Override
    public ReqResult<CustomPageConfigModel> update(CustomPageConfigModel model, UserContext userContext) {
        log.info("[update] update project, project Id={}", model.getId());

        CustomPageConfigModel configModel = customPageConfigRepository.getById(model.getId());
        if (configModel == null) {
            log.error("[Domain] Project config not found, project Id={}", model.getId());
            return ReqResult.error("0001", "Project configuration does not exist");
        }

        // 校验空间权限
        spacePermissionService.checkSpaceUserPermission(configModel.getSpaceId());

        // 更新配置
        model.setModifiedId(userContext.getUserId());
        model.setModifiedName(userContext.getUserName());

        customPageConfigRepository.updateById(model, userContext);

        log.info("[update] update projectsucceeded, project Id={}", model.getId());
        return ReqResult.success(configModel);
    }

    @Override
    public ReqResult<List<ProxyConfig>> addProxy(Long projectId, ProxyConfig proxyConfig,
                                                 UserContext userContext) {
        log.info("[Domain] updatereverse proxy config, project Id={}, env={}, path={}", projectId, proxyConfig.getEnv(),
                proxyConfig.getPath());

        CustomPageConfigModel configModel = customPageConfigRepository.getById(projectId);
        if (configModel == null) {
            log.error("[Domain] Project config not found, project Id={}", projectId);
            return ReqResult.error("0001", "Project configuration does not exist");
        }

        // 校验空间权限
        spacePermissionService.checkSpaceUserPermission(configModel.getSpaceId());

        // 获取现有的代理配置列表
        List<ProxyConfig> existingProxyConfigs = Optional.ofNullable(configModel.getProxyConfigs())
                .orElse(new ArrayList<>());

        // 查找是否已存在相同环境和路径的配置
        boolean found = false;
        for (int i = 0; i < existingProxyConfigs.size(); i++) {
            ProxyConfig existing = existingProxyConfigs.get(i);
            if (existing.getEnv() == proxyConfig.getEnv() && existing.getPath().equals(proxyConfig.getPath())) {
                log.error("[Domain] reverse proxy configalready exists, project Id={}, env={}, path={}", projectId, proxyConfig.getEnv(),
                        proxyConfig.getPath());
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.customPageProxyEnvPathExists);
            }
        }

        // 如果不存在，则添加新配置
        if (!found) {
            existingProxyConfigs.add(proxyConfig);
        }

        CustomPageConfigModel updateModel = new CustomPageConfigModel();
        updateModel.setId(configModel.getId());
        updateModel.setProxyConfigs(existingProxyConfigs);
        updateModel.setModifiedId(userContext.getUserId());
        updateModel.setModifiedName(userContext.getUserName());

        customPageConfigRepository.updateById(updateModel, userContext);

        log.info("[Domain] updatereverse proxy configsucceeded, project Id={}, env={}, path={}",
                projectId, proxyConfig.getEnv(), proxyConfig.getPath());
        return ReqResult.success(existingProxyConfigs);
    }

    @Override
    public ReqResult<Void> editProxy(Long projectId, ProxyConfig proxyConfig, UserContext userContext) {
        log.info("[Domain] editreverse proxy config, project Id={}, env={}, path={}", projectId, proxyConfig.getEnv(),
                proxyConfig.getPath());

        CustomPageConfigModel configModel = customPageConfigRepository.getById(projectId);
        if (configModel == null) {
            log.error("[Domain] Project config not found, project Id={}", projectId);
            return ReqResult.error("0001", "Project configuration does not exist");
        }

        // 校验空间权限
        spacePermissionService.checkSpaceUserPermission(configModel.getSpaceId());

        // 现有的代理配置列表
        List<ProxyConfig> existingProxyConfigs = Optional.ofNullable(configModel.getProxyConfigs())
                .orElse(new ArrayList<>());

        boolean found = false;
        for (int i = 0; i < existingProxyConfigs.size(); i++) {
            ProxyConfig existing = existingProxyConfigs.get(i);
            if (existing.getEnv() == proxyConfig.getEnv() && existing.getPath().equals(proxyConfig.getPath())) {
                existingProxyConfigs.set(i, proxyConfig);
                found = true;
                break;
            }
        }

        if (!found) {
            log.error("[Domain] Reverse proxy config not found, project Id={}, env={}, path={}", projectId, proxyConfig.getEnv(),
                    proxyConfig.getPath());
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.customPageProxyEnvPathNotFoundForEdit);
        }

        CustomPageConfigModel updateModel = new CustomPageConfigModel();
        updateModel.setId(configModel.getId());
        updateModel.setProxyConfigs(existingProxyConfigs);
        updateModel.setModifiedId(userContext.getUserId());
        updateModel.setModifiedName(userContext.getUserName());

        customPageConfigRepository.updateById(updateModel, userContext);

        log.info("[Domain] editreverse proxy configsucceeded, project Id={}, env={}, path={}",
                projectId, proxyConfig.getEnv(), proxyConfig.getPath());
        return ReqResult.success(null);
    }

    @Override
    public ReqResult<Void> deleteProxy(Long projectId, String env, String path, UserContext userContext) {
        log.info("[Domain] delete reverse proxy config, project Id={}, env={}, path={}", projectId, env, path);

        CustomPageConfigModel configModel = customPageConfigRepository.getById(projectId);
        if (configModel == null) {
            log.error("[Domain] Project config not found, project Id={}", projectId);
            return ReqResult.error("0001", "Project configuration does not exist");
        }

        // 校验空间权限
        spacePermissionService.checkSpaceUserPermission(configModel.getSpaceId());

        List<ProxyConfig> existingProxyConfigs = Optional.ofNullable(configModel.getProxyConfigs())
                .orElse(new ArrayList<>());

        // 查找要删除的配置
        boolean found = false;
        ProxyConfig.ProxyEnv proxyEnv = ProxyConfig.ProxyEnv.get(env);
        for (int i = 0; i < existingProxyConfigs.size(); i++) {
            ProxyConfig existing = existingProxyConfigs.get(i);
            if (existing.getEnv() == proxyEnv && existing.getPath().equals(path)) {
                // 删除配置
                existingProxyConfigs.remove(i);
                found = true;
                break;
            }
        }

        if (!found) {
            log.error("[Domain] Reverse proxy config not found, project Id={}, env={}, path={}", projectId, env, path);
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.customPageProxyEnvPathNotFoundForDelete);
        }

        CustomPageConfigModel updateModel = new CustomPageConfigModel();
        updateModel.setId(configModel.getId());
        updateModel.setProxyConfigs(existingProxyConfigs);
        updateModel.setModifiedId(userContext.getUserId());
        updateModel.setModifiedName(userContext.getUserName());

        customPageConfigRepository.updateById(updateModel, userContext);

        log.info("[Domain] delete reverse proxy configsucceeded, project Id={}, env={}, path={}",
                projectId, env, path);
        return ReqResult.success(null);
    }

    @Override
    public ReqResult<Void> savePathArgs(Long projectId, PageArgConfig pageArgConfig, UserContext userContext) {
        log.info("[Domain] configure path args, project Id={}, page Uri={}", projectId, pageArgConfig.getPageUri());

        CustomPageConfigModel configModel = customPageConfigRepository.getById(projectId);
        if (configModel == null) {
            log.error("[Domain] Project config not found, project Id={}", projectId);
            return ReqResult.error("0001", "Project configuration does not exist");
        }

        // 校验空间权限
        spacePermissionService.checkSpaceUserPermission(configModel.getSpaceId());

        // 验证参数配置
        validatePageArgConfig(pageArgConfig);

        // 现有的页面参数配置列表
        List<PageArgConfig> existingPageArgConfigs = Optional.ofNullable(configModel.getPageArgConfigs())
                .orElse(new ArrayList<>());

        // 查找是否已存在相同pageUri的配置
        boolean found = false;
        for (int i = 0; i < existingPageArgConfigs.size(); i++) {
            PageArgConfig existing = existingPageArgConfigs.get(i);
            if (existing.getPageUri().equals(pageArgConfig.getPageUri())) {
                existingPageArgConfigs.set(i, pageArgConfig);
                found = true;
                break;
            }
        }

        if (!found) {
            existingPageArgConfigs.add(pageArgConfig);
        }

        CustomPageConfigModel updateModel = new CustomPageConfigModel();
        updateModel.setId(configModel.getId());
        updateModel.setPageArgConfigs(existingPageArgConfigs);
        updateModel.setModifiedId(userContext.getUserId());
        updateModel.setModifiedName(userContext.getUserName());

        customPageConfigRepository.updateById(updateModel, userContext);

        log.info("[Domain] configure path argssucceeded, project Id={}, page Uri={}",
                projectId, pageArgConfig.getPageUri());
        return ReqResult.success(null);
    }

    @Override
    public ReqResult<Void> addPath(Long projectId, PageArgConfig pageArgConfig, UserContext userContext) {
        log.info("[Domain] add path config, project Id={}, page Uri={}", projectId, pageArgConfig.getPageUri());

        CustomPageConfigModel configModel = customPageConfigRepository.getById(projectId);
        if (configModel == null) {
            log.error("[Domain] Project config not found, project Id={}", projectId);
            return ReqResult.error("0001", "Project configuration does not exist");
        }

        // 校验空间权限
        spacePermissionService.checkSpaceUserPermission(configModel.getSpaceId());

        // 验证参数配置
        validatePageArgConfig(pageArgConfig);

        List<PageArgConfig> existingPageArgConfigs = Optional.ofNullable(configModel.getPageArgConfigs())
                .orElse(new ArrayList<>());

        // 查找是否已存在相同pageUri的配置
        for (PageArgConfig existing : existingPageArgConfigs) {
            if (existing.getPageUri().equals(pageArgConfig.getPageUri())) {
                log.error("[Domain] path configalready exists, project Id={}, page Uri={}", projectId, pageArgConfig.getPageUri());
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.customPagePathConfigExists);
            }
        }

        existingPageArgConfigs.add(pageArgConfig);

        CustomPageConfigModel updateModel = new CustomPageConfigModel();
        updateModel.setId(configModel.getId());
        updateModel.setPageArgConfigs(existingPageArgConfigs);
        updateModel.setModifiedId(userContext.getUserId());
        updateModel.setModifiedName(userContext.getUserName());

        customPageConfigRepository.updateById(updateModel, userContext);

        log.info("[Domain] add path configsucceeded, project Id={}, page Uri={}",
                projectId, pageArgConfig.getPageUri());
        return ReqResult.success(null);
    }

    @Override
    public ReqResult<Void> editPath(Long projectId, PageArgConfig pageArgConfig, UserContext userContext) {
        log.info("[Domain] editpath config, project Id={}, page Uri={}", projectId, pageArgConfig.getPageUri());

        CustomPageConfigModel configModel = customPageConfigRepository.getById(projectId);
        if (configModel == null) {
            log.error("[Domain] Project config not found, project Id={}", projectId);
            return ReqResult.error("0001", "Project configuration does not exist");
        }

        // 校验空间权限
        spacePermissionService.checkSpaceUserPermission(configModel.getSpaceId());

        // 验证参数配置
        validatePageArgConfig(pageArgConfig);

        List<PageArgConfig> existingPageArgConfigs = Optional.ofNullable(configModel.getPageArgConfigs())
                .orElse(new ArrayList<>());

        // 查找是否存在相同pageUri的配置
        boolean found = false;
        for (int i = 0; i < existingPageArgConfigs.size(); i++) {
            PageArgConfig existing = existingPageArgConfigs.get(i);
            if (existing.getPageUri().equals(pageArgConfig.getPageUri())) {
                pageArgConfig.setArgs(existing.getArgs());
                existingPageArgConfigs.set(i, pageArgConfig);
                found = true;
                break;
            }
        }

        if (!found) {
            log.error("[Domain] Path config not found, project Id={}, page Uri={}", projectId, pageArgConfig.getPageUri());
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.customPagePathConfigNotFoundForEdit);
        }

        CustomPageConfigModel updateModel = new CustomPageConfigModel();
        updateModel.setId(configModel.getId());
        updateModel.setPageArgConfigs(existingPageArgConfigs);
        updateModel.setModifiedId(userContext.getUserId());
        updateModel.setModifiedName(userContext.getUserName());

        customPageConfigRepository.updateById(updateModel, userContext);

        log.info("[Domain] editpath configsucceeded, project Id={}, page Uri={}",
                projectId, pageArgConfig.getPageUri());
        return ReqResult.success(null);
    }

    @Override
    public ReqResult<Void> deletePath(Long projectId, String pageUri, UserContext userContext) {
        log.info("[Domain] delete path config, project Id={}, page Uri={}", projectId, pageUri);

        CustomPageConfigModel configModel = customPageConfigRepository.getById(projectId);
        if (configModel == null) {
            log.error("[Domain] Project config not found, project Id={}", projectId);
            return ReqResult.error("0001", "Project configuration does not exist");
        }

        // 校验空间权限
        spacePermissionService.checkSpaceUserPermission(configModel.getSpaceId());

        List<PageArgConfig> existingPageArgConfigs = Optional.ofNullable(configModel.getPageArgConfigs())
                .orElse(new ArrayList<>());

        // 查找是否存在相同pageUri的配置
        boolean found = false;
        for (int i = 0; i < existingPageArgConfigs.size(); i++) {
            PageArgConfig existing = existingPageArgConfigs.get(i);
            if (existing.getPageUri().equals(pageUri)) {
                existingPageArgConfigs.remove(i);
                found = true;
                break;
            }
        }

        if (!found) {
            log.error("[Domain] Path config not found, project Id={}, page Uri={}", projectId, pageUri);
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.customPagePathConfigNotFoundForDelete);
        }

        CustomPageConfigModel updateModel = new CustomPageConfigModel();
        updateModel.setId(configModel.getId());
        updateModel.setPageArgConfigs(existingPageArgConfigs);
        updateModel.setModifiedId(userContext.getUserId());
        updateModel.setModifiedName(userContext.getUserName());

        customPageConfigRepository.updateById(updateModel, userContext);

        log.info("[Domain] delete path configsucceeded, project Id={}, page Uri={}",
                projectId, pageUri);
        return ReqResult.success(null);
    }

    @Override
    public ReqResult<Void> batchConfigProxy(Long projectId, List<ProxyConfig> proxyConfigs, UserContext userContext) {
        log.info("[Domain] batch configure reverse proxy, project Id={}, config Count={}", projectId,
                proxyConfigs != null ? proxyConfigs.size() : 0);

        Optional.ofNullable(projectId).filter(x -> x > 0)
                .orElseThrow(() -> new IllegalArgumentException("projectId is required or invalid"));
        Optional.ofNullable(proxyConfigs)
                .orElseThrow(() -> new IllegalArgumentException("reverse proxy configuration list cannot be empty"));

        CustomPageConfigModel configModel = customPageConfigRepository.getById(projectId);
        if (configModel == null) {
            log.error("[Domain] Project config not found, project Id={}", projectId);
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.customPageProjectConfigNotFound);
        }

        // 验证配置数据
        validateProxyConfigs(proxyConfigs);

        CustomPageConfigModel updateModel = new CustomPageConfigModel();
        updateModel.setId(configModel.getId());
        updateModel.setProxyConfigs(proxyConfigs);
        updateModel.setModifiedId(userContext.getUserId());
        updateModel.setModifiedName(userContext.getUserName());

        customPageConfigRepository.updateById(updateModel, userContext);

        log.info("[Domain] batch configure reverse proxysucceeded, project Id={}, config Count={}",
                projectId, proxyConfigs != null ? proxyConfigs.size() : 0);
        return ReqResult.success(null);
    }

    /**
     * 验证反向代理配置
     */
    private void validateProxyConfigs(List<ProxyConfig> proxyConfigs) {
        if (proxyConfigs.isEmpty()) {
            return; // 允许空列表，表示清空所有配置
        }

        // 检查path重复
        List<String> paths = new ArrayList<>();
        for (ProxyConfig config : proxyConfigs) {
            String pathKey = config.getEnv().name() + ":" + config.getPath();
            if (paths.contains(pathKey)) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.customPageDuplicatePathConfig,
                        config.getEnv().name(), config.getPath());
            }
            paths.add(pathKey);

            // 检查backend是否为空
            if (config.getBackends() == null || config.getBackends().isEmpty()) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.customPageBackendListEmpty,
                        config.getEnv().name(), config.getPath());
            }

            // 检查每个backend的地址是否为空，并设置默认weight
            for (var backend : config.getBackends()) {
                if (backend.getBackend() == null || backend.getBackend().trim().isEmpty()) {
                    throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.customPageBackendAddressEmpty,
                            config.getEnv().name(), config.getPath());
                }
                // 设置默认weight为1
                if (backend.getWeight() <= 0) {
                    backend.setWeight(1);
                }
            }
        }
    }

    public ReqResult<InputStream> exportProject(Long projectId, ExportTypeEnum exportType, UserContext userContext) {
        log.info("[export Project] project Id={}, export Type={},startexportproject", projectId, exportType);

        Optional.ofNullable(projectId).filter(x -> x > 0)
                .orElseThrow(() -> new IllegalArgumentException("projectId is required or invalid"));

        CustomPageConfigModel configModel = customPageConfigRepository.getById(projectId);
        if (configModel == null) {
            return ReqResult.error("0001", "Project does not exist");
        }
        // 校验空间权限
        spacePermissionService.checkSpaceUserPermission(configModel.getSpaceId());

        var buildModel = customPageBuildRepository.getByProjectId(projectId);
        if (buildModel == null) {
            return ReqResult.error("0001", "Project does not exist");
        }

        Integer latestVersion = buildModel.getCodeVersion() == null ? 0 : buildModel.getCodeVersion();
        Integer publishedVersion = buildModel.getBuildVersion();
        Integer exportVersion = exportType == ExportTypeEnum.LATEST ? latestVersion : publishedVersion;

        ProjectConfigExportDto configExportDto = exportType == ExportTypeEnum.LATEST ? buildConfigExportDto(configModel) : null;

        InputStream inputStream = pageFileBuildClient.exportProject(projectId, exportVersion, exportType.name(), configExportDto);
        if (inputStream == null) {
            return ReqResult.error("9999", "Export project failed: build server returned no response");
        }

        log.info("[export Project] exportprojectsucceeded, project Id={}, export Type={}, code Version={}", projectId, exportType, exportVersion);
        return ReqResult.success(inputStream);
    }

    /**
     * 构建配置JSON，包含数据源详细信息
     */
    private ProjectConfigExportDto buildConfigExportDto(CustomPageConfigModel configModel) {
        ProjectConfigExportDto configDto = ProjectConfigExportDto.builder()
                .name(configModel.getName())
                .description(configModel.getDescription())
                .icon(configModel.getIcon())
                .coverImg(configModel.getCoverImg())
                .coverImgSourceType(configModel.getCoverImgSourceType())
                .needLogin(configModel.getNeedLogin().equals(YesOrNoEnum.Y.getKey()))
                .proxyConfigs(configModel.getProxyConfigs())
                .pageArgConfigs(configModel.getPageArgConfigs())
                .build();

        if (configModel.getDataSources() != null && !configModel.getDataSources().isEmpty()) {
            List<Map<String, Object>> dataSourcePlugins = new ArrayList<>();
            List<Map<String, Object>> dataSourceWorkflows = new ArrayList<>();

            for (DataSourceDto dataSource : configModel.getDataSources()) {
                try {
                    if ("plugin".equalsIgnoreCase(dataSource.getType())) {
                        Map<String, Object> pluginMap = null;
                        com.xspaceagi.agent.core.sdk.dto.ReqResult<?> errResult = null;
                        // 查询插件详细信息
                        com.xspaceagi.agent.core.sdk.dto.ReqResult<PluginInfoDto> pluginR1 = agentRpcService.getPublishedPluginInfo(dataSource.getId(), null);
                        errResult = pluginR1;

                        if (pluginR1 != null && pluginR1.isSuccess() && pluginR1.getData() != null) {

                            PluginInfoDto pluginInfoDto = pluginR1.getData();
                            Long pluginSpaceId = pluginInfoDto.getSpaceId();

                            try {
                                spacePermissionService.checkSpaceUserPermission(pluginSpaceId);
                            } catch (Exception e) {
                                log.info("[export Project] project Id={},exportuserno plugin space permission,ignoreplugin,id={}, error={}", configModel.getId(),
                                        pluginSpaceId, e.getMessage());
                                continue;
                            }

                            com.xspaceagi.agent.core.sdk.dto.ReqResult<String> pluginResult = agentRpcService.queryPluginConfig(dataSource.getId(), null);
                            errResult = pluginResult;

                            if (pluginResult != null && pluginResult.isSuccess()) {
                                pluginMap = new HashMap<>();
                                pluginMap.put("key", dataSource.getKey());
                                pluginMap.put("name", pluginInfoDto.getName());
                                pluginMap.put("icon", pluginInfoDto.getIcon());
                                pluginMap.put("data", pluginResult.getData());
                            }
                        }

                        if (pluginMap != null) {
                            dataSourcePlugins.add(pluginMap);
                        } else {
                            log.warn("[export Project] project Id={},queryplugindetailsfailed,id={}, error={}", configModel.getId(),
                                    dataSource.getId(), errResult != null ? errResult.getMessage() : "无响应");
                            throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.customPagePluginQueryFailed,
                                    dataSource.getId(), dataSource.getName());
                        }

                    } else if ("workflow".equalsIgnoreCase(dataSource.getType())) {
                        Map<String, Object> workflowMap = null;
                        com.xspaceagi.agent.core.sdk.dto.ReqResult<?> errResult = null;

                        // 查询工作流详细信息
                        com.xspaceagi.agent.core.sdk.dto.ReqResult<WorkflowInfoDto> workflowR1 = agentRpcService.getPublishedWorkflowInfo(dataSource.getId(), null);
                        errResult = workflowR1;

                        if (workflowR1 != null && workflowR1.isSuccess() && workflowR1.getData() != null) {
                            WorkflowInfoDto workflowInfoDto = workflowR1.getData();
                            Long workflowSpaceId = workflowInfoDto.getSpaceId();

                            try {
                                spacePermissionService.checkSpaceUserPermission(workflowSpaceId);
                            } catch (Exception e) {
                                log.info("[export Project] project Id={},exportuserno workflow space permission,ignoreworkflow,id={}, error={}", configModel.getId(),
                                        workflowSpaceId, e.getMessage());
                                continue;
                            }

                            com.xspaceagi.agent.core.sdk.dto.ReqResult<String> workflowResult = agentRpcService.queryTemplateConfig(TargetTypeEnum.Workflow, dataSource.getId());
                            errResult = workflowResult;

                            if (workflowResult != null && workflowResult.isSuccess()) {
                                workflowMap = new HashMap<>();
                                workflowMap.put("key", dataSource.getKey());
                                workflowMap.put("name", workflowInfoDto.getName());
                                workflowMap.put("icon", workflowInfoDto.getIcon());
                                workflowMap.put("data", workflowResult.getData());
                            }
                        }

                        if (workflowMap != null) {
                            dataSourceWorkflows.add(workflowMap);
                        } else {
                            log.warn("[export Project] project Id={},queryworkflowdetailsfailed,id={}, error={}", configModel.getId(),
                                    dataSource.getId(), errResult != null ? errResult.getMessage() : "无响应");
                            throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.customPageWorkflowQueryFailed,
                                    dataSource.getId(), dataSource.getName());
                        }

                    }
                } catch (Exception e) {
                    log.warn("[export Project] project Id={},querydata sourcedetailsfailed, type={}, id={}, error={}", configModel.getId(),
                            dataSource.getType(), dataSource.getId(), e.getMessage());
                }
            }

            configDto.setDataSourcePlugins(dataSourcePlugins.isEmpty() ? null : dataSourcePlugins);
            configDto.setDataSourceWorkflows(dataSourceWorkflows.isEmpty() ? null : dataSourceWorkflows);
        }
        return configDto;
    }

    @Override
    public ReqResult<Map<String, Object>> queryProjectContent(Long projectId, String command, String proxyPath) {
        log.info("[Domain] query project file content, project Id={}, command={}", projectId, command);

        Optional.ofNullable(projectId).filter(x -> x > 0)
                .orElseThrow(() -> new IllegalArgumentException("projectId is required or invalid"));

        // 校验空间权限
        // CustomPageConfigModel configModel =
        // customPageConfigRepository.getById(projectId);
        // if (configModel == null) {
        // return ReqResult.error("0001", "Project does not exist");
        // }
        // spacePermissionService.checkSpaceUserPermission(configModel.getSpaceId());

        Map<String, Object> resp = pageFileBuildClient.getProjectContent(projectId, command, proxyPath);
        if (resp == null) {
            return ReqResult.error("9999", "Failed to query project file content: build server returned no response");
        }
        boolean success = Boolean.parseBoolean(String.valueOf(resp.get("success")));
        String message = resp.get("message") == null ? "" : String.valueOf(resp.get("message"));
        if (!success) {
            return ReqResult.error("9999", message);
        }
        return ReqResult.success(resp);
    }

    @Override
    public ReqResult<Map<String, Object>> queryProjectContentByVersion(Long projectId, Integer codeVersion, String proxyPath) {
        log.info("[Domain] query project historical version file content, project Id={}, code Version={}", projectId, codeVersion);

        Optional.ofNullable(projectId).filter(x -> x > 0)
                .orElseThrow(() -> new IllegalArgumentException("projectId is required or invalid"));

        Optional.ofNullable(codeVersion).filter(x -> x >= 0)
                .orElseThrow(() -> new IllegalArgumentException("codeVersion is required or invalid"));

        // 查询版本是否存在
        var buildModel = customPageBuildRepository.getByProjectId(projectId);
        if (buildModel == null) {
            return ReqResult.error("0001", "Project does not exist");
        }

        if (codeVersion > buildModel.getCodeVersion()) {
            return ReqResult.error("0002", "The specified version does not exist");
        }

        Map<String, Object> resp = null;
        if (codeVersion < buildModel.getCodeVersion()) {
            List<VersionInfoDto> versionInfo = buildModel.getVersionInfo();
            boolean versionExists = false;
            if (versionInfo != null) {
                versionExists = versionInfo.stream()
                        .anyMatch(version -> codeVersion.equals(version.getVersion()));
            }

            if (!versionExists) {
                return ReqResult.error("0002", "The specified version does not exist");
            }

            resp = pageFileBuildClient.getProjectContentByVersion(projectId, codeVersion, null, proxyPath);
        } else {
            resp = pageFileBuildClient.getProjectContent(projectId, null, proxyPath);
        }

        if (resp == null) {
            return ReqResult.error("9999", "Failed to query historical project file content: build server returned no response");
        }
        boolean success = Boolean.parseBoolean(String.valueOf(resp.get("success")));
        String message = resp.get("message") == null ? "" : String.valueOf(resp.get("message"));
        if (!success) {
            return ReqResult.error("9999", message);
        }
        return ReqResult.success(resp);
    }

    /**
     * 验证页面参数配置
     */
    private void validatePageArgConfig(PageArgConfig pageArgConfig) {
        if (pageArgConfig.getArgs() == null || pageArgConfig.getArgs().isEmpty()) {
            return; // 允许空参数列表
        }

        // 检查args中是否有重复的name
        List<String> names = new ArrayList<>();
        for (PageArg arg : pageArgConfig.getArgs()) {
            if (arg.getName() == null || arg.getName().trim().isEmpty()) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "parameter name");
            }

            if (names.contains(arg.getName())) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.customPageDatasourceParamNameDuplicate, arg.getName());
            }
            names.add(arg.getName());
        }
    }

    @Override
    public ReqResult<Void> bindDataSource(Long projectId, DataSourceDto dataSource, UserContext userContext) {
        log.info("[Domain] savedata source, project Id={}, type={}, data Source Id={}",
                projectId, dataSource.getType(), dataSource.getId());

        if (dataSource.getType() == null || dataSource.getType().trim().isEmpty()) {
            log.error("[Domain] data sourcetype cannot be empty, project Id={}", projectId);
            return ReqResult.error("0003", "Data source type is required");
        }

        if (dataSource.getId() == null || dataSource.getId() <= 0) {
            log.error("[Domain] data source ID cannot be empty or invalid, project Id={}", projectId);
            return ReqResult.error("0004", "Data source ID is required or invalid");
        }

        if (dataSource.getName() == null || dataSource.getName().trim().isEmpty()) {
            log.error("[Domain] data sourcename cannot be empty, project Id={}", projectId);
            return ReqResult.error("0005", "Data source name is required");
        }

        CustomPageConfigModel configModel = customPageConfigRepository.getById(projectId);
        if (configModel == null) {
            log.error("[Domain] Project not found, project Id={}", projectId);
            return ReqResult.error("0001", "Project does not exist");
        }

        // 校验空间权限
        spacePermissionService.checkSpaceUserPermission(configModel.getSpaceId());

        // 现有的数据源列表
        List<DataSourceDto> existingDataSources = Optional.ofNullable(configModel.getDataSources())
                .orElse(new ArrayList<>());

        // 检查是否已存在相同类型和ID的数据源，如果存在则更新，否则添加
        boolean found = false;
        for (int i = 0; i < existingDataSources.size(); i++) {
            DataSourceDto existing = existingDataSources.get(i);
            if (existing.getType().equals(dataSource.getType()) && existing.getId().equals(dataSource.getId())) {
                // 更新现有数据源
                existingDataSources.set(i, dataSource);
                found = true;
                log.info("[Domain] update existing data source, project Id={}, type={}, id={}",
                        projectId, dataSource.getType(), dataSource.getId());
                break;
            }
        }

        if (!found) {
            existingDataSources.add(dataSource);
            log.info("[Domain] add new data source, project Id={}, type={}, id={}",
                    projectId, dataSource.getType(), dataSource.getId());
        }

        CustomPageConfigModel updateModel = new CustomPageConfigModel();
        updateModel.setId(configModel.getId());
        updateModel.setDataSources(existingDataSources);
        updateModel.setModifiedId(userContext.getUserId());
        updateModel.setModifiedName(userContext.getUserName());

        customPageConfigRepository.updateById(updateModel, userContext);

        log.info("[Domain] savedata sourcesucceeded, project Id={}, type={}, id={}",
                projectId, dataSource.getType(), dataSource.getId());
        return ReqResult.success(null);
    }

    @Override
    public ReqResult<Void> unbindDataSource(Long projectId, DataSourceDto dataSource, UserContext userContext) {
        log.info("[unbind Data Source] unbinddata source, project Id={}, type={}, data Source Id={}",
                projectId, dataSource.getType(), dataSource.getId());

        CustomPageConfigModel configModel = customPageConfigRepository.getById(projectId);
        if (configModel == null) {
            log.error("[unbind Data Source] projectnot found, project Id={}", projectId);
            return ReqResult.error("0001", "Project configuration does not exist");
        }

        // 校验空间权限
        spacePermissionService.checkSpaceUserPermission(configModel.getSpaceId());

        // 现有的数据源列表
        List<DataSourceDto> existingDataSources = Optional.ofNullable(configModel.getDataSources())
                .orElse(new ArrayList<>());

        boolean found = false;
        for (int i = 0; i < existingDataSources.size(); i++) {
            DataSourceDto existing = existingDataSources.get(i);
            if (existing.getType().equals(dataSource.getType()) && existing.getId().equals(dataSource.getId())) {
                // 删除现有数据源
                existingDataSources.remove(existing);
                found = true;
                log.info("[unbind Data Source] unbinddata source, project Id={}, type={}, id={}",
                        projectId, dataSource.getType(), dataSource.getId());
                break;
            }
        }

        if (!found) {
            log.info("[unbind Data Source] no data source to unbind found, project Id={}, type={}, id={}",
                    projectId, dataSource.getType(), dataSource.getId());
            return ReqResult.create("0000", "No data source found to unbind", null);
        }

        CustomPageConfigModel updateModel = new CustomPageConfigModel();
        updateModel.setId(configModel.getId());
        updateModel.setDataSources(existingDataSources);
        updateModel.setModifiedId(userContext.getUserId());
        updateModel.setModifiedName(userContext.getUserName());

        customPageConfigRepository.updateById(updateModel, userContext);

        log.info("[Domain] unbinddata sourcesucceeded, project Id={}, type={}, id={}",
                projectId, dataSource.getType(), dataSource.getId());
        return ReqResult.success(null);
    }

    @Override
    public ReqResult<Map<String, Object>> delete(Long projectId, UserContext userContext) {
        log.info("[Domain] delete project, project Id={}", projectId);

        Optional.ofNullable(projectId).filter(x -> x > 0)
                .orElseThrow(() -> new IllegalArgumentException("project ID is required or invalid"));

        CustomPageConfigModel configModel = customPageConfigRepository.getById(projectId);
        if (configModel == null) {
            log.error("[Domain] Project config not found, project Id={}", projectId);
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.customPageProjectConfigNotFound);
        }
        CustomPageBuildModel buildModel = customPageBuildRepository.getByProjectId(projectId);

        // 校验空间权限
        spacePermissionService.checkSpaceUserPermission(configModel.getSpaceId());

        // 清除缓存
        customPageProxyPathService.removeConfigCache(projectId);

        // 删除保活缓存
        keepAliveService.removeKeepAliveCache(projectId);

        // 删除config
        customPageConfigRepository.deleteById(projectId, userContext);
        // 删除build
        customPageBuildRepository.deleteByProjectId(projectId, userContext);

        log.info("[Domain] delete projectsucceeded, project Id={}", projectId);
        Map<String, Object> map = new HashMap<>();
        map.put("config", configModel);
        map.put("build", buildModel);
        return ReqResult.success(map);
    }

    /**
     * 导入项目配置文件
     * 如果上传的压缩包中包含 cpage_config.json 文件，则解析并应用配置
     */
    public void importProjectConfig(CustomPageConfigModel model, UserContext userContext) {
        Long projectId = model.getId();
        log.info("[upload-project] project Id={},startimportconfig file", projectId);

        String proxyPath = "/page/static/" + projectId;
        // 获取项目文件内容
        ReqResult<Map<String, Object>> contentResult = this.queryProjectContent(projectId, "cpage_config", proxyPath);
        if (!contentResult.isSuccess()) {
            log.warn("[upload-project] project Id={},getprojectfilecontentfailed", projectId);
            return;
        }

        Map<String, Object> data = contentResult.getData();
        Object filesObj = data.get("files");
        if (filesObj == null) {
            log.info("[upload-project] project Id={},projectfile list is empty", projectId);
            return;
        }

        // 查找 cpage_config.json 文件
        if (!(filesObj instanceof List)) {
            log.warn("[upload-project] project Id={},files Obj is not a List type", projectId);
            return;
        }

        // 将 List 中的 LinkedHashMap 元素转换为 PageFileInfo 对象
        List<PageFileInfo> files = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Object> rawFiles = (List<Object>) filesObj;
        for (Object rawFile : rawFiles) {
            PageFileInfo fileInfo = JSON.parseObject(JSON.toJSONString(rawFile), PageFileInfo.class);
            files.add(fileInfo);
        }

        PageFileInfo configFile = null;
        for (PageFileInfo file : files) {
            if ("cpage_config.json".equals(file.getName())) {
                configFile = file;
                break;
            }
        }

        if (configFile == null) {
            log.info("[upload-project] project Id={},cpage_config.json file not found", projectId);
            return;
        }
        log.info("[upload-project] project Id={},found cpage_config.json file,startparseconfig", projectId);

        // 解析配置文件
        try {
            ProjectConfigExportDto configDto = JSON.parseObject(configFile.getContents(),
                    ProjectConfigExportDto.class);
            if (configDto == null) {
                log.warn("[upload-project] project Id={}, failed to parse config file", projectId);
                return;
            }
            log.info("[upload-project] project Id={},config file parsed,config Dto={}", projectId, configDto);

            // 应用配置
            applyProjectConfig(model, configDto, userContext);

            log.info("[upload-project] project Id={},config fileimportsucceeded", projectId);
        } catch (Exception e) {
            log.error("[upload-project] project Id={}, failed to parse config file", projectId, e);
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.customPageConfigFileParseFailed,
                    e.getMessage() != null ? e.getMessage() : "");
        }
    }

    /**
     * 应用项目配置
     */
    private void applyProjectConfig(CustomPageConfigModel model, ProjectConfigExportDto configDto,
                                    UserContext userContext) {
        Long projectId = model.getId();
        log.info("[upload-project] project Id={},startapplyconfig", projectId);

        CustomPageConfigModel updateModel = new CustomPageConfigModel();
        updateModel.setId(projectId);

        // 更新icon
        if (configDto.getIcon() != null && model.getIcon() == null) {
            updateModel.setIcon(configDto.getIcon());
        }

        // 更新 coverImg
        if (configDto.getCoverImg() != null && model.getCoverImg() == null) {
            updateModel.setCoverImg(configDto.getCoverImg());
        }

        // 更新 coverImgSourceType
        if (configDto.getCoverImgSourceType() != null && model.getCoverImgSourceType() == null) {
            updateModel.setCoverImgSourceType(configDto.getCoverImgSourceType());
        }

        // 更新 needLogin
        if (configDto.getNeedLogin() != null) {
            updateModel.setNeedLogin(
                    configDto.getNeedLogin() ? YesOrNoEnum.Y.getKey() : YesOrNoEnum.N.getKey());
        }

        // 更新 proxyConfigs
        if (configDto.getProxyConfigs() != null && !configDto.getProxyConfigs().isEmpty()) {
            updateModel.setProxyConfigs(configDto.getProxyConfigs());
        }

        // 更新 pageArgConfigs
        if (configDto.getPageArgConfigs() != null && !configDto.getPageArgConfigs().isEmpty()) {
            updateModel.setPageArgConfigs(configDto.getPageArgConfigs());
        }

        // 创建插件和工作流，准备数据源数据
        List<DataSourceDto> dataSources = new ArrayList<>();

        // 创建插件
        if (configDto.getDataSourcePlugins() != null && !configDto.getDataSourcePlugins().isEmpty()) {
            log.info("[upload-project] project Id={},startcreateplugin,count={}", projectId,
                    configDto.getDataSourcePlugins().size());
            for (Map<String, Object> pluginMap : configDto.getDataSourcePlugins()) {
                try {
                    String pluginName = (String) pluginMap.get("name");
                    String pluginIcon = (String) pluginMap.get("icon");
                    String pluginKey = (String) pluginMap.get("key");
                    String pluginData = (String) pluginMap.get("data");

                    PluginEnableOrUpdateDto pluginDto = new PluginEnableOrUpdateDto();
                    pluginDto.setUserId(userContext.getUserId());
                    pluginDto.setSpaceId(model.getSpaceId());
                    pluginDto.setName(pluginName);
                    pluginDto.setIcon(pluginIcon);
                    pluginDto.setConfig(pluginData);
                    pluginDto.setParamJson(pluginMap.get("paramJson") != null
                            ? pluginMap.get("paramJson").toString()
                            : "{}");

                    com.xspaceagi.agent.core.sdk.dto.ReqResult<Long> pluginResult = agentRpcService
                            .pluginEnableOrUpdate(pluginDto);
                    if (!pluginResult.isSuccess()) {
                        log.error("[upload-project] project Id={},createpluginfailed,message={}",
                                projectId,
                                pluginResult.getMessage());
                        // throw new BizException("创建插件失败: " + pluginResult.getMessage());
                        continue;
                    } else {
                        log.info("[upload-project] project Id={},createpluginsucceeded,plugin Id={}",
                                projectId, pluginResult.getData());
                    }
                    Long pluginId = pluginResult.getData();
                    if (pluginId != null) {
                        DataSourceDto dataSource = DataSourceDto.builder()
                                .type("plugin")
                                .id(pluginId)
                                .name(pluginName)
                                .icon(pluginIcon)
                                .key(pluginKey)
                                .build();
                        dataSources.add(dataSource);
                    }
                } catch (Exception e) {
                    log.error("[upload-project] project Id={},createpluginfailed", projectId, e);
                }
            }
        }

        // 创建工作流
        if (configDto.getDataSourceWorkflows() != null && !configDto.getDataSourceWorkflows().isEmpty()) {
            log.info("[upload-project] project Id={},startcreateworkflow,count={}", projectId,
                    configDto.getDataSourceWorkflows().size());
            for (Map<String, Object> workflowMap : configDto.getDataSourceWorkflows()) {
                try {
                    String workflowName = (String) workflowMap.get("name");
                    String workflowIcon = (String) workflowMap.get("icon");
                    String workflowKey = (String) workflowMap.get("key");
                    String workflowData = (String) workflowMap.get("data");

                    TemplateEnableOrUpdateDto templateDto = new TemplateEnableOrUpdateDto();
                    templateDto.setUserId(userContext.getUserId());
                    templateDto.setTargetType(TargetTypeEnum.Workflow);
                    templateDto.setName(workflowName);
                    templateDto.setIcon(workflowIcon);
                    templateDto.setConfig(workflowData);
                    templateDto.setSpaceId(model.getSpaceId());

                    com.xspaceagi.agent.core.sdk.dto.ReqResult<Long> templateResult = agentRpcService
                            .templateEnableOrUpdate(templateDto);
                    if (!templateResult.isSuccess()) {
                        log.error("[upload-project] project Id={}, failed to create workflow", projectId);
                        // throw new BizException("创建工作流失败: " + templateResult.getMessage());
                        continue;
                    } else {
                        log.info("[upload-project] project Id={},createworkflowsucceeded,workflow Id={}",
                                projectId, templateResult.getData());
                    }
                    Long workflowId = templateResult.getData();
                    if (workflowId != null) {
                        DataSourceDto dataSource = DataSourceDto.builder()
                                .type("workflow")
                                .id(workflowId)
                                .name(workflowName)
                                .icon(workflowIcon)
                                .key(workflowKey)
                                .build();
                        dataSources.add(dataSource);
                    }
                } catch (Exception e) {
                    log.error("[upload-project] project Id={}, failed to create workflow", projectId, e);
                }
            }
        }

        if (!dataSources.isEmpty()) {
            updateModel.setDataSources(dataSources);
            log.info("[upload-project] project Id={},binddata source,data Sources={}", projectId, dataSources);
        }

        // 统一更新项目配置
        if (updateModel.getNeedLogin() != null
                || updateModel.getProxyConfigs() != null
                || updateModel.getPageArgConfigs() != null
                || updateModel.getDataSources() != null) {
            updateModel.setModifiedId(userContext.getUserId());
            updateModel.setModifiedName(userContext.getUserName());

            customPageConfigRepository.updateById(updateModel, userContext);

            log.info("[upload-project] project Id={},update project configsucceeded", projectId);
        }
    }

    @Override
    public List<CustomPageConfigModel> listByDevAgentIds(List<Long> devAgentIds) {
        return customPageConfigRepository.listByDevAgentIds(devAgentIds);
    }

    @Override
    public Long countTotalPages() {
        return customPageConfigRepository.countTotalPages();
    }
}