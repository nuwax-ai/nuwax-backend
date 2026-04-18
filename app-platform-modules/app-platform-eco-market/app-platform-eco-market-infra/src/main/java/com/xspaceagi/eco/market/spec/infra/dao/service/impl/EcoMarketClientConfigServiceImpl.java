package com.xspaceagi.eco.market.spec.infra.dao.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.eco.market.spec.enums.EcoMarketDataTypeEnum;
import com.xspaceagi.eco.market.spec.enums.EcoMarketOwnedFlagEnum;
import com.xspaceagi.eco.market.spec.enums.EcoMarketShareStatusEnum;
import com.xspaceagi.eco.market.spec.infra.dao.entity.EcoMarketClientConfig;
import com.xspaceagi.eco.market.spec.infra.dao.mapper.EcoMarketClientConfigMapper;
import com.xspaceagi.eco.market.spec.infra.dao.service.EcoMarketClientConfigService;
import com.xspaceagi.system.spec.enums.YnEnum;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.KnowledgeException;
import jakarta.annotation.Resource;

import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * @author soddy
 * @description 针对表【eco_market_client_config(生态市场配置)】的数据库操作Service实现
 * @createDate 2025-05-26 17:08:22
 */
@Service
public class EcoMarketClientConfigServiceImpl extends ServiceImpl<EcoMarketClientConfigMapper, EcoMarketClientConfig>
        implements EcoMarketClientConfigService {


    @Lazy
    @Resource
    private EcoMarketClientConfigServiceImpl self;

    @Override
    public List<EcoMarketClientConfig> queryListByIds(List<Long> ids) {
        LambdaQueryWrapper<EcoMarketClientConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(EcoMarketClientConfig::getYn, YnEnum.Y.getKey())
                .in(EcoMarketClientConfig::getId, ids);
        return this.list(queryWrapper);
    }

    @Override
    public EcoMarketClientConfig queryOneInfoById(Long id) {
        LambdaQueryWrapper<EcoMarketClientConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(EcoMarketClientConfig::getYn, YnEnum.Y.getKey())
                .eq(EcoMarketClientConfig::getId, id);
        return this.getOne(queryWrapper);
    }

    @Override
    public Long updateInfo(EcoMarketClientConfig entity) {
        var updateObj = this.getById(entity.getId());

        if (Objects.isNull(updateObj)) {
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }
        entity.setCreated(null);
        entity.setModified(null);

        this.self.updateById(entity);

        return entity.getId();
    }

    @Override
    public Long addInfo(EcoMarketClientConfig entity) {
        entity.setId(null);
        entity.setCreated(null);
        entity.setModified(null);

        this.self.save(entity);

        return entity.getId();
    }

    @Override
    public void deleteById(Long id) {
        this.self.removeById(id);
    }

    @Override
    public EcoMarketClientConfig queryOneByUid(String uid) {
        if (uid == null || uid.isEmpty()) {
            return null;
        }

        LambdaQueryWrapper<EcoMarketClientConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(EcoMarketClientConfig::getUid, uid);
        queryWrapper.eq(EcoMarketClientConfig::getYn, YnEnum.Y.getKey());

        return this.getOne(queryWrapper);
    }

    @Override
    public List<EcoMarketClientConfig> queryListByUids(List<String> uids) {
        if (CollectionUtils.isEmpty(uids)) {
            return List.of();
        }

        LambdaQueryWrapper<EcoMarketClientConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(EcoMarketClientConfig::getUid, uids);
        queryWrapper.eq(EcoMarketClientConfig::getYn, YnEnum.Y.getKey());

        return this.list(queryWrapper);
    }

    @Override
    public boolean updateShareStatusByUid(String uid, Integer shareStatus, String approveMessage, Long modifiedId) {
        if (uid == null || uid.isEmpty() || shareStatus == null) {
            return false;
        }

        LambdaQueryWrapper<EcoMarketClientConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(EcoMarketClientConfig::getUid, uid);

        EcoMarketClientConfig updateEntity = new EcoMarketClientConfig();
        updateEntity.setShareStatus(shareStatus);
        updateEntity.setModified(LocalDateTime.now());
        updateEntity.setApproveMessage(approveMessage);

        if (modifiedId != null) {
            updateEntity.setModifiedId(modifiedId);
        }

        return this.self.update(updateEntity, queryWrapper);
    }

    @Override
    public void deleteByUid(String uid) {
        LambdaQueryWrapper<EcoMarketClientConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(EcoMarketClientConfig::getUid, uid);
        this.self.remove(queryWrapper);
    }

    @Override
    public Long queryTotalMyShare() {
        LambdaQueryWrapper<EcoMarketClientConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(EcoMarketClientConfig::getYn, YnEnum.Y.getKey());
        queryWrapper.eq(EcoMarketClientConfig::getOwnedFlag, EcoMarketOwnedFlagEnum.YES.getCode());
        return this.count(queryWrapper);
    }

    @Override
    public boolean checkConfigRepeat(Long targetId, String targetType, EcoMarketDataTypeEnum dataTypeEnum, String uid) {
        LambdaQueryWrapper<EcoMarketClientConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(EcoMarketClientConfig::getYn, YnEnum.Y.getKey());
        queryWrapper.eq(EcoMarketClientConfig::getTargetId, targetId);
        queryWrapper.eq(EcoMarketClientConfig::getTargetType, targetType);
        queryWrapper.eq(EcoMarketClientConfig::getDataType, dataTypeEnum.getCode());
        queryWrapper.eq(EcoMarketClientConfig::getOwnedFlag, EcoMarketOwnedFlagEnum.YES.getCode());

        // 排除自身(如果uid不为空)
        if (StringUtils.isNotBlank(uid)) {
            queryWrapper.ne(EcoMarketClientConfig::getUid, uid);
        }

        // 限制发布的配置只能有一个
        queryWrapper.in(EcoMarketClientConfig::getShareStatus, EcoMarketShareStatusEnum.PUBLISHED.getCode(),
                EcoMarketShareStatusEnum.REJECTED.getCode(),
                EcoMarketShareStatusEnum.REVIEWING.getCode());

        return this.count(queryWrapper) > 0;
    }

    @Override
    public List<EcoMarketClientConfig> queryMyShareAndReviewing() {
        LambdaQueryWrapper<EcoMarketClientConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(EcoMarketClientConfig::getYn, YnEnum.Y.getKey());
        queryWrapper.eq(EcoMarketClientConfig::getOwnedFlag, EcoMarketOwnedFlagEnum.YES.getCode());
        queryWrapper.eq(EcoMarketClientConfig::getShareStatus, EcoMarketShareStatusEnum.REVIEWING.getCode());
        return this.list(queryWrapper);
    }

}
