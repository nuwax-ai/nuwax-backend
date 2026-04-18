package com.xspaceagi.eco.market.spec.infra.repository;

import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.xspaceagi.eco.market.domain.model.EcoMarketClientConfigModel;
import com.xspaceagi.eco.market.domain.model.valueobj.QueryEcoMarketVo;
import com.xspaceagi.eco.market.domain.repository.IEcoMarketClientConfigRepository;
import com.xspaceagi.eco.market.spec.enums.EcoMarketDataTypeEnum;
import com.xspaceagi.eco.market.spec.infra.dao.entity.EcoMarketClientConfig;
import com.xspaceagi.eco.market.spec.infra.dao.mapper.EcoMarketClientConfigMapper;
import com.xspaceagi.eco.market.spec.infra.dao.service.EcoMarketClientConfigService;
import com.xspaceagi.eco.market.spec.infra.translator.IEcoMarketClientConfigTranslator;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.EcoMarketException;
import com.xspaceagi.system.spec.page.PageUtils;
import com.xspaceagi.system.spec.page.SuperPage;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Repository
public class EcoMarketClientConfigRepository implements IEcoMarketClientConfigRepository {

    @Resource
    private IEcoMarketClientConfigTranslator ecoMarketClientConfigTranslator;

    @Resource
    private EcoMarketClientConfigService ecoMarketClientConfigService;

    @Resource
    private EcoMarketClientConfigMapper ecoMarketClientConfigMapper;

    @Override
    public EcoMarketClientConfigModel queryOneInfoById(Long id) {
        var data = this.ecoMarketClientConfigService.queryOneInfoById(id);
        if (Objects.isNull(data)) {
            return null;
        }
        return this.ecoMarketClientConfigTranslator.convertToModel(data);
    }

    @Override
    public List<EcoMarketClientConfigModel> queryListByIds(List<Long> ids) {
        var dataList = this.ecoMarketClientConfigService.queryListByIds(ids);
        return dataList.stream()
                .map(ecoMarketClientConfigTranslator::convertToModel)
                .collect(Collectors.toList());
    }

    @DSTransactional(rollbackFor = Exception.class)
    @Override
    public void deleteById(Long id) {
        var existObj = this.ecoMarketClientConfigService.getById(id);
        if (existObj == null) {
            throw EcoMarketException.build(BizExceptionCodeEnum.configNotFound);
        }
        this.ecoMarketClientConfigService.deleteById(id);
    }

    @Override
    public Long updateInfo(EcoMarketClientConfigModel model, UserContext userContext) {
        var existObj = this.ecoMarketClientConfigService.queryOneInfoById(model.getId());
        if (Objects.isNull(existObj)) {
            throw EcoMarketException.build(BizExceptionCodeEnum.configNotFound);
        }

        model.setCreatorId(null);
        model.setCreatorName(null);
        model.setModified(null);
        model.setModifiedId(userContext.getUserId());
        model.setModifiedName(userContext.getUserName());
        var entity = this.ecoMarketClientConfigTranslator.convertToEntity(model);
        entity.setId(existObj.getId());
        return this.ecoMarketClientConfigService.updateInfo(entity);
    }

    @Override
    public Long updateInfoByUid(EcoMarketClientConfigModel model, UserContext userContext) {
        var existObj = this.ecoMarketClientConfigService.queryOneByUid(model.getUid());
        if (Objects.isNull(existObj)) {
            throw EcoMarketException.build(BizExceptionCodeEnum.configNotFound);
        }

        model.setCreatorId(null);
        model.setCreatorName(null);
        model.setModified(null);
        model.setModifiedId(userContext.getUserId());
        model.setModifiedName(userContext.getUserName());
        var entity = this.ecoMarketClientConfigTranslator.convertToEntity(model);
        entity.setId(existObj.getId());
        return this.ecoMarketClientConfigService.updateInfo(entity);
    }

    @Override
    public Long addInfo(EcoMarketClientConfigModel model, UserContext userContext) {
        model.setId(null);
        model.setCreatorId(userContext.getUserId());
        model.setCreatorName(userContext.getUserName());

        var entity = this.ecoMarketClientConfigTranslator.convertToEntity(model);
        var id = this.ecoMarketClientConfigService.addInfo(entity);
        model.setId(id);
        return id;
    }

    @Override
    public List<EcoMarketClientConfigModel> pageQuery(Map<String, Object> queryMap, List<OrderItem> orderColumns,
            Long startIndex, Long pageSize) {
        // 调用Mapper执行分页查询
        List<EcoMarketClientConfig> entityList = ecoMarketClientConfigMapper.queryList(queryMap, orderColumns,
                startIndex, pageSize);

        // 转换为领域模型
        if (CollectionUtils.isEmpty(entityList)) {
            return List.of();
        }

        return entityList.stream()
                .map(ecoMarketClientConfigTranslator::convertToModel)
                .collect(Collectors.toList());
    }

    @Override
    public Long queryTotal(Map<String, Object> queryMap) {
        // 调用Mapper获取总数
        return ecoMarketClientConfigMapper.queryTotal(queryMap);
    }

    @Override
    public EcoMarketClientConfigModel queryOneByUid(String uid) {
        EcoMarketClientConfig entity = ecoMarketClientConfigService.queryOneByUid(uid);

        if (entity == null) {
            return null;
        }

        return ecoMarketClientConfigTranslator.convertToModel(entity);
    }

    @Override
    public List<EcoMarketClientConfigModel> queryListByUids(List<String> uids) {
        List<EcoMarketClientConfig> entityList = ecoMarketClientConfigService.queryListByUids(uids);

        if (CollectionUtils.isEmpty(entityList)) {
            return List.of();
        }

        return entityList.stream()
                .map(ecoMarketClientConfigTranslator::convertToModel)
                .collect(Collectors.toList());
    }

    @Override
    @DSTransactional(rollbackFor = Exception.class)
    public boolean updateShareStatusByUid(String uid, Integer shareStatus, String approveMessage, UserContext userContext) {
        log.info("Update share status by uid: uid={}, shareStatus={}", uid, shareStatus);

        if (uid == null || uid.isEmpty()) {
            throw EcoMarketException.build(BizExceptionCodeEnum.fieldRequiredButEmpty, "配置UID");
        }

        if (shareStatus == null) {
            throw EcoMarketException.build(BizExceptionCodeEnum.fieldRequiredButEmpty, "分享状态");
        }

        Long modifiedId = null;
        if (userContext != null && userContext.getUserId() != null) {
            modifiedId = userContext.getUserId();
        }

        boolean result = ecoMarketClientConfigService.updateShareStatusByUid(uid, shareStatus, approveMessage, modifiedId);

        log.info("Share status update result: uid={}, shareStatus={}, result={}", uid, shareStatus, result);

        return result;
    }

    @Override
    public IPage<EcoMarketClientConfigModel> pageQuery(QueryEcoMarketVo queryEcoMarketVo, long current, long size) {
        var queryMap = new HashMap<String, Object>();
        queryMap.put("dataType", queryEcoMarketVo.getDataType());
        queryMap.put("categoryCode", queryEcoMarketVo.getCategoryCode());
        queryMap.put("name", queryEcoMarketVo.getName());
        queryMap.put("shareStatus", queryEcoMarketVo.getShareStatus());
        queryMap.put("useStatus", queryEcoMarketVo.getUseStatus());
        queryMap.put("ownedFlag", queryEcoMarketVo.getOwnedFlag());
        queryMap.put("targetType", queryEcoMarketVo.getTargetType());
        queryMap.put("targetSubType", queryEcoMarketVo.getTargetSubType());

        List<OrderItem> orderColumns = new ArrayList<>();
        var startIndex = PageUtils.getStartIndex(current, size);
        var pageSize = PageUtils.getEndIndex(current, size);

        List<EcoMarketClientConfig> entityList = ecoMarketClientConfigMapper.queryList(queryMap, orderColumns,
                startIndex, pageSize);
        var total = ecoMarketClientConfigMapper.queryTotal(queryMap);
        var page = new SuperPage<>(current, size, total, entityList);
        return page.convert(ecoMarketClientConfigTranslator::convertToModel);
    }

    @Override
    public void deleteByUid(String uid) {
        ecoMarketClientConfigService.deleteByUid(uid);
    }

    @Override
    public Long queryTotalMyShare() {
        return ecoMarketClientConfigService.queryTotalMyShare();
    }

    @Override
    public boolean checkConfigRepeat(Long targetId, String targetType, EcoMarketDataTypeEnum dataTypeEnum, String uid) {
        return ecoMarketClientConfigService.checkConfigRepeat(targetId, targetType, dataTypeEnum, uid);
    }

    @Override
    public List<EcoMarketClientConfigModel> queryMyShareAndReviewing() {
        List<EcoMarketClientConfig> entityList = ecoMarketClientConfigService.queryMyShareAndReviewing();
        return entityList.stream()
                .map(ecoMarketClientConfigTranslator::convertToModel)
                .collect(Collectors.toList());
    }
}
