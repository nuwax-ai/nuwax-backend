package com.xspaceagi.eco.market.spec.infra.repository;

import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.xspaceagi.eco.market.domain.model.EcoMarketClientPublishConfigModel;
import com.xspaceagi.eco.market.domain.model.valueobj.QueryEcoMarketVo;
import com.xspaceagi.eco.market.domain.repository.IEcoMarketClientPublishConfigRepository;
import com.xspaceagi.eco.market.spec.enums.EcoMarketDataTypeEnum;
import com.xspaceagi.eco.market.spec.infra.dao.entity.EcoMarketClientPublishConfig;
import com.xspaceagi.eco.market.spec.infra.dao.mapper.EcoMarketClientPublishConfigMapper;
import com.xspaceagi.eco.market.spec.infra.dao.service.EcoMarketClientPublishConfigService;
import com.xspaceagi.eco.market.spec.infra.translator.IEcoMarketClientPublishConfigTranslator;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.EcoMarketException;
import com.xspaceagi.system.spec.page.PageUtils;
import com.xspaceagi.system.spec.page.SuperPage;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Repository
public class EcoMarketClientPublishConfigRepository implements IEcoMarketClientPublishConfigRepository {

    @Resource
    private IEcoMarketClientPublishConfigTranslator ecoMarketClientPublishConfigTranslator;

    @Resource
    private EcoMarketClientPublishConfigService ecoMarketClientPublishConfigService;

    @Resource
    private EcoMarketClientPublishConfigMapper ecoMarketClientPublishConfigMapper;

    @Override
    public EcoMarketClientPublishConfigModel queryOneInfoById(Long id) {
        var data = this.ecoMarketClientPublishConfigService.queryOneInfoById(id);
        if (Objects.isNull(data)) {
            return null;
        }
        return this.ecoMarketClientPublishConfigTranslator.convertToModel(data);
    }

    @Override
    public List<EcoMarketClientPublishConfigModel> queryListByIds(List<Long> ids) {
        var dataList = this.ecoMarketClientPublishConfigService.queryListByIds(ids);
        return dataList.stream()
                .map(ecoMarketClientPublishConfigTranslator::convertToModel)
                .collect(Collectors.toList());
    }

    @DSTransactional(rollbackFor = Exception.class)
    @Override
    public void deleteById(Long id) {
        var existObj = this.ecoMarketClientPublishConfigService.getById(id);
        if (existObj == null) {
            throw EcoMarketException.build(BizExceptionCodeEnum.configNotFound);
        }
        this.ecoMarketClientPublishConfigService.deleteById(id);
    }

    @Override
    public Long updateInfo(EcoMarketClientPublishConfigModel model, UserContext userContext) {
        var existObj = this.ecoMarketClientPublishConfigService.queryOneInfoById(model.getId());
        if (Objects.isNull(existObj)) {
            throw EcoMarketException.build(BizExceptionCodeEnum.configNotFound);
        }

        model.setCreatorId(null);
        model.setCreatorName(null);
        model.setModified(null);
        model.setModifiedId(userContext.getUserId());
        model.setModifiedName(userContext.getUserName());
        var entity = this.ecoMarketClientPublishConfigTranslator.convertToEntity(model);
        entity.setId(existObj.getId());
        return this.ecoMarketClientPublishConfigService.updateInfo(entity);
    }

    @Override
    public Long addInfo(EcoMarketClientPublishConfigModel model, UserContext userContext) {
        model.setId(null);
        model.setCreatorId(userContext.getUserId());
        model.setCreatorName(userContext.getUserName());

        var entity = this.ecoMarketClientPublishConfigTranslator.convertToEntity(model);
        return this.ecoMarketClientPublishConfigService.addInfo(entity);
    }

    @Override
    public EcoMarketClientPublishConfigModel queryOneByUid(String uid) {
        if (uid == null || uid.isEmpty()) {
            return null;
        }

        var data = this.ecoMarketClientPublishConfigService.queryOneByUid(uid);
        if (data == null) {
            return null;
        }

        return this.ecoMarketClientPublishConfigTranslator.convertToModel(data);
    }

    @Override
    public IPage<EcoMarketClientPublishConfigModel> pageQuery(QueryEcoMarketVo queryEcoMarketVo, long current,
            long size) {
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

        List<EcoMarketClientPublishConfig> entityList = ecoMarketClientPublishConfigMapper.queryList(queryMap,
                orderColumns, startIndex, pageSize);
        var total = ecoMarketClientPublishConfigMapper.queryTotal(queryMap);
        var page = new SuperPage<>(current, size, total, entityList);
        return page.convert(this.ecoMarketClientPublishConfigTranslator::convertToModel);
    }

    @Override
    public void deleteByUid(String uid) {
        var existObj = this.ecoMarketClientPublishConfigService.queryOneByUid(uid);
        if (existObj == null) {
            throw EcoMarketException.build(BizExceptionCodeEnum.configNotFound);
        }
        this.ecoMarketClientPublishConfigService.deleteByUid(uid);
    }

    @Override
    public List<EcoMarketClientPublishConfigModel> queryListByUids(List<String> uids) {
        var dataList = this.ecoMarketClientPublishConfigService.queryListByUids(uids);
        return dataList.stream()
                .map(ecoMarketClientPublishConfigTranslator::convertToModel)
                .collect(Collectors.toList());
    }

    @Override
    public boolean checkConfigRepeat(Long targetId, String targetType, EcoMarketDataTypeEnum dataTypeEnum) {
        return this.ecoMarketClientPublishConfigService.checkConfigRepeat(targetId, targetType, dataTypeEnum);
    }

}
