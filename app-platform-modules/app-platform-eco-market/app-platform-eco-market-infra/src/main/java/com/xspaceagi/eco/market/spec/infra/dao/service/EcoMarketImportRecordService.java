package com.xspaceagi.eco.market.spec.infra.dao.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.xspaceagi.eco.market.spec.infra.dao.entity.EcoMarketImportRecord;

import java.util.List;

public interface EcoMarketImportRecordService extends IService<EcoMarketImportRecord> {

    Long addInfo(EcoMarketImportRecord entity);

    EcoMarketImportRecord getBySpaceIdAndTargetTypeAndEcoTargetId(Long spaceId, String targetType, String ecoTargetId);

    void deleteById(Long id);

    List<EcoMarketImportRecord> listByUserIdAndTargetTypeAndTargetId(Long userId, String targetType, Long targetId);

    List<EcoMarketImportRecord> listByUserIdAndTargetTypeAndEcoTargetId(Long userId, String targetType, String ecoTargetId);
}
