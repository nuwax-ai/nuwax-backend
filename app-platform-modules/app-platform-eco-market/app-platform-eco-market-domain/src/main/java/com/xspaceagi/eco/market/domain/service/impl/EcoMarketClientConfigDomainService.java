package com.xspaceagi.eco.market.domain.service.impl;

import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xspaceagi.agent.core.sdk.enums.TargetTypeEnum;
import com.xspaceagi.eco.market.domain.adaptor.IEcoMarketAdaptor;
import com.xspaceagi.eco.market.domain.client.EcoMarketServerApiService;
import com.xspaceagi.eco.market.domain.model.EcoMarketClientConfigModel;
import com.xspaceagi.eco.market.domain.model.valueobj.QueryEcoMarketVo;
import com.xspaceagi.eco.market.domain.repository.IEcoMarketClientConfigRepository;
import com.xspaceagi.eco.market.domain.repository.IEcoMarketClientPublishConfigRepository;
import com.xspaceagi.eco.market.domain.service.IEcoMarketClientConfigDomainService;
import com.xspaceagi.eco.market.spec.enums.EcoMarketDataTypeEnum;
import com.xspaceagi.eco.market.spec.enums.EcoMarketOwnedFlagEnum;
import com.xspaceagi.eco.market.spec.enums.EcoMarketShareStatusEnum;
import com.xspaceagi.eco.market.spec.enums.EcoMarketUseStatusEnum;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.EcoMarketException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
public class EcoMarketClientConfigDomainService implements IEcoMarketClientConfigDomainService {

    @Resource
    private IEcoMarketClientConfigRepository ecoMarketClientConfigRepository;

    @Resource
    private IEcoMarketClientPublishConfigRepository ecoMarketClientPublishConfigRepository;

    @Resource
    private EcoMarketServerApiService ecoMarketServerApiService;

    @Resource
    private IEcoMarketAdaptor ecoMarketAdaptor;

    @Override
    public EcoMarketClientConfigModel queryOneInfoById(Long id) {
        return this.ecoMarketClientConfigRepository.queryOneInfoById(id);
    }

    @Override
    public List<EcoMarketClientConfigModel> queryListByIds(List<Long> ids) {
        return this.ecoMarketClientConfigRepository.queryListByIds(ids);
    }

    @DSTransactional(rollbackFor = Exception.class)
    @Override
    public void deleteById(Long id) {
        this.ecoMarketClientConfigRepository.deleteById(id);
    }

    @Override
    public Long updateInfo(EcoMarketClientConfigModel model, UserContext userContext) {
        return this.ecoMarketClientConfigRepository.updateInfo(model, userContext);
    }

    @Override
    public Long updateInfoByUid(EcoMarketClientConfigModel model, UserContext userContext) {
        return this.ecoMarketClientConfigRepository.updateInfoByUid(model, userContext);
    }

    @DSTransactional(rollbackFor = Exception.class)
    @Override
    public Long addInfo(EcoMarketClientConfigModel model, UserContext userContext) {
        return this.ecoMarketClientConfigRepository.addInfo(model, userContext);
    }

    @Override
    public IPage<EcoMarketClientConfigModel> pageQuery(Map<String, Object> queryParams, long current, long size) {
        // 查询总数
        long total = this.ecoMarketClientConfigRepository.queryTotal(queryParams);

        // 查询分页数据
        List<OrderItem> orderItems = null; // 默认排序
        long startIndex = (current - 1) * size;
        List<EcoMarketClientConfigModel> records = this.ecoMarketClientConfigRepository
                .pageQuery(queryParams, orderItems, startIndex, size);

        // 构建分页结果
        Page<EcoMarketClientConfigModel> page = new Page<>(current, size, total);
        page.setRecords(records);

        return page;
    }

    @Override
    public EcoMarketClientConfigModel queryOneByUid(String uid) {
        return this.ecoMarketClientConfigRepository.queryOneByUid(uid);
    }

    @Override
    public List<EcoMarketClientConfigModel> queryListByUids(List<String> uids) {
        log.info("Query client configs by uids: uids={}", uids);
        if (CollectionUtils.isEmpty(uids)) {
            return List.of();
        }
        return this.ecoMarketClientConfigRepository.queryListByUids(uids);
    }

    @Override
    @DSTransactional(rollbackFor = Exception.class)
    public void offlineConfig(String uid, String clientId, String clientSecret,
            UserContext userContext) {
        // 检查参数
        if (uid == null || uid.isEmpty()) {
            log.error("uid cannot be empty");
            throw EcoMarketException.build(BizExceptionCodeEnum.fieldRequiredButEmpty, "配置UID");
        }

        log.info("Offline client config: uid={}, clientId={}", uid, clientId);

        // 查询配置详情
        EcoMarketClientConfigModel configModel = queryOneByUid(uid);
        if (configModel == null) {
            log.error("Config not found, uid={}", uid);
            throw EcoMarketException.build(BizExceptionCodeEnum.configNotFound);
        }

        // 检查是否可以下线，只有已发布状态可以下线
        if (!(Objects.equals(configModel.getShareStatus(), EcoMarketShareStatusEnum.PUBLISHED.getCode())
                || Objects.equals(configModel.getShareStatus(), EcoMarketShareStatusEnum.REVIEWING.getCode()))) {
            log.error("Current state does not allow offline, uid={}", uid);
            throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketOfflineOnlyPublishedOrReviewing);
        }

        // 调用服务器端下线接口
        boolean offlineSuccess = ecoMarketServerApiService.offlineServerConfig(
                uid, clientId, clientSecret);

        if (!offlineSuccess) {
            log.error("Offline server config failed");
            throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketOfflineServerConfigFailed);
        }

        // 修改状态为已下线
        configModel.setShareStatus(EcoMarketShareStatusEnum.OFFLINE.getCode()); // 已下线状态
        configModel.setOfflineTime(LocalDateTime.now());

        // 更新配置
        this.ecoMarketClientConfigRepository.updateInfo(configModel, userContext);

    }

    @Override
    public void unpublishConfig(String uid, String clientId, String clientSecret, UserContext userContext) {
        // 检查参数
        if (uid == null || uid.isEmpty()) {
            log.error("uid cannot be empty");
            throw EcoMarketException.build(BizExceptionCodeEnum.fieldRequiredButEmpty, "配置UID");
        }

        log.info("Revoke publish client config: uid={}, clientId={}", uid, clientId);

        // 查询配置详情
        EcoMarketClientConfigModel configModel = queryOneByUid(uid);
        if (configModel == null) {
            log.error("Config not found, uid={}", uid);
            throw EcoMarketException.build(BizExceptionCodeEnum.configNotFound);
        }

        // 检查是否可以下线，只有已发布状态可以下线
        if (!(Objects.equals(configModel.getShareStatus(), EcoMarketShareStatusEnum.REVIEWING.getCode()))) {
            log.error("Current state does not allow revoke publish, uid={}", uid);
            throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketRevokeOnlyReviewing);
        }

        // 调用服务器端下线接口
        boolean unpublishSuccess = ecoMarketServerApiService.unpublishServerConfig(
                uid, clientId, clientSecret);

        if (!unpublishSuccess) {
            log.error("Revoke publish server config failed");
            throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketOfflineServerConfigFailed);
        }

        // 查询生态市场的数据,如果有数据,则回滚状态为:已发布,否则回滚状态为:草稿
        var ecoMarketData = ecoMarketServerApiService.getServerConfigDetail(uid, clientId, clientSecret);
        if (ecoMarketData != null) {
            configModel.setShareStatus(EcoMarketShareStatusEnum.PUBLISHED.getCode());
        } else {
            configModel.setShareStatus(EcoMarketShareStatusEnum.DRAFT.getCode());
        }

        // 更新配置
        this.ecoMarketClientConfigRepository.updateInfo(configModel, userContext);

    }

    @Override
    @DSTransactional(rollbackFor = Exception.class)
    public Long updateDraft(EcoMarketClientConfigModel model, UserContext userContext) {
        log.info("Update client config draft: uid={}, name={}", model.getUid(), model.getName());

        // 检查参数
        if (model == null || model.getId() == null) {
            throw new IllegalArgumentException("Configuration model cannot be empty and ID must be specified");
        }

        // 确保状态为草稿
        model.setShareStatus(EcoMarketShareStatusEnum.DRAFT.getCode());

        // 设置修改时间
        model.setModified(java.time.LocalDateTime.now());

        // 更新配置
        return this.ecoMarketClientConfigRepository.updateInfo(model, userContext);
    }

    @Override
    @DSTransactional(rollbackFor = Exception.class)
    public boolean updateShareStatusByUid(String uid, Integer shareStatus, String approveMessage,
            UserContext userContext) {
        log.info("Update client config share status: uid={}, shareStatus={}", uid, shareStatus);

        // 参数校验
        if (uid == null || uid.isEmpty()) {
            throw EcoMarketException.build(BizExceptionCodeEnum.fieldRequiredButEmpty, "配置UID");
        }

        if (shareStatus == null) {
            throw EcoMarketException.build(BizExceptionCodeEnum.fieldRequiredButEmpty, "分享状态");
        }

        // 检查配置是否存在
        EcoMarketClientConfigModel existingConfig = this.ecoMarketClientConfigRepository.queryOneByUid(uid);
        if (existingConfig == null) {
            throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketConfigInfoNotFound);
        }

        // 调用repository层更新分享状态
        boolean result = this.ecoMarketClientConfigRepository.updateShareStatusByUid(uid, shareStatus, approveMessage,
                userContext);

        if (result) {
            log.info("Share status update OK: uid={}, shareStatus={}", uid, shareStatus);
        } else {
            log.warn("Share status update failed: uid={}, shareStatus={}", uid, shareStatus);
        }

        return result;
    }

    @Override
    public String obtainConfigJson(EcoMarketDataTypeEnum dataTypeEnum, Long targetId, String targetType,
            String configParamJson) {

        // check targetId and targetType
        if (targetId == null || targetType == null) {
            throw EcoMarketException.build(BizExceptionCodeEnum.fieldRequiredButEmpty, "目标ID与目标类型");
        }

        switch (dataTypeEnum) {
            case PLUGIN:
                return this.ecoMarketAdaptor.queryPluginConfig(targetId, configParamJson);
            case TEMPLATE:
                return this.ecoMarketAdaptor.queryTemplateConfig(TargetTypeEnum.valueOf(targetType), targetId);
            case MCP:
                throw new UnsupportedOperationException("MCP数据类型不支持");
            default:
                throw new UnsupportedOperationException("不支持的数据类型");
        }

    }

    @Override
    public IPage<EcoMarketClientConfigModel> pageQueryEnabled(QueryEcoMarketVo queryEcoMarketVo, long current,
            long size) {
        log.info("Paged query enabled configs: current={}, size={}, dataType={}", current, size, queryEcoMarketVo.getDataType());

        // 构建查询参数
        queryEcoMarketVo.setUseStatus(EcoMarketUseStatusEnum.ENABLED.getCode());
        queryEcoMarketVo.setOwnedFlag(EcoMarketOwnedFlagEnum.NO.getCode());

        var page = this.ecoMarketClientConfigRepository.pageQuery(queryEcoMarketVo, current, size);
        return page;
    }

    @Override
    public IPage<EcoMarketClientConfigModel> pageQueryMyShare(QueryEcoMarketVo queryEcoMarketVo, long current,
            long size) {

        // 构建查询参数
        queryEcoMarketVo.setOwnedFlag(EcoMarketOwnedFlagEnum.YES.getCode());

        var page = this.ecoMarketClientConfigRepository.pageQuery(queryEcoMarketVo, current, size);
        return page;
    }

    @Override
    public void deleteByUid(String uid) {
        this.ecoMarketClientConfigRepository.deleteByUid(uid);
    }

    @Override
    public Long queryTotalMyShare() {
        return this.ecoMarketClientConfigRepository.queryTotalMyShare();
    }

    @Override
    public boolean checkConfigRepeat(Long targetId, String targetType, EcoMarketDataTypeEnum dataTypeEnum, String uid) {
        return this.ecoMarketClientConfigRepository.checkConfigRepeat(targetId, targetType, dataTypeEnum, uid);
    }

    @Override
    public List<EcoMarketClientConfigModel> queryMyShareAndReviewing() {
        return this.ecoMarketClientConfigRepository.queryMyShareAndReviewing();
    }

}
