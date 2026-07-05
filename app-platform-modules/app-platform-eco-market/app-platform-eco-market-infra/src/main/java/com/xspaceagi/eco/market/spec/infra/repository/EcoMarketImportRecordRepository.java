package com.xspaceagi.eco.market.spec.infra.repository;

import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.xspaceagi.eco.market.domain.model.EcoMarketImportRecordModel;
import com.xspaceagi.eco.market.domain.repository.IEcoMarketImportRecordRepository;
import com.xspaceagi.eco.market.spec.infra.dao.entity.EcoMarketImportRecord;
import com.xspaceagi.eco.market.spec.infra.dao.service.EcoMarketImportRecordService;
import com.xspaceagi.eco.market.spec.infra.translator.IEcoMarketImportRecordTranslator;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Repository
public class EcoMarketImportRecordRepository implements IEcoMarketImportRecordRepository {

    @Resource
    private IEcoMarketImportRecordTranslator ecoMarketImportRecordTranslator;

    @Resource
    private EcoMarketImportRecordService ecoMarketImportRecordService;

    @Override
    @DSTransactional(rollbackFor = Exception.class)
    public Long add(EcoMarketImportRecordModel model) {
        model.setId(null);
        var entity = ecoMarketImportRecordTranslator.convertToEntity(model);
        return ecoMarketImportRecordService.addInfo(entity);
    }

    @Override
    public EcoMarketImportRecordModel existsBySpaceIdAndTargetTypeAndEcoTargetId(Long spaceId, String targetType, String ecoTargetId) {
        var data = ecoMarketImportRecordService.getBySpaceIdAndTargetTypeAndEcoTargetId(spaceId, targetType, ecoTargetId);
        if (data == null) {
            return null;
        }
        return ecoMarketImportRecordTranslator.convertToModel(data);
    }

    @Override
    @DSTransactional(rollbackFor = Exception.class)
    public void deleteById(Long id) {
        var existObj = ecoMarketImportRecordService.getById(id);
        if (existObj == null) {
            return;
        }
        ecoMarketImportRecordService.deleteById(id);
    }

    @Override
    public List<EcoMarketImportRecordModel> listByUserIdAndTargetTypeAndTargetId(Long userId, String targetType, Long targetId) {
        var dataList = ecoMarketImportRecordService.listByUserIdAndTargetTypeAndTargetId(userId, targetType, targetId);
        return dataList.stream()
                .map(ecoMarketImportRecordTranslator::convertToModel)
                .collect(Collectors.toList());
    }

    @Override
    public List<EcoMarketImportRecordModel> listByUserIdAndTargetTypeAndEcoTargetId(Long userId, String targetType, String ecoTargetId) {
        var dataList = ecoMarketImportRecordService.listByUserIdAndTargetTypeAndEcoTargetId(userId, targetType, ecoTargetId);
        return dataList.stream()
                .map(ecoMarketImportRecordTranslator::convertToModel)
                .collect(Collectors.toList());
    }
}
