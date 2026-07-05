package com.xspaceagi.eco.market.domain.repository;

import com.xspaceagi.eco.market.domain.model.EcoMarketImportRecordModel;

import java.util.List;

public interface IEcoMarketImportRecordRepository {

    Long add(EcoMarketImportRecordModel model);

    EcoMarketImportRecordModel existsBySpaceIdAndTargetTypeAndEcoTargetId(Long spaceId, String targetType, String ecoTargetId);

    void deleteById(Long id);

    List<EcoMarketImportRecordModel> listByUserIdAndTargetTypeAndTargetId(Long userId, String targetType, Long targetId);

    List<EcoMarketImportRecordModel> listByUserIdAndTargetTypeAndEcoTargetId(Long userId, String targetType, String ecoTargetId);
}
