package com.xspaceagi.eco.market.domain.service.impl;

import com.xspaceagi.eco.market.domain.model.EcoMarketImportRecordModel;
import com.xspaceagi.eco.market.domain.repository.IEcoMarketImportRecordRepository;
import com.xspaceagi.eco.market.domain.service.IEcoMarketImportRecordDomainService;
import jakarta.annotation.Resource;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class EcoMarketImportRecordDomainService implements IEcoMarketImportRecordDomainService {

    @Resource
    private IEcoMarketImportRecordRepository ecoMarketImportRecordRepository;

    @Override
    public Long addImportRecord(EcoMarketImportRecordModel model) {
        return ecoMarketImportRecordRepository.add(model);
    }

    @Override
    public EcoMarketImportRecordModel existsImportRecord(Long spaceId, String targetType, String ecoTargetId) {
        return ecoMarketImportRecordRepository.existsBySpaceIdAndTargetTypeAndEcoTargetId(spaceId, targetType, ecoTargetId);
    }

    @Override
    public void deleteImportRecord(Long id) {
        ecoMarketImportRecordRepository.deleteById(id);
    }

    @Override
    public List<EcoMarketImportRecordModel> listImportRecords(Long userId, String targetType, Long targetId) {
        return ecoMarketImportRecordRepository.listByUserIdAndTargetTypeAndTargetId(userId, targetType, targetId);
    }

    @Override
    public List<EcoMarketImportRecordModel> listImportRecordsByEcoTargetId(Long userId, String targetType, String ecoTargetId) {
        return ecoMarketImportRecordRepository.listByUserIdAndTargetTypeAndEcoTargetId(userId, targetType, ecoTargetId);
    }
}
