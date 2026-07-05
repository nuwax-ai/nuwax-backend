package com.xspaceagi.eco.market.spec.infra.translator.impl;

import com.xspaceagi.eco.market.domain.model.EcoMarketImportRecordModel;
import com.xspaceagi.eco.market.spec.infra.dao.entity.EcoMarketImportRecord;
import com.xspaceagi.eco.market.spec.infra.translator.IEcoMarketImportRecordTranslator;
import org.springframework.stereotype.Component;

@Component
public class EcoMarketImportRecordTranslator implements IEcoMarketImportRecordTranslator {

    @Override
    public EcoMarketImportRecordModel convertToModel(EcoMarketImportRecord entity) {
        if (entity == null) {
            return null;
        }
        return EcoMarketImportRecordModel.builder()
                .id(entity.getId())
                .tenantId(entity.getTenantId())
                .userId(entity.getUserId())
                .spaceId(entity.getSpaceId())
                .targetType(entity.getTargetType())
                .targetId(entity.getTargetId())
                .ecoTargetId(entity.getEcoTargetId())
                .created(entity.getCreated())
                .modified(entity.getModified())
                .build();
    }

    @Override
    public EcoMarketImportRecord convertToEntity(EcoMarketImportRecordModel model) {
        if (model == null) {
            return null;
        }
        EcoMarketImportRecord entity = new EcoMarketImportRecord();
        entity.setId(model.getId());
        entity.setTenantId(model.getTenantId());
        entity.setUserId(model.getUserId());
        entity.setSpaceId(model.getSpaceId());
        entity.setTargetType(model.getTargetType());
        entity.setTargetId(model.getTargetId());
        entity.setEcoTargetId(model.getEcoTargetId());
        entity.setCreated(model.getCreated());
        entity.setModified(model.getModified());
        return entity;
    }
}
