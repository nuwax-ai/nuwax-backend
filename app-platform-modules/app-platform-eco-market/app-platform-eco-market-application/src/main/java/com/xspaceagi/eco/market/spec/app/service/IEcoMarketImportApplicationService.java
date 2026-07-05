package com.xspaceagi.eco.market.spec.app.service;

import com.xspaceagi.eco.market.domain.model.EcoMarketImportRecordModel;

import java.util.List;

public interface IEcoMarketImportApplicationService {

    /**
     * 新增导入记录
     */
    Long addImportRecord(EcoMarketImportRecordModel model);

    /**
     * 判断是否已导入（唯一条件：space_id + target_type + eco_target_id）
     */
    EcoMarketImportRecordModel existsImportRecord(Long spaceId, String targetType, String ecoTargetId);

    /**
     * 删除导入记录
     */
    void deleteImportRecord(Long id);

    /**
     * 查询用户对某个对象已导入的空间列表
     */
    List<EcoMarketImportRecordModel> listImportRecords(Long userId, String targetType, Long targetId);

    /**
     * 查询用户对某个生态对象已导入的空间列表
     */
    List<EcoMarketImportRecordModel> listImportRecordsByEcoTargetId(Long userId, String targetType, String ecoTargetId);
}
