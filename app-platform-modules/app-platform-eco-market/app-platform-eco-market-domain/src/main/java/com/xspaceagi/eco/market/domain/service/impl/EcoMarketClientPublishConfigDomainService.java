package com.xspaceagi.eco.market.domain.service.impl;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import com.xspaceagi.system.application.dto.UserDto;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xspaceagi.agent.core.adapter.application.AgentApplicationService;
import com.xspaceagi.agent.core.adapter.application.PublishApplicationService;
import com.xspaceagi.agent.core.adapter.dto.CategoryDto;
import com.xspaceagi.agent.core.adapter.dto.PublishApplyDto;
import com.xspaceagi.agent.core.adapter.dto.config.AgentConfigDto;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.sdk.dto.PluginEnableOrUpdateDto;
import com.xspaceagi.agent.core.sdk.dto.TemplateEnableOrUpdateDto;
import com.xspaceagi.agent.core.sdk.enums.TargetTypeEnum;
import com.xspaceagi.agent.core.spec.utils.UrlFile;
import com.xspaceagi.custompage.application.service.ICustomPageBuildApplicationService;
import com.xspaceagi.custompage.application.service.ICustomPageConfigApplicationService;
import com.xspaceagi.custompage.domain.model.CustomPageConfigModel;
import com.xspaceagi.custompage.domain.proxypath.ICustomPageProxyPathService;
import com.xspaceagi.custompage.sdk.dto.ProjectType;
import com.xspaceagi.custompage.sdk.dto.PublishTypeEnum;
import com.xspaceagi.eco.market.domain.adaptor.IEcoMarketAdaptor;
import com.xspaceagi.eco.market.domain.assembler.EcoMarketConfigTranslator;
import com.xspaceagi.eco.market.domain.client.EcoMarketServerApiService;
import com.xspaceagi.eco.market.domain.dto.req.UpdateAndEnableConfigReqDTO;
import com.xspaceagi.eco.market.domain.dto.resp.ServerConfigDetailRespDTO;
import com.xspaceagi.eco.market.domain.model.EcoMarketClientConfigModel;
import com.xspaceagi.eco.market.domain.model.EcoMarketClientPublishConfigModel;
import com.xspaceagi.eco.market.domain.model.valueobj.QueryEcoMarketVo;
import com.xspaceagi.eco.market.domain.repository.IEcoMarketClientPublishConfigRepository;
import com.xspaceagi.eco.market.domain.service.IEcoMarketClientConfigDomainService;
import com.xspaceagi.eco.market.domain.service.IEcoMarketClientPublishConfigDomainService;
import com.xspaceagi.eco.market.domain.specification.EcoMarkerSecretWrapper;
import com.xspaceagi.eco.market.sdk.model.ClientSecretDTO;
import com.xspaceagi.eco.market.spec.enums.EcoMarketDataTypeEnum;
import com.xspaceagi.eco.market.spec.enums.EcoMarketOwnedFlagEnum;
import com.xspaceagi.eco.market.spec.enums.EcoMarketUseStatusEnum;
import com.xspaceagi.mcp.sdk.dto.CreatorDto;
import com.xspaceagi.mcp.sdk.dto.McpDto;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.EcoMarketException;
import com.xspaceagi.system.spec.utils.IPUtil;

import jakarta.annotation.Resource;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class EcoMarketClientPublishConfigDomainService implements IEcoMarketClientPublishConfigDomainService {

    @Resource
    private IEcoMarketClientPublishConfigRepository ecoMarketClientPublishConfigRepository;

    @Resource
    private IEcoMarketAdaptor ecoMarketAdaptor;

    @Resource
    private IEcoMarketClientConfigDomainService ecoMarketClientConfigDomainService;

    @Resource
    private EcoMarketServerApiService ecoMarketServerApiService;

    @Resource
    private EcoMarketConfigTranslator ecoMarketConfigTranslator;

    @Resource
    private EcoMarkerSecretWrapper ecoMarkerSecretWrapper;

    @Resource
    private ICustomPageConfigApplicationService customPageConfigApplicationService;

    @Resource
    private ICustomPageBuildApplicationService customPageBuildApplicationService;

    @Resource
    private ICustomPageProxyPathService customPageProxyPathService;

    @Resource
    private PublishApplicationService publishApplicationService;

    @Resource
    private AgentApplicationService agentApplicationService;

    @Override
    public EcoMarketClientPublishConfigModel queryOneInfoById(Long id) {
        return this.ecoMarketClientPublishConfigRepository.queryOneInfoById(id);
    }

    @Override
    public List<EcoMarketClientPublishConfigModel> queryListByIds(List<Long> ids) {
        return this.ecoMarketClientPublishConfigRepository.queryListByIds(ids);
    }

    @Override
    public Long updateInfo(EcoMarketClientPublishConfigModel model, UserContext userContext) {
        return this.ecoMarketClientPublishConfigRepository.updateInfo(model, userContext);
    }

    @DSTransactional(rollbackFor = Exception.class)
    @Override
    public Long addInfo(EcoMarketClientPublishConfigModel model, UserContext userContext) {
        return this.ecoMarketClientPublishConfigRepository.addInfo(model, userContext);
    }

    @Override
    public EcoMarketClientPublishConfigModel queryOneByUid(String uid) {
        return this.ecoMarketClientPublishConfigRepository.queryOneByUid(uid);
    }

    /**
     * 保存或更新发布配置记录
     * 如果存在相同UID的记录先删除再新增
     *
     * @param model       配置模型
     * @param userContext 用户上下文
     * @return 记录ID
     */
    @DSTransactional(rollbackFor = Exception.class)
    @Override
    public Long saveOrUpdateByUid(EcoMarketClientPublishConfigModel model, UserContext userContext) {
        if (model == null || model.getUid() == null) {
            log.error("Save publish config param error: model={}", model);
            throw new IllegalArgumentException("Configuration model or UID cannot be empty");
        }

        String uid = model.getUid();
        log.info("Save or update publish config: uid={}", uid);

        var existObj = this.ecoMarketClientPublishConfigRepository.queryOneByUid(uid);
        if (existObj != null) {
            // 先删除已有记录(如果存在)
            this.ecoMarketClientPublishConfigRepository.deleteByUid(uid);
            model.setCreated(existObj.getCreated());
        }

        // 添加新记录
        return this.ecoMarketClientPublishConfigRepository.addInfo(model, userContext);

    }

    /**
     * 启用配置
     *
     * @param uid         配置唯一标识
     * @param userContext 用户上下文
     * @return 启用后的配置模型
     */
    @DSTransactional(rollbackFor = Exception.class)
    @Override
    public EcoMarketClientPublishConfigModel enableConfig(String uid, UserContext userContext) {
        log.info("Enable config: uid={}", uid);

        // 直接查询本地配置
        EcoMarketClientPublishConfigModel config = this.ecoMarketClientPublishConfigRepository.queryOneByUid(uid);

        // 如果本地没有记录，抛出异常
        if (config == null) {
            log.info("No published config locally, uid={}", uid);
            throw EcoMarketException.build(BizExceptionCodeEnum.configNotFound);
        }

        // 更新状态为启用
        config.setUseStatus(EcoMarketUseStatusEnum.ENABLED.getCode());

        // 调用启用接口
        Long targetId = enableTargetByType(config, userContext);

        // 更新targetId，用于后续禁用使用
        if (targetId != null) {
            config.setTargetId(targetId);
        }

        // 更新发布配置记录
        this.ecoMarketClientPublishConfigRepository.updateInfo(config, userContext);

        // 更新本地配置记录
        EcoMarketClientConfigModel existLocalConfig = this.ecoMarketClientConfigDomainService.queryOneByUid(uid);
        if (existLocalConfig != null) {
            existLocalConfig.setUseStatus(EcoMarketUseStatusEnum.ENABLED.getCode());
            this.ecoMarketClientConfigDomainService.updateInfo(existLocalConfig, userContext);
        } else {
            log.warn("No local config, uid={}, creating local config", uid);
            // 根据 publish 转换本地配置
            EcoMarketClientConfigModel localConfig = EcoMarketClientPublishConfigModel
                    .toClientConfigModel(config);
            localConfig.setId(null);
            // 保存本地配置记录
            this.ecoMarketClientConfigDomainService.addInfo(localConfig, userContext);
        }

        // 返回更新后的配置
        return this.ecoMarketClientPublishConfigRepository.queryOneByUid(uid);
    }

    /**
     * 更新并启用配置
     *
     * @param request     请求参数
     * @param userContext 用户上下文
     * @return 启用后的配置模型
     */
    @DSTransactional(rollbackFor = Throwable.class)
    @Override
    public EcoMarketClientPublishConfigModel updateAndEnableConfig(UpdateAndEnableConfigReqDTO request,
            UserContext userContext) {
        var uid = request.getUid();
        var configParamJson = request.getConfigParamJson();
        var configJson = request.getConfigJson();
        log.info("Update and enable config: uid={}", uid);

        // 获取客户端密钥
        Long tenantId = userContext.getTenantId();
        ClientSecretDTO clientSecret = this.ecoMarkerSecretWrapper.obtainClientSecretOrRegister(tenantId);
        if (clientSecret == null) {
            log.error("Get client secret failed: uid={}", uid);
            throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketClientSecretFetchFailed);
        }

        // 从远程服务器获取最新配置数据
        ServerConfigDetailRespDTO serverConfig;
        try {
            serverConfig = ecoMarketServerApiService.getServerConfigDetail(
                    uid,
                    clientSecret.getClientId(),
                    clientSecret.getClientSecret());

            log.info("Server config detail OK: uid={}", uid);
        } catch (Exception e) {
            log.error("Server config detail failed: uid={}, error={}", uid, e.getMessage(), e);
            throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketUnsupportedDataType,
                    "从服务器获取配置详情失败: " + e.getMessage());
        }

        // 如果服务器返回为空，抛出异常
        if (serverConfig == null) {
            throw EcoMarketException.build(BizExceptionCodeEnum.configNotFound, "服务器未找到配置记录");
        }

        // 删除本地的配置,然后重新创建
        Long oldTargetId = null;
        var existPublishObj = this.ecoMarketClientPublishConfigRepository.queryOneByUid(uid);
        if (existPublishObj != null) {
            oldTargetId = existPublishObj.getTargetId();
            this.ecoMarketClientPublishConfigRepository.deleteByUid(uid);
        }
        var existLocalObj = this.ecoMarketClientConfigDomainService.queryOneByUid(uid);

        // 转换服务器配置为客户端配置, 设置租户ID为空,避免后续使用
        EcoMarketClientConfigModel clientConfig = ecoMarketConfigTranslator.translateServerConfigToClientConfig(
                serverConfig, null);

        // 检查转换结果
        if (clientConfig == null) {
            throw EcoMarketException.build(BizExceptionCodeEnum.configNotFound, "配置转换失败");
        }

        // 将客户端配置转换为发布配置
        EcoMarketClientPublishConfigModel publishConfig = ecoMarketConfigTranslator
                .translateClientConfigToPublishConfig(clientConfig);
        // 设置旧的targetId,如果没有,会按照新插件/模板,本地创建;如果有值,启用会更新插件配置
        publishConfig.setTargetId(oldTargetId);
        // 设置状态为启用
        publishConfig.setUseStatus(EcoMarketUseStatusEnum.DISABLED.getCode());
        publishConfig.setCreateClientId(clientSecret.getClientId());
        // 设置前端给的参数配置json
        publishConfig.setConfigParamJson(configParamJson);

        if (StringUtils.isNoneBlank(configJson)) {
            publishConfig.setConfigJson(configJson);
        }

        // 确保用户的我的分享,标记不丢失,需要用老数据更新下
        if (existLocalObj != null) {
            this.ecoMarketClientConfigDomainService.deleteByUid(uid);

            // 打上我的分享标记
            publishConfig.setOwnedFlag(existLocalObj.getOwnedFlag());
            // 启用/禁用标记
            publishConfig.setCreatorId(existLocalObj.getCreatorId());
            publishConfig.setCreatorName(existLocalObj.getCreatorName());
        }
        // 保存发布配置
        this.ecoMarketClientPublishConfigRepository.addInfo(publishConfig, userContext);
        // 重新查询
        publishConfig = this.ecoMarketClientPublishConfigRepository.queryOneByUid(uid);

        // 调用启用接口
        try {
            Long targetId = enableTargetByType(publishConfig, userContext);
            // 更新targetId，用于后续禁用使用
            if (targetId != null) {
                // 更新targetId
                publishConfig.setTargetId(targetId);
                publishConfig.setUseStatus(EcoMarketUseStatusEnum.ENABLED.getCode());

                // 更新配置记录
                this.ecoMarketClientPublishConfigRepository.updateInfo(publishConfig, userContext);

                // 根据 publish 转换本地配置
                EcoMarketClientConfigModel localConfig = EcoMarketClientPublishConfigModel
                        .toClientConfigModel(publishConfig);
                localConfig.setId(null);
                // 保存本地配置记录
                this.ecoMarketClientConfigDomainService.addInfo(localConfig, userContext);

            } else {
                log.warn("Enable config failed: uid={}, targetId empty from create response", uid);
                throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketEnableConfigFailed, "targetId empty from create response");
            }
        } catch (Exception e) {
            log.error("Enable config failed: uid={}", uid, e);
            throw e;
        }

        // 返回更新后的配置
        return this.ecoMarketClientPublishConfigRepository.queryOneByUid(uid);
    }

    /**
     * 禁用配置
     *
     * @param uid         配置唯一标识
     * @param userContext 用户上下文
     * @return 禁用后的配置模型
     */
    @DSTransactional(rollbackFor = Exception.class)
    @Override
    public EcoMarketClientPublishConfigModel disableConfig(String uid, UserContext userContext) {
        log.info("Disable config: uid={}", uid);

        // 查询本地配置
        EcoMarketClientPublishConfigModel config = this.ecoMarketClientPublishConfigRepository.queryOneByUid(uid);

        // 如果本地没有记录，抛出异常
        if (config == null) {
            log.error("Config to disable not found: uid={}", uid);
            throw EcoMarketException.build(BizExceptionCodeEnum.configNotFound);
        }

        // 设置状态为禁用
        config.setUseStatus(EcoMarketUseStatusEnum.DISABLED.getCode());

        try {
            // 调用禁用接口
            disableTargetByType(config);
        } catch (Exception e) {
            log.error("Disable API call failed: uid={}, error={}", uid, e.getMessage(), e);
            throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketUnsupportedDataType, "禁用接口调用失败: " + e.getMessage());
        }

        // 更新配置记录
        this.ecoMarketClientPublishConfigRepository.updateInfo(config, userContext);

        EcoMarketClientConfigModel clientConfig = this.ecoMarketClientConfigDomainService.queryOneByUid(uid);

        if (clientConfig != null) {
            // 更新本地配置
            clientConfig.setUseStatus(EcoMarketUseStatusEnum.DISABLED.getCode());
            this.ecoMarketClientConfigDomainService.updateInfo(clientConfig, userContext);
        }

        // 返回更新后的配置
        return this.ecoMarketClientPublishConfigRepository.queryOneByUid(uid);
    }

    /**
     * 根据配置类型启用目标
     * 
     * @param config 配置模型
     * @return 目标ID
     */
    private Long enableTargetByType(EcoMarketClientPublishConfigModel config, UserContext userContext) {
        if (config == null) {
            throw new IllegalArgumentException("Configuration model cannot be empty");
        }

        // 判断数据类型
        Integer dataType = config.getDataType();
        EcoMarketDataTypeEnum dataTypeEnum = EcoMarketDataTypeEnum.getByCode(dataType);

        if (dataTypeEnum == null) {
            throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketPublishedConfigNotFound, "无效的数据类型: " + dataType);
        }

        // 根据数据类型调用不同的启用接口
        Long resultId;

        try {
            switch (dataTypeEnum) {
                case PLUGIN -> {
                    // 启用插件
                    PluginEnableOrUpdateDto pluginDto = new PluginEnableOrUpdateDto();
                    pluginDto.setPluginId(config.getTargetId());
                    pluginDto.setUserId(userContext.getUserId());
                    pluginDto.setConfig(config.getConfigJson());
                    pluginDto.setCategory(config.getCategoryCode());
                    // 设置配置参数
                    pluginDto.setParamJson(config.getConfigParamJson());
                    pluginDto.setIcon(config.getIcon());
                    pluginDto.setName(config.getName());

                    // check configJson 不能为空
                    if (pluginDto.getConfig() == null) {
                        log.warn("Config JSON cannot be empty: uid={}", config.getUid());
                        throw EcoMarketException.build(BizExceptionCodeEnum.fieldRequiredButEmpty, "配置JSON");
                    }

                    resultId = ecoMarketAdaptor.pluginEnableOrUpdate(pluginDto);
                }
                case TEMPLATE -> {
                    // 启用模板（智能体、工作流）

                    TargetTypeEnum targetType = TargetTypeEnum.valueOf(config.getTargetType());
                    boolean isPageApp = targetType == TargetTypeEnum.Agent && Published.TargetSubType.PageApp.name().equals(config.getTargetSubType());

                    if (!isPageApp) {
                        TemplateEnableOrUpdateDto templateDto = new TemplateEnableOrUpdateDto();
                        templateDto.setTargetId(config.getTargetId());
                        templateDto.setTargetType(targetType);
                        templateDto.setUserId(userContext.getUserId());
                        templateDto.setConfig(config.getConfigJson());
                        templateDto.setCategory(config.getCategoryCode());
                        templateDto.setIcon(config.getIcon());
                        templateDto.setName(config.getName());
                        // 转换目标类型
                        try {
                            templateDto.setTargetType(TargetTypeEnum.valueOf(config.getTargetType()));
                        } catch (IllegalArgumentException e) {
                            log.error("Invalid target type: uid={}, targetType={}", config.getUid(), config.getTargetType(), e);
                            throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketPublishedConfigNotFound,
                                    "无效的目标类型: " + config.getTargetType());
                        }

                        // check configJson 不能为空
                        if (templateDto.getConfig() == null) {
                            log.warn("Config JSON cannot be empty: uid={}", config.getUid());
                            throw EcoMarketException.build(BizExceptionCodeEnum.fieldRequiredButEmpty, "配置JSON");
                        }
                        resultId = ecoMarketAdaptor.templateEnableOrUpdate(templateDto);
                    } else {
                        //应用页面
                        String pageZipUrl = config.getPageZipUrl();
                        if (StringUtils.isBlank(pageZipUrl)) {
                            log.warn("Page zip URL required: uid={}", config.getUid());
                            throw EcoMarketException.build(BizExceptionCodeEnum.fieldRequiredButEmpty, "页面压缩包URL");
                        }
                        
                        try {
                            // 下载页面压缩包
                            log.info("Downloading page zip: uid={}, url={}", config.getUid(), pageZipUrl);
                            byte[] zipBytes = UrlFile.downLoad(pageZipUrl);
                            if (zipBytes == null || zipBytes.length == 0) {
                                log.error("Page zip download failed or empty: uid={}", config.getUid());
                                throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketPageArchiveDownloadFailed);
                            }
                            
                            // 创建页面项目
                            log.info("Creating page project: uid={}, spaceId={}, name={}", config.getUid(), -1L, config.getName());
                            CustomPageConfigModel pageModel = new CustomPageConfigModel();
                            pageModel.setName(config.getName());
                            pageModel.setDescription(config.getDescription());
                            pageModel.setSpaceId(-1L);
                            pageModel.setIcon(config.getIcon());
                            pageModel.setNeedLogin(YesOrNoEnum.Y.getKey()); // 需要登录
                            pageModel.setProjectType(ProjectType.ONLINE_DEPLOY);
                            
                            com.xspaceagi.system.spec.dto.ReqResult<CustomPageConfigModel> createResult = customPageConfigApplicationService.create(pageModel, userContext);
                            if (!createResult.isSuccess()) {
                                log.error("Create page project failed: uid={}, error={}", config.getUid(), createResult.getMessage());
                                throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketPageProjectCreateFailed);
                            }
                            CustomPageConfigModel createdPageModel = createResult.getData();
                            Long projectId = createdPageModel.getId();
                            log.info("Create page project OK: uid={}, projectId={}", config.getUid(), projectId);
                            
                            // 上传压缩包
                            log.info("Uploading page zip: uid={}, projectId={}", config.getUid(), projectId);
                            MultipartFile multipartFile = new ByteArrayMultipartFile(zipBytes, "project_" + projectId + ".zip", "application/zip");

                            try {
                                com.xspaceagi.system.spec.dto.ReqResult<Map<String, Object>> uploadResult = customPageConfigApplicationService.uploadProject(
                                        createdPageModel, multipartFile, true, userContext);
                                if (!uploadResult.isSuccess()) {
                                    log.error("Page zip upload failed: uid={}, projectId={}, error={}", config.getUid(), projectId, uploadResult.getMessage());
                                    throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketPageArchiveUploadFailed);
                                }
                                log.info("Page zip upload OK: uid={}, projectId={}", config.getUid(), projectId);
                            } catch (Exception e) {
                                log.error("Page zip upload failed: uid={}, projectId={}", config.getUid(), projectId, e);
                                try {
                                    customPageConfigApplicationService.deleteProject(projectId, userContext);
                                } catch (Exception e1) {
                                    log.error("Cleanup page project failed: uid={}, projectId={}", config.getUid(), projectId, e1);
                                }
                                throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketPageArchiveUploadFailed);
                            }
                            // 发布页面
                            log.info("Publishing page: uid={}, projectId={}", config.getUid(), projectId);
                            com.xspaceagi.system.spec.dto.ReqResult<java.util.Map<String, Object>> buildResult = customPageBuildApplicationService.build(
                                    projectId, PublishTypeEnum.AGENT.name(), userContext);
                            if (!buildResult.isSuccess()) {
                                log.error("Publish page failed: uid={}, projectId={}, error={}", config.getUid(), projectId, buildResult.getMessage());
                                throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketPagePublishFailed);
                            }
                            log.info("Publish page OK: uid={}, projectId={}", config.getUid(), projectId);

                            Long agentId = createdPageModel.getDevAgentId();
                            
                            // 获取智能体配置作为 targetConfig
                            AgentConfigDto agentConfigDto = agentApplicationService.queryById(agentId);
                            if (agentConfigDto == null) {
                                log.error("Get agent config failed: uid={}, agentId={}", config.getUid(), agentId);
                                throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketAppPageEnableFailed);
                            }
                            
                            UserDto userDto = new  UserDto();
                            userDto.setId(userContext.getUserId());
                            userDto.setUserName(userContext.getUserName());
                            userDto.setNickName(userContext.getNickName());

                            //发布智能体
                            PublishApplyDto publishApply = new PublishApplyDto();
                            publishApply.setSpaceId(-1L);
                            publishApply.setTargetId(agentId);
                            publishApply.setTargetType(Published.TargetType.Agent);
                            publishApply.setTargetSubType(Published.TargetSubType.PageApp);
                            publishApply.setApplyUser(userDto);
                            publishApply.setName(config.getName());
                            publishApply.setDescription(config.getDescription());
                            publishApply.setIcon(generateIcon(config.getIcon()));
                            publishApply.setChannels(List.of(Published.PublishChannel.System));
                            publishApply.setScope(Published.PublishScope.Tenant);
                            publishApply.setCategory(CategoryDto.PluginCategoryEnum.Other.getName());
                            publishApply.setTargetConfig(agentConfigDto);
                            publishApply.setAllowCopy(YesOrNoEnum.Y.getKey());
                            publishApply.setOnlyTemplate(YesOrNoEnum.Y.getKey());
                            Long applyId = publishApplicationService.publishApply(publishApply);
                            publishApplicationService.publish(applyId);

                            // 获取生产环境地址
                            String prodProxyPath = customPageProxyPathService.getProdProxyPath(projectId);
                            log.info("Prod URL OK: uid={}, projectId={}, prodProxyPath={}", config.getUid(), projectId, prodProxyPath);

                            resultId = agentId;
                        } catch (EcoMarketException e) {
                            throw e;
                        } catch (Exception e) {
                            log.error("Handle page project failed: uid={}", config.getUid(), e);
                            throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketAppPageEnableFailed);
                        }

                    }
                }
                case MCP -> {
                    // MCP类型启用

                    var configJson = config.getConfigJson();
                    if (StringUtils.isBlank(configJson)) {
                        log.error("MCP config content required: uid={}", config.getUid());
                        throw EcoMarketException.build(BizExceptionCodeEnum.fieldRequiredButEmpty, "MCP配置");
                    }

                    McpDto mcpDtoFromJson = JSONObject.parseObject(configJson, McpDto.class);
                    if (mcpDtoFromJson == null) {
                        log.error("MCP config parse failed: uid={}", config.getUid());
                        throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketMcpConfigParseFailed);
                    }
                    mcpDtoFromJson.setId(null);
                    mcpDtoFromJson.setSpaceId(null);
                    mcpDtoFromJson.setUid(config.getUid());
                    mcpDtoFromJson.setDescription(config.getDescription());
                    mcpDtoFromJson.setCategory(config.getCategoryCode());
                    mcpDtoFromJson.setIcon(config.getIcon());
                    mcpDtoFromJson.setName(config.getName());
                    mcpDtoFromJson.setCreatorId(userContext.getUserId());
                    mcpDtoFromJson.setCreated(null);
                    mcpDtoFromJson.setModified(null);
                    var creator = CreatorDto.builder()
                            .userId(userContext.getUserId())
                            .userName(userContext.getUserName())
                            .nickName(userContext.getNickName())
                            .avatar(userContext.getAvatar())
                            .build();
                    mcpDtoFromJson.setCreator(creator);

                    resultId = ecoMarketAdaptor.deployOfficialMcp(mcpDtoFromJson);

                }
                default -> {
                    log.error("Unsupported data type: {}", dataType);
                    throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketPublishedConfigNotFound, "不支持的数据类型");
                }
            }

            log.info("Enable API OK: uid={}, resultId={}", config.getUid(), resultId);
            return resultId;
        } catch (EcoMarketException e) {
            // 业务异常直接抛出
            throw e;
        } catch (Exception e) {
            log.error("Enable config failed: uid={}", config.getUid(), e);
            throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketEnableConfigFailed);
        }
    }

    private String generateIcon(String iconUrl) {
        // 检查pluginAddDto.getIcon()是否可网络上访问
        try {
            if (StringUtils.isNotBlank(iconUrl)) {
                //检查是否为内网URL
                if (!IPUtil.isInternalAddress(iconUrl)) {
                    return iconUrl;
                }
            }
        } catch (Exception e) {
            //  忽略
            log.warn("Plugin icon download failed {}", iconUrl);
        }
        return null;
    }

    /**
     * 根据配置类型禁用目标
     * 
     * @param config 配置模型
     */
    private void disableTargetByType(EcoMarketClientPublishConfigModel config) {
        if (config == null) {
            throw new IllegalArgumentException("Configuration model cannot be empty");
        }

        // 判断数据类型
        Integer dataType = config.getDataType();
        EcoMarketDataTypeEnum dataTypeEnum = EcoMarketDataTypeEnum.getByCode(dataType);

        if (dataTypeEnum == null) {
            throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketPublishedConfigNotFound, "无效的数据类型: " + dataType);
        }

        // 检查targetId是否存在
        if (config.getTargetId() == null) {
            log.warn("Disable config: targetId empty, uid={}", config.getUid());
            return; // 没有targetId无法禁用，但不抛异常
        }

        try {
            switch (dataTypeEnum) {
                case PLUGIN -> {
                    // 禁用插件
                    ecoMarketAdaptor.disablePlugin(config.getTargetId());
                }
                case TEMPLATE -> {
                    // 禁用模板（智能体、工作流）
                    TargetTypeEnum targetType = TargetTypeEnum.valueOf(config.getTargetType());
                    ecoMarketAdaptor.disableTemplate(targetType, config.getTargetId());
                }
                case MCP -> {
                    // 停止MCP
                    ecoMarketAdaptor.stopOfficialMcp(config.getTargetId());

                }
                default -> {
                    log.error("Unsupported data type: {}", dataType);
                    throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketPublishedConfigNotFound, "不支持的数据类型");
                }
            }
            log.info("Disable API OK: uid={}, targetId={}", config.getUid(), config.getTargetId());
        } catch (Exception e) {
            log.error("Disable config failed: uid={},", config.getUid(), e);
            throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketDisableConfigFailed);
        }
    }

    @Override
    public IPage<EcoMarketClientPublishConfigModel> pageQueryEnabled(QueryEcoMarketVo queryEcoMarketVo, long current,
            long size) {
        // 只查询启用状态的配置
        queryEcoMarketVo.setUseStatus(EcoMarketUseStatusEnum.ENABLED.getCode());
        queryEcoMarketVo.setOwnedFlag(EcoMarketOwnedFlagEnum.NO.getCode());
        return this.ecoMarketClientPublishConfigRepository.pageQuery(queryEcoMarketVo, current, size);
    }

    @Override
    public List<EcoMarketClientPublishConfigModel> queryListByUids(List<String> uids) {
        if (uids == null || uids.isEmpty()) {
            return List.of();
        }
        return this.ecoMarketClientPublishConfigRepository.queryListByUids(uids);
    }

    @Override
    public boolean checkConfigRepeat(Long targetId, String targetType, EcoMarketDataTypeEnum dataTypeEnum) {
        return this.ecoMarketClientPublishConfigRepository.checkConfigRepeat(targetId, targetType, dataTypeEnum);
    }

    /**
     * 字节数组 MultipartFile 实现类
     */
    @Data
    @AllArgsConstructor
    private static class ByteArrayMultipartFile implements MultipartFile {
        private final byte[] content;
        private final String name;
        private final String contentType;

        @Override
        public String getOriginalFilename() {
            return name;
        }

        @Override
        public boolean isEmpty() {
            return content == null || content.length == 0;
        }

        @Override
        public long getSize() {
            return content != null ? content.length : 0;
        }

        @Override
        public byte[] getBytes() throws IOException {
            return content;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return new ByteArrayInputStream(content);
        }

        @Override
        public org.springframework.core.io.Resource getResource() {
            return new ByteArrayResource(content) {
                @Override
                public String getFilename() {
                    return name;
                }
            };
        }

        @Override
        public void transferTo(File dest) throws IOException, IllegalStateException {
            java.nio.file.Files.write(dest.toPath(), content);
        }
    }
}
