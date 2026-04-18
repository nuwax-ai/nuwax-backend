package com.xspaceagi.eco.market.spec.infra.repository;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import org.springframework.stereotype.Repository;

import com.xspaceagi.eco.market.domain.model.EcoMarketClientSecretModel;
import com.xspaceagi.eco.market.domain.repository.IEcoMarketClientSecretRepository;
import com.xspaceagi.eco.market.spec.infra.dao.service.EcoMarketClientSecretService;
import com.xspaceagi.eco.market.spec.infra.translator.IEcoMarketClientSecretTranslator;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.KnowledgeException;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Repository
public class EcoMarketClientSecretRepository implements IEcoMarketClientSecretRepository {

    @Resource
    private IEcoMarketClientSecretTranslator ecoMarketClientSecretTranslator;

    @Resource
    private EcoMarketClientSecretService ecoMarketClientSecretService;

    @Override
    public EcoMarketClientSecretModel queryOneInfoById(Long id) {
        var data = this.ecoMarketClientSecretService.queryOneInfoById(id);
        if (Objects.isNull(data)) {
            return null;
        }
        return this.ecoMarketClientSecretTranslator.convertToModel(data);
    }

    @Override
    public List<EcoMarketClientSecretModel> queryListByIds(List<Long> ids) {
        var dataList = this.ecoMarketClientSecretService.queryListByIds(ids);
        return dataList.stream()
                .map(ecoMarketClientSecretTranslator::convertToModel)
                .collect(Collectors.toList());
    }

    @DSTransactional(rollbackFor = Exception.class)
    @Override
    public void deleteById(Long id) {
        var existObj = this.ecoMarketClientSecretService.getById(id);
        if (existObj == null) {
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }
        this.ecoMarketClientSecretService.deleteById(id);
    }

    @Override
    public Long updateInfo(EcoMarketClientSecretModel model, UserContext userContext) {
        var existObj = this.ecoMarketClientSecretService.queryOneInfoById(model.getId());
        if (Objects.isNull(existObj)) {
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }

        model.setCreatorId(null);
        model.setCreatorName(null);
        model.setModified(null);
        var entity = this.ecoMarketClientSecretTranslator.convertToEntity(model);
        entity.setId(existObj.getId());
        return this.ecoMarketClientSecretService.updateInfo(entity);
    }

    @Override
    public Long addInfo(EcoMarketClientSecretModel model, UserContext userContext) {
        model.setId(null);
        model.setCreatorId(userContext.getUserId());
        model.setCreatorName(userContext.getUserName());

        var entity = this.ecoMarketClientSecretTranslator.convertToEntity(model);
        return this.ecoMarketClientSecretService.addInfo(entity);
    }
    
    @Override
    public EcoMarketClientSecretModel queryByTenantId(Long tenantId) {
        var data = this.ecoMarketClientSecretService.getByTenantId(tenantId);
        if (Objects.isNull(data)) {
            return null;
        }
        return this.ecoMarketClientSecretTranslator.convertToModel(data);
    }
    
    @Override
    public EcoMarketClientSecretModel queryByClientId(String clientId) {
        var data = this.ecoMarketClientSecretService.getByClientId(clientId);
        if (Objects.isNull(data)) {
            return null;
        }
        return this.ecoMarketClientSecretTranslator.convertToModel(data);
    }
    
    @Override
    public List<EcoMarketClientSecretModel> queryListByParams(Map<String, Object> params) {
        // 实际实现应该是基于参数查询，这里简化处理
        log.info("Query client secrets by params: {}", params);
        
        // 如果有tenantId参数，则根据租户ID查询
        if (params != null && params.containsKey("tenantId")) {
            Long tenantId = (Long) params.get("tenantId");
            var model = queryByTenantId(tenantId);
            if (model != null) {
                return List.of(model);
            }
        }
        
        // 如果有clientId参数，则根据客户端ID查询
        if (params != null && params.containsKey("clientId")) {
            String clientId = (String) params.get("clientId");
            var model = queryByClientId(clientId);
            if (model != null) {
                return List.of(model);
            }
        }
        
        // 默认返回空列表
        return List.of();
    }

    @Override
    public List<EcoMarketClientSecretModel> queryAllList() {
        var dataList = this.ecoMarketClientSecretService.queryAllList();
        return dataList.stream()
                .map(ecoMarketClientSecretTranslator::convertToModel)
                .collect(Collectors.toList());
    }
}
