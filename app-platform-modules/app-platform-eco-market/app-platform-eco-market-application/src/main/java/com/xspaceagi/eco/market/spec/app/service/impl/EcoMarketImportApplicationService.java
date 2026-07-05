package com.xspaceagi.eco.market.spec.app.service.impl;

import com.xspaceagi.eco.market.domain.model.EcoMarketImportRecordModel;
import com.xspaceagi.eco.market.domain.service.IEcoMarketImportRecordDomainService;
import com.xspaceagi.eco.market.spec.app.service.IEcoMarketImportApplicationService;
import jakarta.annotation.Resource;

import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class EcoMarketImportApplicationService implements IEcoMarketImportApplicationService {

    @Resource
    private IEcoMarketImportRecordDomainService ecoMarketImportRecordDomainService;

    @Override
    public Long addImportRecord(EcoMarketImportRecordModel model) {
        return ecoMarketImportRecordDomainService.addImportRecord(model);
    }

    @Override
    public EcoMarketImportRecordModel existsImportRecord(Long spaceId, String targetType, String ecoTargetId) {
        return ecoMarketImportRecordDomainService.existsImportRecord(spaceId, targetType, ecoTargetId);
    }

    @Override
    public void deleteImportRecord(Long id) {
        ecoMarketImportRecordDomainService.deleteImportRecord(id);
    }

    @Override
    public List<EcoMarketImportRecordModel> listImportRecords(Long userId, String targetType, Long targetId) {
        return ecoMarketImportRecordDomainService.listImportRecords(userId, targetType, targetId);
    }

    @Override
    public List<EcoMarketImportRecordModel> listImportRecordsByEcoTargetId(Long userId, String targetType, String ecoTargetId) {
        return ecoMarketImportRecordDomainService.listImportRecordsByEcoTargetId(userId, targetType, ecoTargetId);
    }
}
