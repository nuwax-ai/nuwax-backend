package com.xspaceagi.eco.market.spec.app.service.impl;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.xspaceagi.custompage.sdk.ICustomPageRpcService;
import com.xspaceagi.custompage.sdk.dto.CustomPageDto;
import org.springframework.stereotype.Service;

import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xspaceagi.agent.core.adapter.repository.AgentComponentConfigRepository;
import com.xspaceagi.agent.core.adapter.repository.entity.AgentComponentConfig;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.custompage.application.service.ICustomPageConfigApplicationService;
import com.xspaceagi.eco.market.domain.client.EcoMarketServerApiService;
import com.xspaceagi.eco.market.domain.dto.req.ServerConfigQueryRequest;
import com.xspaceagi.eco.market.domain.dto.req.ServerConfigSaveReqDTO;
import com.xspaceagi.eco.market.domain.dto.resp.ServerConfigDetailRespDTO;
import com.xspaceagi.eco.market.domain.dto.resp.ServerConfigListRespDTO;
import com.xspaceagi.eco.market.domain.model.EcoMarketClientConfigModel;
import com.xspaceagi.eco.market.domain.model.EcoMarketClientPublishConfigModel;
import com.xspaceagi.eco.market.domain.service.IEcoMarketClientConfigDomainService;
import com.xspaceagi.eco.market.domain.service.IEcoMarketClientPublishConfigDomainService;
import com.xspaceagi.eco.market.domain.specification.EcoMarkerSecretWrapper;
import com.xspaceagi.eco.market.sdk.model.ClientSecretDTO;
import com.xspaceagi.eco.market.spec.app.assembler.EcoMarketClientConfigTranslator;
import com.xspaceagi.eco.market.spec.app.dto.request.ClientConfigQueryRequest;
import com.xspaceagi.eco.market.spec.app.service.IEcoMarketClientConfigApplicationService;
import com.xspaceagi.eco.market.spec.enums.EcoMarketDataTypeEnum;
import com.xspaceagi.eco.market.spec.enums.EcoMarketOwnedFlagEnum;
import com.xspaceagi.eco.market.spec.enums.EcoMarketShareStatusEnum;
import com.xspaceagi.eco.market.spec.enums.EcoMarketSubTabType;
import com.xspaceagi.eco.market.spec.enums.EcoMarketUseStatusEnum;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.EcoMarketException;
import com.xspaceagi.system.spec.page.PageQueryVo;
import com.xspaceagi.system.spec.page.SuperPage;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class EcoMarketClientConfigApplicationService implements IEcoMarketClientConfigApplicationService {

    @Resource
    private IEcoMarketClientConfigDomainService ecoMarketClientConfigDomainService;

    @Resource
    private EcoMarketServerApiService ecoMarketServerApiService;

    @Resource
    private IEcoMarketClientPublishConfigDomainService ecoMarketClientPublishConfigDomainService;

    @Resource
    private EcoMarkerSecretWrapper ecoMarkerSecretWrapper;

    @Resource
    private AgentComponentConfigRepository agentComponentConfigRepository;

    @Resource
    private ICustomPageConfigApplicationService customPageConfigApplicationService;

    @Resource
    private ICustomPageRpcService  iCustomPageRpcService;

    @Override
    public EcoMarketClientConfigModel queryOneInfoById(Long id) {
        return ecoMarketClientConfigDomainService.queryOneInfoById(id);
    }

    @Override
    public List<EcoMarketClientConfigModel> queryListByIds(List<Long> ids) {
        return ecoMarketClientConfigDomainService.queryListByIds(ids);
    }

    @Override
    public void deleteById(Long id) {
        ecoMarketClientConfigDomainService.deleteById(id);
    }

    @Override
    public IPage<EcoMarketClientConfigModel> pageQueryWithServerCompare(ClientConfigQueryRequest request, long current,
            long size, ClientSecretDTO clientSecret, UserContext userContext) {
        // 获取客户端凭证
        if (clientSecret == null) {
            throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketClientSecretFetchFailed);
        }

        IPage<EcoMarketClientConfigModel> result;

        // 获取tab类型
        var subTabType = EcoMarketSubTabType.getByCode(request.getSubTabType());
        if (subTabType == null) {
            request.setSubTabType(EcoMarketSubTabType.ALL.getCode());
            subTabType = EcoMarketSubTabType.ALL;
        }

        var queryEcoMarketVo = ClientConfigQueryRequest.convertToQueryEcoMarketVo(request);

        // 根据tab类型执行不同查询策略
        switch (subTabType) {
            case ALL -> {
                // 对于"ALL"类型,通过HTTP请求服务器端list接口获取所有配置
                log.info("Query all configs: subTabType={}", subTabType.getCode());

                // 创建请求DTO
                PageQueryVo<ServerConfigQueryRequest> listReqDTO = new PageQueryVo<>();
                // 设置分页参数
                listReqDTO.setCurrent(current);
                listReqDTO.setPageSize(size);

                // 设置查询条件
                ServerConfigQueryRequest queryFilter = ClientConfigQueryRequest.convertToServerConfigQueryRequest(
                        request,
                        subTabType.getCode());
                listReqDTO.setQueryFilter(queryFilter);

                // 调用服务器端查询接口
                SuperPage<ServerConfigListRespDTO> serverPage = ecoMarketServerApiService
                        .queryServerConfigList(listReqDTO);

                if (serverPage == null) {
                    throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketGetConfigFailed, "从服务器查询配置列表失败");
                }

                // 处理服务器返回的数据
                List<ServerConfigListRespDTO> serverRecords = serverPage.getRecords();

                if (serverRecords.isEmpty()) {
                    // 服务器没有数据,返回空结果
                    SuperPage<EcoMarketClientConfigModel> emptyPage = new SuperPage<>();
                    emptyPage.setCurrent(current);
                    emptyPage.setSize(size);
                    emptyPage.setTotal(serverPage.getTotal());
                    emptyPage.setRecords(Collections.emptyList());
                    return emptyPage;
                }

                // 提取服务器端配置的UID列表
                List<String> serverUids = serverRecords.stream()
                        .map(ServerConfigListRespDTO::getUid)
                        .collect(Collectors.toList());

                // 在本地查询这些UID对应的配置
                List<EcoMarketClientConfigModel> localConfigs = ecoMarketClientConfigDomainService
                        .queryListByUids(serverUids);

                // 查询本地启用的配置
                List<EcoMarketClientPublishConfigModel> enabledConfigs = ecoMarketClientPublishConfigDomainService
                        .queryListByUids(serverUids);

                // 构建UID到本地配置的映射
                Map<String, EcoMarketClientConfigModel> localConfigMap = localConfigs.stream()
                        .collect(Collectors.toMap(
                                EcoMarketClientConfigModel::getUid,
                                config -> config,
                                (existing, replacement) -> existing));

                // 构建UID到本地配置的映射
                Map<String, EcoMarketClientPublishConfigModel> enabledConfigMap = enabledConfigs.stream()
                        .filter(config -> config.getUseStatus() == EcoMarketUseStatusEnum.ENABLED.getCode())
                        .collect(Collectors.toMap(
                                EcoMarketClientPublishConfigModel::getUid,
                                config -> config,
                                (existing, replacement) -> existing));

                // 将服务器数据转换为本地模型,并与本地数据比较版本
                var page = serverPage.convert(serverConfig -> {

                    EcoMarketClientConfigModel newModel = EcoMarketClientConfigTranslator
                            .serverListItemToClientConfig(serverConfig, clientSecret.getTenantId());

                    // serverConfig 服务器的初始为:未启用,后续根据本地数据的情况,来修改启用状态
                    newModel.setUseStatus(EcoMarketUseStatusEnum.DISABLED.getCode());
                    // 设置为非自己分享
                    newModel.setOwnedFlag(EcoMarketOwnedFlagEnum.NO.getCode());
                    // 默认没有新版本
                    newModel.setIsNewVersion(false);
                    newModel.setServerVersionNumber(serverConfig.getVersionNumber());

                    var localConfig = localConfigMap.get(serverConfig.getUid());
                    var enabledConfig = enabledConfigMap.get(serverConfig.getUid());

                    if (localConfig != null) {
                        // 根据 localConfig 设置 是否我的分享标记
                        if (localConfig.getOwnedFlag() == EcoMarketOwnedFlagEnum.YES.getCode()) {
                            newModel.setOwnedFlag(EcoMarketOwnedFlagEnum.YES.getCode());
                        }

                    }
                    // 检查启用状态
                    if (enabledConfig != null) {
                        newModel.setUseStatus(enabledConfig.getUseStatus());

                        // 本地存在,比较版本
                        if (serverConfig.getVersionNumber() > enabledConfig.getVersionNumber()) {
                            // 服务器有新版本
                            newModel.setIsNewVersion(true);
                            newModel.setServerVersionNumber(serverConfig.getVersionNumber());
                        }
                    } else {
                        // 如果本地不存在启用配置,则设置为禁用
                        newModel.setUseStatus(EcoMarketUseStatusEnum.DISABLED.getCode());
                    }
                    return newModel;
                });

                result = page;
                break;
            }
            case ENABLED -> {
                // 对于"ENABLED"类型,查询本地已启用配置表
                log.info("Query enabled configs: subTabType={}, dataType={}", subTabType.getCode(), request.getDataType());

                // 直接使用领域服务的分页查询启用状态的配置方法
                IPage<EcoMarketClientPublishConfigModel> clientPage = ecoMarketClientPublishConfigDomainService
                        .pageQueryEnabled(queryEcoMarketVo, current, size);
                IPage<EcoMarketClientConfigModel> clientLocalConfigs = clientPage
                        .convert(EcoMarketClientPublishConfigModel::toClientConfigModel);

                if (clientLocalConfigs.getRecords().isEmpty()) {
                    // 没有已启用配置,返回空结果
                    return clientLocalConfigs;
                }

                // 获取所有记录的uid列表
                List<String> configUids = clientLocalConfigs.getRecords().stream()
                        .map(EcoMarketClientConfigModel::getUid)
                        .toList();

                // 从服务器获取最新版本信息
                try {
                    // 使用批量获取服务器配置审批详情接口
                    List<ServerConfigDetailRespDTO> serverConfigDetails = ecoMarketServerApiService
                            .getBatchServerConfigDetail(
                                    configUids,
                                    clientSecret.getClientId(),
                                    clientSecret.getClientSecret());

                    if (serverConfigDetails != null && !serverConfigDetails.isEmpty()) {
                        // 构建UID到服务器配置的映射
                        Map<String, ServerConfigDetailRespDTO> serverConfigMap = serverConfigDetails.stream()
                                .collect(Collectors.toMap(
                                        ServerConfigDetailRespDTO::getUid,
                                        config -> config,
                                        (existing, replacement) -> existing));

                        // 比较版本并更新本地配置信息
                        for (EcoMarketClientConfigModel clientConfig : clientLocalConfigs.getRecords()) {
                            ServerConfigDetailRespDTO serverConfig = serverConfigMap.get(clientConfig.getUid());

                            if (serverConfig != null && serverConfig.getVersionNumber() != null) {
                                // 比较版本
                                if (serverConfig.getVersionNumber() > clientConfig.getVersionNumber()) {
                                    // 服务器有新版本
                                    clientConfig.setIsNewVersion(true);
                                    clientConfig.setServerVersionNumber(serverConfig.getVersionNumber());
                                } else {
                                    // 本地版本是最新的
                                    clientConfig.setIsNewVersion(false);
                                    clientConfig.setServerVersionNumber(serverConfig.getVersionNumber());
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("Failed to get server config detail", e);
                    // 即使获取服务器配置失败，仍然返回本地数据
                }

                // 由于我们已经使用了分页查询，直接返回结果即可
                result = clientLocalConfigs;
                break;
            }
            case MY_SHARE -> {
                // 对于"MY_SHARE"类型,查询本地我分享的配置
                log.info("Query my shares: subTabType={}", subTabType.getCode());

                // 检查待审批的数据,进行更新我的分享状态
                updateApproveShareStatus(clientSecret, userContext);

                // 直接调用领域服务查询
                var clientLocalConfigs = ecoMarketClientConfigDomainService.pageQueryMyShare(queryEcoMarketVo, current,
                        size);
                // 查询服务器,获取其审批状态
                if (clientLocalConfigs.getRecords().isEmpty()) {
                    // 没有已启用配置,返回空结果
                    return clientLocalConfigs;
                }

                // 获取所有记录的uid列表
                List<String> configUids = clientLocalConfigs.getRecords().stream()
                        .map(EcoMarketClientConfigModel::getUid)
                        .collect(Collectors.toList());

                // 从服务器获取最新版本信息
                try {
                    // 使用批量获取服务器配置详情接口
                    List<ServerConfigDetailRespDTO> serverConfigDetails = ecoMarketServerApiService
                            .getBatchServerApproveDetail(
                                    configUids,
                                    clientSecret.getClientId(),
                                    clientSecret.getClientSecret());

                    if (serverConfigDetails != null && !serverConfigDetails.isEmpty()) {
                        // 构建UID到服务器配置的映射
                        Map<String, ServerConfigDetailRespDTO> serverConfigMap = serverConfigDetails.stream()
                                .collect(Collectors.toMap(
                                        ServerConfigDetailRespDTO::getUid,
                                        config -> config,
                                        (existing, replacement) -> existing));

                        // 比较版本并更新本地配置信息
                        for (EcoMarketClientConfigModel clientConfig : clientLocalConfigs.getRecords()) {
                            ServerConfigDetailRespDTO serverConfig = serverConfigMap.get(clientConfig.getUid());

                            if (serverConfig != null && serverConfig.getVersionNumber() != null) {
                                // 获取审批状态
                                var shareStatus = serverConfig.getShareStatus();
                                var approveMessage = serverConfig.getApproveMessage();

                                var uid = clientConfig.getUid();
                                var clientShareStatus = clientConfig.getShareStatus();

                                // 如果server端的状态,和本地状态不一样,也进行更新,可能server端操作了下架动作
                                if (!Objects.equals(shareStatus, clientShareStatus)) {
                                    try {
                                        log.info("Update local config share status, uid:{}, shareStatus:{}",
                                                clientConfig.getUid(), shareStatus);
                                        ecoMarketClientConfigDomainService.updateShareStatusByUid(
                                                clientConfig.getUid(),
                                                shareStatus, approveMessage, userContext);
                                    } catch (Exception e) {
                                        log.error("Update local share status failed, uid:{}, shareStatus:{}",
                                                clientConfig.getUid(), shareStatus, e);
                                    }
                                }
                                // 设置分享状态
                                clientConfig.setShareStatus(shareStatus);
                                clientConfig.setApproveMessage(approveMessage);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("Failed to get server config detail", e);
                    // 即使获取服务器配置失败，仍然返回本地数据
                }
                result =  clientLocalConfigs;
                break;
            }
            default -> {
                throw new UnsupportedOperationException("不支持的tab类型: " + subTabType.getCode());
            }
        }

        if (!result.getRecords().isEmpty()) {
            // 查询页面封面
            List<Long> agentIds = result.getRecords().stream()
                    .filter(c -> Published.TargetType.Agent.name().equals(c.getTargetType())
                            && Published.TargetSubType.PageApp.name().equals(c.getTargetSubType()))
                    .map(EcoMarketClientConfigModel::getTargetId).toList();
            if (!agentIds.isEmpty()) {
                List<CustomPageDto> pageDtos = iCustomPageRpcService.listByAgentIds(agentIds);
                Map<Long, CustomPageDto> pageDtoMap = pageDtos.stream()
                        .filter(dto -> dto.getDevAgentId() != null)
                        .collect(Collectors.toMap(
                                CustomPageDto::getDevAgentId,
                                dto -> dto,
                                (v1, v2) -> v1 // 如果有重复，保留第一个
                        ));
                for (EcoMarketClientConfigModel config : result.getRecords()) {
                    CustomPageDto pageDto = pageDtoMap.get(config.getTargetId());
                    if (pageDto != null) {
                        config.setCoverImg(pageDto.getCoverImg());
                        config.setCoverImgSourceType(pageDto.getCoverImgSourceType());
                    }
                }
            }
        }
        return result;
    }

    /**
     * 检查待审核的状态的数据,获取最新状态并更新
     *
     * @param clientSecret
     * @param userContext
     */
    private void updateApproveShareStatus(ClientSecretDTO clientSecret, UserContext userContext) {
        // 先查询审批中的数据,调用远程服务器,然后更新审批状态到本地

        var myShareAndReviewing = ecoMarketClientConfigDomainService.queryMyShareAndReviewing();
        if (!myShareAndReviewing.isEmpty()) {
            log.info("Found pending-approval configs, updating approval status, count:{}", myShareAndReviewing.size());
            try {

                // 获取所有记录的uid列表
                List<String> configUids = myShareAndReviewing.stream()
                        .map(EcoMarketClientConfigModel::getUid)
                        .collect(Collectors.toList());

                // 从服务器获取最新版本信息
                List<ServerConfigDetailRespDTO> serverConfigDetails = ecoMarketServerApiService
                        .getBatchServerApproveDetail(
                                configUids,
                                clientSecret.getClientId(),
                                clientSecret.getClientSecret());

                if (serverConfigDetails != null && !serverConfigDetails.isEmpty()) {
                    // 构建UID到服务器配置的映射
                    Map<String, ServerConfigDetailRespDTO> serverConfigMap = serverConfigDetails.stream()
                            .collect(Collectors.toMap(
                                    ServerConfigDetailRespDTO::getUid,
                                    config -> config,
                                    (existing, replacement) -> existing));

                    // 比较版本并更新本地配置信息
                    for (EcoMarketClientConfigModel clientConfig : myShareAndReviewing) {
                        ServerConfigDetailRespDTO serverConfig = serverConfigMap.get(clientConfig.getUid());
                        if (serverConfig != null && serverConfig.getVersionNumber() != null) {
                            // 获取审批状态
                            var shareStatus = serverConfig.getShareStatus();
                            var approveMessage = serverConfig.getApproveMessage();
                            // 如果server端的审批状态不是 审批中 状态,则更新状态
                            if (!EcoMarketShareStatusEnum.REVIEWING.getCode().equals(shareStatus)) {
                                try {
                                    log.info("Update local approval status, uid:{}, shareStatus:{}",
                                            clientConfig.getUid(), shareStatus);
                                    ecoMarketClientConfigDomainService.updateShareStatusByUid(
                                            clientConfig.getUid(),
                                            shareStatus, approveMessage, userContext);
                                } catch (Exception e) {
                                    log.error("Update local approval status failed, uid:{}, shareStatus:{}",
                                            clientConfig.getUid(), shareStatus, e);
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Get server config approval status failed", e);
            }
        }
    }

    @Override
    public EcoMarketClientConfigModel queryOneByUid(String uid) {
        return ecoMarketClientConfigDomainService.queryOneByUid(uid);
    }

    @Override
    public List<EcoMarketClientConfigModel> queryListByUids(List<String> uids) {
        return ecoMarketClientConfigDomainService.queryListByUids(uids);
    }

    @Override
    public EcoMarketClientConfigModel getConfigDetail(String uid, UserContext userContext) {
        Long tenantId = userContext.getTenantId();
        log.info("Get config detail: uid={}, tenantId={}", uid, tenantId);

        // 先查询本地是否存在该uid的配置
        EcoMarketClientConfigModel clientConfigModel = ecoMarketClientConfigDomainService.queryOneByUid(uid);

        // 查询publish_config 表,是否存在该uid的配置
        EcoMarketClientPublishConfigModel publishConfigModel = ecoMarketClientPublishConfigDomainService
                .queryOneByUid(uid);

        // 获取服务端的配置详情
        EcoMarketClientConfigModel serverConfigModel = fetchConfigFromServer(uid, tenantId);

        // 如果本地和服务器都没有找到配置
        if (publishConfigModel == null && serverConfigModel == null && clientConfigModel == null) {
            log.warn("Config not found: uid={}", uid);
            throw EcoMarketException.build(BizExceptionCodeEnum.configNotFound);
        }

        // 首先判断这个uid对应的配置,是否是我的创建分享的配置
        var ownedFlag = false;

        if (Objects.nonNull(clientConfigModel)) {
            ownedFlag = Objects.equals(clientConfigModel.getOwnedFlag(), EcoMarketOwnedFlagEnum.YES.getCode());
            if (!ownedFlag) {
                // 不是我创建分享的配置,则使用publish配置
                if (Objects.nonNull(publishConfigModel)) {
                    clientConfigModel = EcoMarketClientConfigTranslator.toClientConfigModel(publishConfigModel);
                }
                clientConfigModel.setOwnedFlag(EcoMarketOwnedFlagEnum.NO.getCode());
            } else {
                clientConfigModel.setOwnedFlag(EcoMarketOwnedFlagEnum.YES.getCode());
            }
            // 比较版本
            EcoMarketClientConfigTranslator.compareVersion(clientConfigModel, serverConfigModel);

            // 设置本地 LocalConfigParamJson
            clientConfigModel.setLocalConfigParamJson(clientConfigModel.getConfigParamJson());

            // 设置本地 ServerConfigJson
            clientConfigModel.setLocalConfigJson(clientConfigModel.getConfigJson());

            // 设置服务器配置参数json
            if (Objects.nonNull(serverConfigModel)) {
                clientConfigModel.setServerConfigParamJson(serverConfigModel.getConfigParamJson());
                // mcp 需要使用这个 configJSON
                clientConfigModel.setServerConfigJson(serverConfigModel.getConfigJson());
            }

            return clientConfigModel;
        } else {
            // 如果本地不存在但服务器存在，使用服务器配置
            if (Objects.nonNull(serverConfigModel)) {
                serverConfigModel.setServerConfigParamJson(serverConfigModel.getConfigParamJson());
                serverConfigModel.setServerConfigJson(serverConfigModel.getConfigJson());
                serverConfigModel.setOwnedFlag(EcoMarketOwnedFlagEnum.NO.getCode());
                serverConfigModel.setTenantId(null);
                serverConfigModel.setUseStatus(EcoMarketUseStatusEnum.DISABLED.getCode());
            }
            return serverConfigModel;
        }

    }

    /**
     * 从服务器获取配置
     * 
     * @param uid      配置唯一标识
     * @param tenantId 租户ID
     * @return 配置信息
     */
    private EcoMarketClientConfigModel fetchConfigFromServer(String uid, Long tenantId) {

        // 获取客户端密钥
        ClientSecretDTO clientSecret = ecoMarkerSecretWrapper.obtainClientSecretOrRegister(tenantId);
        if (clientSecret == null) {
            throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketClientSecretFetchFailed, "获取客户端密钥失败");
        }

        // 直接调用ServerApiService的getDetailByUid方法获取数据
        ServerConfigDetailRespDTO serverData = ecoMarketServerApiService.getServerConfigDetail(
                uid, clientSecret.getClientId(), clientSecret.getClientSecret());

        if (serverData == null) {
            return null;
        }

        // 使用转换器将服务器配置转换为客户端配置模型
        return EcoMarketClientConfigTranslator.serverDetailToClientConfig(serverData, tenantId);
    }

    @DSTransactional(rollbackFor = Exception.class)
    @Override
    public EcoMarketClientConfigModel saveAndPublish(EcoMarketClientConfigModel model, String clientId,
            String clientSecret, UserContext userContext) {
        // 检查参数
        if (Objects.isNull(model)) {
            throw EcoMarketException.build(BizExceptionCodeEnum.fieldRequiredButEmpty, "配置模型");
        }

        log.info("Save and publish config: clientId={}, name={}", clientId, model.getName());

        // 根据 targetId,targetType,进行重复检查,相同的插件/模板,不允许重复创建分享发布
        var targetId = model.getTargetId();
        var targetType = model.getTargetType();
        var targetSubType = model.getTargetSubType();
        var dataTypeEnum = EcoMarketDataTypeEnum.getByCode(model.getDataType());
        String uid = model.getUid();

        if (Published.TargetType.Agent.name().equals(targetType)) {
            if (targetSubType == null
                    || Stream.of(Published.TargetSubType.ChatBot, Published.TargetSubType.PageApp, Published.TargetSubType.TaskAgent).noneMatch(t -> t.name().equals(targetSubType))) {
                throw new EcoMarketException("8000", "targetSubType不能为空");
            }
        }

        // 检查配置是否重复(排除自身uid)
        var isRepeat = ecoMarketClientConfigDomainService.checkConfigRepeat(targetId, targetType, dataTypeEnum, uid);
        if (isRepeat) {
            var dataType = Optional.ofNullable(dataTypeEnum).map(EcoMarketDataTypeEnum::getCode).orElse(null);
            log.warn("Duplicate config: targetId={}, targetType={}, dataType={}", targetId, targetType, dataType);
            throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketDuplicateShareNotAllowed);
        }
        // 再 ecoMarketClientPublishConfig 表里的,都是从生态市场里获取到的,禁止重复分享
        var isPublishRepeat = ecoMarketClientPublishConfigDomainService.checkConfigRepeat(targetId, targetType,
                dataTypeEnum);
        if (isPublishRepeat) {
            var dataType = Optional.ofNullable(dataTypeEnum).map(EcoMarketDataTypeEnum::getCode).orElse(null);
            log.warn("Sharing eco-market fetched config is forbidden: targetId={}, targetType={}, dataType={}", targetId, targetType, dataType);
            throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketCannotShareFromMarket);
        }

        // 获取配置json
        var configJson = ecoMarketClientConfigDomainService.obtainConfigJson(dataTypeEnum, model.getTargetId(),
                model.getTargetType(), model.getConfigParamJson());
        model.setConfigJson(configJson);

        // 应用页面
        if (Published.TargetType.Agent.name().equals(targetType) && Published.TargetSubType.PageApp.name().equals(targetSubType)) {
            String pageZipUrl = uploadPageZip(targetId, clientId, clientSecret, userContext);
            model.setPageZipUrl(pageZipUrl);
        }

        // 设置基本属性
        model.setShareStatus(EcoMarketShareStatusEnum.REVIEWING.getCode()); // 初始设置为审核中状态
        model.setCreateClientId(clientId); // 使用注册的客户端ID
        model.setOwnedFlag(EcoMarketOwnedFlagEnum.YES.getCode()); // 我的分享
        model.setUseStatus(EcoMarketUseStatusEnum.DISABLED.getCode()); // 启用状态

        // 先在本地保存
        if (Objects.isNull(uid)) {
            // 新增操作,生成新的uid
            uid = UUID.randomUUID().toString().replace("-", "");
            model.setUid(uid);
            // 新增配置
            ecoMarketClientConfigDomainService.addInfo(model, userContext);
        } else {
            // 更新配置
            ecoMarketClientConfigDomainService.updateInfoByUid(model, userContext);
        }

        // 重新查询最新数据
        model = ecoMarketClientConfigDomainService.queryOneByUid(uid);
        if (model == null) {
            throw EcoMarketException.build(BizExceptionCodeEnum.configNotFound, "配置保存失败");
        }

        // 构建请求DTO并调用服务器端接口
        ServerConfigSaveReqDTO reqDTO = EcoMarketClientConfigTranslator.toServerConfigSaveReqDTO(model, clientId,
                clientSecret);
        ServerConfigDetailRespDTO serverResponse = ecoMarketServerApiService.saveAndPublishServerConfig(reqDTO,
                ServerConfigDetailRespDTO.class);

        // 服务器端操作成功,更新本地状态
        if (serverResponse != null) {
            // 更新本地配置状态为审核中,发布时间和版本号
            model.setPublishTime(LocalDateTime.now());
            model.setVersionNumber(serverResponse.getVersionNumber()); // 使用服务器返回的版本号

            // 更新本地数据库
            ecoMarketClientConfigDomainService.updateInfoByUid(model, userContext);

            log.info("Publish config row updated: uid={}", uid);

            // 返回更新后的配置
            return ecoMarketClientConfigDomainService.queryOneByUid(uid);
        }

        throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketPublishConfigFailed, "保存并发布配置失败");
    }

    // 上传页面项目到生态市场server端
    private String uploadPageZip(Long agentId, String clientId, String clientSecret, UserContext userContext) {
        AgentComponentConfig config = agentComponentConfigRepository.getOne(Wrappers.<AgentComponentConfig>lambdaQuery()
                .eq(AgentComponentConfig::getAgentId, agentId)
                .eq(AgentComponentConfig::getType, AgentComponentConfig.Type.Page));
        if (Objects.isNull(config)) {
            throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketAgentPageRequiredForShare);
        }
        Long pageId = config.getTargetId();
        ReqResult<InputStream> result = customPageConfigApplicationService.exportProjectPublished(pageId, userContext);
        if (!result.isSuccess()) {
            throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketPageExportFailed);
        }
        
        InputStream inputStream = result.getData();
        if (inputStream == null) {
            throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketPageExportFailed);
        }
        
        try {
            byte[] bytes = inputStream.readAllBytes();
            String fileName = String.format("project_%s.zip", pageId);
            
            String url = ecoMarketServerApiService.uploadPageZip(bytes, fileName, "application/zip", clientId, clientSecret);
            if (url == null) {
                throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketPageExportFailed, "上传文件到服务器端失败");
            }
            return url;
        } catch (IOException e) {
            log.error("Upload page zip failed: agentId={}, pageId={}", agentId, pageId, e);
            throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketPageExportFailed, "读取文件流失败");
        } finally {
            try {
                if (inputStream != null) {
                    inputStream.close();
                }
            } catch (IOException e) {
                log.warn("Failed to close input stream", e);
            }
        }
    }

    @Override
    @DSTransactional(rollbackFor = Exception.class)
    public boolean deleteConfigByUid(String uid, ClientSecretDTO clientSecret, UserContext userContext) {
        log.info("Delete client config by uid: uid={}", uid);

        // 根据uid查询配置详情
        EcoMarketClientConfigModel configModel = queryOneByUid(uid);
        if (configModel == null) {
            throw EcoMarketException.build(BizExceptionCodeEnum.configNotFound);
        }
        // 限制是我的分享,是否我的分享,0:否(生态市场获取的);1:是(我的分享)
        if (Objects.equals(configModel.getOwnedFlag(), EcoMarketOwnedFlagEnum.NO.getCode())) {
            log.warn("Cannot delete eco-market-fetched config: uid={}, ownedFlag={}", uid, configModel.getOwnedFlag());
            throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketDeleteOnlyOwnShare);
        }


        // 直接调用ServerApiService的getDetailByUid方法获取数据
        ServerConfigDetailRespDTO serverData = ecoMarketServerApiService.getServerConfigDetail(
                uid, clientSecret.getClientId(), clientSecret.getClientSecret());


        // 远程生态市场有上架的对应配置,禁止删除我的分享
        if (Objects.nonNull(serverData) && Objects.equals(serverData.getShareStatus(), EcoMarketShareStatusEnum.PUBLISHED.getCode())) {
            log.warn("Cannot delete remote published config: uid={}, useStatus={}", uid, serverData.getUseStatus());
            throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketCannotDeleteRemotePublished);
        }

        // 删除配置
        ecoMarketClientConfigDomainService.deleteById(configModel.getId());

        return true;
    }

    @Override
    public EcoMarketClientConfigModel offlineConfigByUid(String uid, ClientSecretDTO clientSecret,
            UserContext userContext) {
        log.info("Offline client config by uid: uid={}", uid);

        // 获取客户端密钥
        if (clientSecret == null) {
            throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketClientSecretFetchFailed);
        }

        // 调用领域层下线配置
        ecoMarketClientConfigDomainService.offlineConfig(
                uid, clientSecret.getClientId(), clientSecret.getClientSecret(), userContext);
        // 查询详情并返回
        return this.queryOneByUid(uid);
    }

    @Override
    public void unpublishConfigByUid(String uid, ClientSecretDTO clientSecret, UserContext userContext) {

        ecoMarketClientConfigDomainService.unpublishConfig(uid, clientSecret.getClientId(),
                clientSecret.getClientSecret(), userContext);
    }

    @Override
    @DSTransactional(rollbackFor = Exception.class)
    public EcoMarketClientConfigModel updateDraft(EcoMarketClientConfigModel model, UserContext userContext) {
        log.info("Update client config draft: uid={}, name={}", model.getUid(), model.getName());

        // 参数校验
        if (model == null || model.getUid() == null) {
            throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketModelAndUidRequired);
        }

        // 获取配置json
        var dataTypeEnum = EcoMarketDataTypeEnum.getByCode(model.getDataType());
        var configJson = ecoMarketClientConfigDomainService.obtainConfigJson(dataTypeEnum, model.getTargetId(),
                model.getTargetType(), model.getConfigParamJson());
        model.setConfigJson(configJson);

        var uid = model.getUid();

        // 根据UID查询配置
        EcoMarketClientConfigModel existingModel = ecoMarketClientConfigDomainService.queryOneByUid(uid);
        if (existingModel == null) {
            throw EcoMarketException.build(BizExceptionCodeEnum.configNotFound);
        }

        // 检查是否为草稿状态
        if (existingModel.getShareStatus() != EcoMarketShareStatusEnum.DRAFT.getCode() &&
                existingModel.getShareStatus() != EcoMarketShareStatusEnum.REJECTED.getCode()) {
            throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketUpdateOnlyDraftOrRejected);
        }

        // 检查是否有权限更新
        if (existingModel.getOwnedFlag() != EcoMarketOwnedFlagEnum.YES.getCode() ||
                !userContext.getTenantId().equals(existingModel.getTenantId())) {
            throw EcoMarketException.build(BizExceptionCodeEnum.permissionDenied);
        }

        // 保留原有ID和创建信息
        model.setId(existingModel.getId());
        model.setCreateClientId(existingModel.getCreateClientId());
        model.setCreated(existingModel.getCreated());
        model.setCreatorId(existingModel.getCreatorId());
        model.setCreatorName(existingModel.getCreatorName());

        // 确保是草稿状态
        model.setShareStatus(EcoMarketShareStatusEnum.DRAFT.getCode());

        // 调用领域服务更新配置
        Long id = ecoMarketClientConfigDomainService.updateDraft(model, userContext);

        // 查询详情并返回
        return queryOneInfoById(id);
    }

    @Override
    @DSTransactional(rollbackFor = Exception.class)
    public EcoMarketClientConfigModel saveDraft(EcoMarketClientConfigModel model, String clientId,
            UserContext userContext) {
        log.info("Create client config draft: name={}", model.getName());

        var targetId = model.getTargetId();
        var targetType = model.getTargetType();
        var dataTypeEnum = EcoMarketDataTypeEnum.getByCode(model.getDataType());

        // 再 ecoMarketClientPublishConfig 表里的,都是从生态市场里获取到的,禁止重复分享
        var isPublishRepeat = ecoMarketClientPublishConfigDomainService.checkConfigRepeat(targetId, targetType,
                dataTypeEnum);
        if (isPublishRepeat) {
            var dataType = Optional.ofNullable(dataTypeEnum).map(EcoMarketDataTypeEnum::getCode).orElse(null);
            log.warn("Sharing eco-market fetched config is forbidden: targetId={}, targetType={}, dataType={}", targetId, targetType, dataType);
            throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketCannotShareFromMarket);
        }

        // 获取配置json
        var configJson = ecoMarketClientConfigDomainService.obtainConfigJson(dataTypeEnum, model.getTargetId(),
                model.getTargetType(), model.getConfigParamJson());
        model.setConfigJson(configJson);
        // 设置基本属性
        model.setUid(UUID.randomUUID().toString().replace("-", "")); // 生成UID
        model.setVersionNumber(1L); // 初始版本号
        model.setShareStatus(EcoMarketShareStatusEnum.DRAFT.getCode()); // 草稿状态
        model.setOwnedFlag(EcoMarketOwnedFlagEnum.YES.getCode()); // 我的分享
        model.setTenantId(userContext.getTenantId());
        model.setCreateClientId(clientId); // 设置客户端ID
        model.setUseStatus(EcoMarketUseStatusEnum.ENABLED.getCode());

        // 调用领域服务添加配置
        Long id = ecoMarketClientConfigDomainService.addInfo(model, userContext);

        // 查询详情并返回
        return queryOneInfoById(id);
    }

}
