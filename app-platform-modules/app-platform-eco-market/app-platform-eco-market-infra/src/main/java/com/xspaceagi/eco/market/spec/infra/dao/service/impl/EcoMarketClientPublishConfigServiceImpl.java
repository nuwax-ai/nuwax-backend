package com.xspaceagi.eco.market.spec.infra.dao.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.eco.market.spec.enums.EcoMarketDataTypeEnum;
import com.xspaceagi.eco.market.spec.infra.dao.entity.EcoMarketClientPublishConfig;
import com.xspaceagi.eco.market.spec.infra.dao.mapper.EcoMarketClientPublishConfigMapper;
import com.xspaceagi.eco.market.spec.infra.dao.service.EcoMarketClientPublishConfigService;
import com.xspaceagi.system.spec.enums.YnEnum;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.KnowledgeException;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * @author soddy
 * @description 针对表【eco_market_client_publish_config(生态市场,客户端,已发布配置)】的数据库操作Service实现
 * @createDate 2025-05-26 17:08:22
 */
@Service
public class EcoMarketClientPublishConfigServiceImpl
        extends ServiceImpl<EcoMarketClientPublishConfigMapper, EcoMarketClientPublishConfig>
        implements EcoMarketClientPublishConfigService {

    @Lazy
    @Resource
    private EcoMarketClientPublishConfigServiceImpl self;

    @Override
    public List<EcoMarketClientPublishConfig> queryListByIds(List<Long> ids) {
        LambdaQueryWrapper<EcoMarketClientPublishConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(EcoMarketClientPublishConfig::getYn, YnEnum.Y.getKey())
                .in(EcoMarketClientPublishConfig::getId, ids);
        return this.list(queryWrapper);
    }

    @Override
    public EcoMarketClientPublishConfig queryOneInfoById(Long id) {
        LambdaQueryWrapper<EcoMarketClientPublishConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(EcoMarketClientPublishConfig::getYn, YnEnum.Y.getKey())
                .eq(EcoMarketClientPublishConfig::getId, id);
        return this.getOne(queryWrapper);
    }

    @Override
    public EcoMarketClientPublishConfig queryOneByUid(String uid) {
        LambdaQueryWrapper<EcoMarketClientPublishConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(EcoMarketClientPublishConfig::getYn, YnEnum.Y.getKey())
                .eq(EcoMarketClientPublishConfig::getUid, uid);
        return this.getOne(queryWrapper);
    }

    @Override
    public Long updateInfo(EcoMarketClientPublishConfig entity) {
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
    public Long addInfo(EcoMarketClientPublishConfig entity) {
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
    public void deleteByUid(String uid) {
        LambdaQueryWrapper<EcoMarketClientPublishConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(EcoMarketClientPublishConfig::getUid, uid);
        this.self.remove(queryWrapper);
    }

    @Override
    public List<EcoMarketClientPublishConfig> queryListByUids(List<String> uids) {
        LambdaQueryWrapper<EcoMarketClientPublishConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(EcoMarketClientPublishConfig::getYn, YnEnum.Y.getKey())
                .in(EcoMarketClientPublishConfig::getUid, uids);
        return this.list(queryWrapper);
    }

    @Override
    public boolean checkConfigRepeat(Long targetId, String targetType, EcoMarketDataTypeEnum dataTypeEnum) {
        LambdaQueryWrapper<EcoMarketClientPublishConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(EcoMarketClientPublishConfig::getYn, YnEnum.Y.getKey());
        queryWrapper.eq(EcoMarketClientPublishConfig::getTargetId, targetId);
        queryWrapper.eq(EcoMarketClientPublishConfig::getTargetType, targetType);
        queryWrapper.eq(EcoMarketClientPublishConfig::getDataType, dataTypeEnum.getCode());

        return this.count(queryWrapper) > 0;
    }
}
