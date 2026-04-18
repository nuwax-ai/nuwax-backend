package com.xspaceagi.eco.market.spec.infra.dao.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.eco.market.spec.infra.dao.entity.EcoMarketClientSecret;
import com.xspaceagi.eco.market.spec.infra.dao.mapper.EcoMarketClientSecretMapper;
import com.xspaceagi.eco.market.spec.infra.dao.service.EcoMarketClientSecretService;
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
* @description 针对表【eco_market_client_secret(生态市场,客户端端配置)】的数据库操作Service实现
* @createDate 2025-05-26 17:08:22
*/
@Service
public class EcoMarketClientSecretServiceImpl extends ServiceImpl<EcoMarketClientSecretMapper, EcoMarketClientSecret>
    implements EcoMarketClientSecretService {

    @Lazy
    @Resource
    private EcoMarketClientSecretServiceImpl self;

    @Override
    public List<EcoMarketClientSecret> queryListByIds(List<Long> ids) {
        LambdaQueryWrapper<EcoMarketClientSecret> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(EcoMarketClientSecret::getYn, YnEnum.Y.getKey())
                .in(EcoMarketClientSecret::getId, ids);
        return this.list(queryWrapper);
    }

    @Override
    public EcoMarketClientSecret queryOneInfoById(Long id) {
        LambdaQueryWrapper<EcoMarketClientSecret> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(EcoMarketClientSecret::getYn, YnEnum.Y.getKey())
                .eq(EcoMarketClientSecret::getId, id);
        return this.getOne(queryWrapper);
    }

    @Override
    public Long updateInfo(EcoMarketClientSecret entity) {
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
    public Long addInfo(EcoMarketClientSecret entity) {
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
    public EcoMarketClientSecret getByTenantId(Long tenantId) {
        LambdaQueryWrapper<EcoMarketClientSecret> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(EcoMarketClientSecret::getTenantId, tenantId);
        queryWrapper.eq(EcoMarketClientSecret::getYn, 1); // 只查询有效记录
        return getOne(queryWrapper);
    }

    @Override
    public EcoMarketClientSecret getByClientId(String clientId) {
        LambdaQueryWrapper<EcoMarketClientSecret> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(EcoMarketClientSecret::getClientId, clientId);
        queryWrapper.eq(EcoMarketClientSecret::getYn, 1); // 只查询有效记录
        return getOne(queryWrapper);
    }

    @Override
    public List<EcoMarketClientSecret> queryAllList() {
        LambdaQueryWrapper<EcoMarketClientSecret> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(EcoMarketClientSecret::getYn, YnEnum.Y.getKey());
        return this.list(queryWrapper);
    }
}




