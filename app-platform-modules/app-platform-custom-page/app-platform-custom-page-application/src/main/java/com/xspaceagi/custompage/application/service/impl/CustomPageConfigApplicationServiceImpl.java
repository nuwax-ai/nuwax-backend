package com.xspaceagi.custompage.application.service.impl;

import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.xspaceagi.agent.core.adapter.application.PluginApplicationService;
import com.xspaceagi.agent.core.adapter.application.PublishApplicationService;
import com.xspaceagi.agent.core.adapter.application.WorkflowApplicationService;
import com.xspaceagi.agent.core.adapter.dto.PublishedDto;
import com.xspaceagi.agent.core.adapter.dto.PublishedPermissionDto;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.PluginDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowConfigDto;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.sdk.IAgentRpcService;
import com.xspaceagi.agent.core.sdk.dto.*;
import com.xspaceagi.agent.core.sdk.enums.TargetTypeEnum;
import com.xspaceagi.custompage.application.service.ICustomPageConfigApplicationService;
import com.xspaceagi.custompage.domain.model.CustomPageBuildModel;
import com.xspaceagi.custompage.domain.model.CustomPageConfigModel;
import com.xspaceagi.custompage.domain.service.ICustomPageBuildDomainService;
import com.xspaceagi.custompage.domain.service.ICustomPageConfigDomainService;
import com.xspaceagi.custompage.sdk.dto.DataSourceDto;
import com.xspaceagi.custompage.sdk.dto.ExportTypeEnum;
import com.xspaceagi.custompage.sdk.dto.PageArgConfig;
import com.xspaceagi.custompage.sdk.dto.ProxyConfig;
import com.xspaceagi.sandbox.sdk.server.ISandboxConfigRpcService;
import com.xspaceagi.sandbox.sdk.service.dto.SandboxConfigRpcDto;
import com.xspaceagi.system.sdk.permission.IUserDataPermissionRpcService;
import com.xspaceagi.system.sdk.service.dto.UserDataPermissionDto;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 自定义页面配置应用服务实现
 */
@Slf4j
@Service
public class CustomPageConfigApplicationServiceImpl implements ICustomPageConfigApplicationService {

    @Resource
    private ICustomPageConfigDomainService customPageConfigDomainService;
    @Resource
    private ICustomPageBuildDomainService customPageBuildDomainService;
    @Resource
    private PluginApplicationService pluginApplicationService;
    @Resource
    private WorkflowApplicationService workflowApplicationService;
    @Resource
    private IAgentRpcService agentRpcService;
    @Resource
    private PublishApplicationService publishApplicationService;
    @Resource
    private IUserDataPermissionRpcService userDataPermissionRpcService;
    @Resource
    private ISandboxConfigRpcService sandboxConfigRpcService;

    // 需要事务
    @Transactional(rollbackFor = Exception.class)
    @Override
    public ReqResult<CustomPageConfigModel> create(CustomPageConfigModel model, UserContext userContext)
            throws JsonProcessingException {
        log.info("[create] createproject,name={}", model.getName());

        Optional.ofNullable(model.getName()).filter(StringUtils::isNotBlank)
                .orElseThrow(() -> new IllegalArgumentException("projectName is required"));
        if (model.getSpaceId() == null) {
            throw new IllegalArgumentException("spaceId is required");
        }

        // 校验用户网页应用数量是否超限
        UserDataPermissionDto dataPermission = userDataPermissionRpcService.getUserDataPermission(userContext.getUserId());
        if (dataPermission != null) {
            Integer maxPageAppCount = dataPermission.getMaxPageAppCount();
            if (maxPageAppCount != null && maxPageAppCount != -1) {
                CustomPageConfigModel queryModel = new CustomPageConfigModel();
                queryModel.setCreatorId(userContext.getUserId());
                List<CustomPageConfigModel> existingPages = customPageConfigDomainService.list(queryModel);
                int currentCount = existingPages == null ? 0 : existingPages.size();
                if (currentCount >= maxPageAppCount) {
                    log.warn("[create] create project failed, user page countreached limit, user Id={}, current Count={}, max page app count={}", userContext.getUserId(), currentCount, maxPageAppCount);
                    throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.customPageWebAppCountExceeded, maxPageAppCount);
                }
            }
        }

        // 创建config表
        ReqResult<CustomPageConfigModel> configResult = customPageConfigDomainService.create(model,
                userContext);

        if (!configResult.isSuccess()) {
            log.error("[create] create project failed(configtable), name={}, base Path={}, error={}",
                    model.getName(), model.getBasePath(), configResult.getMessage());
            throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.customPageCreateConfigFailed,
                    configResult.getMessage() != null ? configResult.getMessage() : "");
        }
        CustomPageConfigModel configModel = configResult.getData();
        Long projectId = configModel.getId();

        Long sandboxId = null;
        try {
            SandboxConfigRpcDto sandboxConfig = sandboxConfigRpcService.selectAppDevelopmentSandbox(
                    userContext.getTenantId(),
                    userContext.getUserId(),
                    configModel.getSpaceId(),
                    projectId,
                    null);
            sandboxId = sandboxConfig != null ? sandboxConfig.getId() : null;
            if (sandboxId == null) {
                log.info("[create] project Id={},bind sandbox failed,message=No available sandbox", projectId);
            }
        } catch (Exception e) {
            log.info("[create] project Id={},bind sandbox failed,message={}", projectId, e.getMessage());
        }

        // 创建build表
        ReqResult<CustomPageBuildModel> buildResult = customPageBuildDomainService.createProject(projectId,
                model.getSpaceId(),
                userContext);

        if (!buildResult.isSuccess()) {
            log.error("[create] create project failed(buildtable), name={}, base Path={}, error={}",
                    model.getName(), model.getBasePath(), buildResult.getMessage());
            throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.customPageCreateBuildFailed,
                    buildResult.getMessage() != null ? buildResult.getMessage() : "");
        }

        // 创建智能体
        PageAppAgentCreateDto agentDto = new PageAppAgentCreateDto();
        agentDto.setCreatorId(userContext.getUserId());
        agentDto.setSpaceId(model.getSpaceId());
        agentDto.setName(model.getName());
        agentDto.setDescription(model.getDescription());
        agentDto.setIcon(model.getIcon());
        agentDto.setProjectId(projectId);
        com.xspaceagi.agent.core.sdk.dto.ReqResult<Long> agentResult = agentRpcService
                .createPageAppAgent(agentDto);

        if (!agentResult.isSuccess()) {
            log.error("[create] createagentfailed, name={}, error={}", model.getName(), agentResult.getMessage());
            throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.customPageCreateAgentFailed,
                    agentResult.getMessage() != null ? agentResult.getMessage() : "");
        }

        // 绑定智能体到项目
        CustomPageConfigModel bindModel = new CustomPageConfigModel();
        bindModel.setId(projectId);
        bindModel.setDevAgentId(agentResult.getData());
        bindModel.setSandboxId(sandboxId);
        ReqResult<CustomPageConfigModel> result = customPageConfigDomainService.update(
                bindModel,
                userContext);
        if (!result.isSuccess()) {
            log.error("[create] project Id={},bind agent to project failed,message={}", projectId,
                    result.getMessage());
            throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.customPageBindAgentFailed,
                    result.getMessage() != null ? result.getMessage() : "");
        }

        log.info("[create] createprojectsucceeded, project Id={}, name={}", projectId, model.getName());
        configResult.getData().setDevAgentId(agentResult.getData());
        return ReqResult.success(configResult.getData());
    }

    // 不需要事务
    @Override
    public ReqResult<Map<String, Object>> uploadProject(CustomPageConfigModel model, MultipartFile file,
                                                        boolean isInitProject,
                                                        UserContext userContext) throws Exception {
        log.info("[upload-project] project Id={}startupload", model.getId());
        if (file == null || file.isEmpty()) {
            return ReqResult.error("0001", "File is required");
        }
        Long projectId = model.getId();

        // 传输压缩包
        ReqResult<Map<String, Object>> result = customPageBuildDomainService.uploadProject(
                projectId,
                file,
                isInitProject,
                userContext);

        if (!result.isSuccess()) {
            log.info("[upload-project] uploadfailed, project Id={}, code={}, message={}",
                    projectId,
                    result.getCode(), result.getMessage());
            return ReqResult.error(result.getCode(), result.getMessage());
        }
        log.info("[upload-project] uploadsucceeded, project Id={}, result={}", projectId, result);

        // 如果是创建新项目，尝试导入配置文件
        if (isInitProject) {
            try {
                customPageConfigDomainService.importProjectConfig(model, userContext);
            } catch (Exception e) {
                log.error("[upload-project] project Id={},config fileimportfailed", projectId, e);
                // 配置文件导入失败不影响整体上传流程
            }
        }

        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReqResult<CustomPageConfigModel> createReverseProxyProject(CustomPageConfigModel model,
                                                                      UserContext userContext) {
        log.info("[create Proxy Project] create reverse proxy project, name={}", model.getName());

        Optional.ofNullable(model.getName()).filter(StringUtils::isNotBlank)
                .orElseThrow(() -> new IllegalArgumentException("projectName is required"));
        if (model.getSpaceId() == null) {
            throw new IllegalArgumentException("spaceId is required");
        }

        ReqResult<CustomPageConfigModel> configResult = customPageConfigDomainService.create(model, userContext);

        if (!configResult.isSuccess()) {
            log.error("[create Proxy Project] create reverse proxy projectfailed, name={}, message={}",
                    model.getName(), configResult.getMessage());
            throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.customPageCreateReverseProxyFailed,
                    configResult.getMessage() != null ? configResult.getMessage() : "");
        }

        log.info("[create Proxy Project] create reverse proxy projectsucceeded, project Id={}, name={}",
                configResult.getData().getId(), model.getName());
        return ReqResult.success(configResult.getData());
    }

    @Override
    public ReqResult<Map<String, Object>> queryProjectContent(Long projectId, String proxyPath) {
        log.info("[query Project Content] project Id={},query project file content", projectId);
        Optional.ofNullable(projectId).filter(x -> x > 0)
                .orElseThrow(() -> new IllegalArgumentException("projectId is required or invalid"));

        ReqResult<Map<String, Object>> result = customPageConfigDomainService.queryProjectContent(projectId,
                null, proxyPath);
        if (!result.isSuccess()) {
            log.error("[query Project Content] project Id={},query project file contentfailed,message={}", projectId,
                    result.getMessage());
            return ReqResult.error(result.getCode(), result.getMessage());
        }

        log.info("[query Project Content] project Id={},query project file contentsucceeded", projectId);
        return result;
    }

    @Override
    public ReqResult<Map<String, Object>> queryProjectContentByVersion(Long projectId, Integer codeVersion, String proxyPath) {
        log.info("[query Project Content By Version] project Id={},code Version={},query project historical version file content", projectId,
                codeVersion);

        ReqResult<Map<String, Object>> result = customPageConfigDomainService
                .queryProjectContentByVersion(projectId, codeVersion, proxyPath);
        if (!result.isSuccess()) {
            log.error("[query Project Content By Version] project Id={},code Version={},query project historical version file contentfailed,message={}",
                    projectId,
                    codeVersion, result.getMessage());
            return ReqResult.error(result.getCode(), result.getMessage());
        }

        log.info("[query Project Content By Version] project Id={},code Version={},query project historical version file contentsucceeded", projectId,
                codeVersion);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReqResult<List<ProxyConfig>> addProxy(Long projectId, ProxyConfig proxyConfig,
                                                 UserContext userContext) {
        log.info("[add Proxy] project Id={},env={},path={},add reverse proxy config", projectId, proxyConfig.getEnv(),
                proxyConfig.getPath());

        Optional.ofNullable(projectId).filter(x -> x > 0)
                .orElseThrow(() -> new IllegalArgumentException("projectId is required or invalid"));
        Optional.ofNullable(proxyConfig.getEnv())
                .orElseThrow(() -> new IllegalArgumentException("environment is required"));
        Optional.ofNullable(proxyConfig.getPath()).filter(StringUtils::isNotBlank)
                .orElseThrow(() -> new IllegalArgumentException("path is required"));
        Optional.ofNullable(proxyConfig.getBackends()).filter(list -> !list.isEmpty())
                .orElseThrow(() -> new IllegalArgumentException("backend address list cannot be empty"));

        ReqResult<List<ProxyConfig>> result = customPageConfigDomainService.addProxy(projectId,
                proxyConfig, userContext);

        if (!result.isSuccess()) {
            log.error("[add Proxy] project Id={},env={},path={},add reverse proxy configfailed,message={}",
                    projectId, proxyConfig.getEnv(), proxyConfig.getPath(), result.getMessage());
            return result;
        }

        log.info("[add Proxy] project Id={},env={},path={},add reverse proxy configsucceeded",
                projectId, proxyConfig.getEnv(), proxyConfig.getPath());
        return ReqResult.success(result.getData());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReqResult<Void> editProxyConfig(Long projectId, ProxyConfig proxyConfig, UserContext userContext) {
        log.info("[edit Proxy Config] project Id={},env={},path={},editreverse proxy config", projectId, proxyConfig.getEnv(),
                proxyConfig.getPath());

        Optional.ofNullable(projectId).filter(x -> x > 0)
                .orElseThrow(() -> new IllegalArgumentException("projectId is required or invalid"));
        Optional.ofNullable(proxyConfig.getEnv())
                .orElseThrow(() -> new IllegalArgumentException("environment is required"));
        Optional.ofNullable(proxyConfig.getPath()).filter(StringUtils::isNotBlank)
                .orElseThrow(() -> new IllegalArgumentException("path is required"));
        Optional.ofNullable(proxyConfig.getBackends()).filter(list -> !list.isEmpty())
                .orElseThrow(() -> new IllegalArgumentException("backend address list cannot be empty"));

        ReqResult<Void> result = customPageConfigDomainService.editProxy(projectId, proxyConfig,
                userContext);

        if (!result.isSuccess()) {
            log.error("[edit Proxy Config] project Id={},env={},path={},editreverse proxy configfailed,message={}",
                    projectId, proxyConfig.getEnv(), proxyConfig.getPath(), result.getMessage());
            return result;
        }

        log.info("[edit Proxy Config] project Id={},env={},path={},editreverse proxy configsucceeded",
                projectId, proxyConfig.getEnv(), proxyConfig.getPath());
        return ReqResult.success(null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReqResult<Void> deleteProxy(Long projectId, String env, String path, UserContext userContext) {
        log.info("[delete Proxy] project Id={},env={},path={},delete reverse proxy config", projectId, env, path);

        Optional.ofNullable(projectId).filter(x -> x > 0)
                .orElseThrow(() -> new IllegalArgumentException("projectId is required or invalid"));
        Optional.ofNullable(env).filter(StringUtils::isNotBlank)
                .orElseThrow(() -> new IllegalArgumentException("environment is required"));
        Optional.ofNullable(path).filter(StringUtils::isNotBlank)
                .orElseThrow(() -> new IllegalArgumentException("path is required"));

        ReqResult<Void> result = customPageConfigDomainService.deleteProxy(projectId, env, path, userContext);

        if (!result.isSuccess()) {
            log.error("[delete Proxy] project Id={},env={},path={},delete reverse proxy configfailed,message={}",
                    projectId, env, path, result.getMessage());
            throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.customPageDeleteReverseProxyFailed,
                    result.getMessage() != null ? result.getMessage() : "");
        }

        log.info("[delete Proxy] project Id={},env={},path={},delete reverse proxy configsucceeded",
                projectId, env, path);
        return ReqResult.success(null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReqResult<Void> savePathArgs(Long projectId, PageArgConfig pageArgConfig, UserContext userContext) {
        log.info("[save Path Args] project Id={},page Uri={},configure page args", projectId, pageArgConfig.getPageUri());

        Optional.ofNullable(projectId).filter(x -> x > 0)
                .orElseThrow(() -> new IllegalArgumentException("projectId is required or invalid"));
        Optional.ofNullable(pageArgConfig.getPageUri()).filter(StringUtils::isNotBlank)
                .orElseThrow(() -> new IllegalArgumentException("page path cannot be empty"));

        ReqResult<Void> result = customPageConfigDomainService.savePathArgs(projectId, pageArgConfig,
                userContext);

        if (!result.isSuccess()) {
            log.error("[save Path Args] project Id={},page Uri={},configure page argsfailed,message={}",
                    projectId, pageArgConfig.getPageUri(), result.getMessage());
            throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.customPageConfigPageParamsFailed,
                    result.getMessage() != null ? result.getMessage() : "");
        }

        log.info("[save Path Args] project Id={},page Uri={},configure page argssucceeded",
                projectId, pageArgConfig.getPageUri());
        return ReqResult.success(null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReqResult<Void> addPath(Long projectId, PageArgConfig pageArgConfig, UserContext userContext) {
        log.info("[add Path] project Id={},page Uri={},add path config", projectId, pageArgConfig.getPageUri());

        Optional.ofNullable(projectId).filter(x -> x > 0)
                .orElseThrow(() -> new IllegalArgumentException("projectId is required or invalid"));
        Optional.ofNullable(pageArgConfig.getPageUri()).filter(StringUtils::isNotBlank)
                .orElseThrow(() -> new IllegalArgumentException("page path cannot be empty"));

        ReqResult<Void> result = customPageConfigDomainService.addPath(projectId, pageArgConfig,
                userContext);

        if (!result.isSuccess()) {
            log.error("[add Path] project Id={},page Uri={},add path configfailed,message={}",
                    projectId, pageArgConfig.getPageUri(), result.getMessage());
            throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.customPageAddPathConfigFailed,
                    result.getMessage() != null ? result.getMessage() : "");
        }

        log.info("[add Path] project Id={},page Uri={},add path configsucceeded",
                projectId, pageArgConfig.getPageUri());
        return ReqResult.success(null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReqResult<Void> editPath(Long projectId, PageArgConfig pageArgConfig, UserContext userContext) {
        log.info("[edit Path] project Id={},page Uri={},editpath config", projectId, pageArgConfig.getPageUri());

        Optional.ofNullable(projectId).filter(x -> x > 0)
                .orElseThrow(() -> new IllegalArgumentException("projectId is required or invalid"));
        Optional.ofNullable(pageArgConfig.getPageUri()).filter(StringUtils::isNotBlank)
                .orElseThrow(() -> new IllegalArgumentException("page path cannot be empty"));

        ReqResult<Void> result = customPageConfigDomainService.editPath(projectId, pageArgConfig,
                userContext);

        if (!result.isSuccess()) {
            log.error("[edit Path] project Id={},page Uri={},editpath configfailed,message={}",
                    projectId, pageArgConfig.getPageUri(), result.getMessage());
            throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.customPageEditPathConfigFailed,
                    result.getMessage() != null ? result.getMessage() : "");
        }

        log.info("[edit Path] project Id={},page Uri={},editpath configsucceeded",
                projectId, pageArgConfig.getPageUri());
        return ReqResult.success(null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReqResult<Void> deletePath(Long projectId, String pageUri, UserContext userContext) {
        log.info("[delete Path] project Id={},page Uri={},delete path config", projectId, pageUri);

        Optional.ofNullable(projectId).filter(x -> x > 0)
                .orElseThrow(() -> new IllegalArgumentException("projectId is required or invalid"));
        Optional.ofNullable(pageUri).filter(StringUtils::isNotBlank)
                .orElseThrow(() -> new IllegalArgumentException("page path cannot be empty"));

        ReqResult<Void> result = customPageConfigDomainService.deletePath(projectId, pageUri,
                userContext);

        if (!result.isSuccess()) {
            log.error("[delete Path] project Id={},page Uri={},delete path configfailed,message={}",
                    projectId, pageUri, result.getMessage());
            throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.customPageDeletePathConfigFailed,
                    result.getMessage() != null ? result.getMessage() : "");
        }

        log.info("[delete Path] project Id={},page Uri={},delete path configsucceeded",
                projectId, pageUri);
        return ReqResult.success(null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReqResult<Void> batchConfigProxy(Long projectId, List<ProxyConfig> proxyConfigs,
                                            UserContext userContext) {
        log.info("[batch Config Proxy] project Id={},config Count={},batch configure reverse proxy", projectId,
                proxyConfigs != null ? proxyConfigs.size() : 0);

        Optional.ofNullable(projectId).filter(x -> x > 0)
                .orElseThrow(() -> new IllegalArgumentException("projectId is required or invalid"));
        Optional.ofNullable(proxyConfigs)
                .orElseThrow(() -> new IllegalArgumentException("reverse proxy configuration list cannot be empty"));

        ReqResult<Void> result = customPageConfigDomainService.batchConfigProxy(projectId, proxyConfigs,
                userContext);

        if (!result.isSuccess()) {
            log.error("[batch Config Proxy] project Id={},config Count={},batch configure reverse proxyfailed,message={}", projectId,
                    proxyConfigs != null ? proxyConfigs.size() : 0, result.getMessage());
            throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.customPageBatchReverseProxyFailed,
                    result.getMessage() != null ? result.getMessage() : "");
        }

        log.info("[batch Config Proxy] project Id={},config Count={},batch configure reverse proxysucceeded",
                projectId, proxyConfigs != null ? proxyConfigs.size() : 0);
        return ReqResult.success(null);
    }

    public ReqResult<InputStream> exportProjectPublished(Long projectId, UserContext userContext) {
        return customPageConfigDomainService.exportProject(projectId, ExportTypeEnum.PUBLISHED, userContext);
    }

    public ReqResult<InputStream> exportProjectLatest(Long projectId, UserContext userContext) {
        return customPageConfigDomainService.exportProject(projectId, ExportTypeEnum.LATEST, userContext);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReqResult<Void> bindDataSource(Long projectId, String type, Long dataSourceId,
                                          UserContext userContext) {
        log.info("[bind Data Source] project Id={},type={},data Source Id={},binddata source", projectId, type,
                dataSourceId);

        Optional.ofNullable(projectId).filter(x -> x > 0)
                .orElseThrow(() -> new IllegalArgumentException("projectId is required or invalid"));
        Optional.ofNullable(type).filter(StringUtils::isNotBlank)
                .orElseThrow(() -> new IllegalArgumentException("data source type is required"));
        Optional.ofNullable(dataSourceId).filter(x -> x > 0)
                .orElseThrow(() -> new IllegalArgumentException("data source ID is required or invalid"));

        String dataSourceName = null;
        String dataSourceIcon = null;
        if ("plugin".equalsIgnoreCase(type)) {
            PluginDto pluginDto = pluginApplicationService.queryPublishedPluginConfig(dataSourceId, null);
            if (pluginDto == null) {
                log.error("[bind Data Source] project Id={},type={},data Source Id={},pluginnot foundor not published", projectId, type, dataSourceId);
                return ReqResult.error("0001", "Plugin does not exist or is not published");
            }

            dataSourceName = pluginDto.getName();
            dataSourceIcon = pluginDto.getIcon();
            log.info("[bind Data Source] project Id={},type={},data Source Id={},getplugin succeeded", projectId, type, dataSourceId);

        } else if ("workflow".equalsIgnoreCase(type)) {
            WorkflowConfigDto workflowConfigDto = workflowApplicationService
                    .queryPublishedWorkflowConfig(dataSourceId, null);
            if (workflowConfigDto == null) {
                log.error("[bind Data Source] project Id={},type={},data Source Id={},workflownot foundor not published", projectId, type, dataSourceId);
                return ReqResult.error("0003", "Workflow does not exist or is not published");
            }

            dataSourceName = workflowConfigDto.getName();
            dataSourceIcon = workflowConfigDto.getIcon();
            log.info("[bind Data Source] project Id={},type={},data Source Id={},getworkflow succeeded", projectId, type, dataSourceId);

        } else {
            log.error("[bind Data Source] project Id={},type={},data Source Id={},unsupported data source type, type={}", projectId, type, dataSourceId, type);
            return ReqResult.error("0004", "Unsupported data source type: " + type);
        }

        DataSourceDto dataSource = DataSourceDto.builder()
                .type(type.toLowerCase())
                .id(dataSourceId)
                .key(System.currentTimeMillis() / 1000 + dataSourceId.toString())// 在页面范围内生一个不重复的key
                .name(dataSourceName)
                .icon(dataSourceIcon)
                .build();

        ReqResult<Void> result = customPageConfigDomainService.bindDataSource(projectId, dataSource, userContext);

        if (!result.isSuccess()) {
            log.error("[bind Data Source] savedata sourcefailed, project Id={}, type={}, data Source Id={}, error={}",
                    projectId, type, dataSourceId, result.getMessage());
            throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.customPageSaveDataSourceFailed,
                    result.getMessage() != null ? result.getMessage() : "");
        }

        log.info("[bind Data Source] savedata sourcesucceeded, project Id={}, type={}, data Source Id={}", projectId, type, dataSourceId);
        return ReqResult.success(null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReqResult<Void> unbindDataSource(Long projectId, String type, Long dataSourceId,
                                            UserContext userContext) {
        log.info("[unbind Data Source] project Id={},type={},data Source Id={},unbinddata source", projectId, type,
                dataSourceId);

        Optional.ofNullable(projectId).filter(x -> x > 0)
                .orElseThrow(() -> new IllegalArgumentException("projectId is required or invalid"));
        Optional.ofNullable(type).filter(StringUtils::isNotBlank)
                .orElseThrow(() -> new IllegalArgumentException("data source type is required"));
        Optional.ofNullable(dataSourceId).filter(x -> x > 0)
                .orElseThrow(() -> new IllegalArgumentException("data source ID is required or invalid"));

        DataSourceDto dataSource = DataSourceDto.builder()
                .type(type.toLowerCase())
                .id(dataSourceId)
                .build();

        ReqResult<Void> result = customPageConfigDomainService.unbindDataSource(projectId, dataSource, userContext);

        if (!result.isSuccess()) {
            log.error("[unbind Data Source] unbinddata sourcefailed, project Id={}, type={}, data Source Id={}, error={}", projectId, type, dataSourceId, result.getMessage());
            throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.customPageUnbindDataSourceFailed,
                    result.getMessage() != null ? result.getMessage() : "");
        }

        log.info("[unbind Data Source] unbinddata sourcesucceeded, project Id={}, type={}, data Source Id={}", projectId, type, dataSourceId);
        return ReqResult.success(null);
    }

    @Override
    public CustomPageConfigModel getByProjectId(Long projectId) {
        log.info("[get By Project Id] project Id={},queryproject", projectId);
        return customPageConfigDomainService.getById(projectId);
    }

    @Override
    public List<CustomPageConfigModel> listByIds(List<Long> ids) {
        return customPageConfigDomainService.listByIds(ids);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReqResult<CustomPageConfigModel> updateProject(CustomPageConfigModel model, UserContext userContext) {
        log.info("[update Project] project Id={},modify project", model.getId());
        try {
            Optional.ofNullable(model.getId()).filter(x -> x > 0)
                    .orElseThrow(() -> new IllegalArgumentException("project ID is required or invalid"));
            Optional.ofNullable(model.getName()).filter(StringUtils::isNotBlank)
                    .orElseThrow(() -> new IllegalArgumentException("project name is required"));

            // 更新config表
            ReqResult<CustomPageConfigModel> result = customPageConfigDomainService.update(model,
                    userContext);
            if (!result.isSuccess()) {
                log.error("[update Project] project Id={},modify projectfailed,message={}", model.getId(),
                        result.getMessage());
                throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.customPageUpdateProjectFailed,
                        result.getMessage() != null ? result.getMessage() : "");
            }
            // 更新智能体
            Long devAgentId = ((CustomPageConfigModel) result.getData()).getDevAgentId();
            PageAppAgentUpdateDto agentDto = new PageAppAgentUpdateDto();
            agentDto.setAgentId(devAgentId);
            agentDto.setName(model.getName());
            agentDto.setDescription(model.getDescription());
            agentDto.setIcon(model.getIcon());
            com.xspaceagi.agent.core.sdk.dto.ReqResult<Void> agentResult = agentRpcService
                    .updatePageAppAgent(agentDto);
            if (!agentResult.isSuccess()) {
                log.error("[update Project] project Id={},updateagentfailed,message={}", model.getId(),
                        agentResult.getMessage());
                throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.customPageUpdateAgentFailed,
                        agentResult.getMessage() != null ? agentResult.getMessage() : "");
            }

            log.info("[update Project] project Id={},modify projectsucceeded", model.getId());
            return ReqResult.success(result.getData());
        } catch (Exception e) {
            log.error("[update Project] project Id={},modify projectexception", model.getId(), e);
            throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.customPageUpdateProjectException,
                    e.getMessage() != null ? e.getMessage() : "");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReqResult<Map<String, Object>> deleteProject(Long projectId, UserContext userContext) {
        log.info("[delete Project] project Id={},delete project", projectId);
        try {
            Optional.ofNullable(projectId).filter(x -> x > 0)
                    .orElseThrow(() -> new IllegalArgumentException("project ID is required or invalid"));

            ReqResult<Map<String, Object>> result = customPageConfigDomainService.delete(projectId, userContext);
            if (!result.isSuccess()) {
                log.error("[delete Project] project Id={},delete projectfailed,message={}", projectId, result.getMessage());
                throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.customPageDeleteProjectFailed,
                        result.getMessage() != null ? result.getMessage() : "");
            }

            // 删除智能体
            try {
                Long devAgentId = ((CustomPageConfigModel) result.getData().get("config"))
                        .getDevAgentId();
                com.xspaceagi.agent.core.sdk.dto.ReqResult<Void> agentResult = agentRpcService
                        .deletePageAppAgent(devAgentId);
                if (!agentResult.isSuccess()) {
                    log.error("[delete Project] project Id={},deleteagentfailed,message={}", projectId,
                            agentResult.getMessage());
                    throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.customPageDeleteAgentFailed,
                            agentResult.getMessage() != null ? agentResult.getMessage() : "");
                }
            } catch (Exception e) {
                log.error("[delete Project] project Id={},deleteagentfailed", projectId, e);
                // 不抛异常,老数据没有智能体,删除会异常
            }

            log.info("[delete Project] project Id={},delete projectsucceeded", projectId);
            return result;
        } catch (Exception e) {
            log.error("[delete Project] project Id={},delete projectexception", projectId, e);
            throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.customPageDeleteProjectException,
                    e.getMessage() != null ? e.getMessage() : "");
        }
    }

    @Override
    @DSTransactional(rollbackFor = Exception.class)
    public List<DataSourceDto> copyProjectDataSources(CustomPageConfigModel sourceConfig, CustomPageConfigModel targetConfig, UserContext userContext) {
        List<DataSourceDto> sourceDataSources = sourceConfig.getDataSources();
        if (sourceDataSources == null || sourceDataSources.isEmpty()) {
            return null;
        }

        Long sourceSpaceId = sourceConfig.getSpaceId();
        Long targetProjectId = targetConfig.getId();
        Long targetSpaceId = targetConfig.getSpaceId();
        log.info("[copy Project] target Project Id={}, target Space Id={},startcopydata source", targetProjectId, targetSpaceId);

        //本空间复制项目
        //直接绑定数据源
        if (sourceSpaceId.equals(targetSpaceId)) {
            log.info("[copy Project] target Project Id={}, target Space Id={},same space,fully copy binding relationships", targetProjectId, targetSpaceId);
            CustomPageConfigModel updateConfig = new CustomPageConfigModel();
            updateConfig.setId(targetProjectId);
            updateConfig.setDataSources(sourceDataSources);
            customPageConfigDomainService.update(updateConfig, userContext);
            return null;
        }

        //跨空间复制项目

        //新增的数据源
        List<DataSourceDto> newCreateDataSources = new ArrayList<>();
        //目标绑定数据源
        List<DataSourceDto> targetDataSources = new ArrayList<>();

        for (DataSourceDto dataSource : sourceDataSources) {
            Published.TargetType dataSourceType = "plugin".equalsIgnoreCase(dataSource.getType()) ? Published.TargetType.Plugin : Published.TargetType.Workflow;

            //List<PublishedDto> publishedDtos = publishApplicationService.queryPublishedList(dataSourceType, List.of(dataSource.getId()));
            PublishedDto publishedDto = publishApplicationService.queryPublished(dataSourceType, dataSource.getId());

            if (publishedDto == null) {
                log.info("[copy Project] project Id={},data Source Id={},type={}, data sourcenot found,skip", targetProjectId, dataSource.getId(), dataSourceType);
                continue;
            }

            if (publishedDto.getScope() == Published.PublishScope.Global || publishedDto.getScope() == Published.PublishScope.Tenant) {
                //全局数据源，不需要复制，直接绑定
                log.info("[copy Project] project Id={},data Source Id={},type={}, global data source, bind directly", targetProjectId, dataSource.getId(), dataSourceType);
                targetDataSources.add(dataSource);
            } else if (publishedDto.getPublishedSpaceIds() != null && publishedDto.getPublishedSpaceIds().contains(targetSpaceId)) {
                //已经发布到了目标空间
                log.info("[copy Project] project Id={},data Source Id={},type={}, data sourcealready published in target space, bind directly", targetProjectId, dataSource.getId(), dataSourceType);
                targetDataSources.add(dataSource);
            } else {

                if (dataSourceType == Published.TargetType.Plugin) {// 插件
                    //判断是否允许复制
                    boolean allowCopy = false;
                    com.xspaceagi.agent.core.sdk.dto.ReqResult<PluginInfoDto> publishedPluginInfo = agentRpcService.getPublishedPluginInfo(dataSource.getId(), null);
                    if (publishedPluginInfo != null && publishedPluginInfo.isSuccess()) {
                        PluginInfoDto data = publishedPluginInfo.getData();
                        if (sourceConfig.getSpaceId().equals(data.getSpaceId())) {
                            //数据资源和源项目在同空间,复制走
                            allowCopy = true;
                        } else {
                            try {
                                PublishedPermissionDto permissionDto = publishApplicationService.hasPermission(dataSourceType, dataSource.getId());
                                allowCopy = permissionDto.isCopy();
                            } catch (Exception e) {
                                log.info("[copy Project] project Id={},data Source Id={},type={}, data source copy permission check failed, skip", targetProjectId, dataSource.getId(), dataSourceType, e);
                            }
                        }
                    }
                    if (!allowCopy) {
                        //不允许复制
                        log.info("[copy Project] project Id={},data Source Id={},type={}, no data source copy permission, skip", targetProjectId, dataSource.getId(), dataSourceType);
                        continue;
                    }

                    //开始复制
                    com.xspaceagi.agent.core.sdk.dto.ReqResult<String> pluginRes = agentRpcService.queryPluginConfig(dataSource.getId(), null);
                    if (pluginRes != null && pluginRes.isSuccess()) {
                        PluginEnableOrUpdateDto pluginDto = new PluginEnableOrUpdateDto();
                        pluginDto.setUserId(userContext.getUserId());
                        pluginDto.setSpaceId(targetSpaceId);
                        pluginDto.setName(publishedDto.getName());
                        pluginDto.setIcon(publishedDto.getIcon());
                        pluginDto.setConfig(pluginRes.getData());
                        pluginDto.setParamJson("{}");

                        com.xspaceagi.agent.core.sdk.dto.ReqResult<Long> enableResult = agentRpcService.pluginEnableOrUpdate(pluginDto);
                        if (!enableResult.isSuccess()) {
                            log.error("[copy Project] project Id={},data Source Id={},type={},copypluginfailed,message={}", targetProjectId, dataSource.getId(), dataSourceType, enableResult.getMessage());
                            throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.customPageCopyPluginFailed,
                                    enableResult.getMessage() != null ? enableResult.getMessage() : "");
                        } else {
                            log.info("[copy Project] project Id={},data Source Id={},type={},copypluginsucceeded", targetProjectId, dataSource.getId(), dataSourceType, enableResult.getData());
                        }
                        Long newPluginId = enableResult.getData();
                        DataSourceDto dataSourceDto = DataSourceDto.builder()
                                .id(newPluginId)
                                .type("plugin")
                                .key(dataSource.getKey())
                                .name(publishedDto.getName())
                                .icon(publishedDto.getIcon())
                                .build();
                        targetDataSources.add(dataSourceDto);
                        newCreateDataSources.add(dataSourceDto);
                    }
                } else {
                    // 工作流

                    //判断是否允许复制
                    boolean allowCopy = false;
                    com.xspaceagi.agent.core.sdk.dto.ReqResult<WorkflowInfoDto> publishedWorkflowInfo = agentRpcService.getPublishedWorkflowInfo(dataSource.getId(), null);
                    if (publishedWorkflowInfo != null && publishedWorkflowInfo.isSuccess()) {
                        WorkflowInfoDto data = publishedWorkflowInfo.getData();
                        if (sourceConfig.getSpaceId().equals(data.getSpaceId())) {
                            //数据资源和源项目在同空间,复制走
                            allowCopy = true;
                        } else {
                            try {
                                PublishedPermissionDto permissionDto = publishApplicationService.hasPermission(dataSourceType, dataSource.getId());
                                allowCopy = permissionDto.isCopy();
                            } catch (Exception e) {
                                log.info("[copy Project] project Id={},data Source Id={},type={}, data source copy permission check failed, skip", targetProjectId, dataSource.getId(), dataSourceType, e);
                            }
                        }
                    }
                    if (!allowCopy) {
                        //不允许复制
                        log.info("[copy Project] project Id={},data Source Id={},type={}, no data source copy permission, skip", targetProjectId, dataSource.getId(), dataSourceType);
                        continue;
                    }

                    //开始复制
                    com.xspaceagi.agent.core.sdk.dto.ReqResult<String> workflowRes = agentRpcService.queryTemplateConfig(TargetTypeEnum.Workflow, dataSource.getId());
                    if (workflowRes != null && workflowRes.isSuccess()) {
                        TemplateEnableOrUpdateDto templateDto = new TemplateEnableOrUpdateDto();
                        templateDto.setUserId(userContext.getUserId());
                        templateDto.setTargetType(TargetTypeEnum.Workflow);
                        templateDto.setName(publishedDto.getName());
                        templateDto.setIcon(publishedDto.getIcon());
                        templateDto.setConfig(workflowRes.getData());
                        templateDto.setSpaceId(targetSpaceId);

                        com.xspaceagi.agent.core.sdk.dto.ReqResult<Long> enableResult = agentRpcService.templateEnableOrUpdate(templateDto);
                        if (!enableResult.isSuccess()) {
                            log.error("[copy Project] project Id={},data Source Id={},type={},copyworkflowfailed,message={}", targetProjectId, dataSource.getId(), dataSourceType, enableResult.getMessage());
                            throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.customPageCopyWorkflowFailed,
                                    enableResult.getMessage() != null ? enableResult.getMessage() : "");
                        } else {
                            log.info("[copy Project] project Id={},data Source Id={},type={},copyworkflowsucceeded", targetProjectId, dataSource.getId(), dataSourceType);
                        }
                        Long newWorkflowId = enableResult.getData();
                        DataSourceDto dataSourceDto = DataSourceDto.builder()
                                .id(newWorkflowId)
                                .type("workflow")
                                .key(dataSource.getKey())
                                .name(publishedDto.getName())
                                .icon(publishedDto.getIcon())
                                .build();
                        targetDataSources.add(dataSourceDto);
                        newCreateDataSources.add(dataSourceDto);
                    }
                }
            }
        }
        if (targetDataSources.isEmpty()) {
            return newCreateDataSources;
        }

        log.info("[copy Project] target Project Id,target Space Id={},cross-spacecopy,binddata source,size={}", targetProjectId, targetSpaceId, targetDataSources.size());
        CustomPageConfigModel updateConfig = new CustomPageConfigModel();
        updateConfig.setId(targetProjectId);
        updateConfig.setDataSources(targetDataSources);
        customPageConfigDomainService.update(updateConfig, userContext);

        return newCreateDataSources;
    }

}
