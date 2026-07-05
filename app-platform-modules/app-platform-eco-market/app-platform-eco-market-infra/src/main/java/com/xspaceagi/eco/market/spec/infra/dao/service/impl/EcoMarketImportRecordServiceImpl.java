package com.xspaceagi.eco.market.spec.infra.dao.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.eco.market.spec.infra.dao.entity.EcoMarketImportRecord;
import com.xspaceagi.eco.market.spec.infra.dao.mapper.EcoMarketImportRecordMapper;
import com.xspaceagi.eco.market.spec.infra.dao.service.EcoMarketImportRecordService;
import jakarta.annotation.Resource;

import java.util.List;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
public class EcoMarketImportRecordServiceImpl extends ServiceImpl<EcoMarketImportRecordMapper, EcoMarketImportRecord>
        implements EcoMarketImportRecordService {

    @Lazy
    @Resource
    private EcoMarketImportRecordServiceImpl self;

    @Override
    public Long addInfo(EcoMarketImportRecord entity) {
        entity.setId(null);
        entity.setCreated(null);
        entity.setModified(null);
        self.save(entity);
        return entity.getId();
    }

    @Override
    public EcoMarketImportRecord getBySpaceIdAndTargetTypeAndEcoTargetId(Long spaceId, String targetType, String ecoTargetId) {
        LambdaQueryWrapper<EcoMarketImportRecord> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(EcoMarketImportRecord::getSpaceId, spaceId)
                .eq(EcoMarketImportRecord::getTargetType, targetType)
                .eq(EcoMarketImportRecord::getEcoTargetId, ecoTargetId);
        return getOne(queryWrapper, false);
    }

    @Override
    public void deleteById(Long id) {
        self.removeById(id);
    }

    @Override
    public List<EcoMarketImportRecord> listByUserIdAndTargetTypeAndTargetId(Long userId, String targetType, Long targetId) {
        LambdaQueryWrapper<EcoMarketImportRecord> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(EcoMarketImportRecord::getUserId, userId)
                .eq(EcoMarketImportRecord::getTargetType, targetType)
                .eq(EcoMarketImportRecord::getTargetId, targetId);
        return list(queryWrapper);
    }

    @Override
    public List<EcoMarketImportRecord> listByUserIdAndTargetTypeAndEcoTargetId(Long userId, String targetType, String ecoTargetId) {
        LambdaQueryWrapper<EcoMarketImportRecord> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(EcoMarketImportRecord::getUserId, userId)
                .eq(EcoMarketImportRecord::getTargetType, targetType)
                .eq(EcoMarketImportRecord::getEcoTargetId, ecoTargetId);
        return list(queryWrapper);
    }
}
