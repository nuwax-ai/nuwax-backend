package com.xspaceagi.eco.market.spec.app.service.impl;

import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.xspaceagi.eco.market.domain.model.EcoMarketClientSecretModel;
import com.xspaceagi.eco.market.domain.service.IEcoMarketClientSecretDomainService;
import com.xspaceagi.eco.market.sdk.model.ClientSecretDTO;
import com.xspaceagi.eco.market.spec.app.service.IEcoMarketClientSecretApplicationService;
import com.xspaceagi.system.spec.common.UserContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class EcoMarketClientSecretApplicationService implements IEcoMarketClientSecretApplicationService {

    @Resource
    private IEcoMarketClientSecretDomainService ecoMarketClientSecretDomainService;

    @Override
    public EcoMarketClientSecretModel queryOneInfoById(Long id) {
        return ecoMarketClientSecretDomainService.queryOneInfoById(id);
    }

    @Override
    public List<EcoMarketClientSecretModel> queryListByIds(List<Long> ids) {
        return ecoMarketClientSecretDomainService.queryListByIds(ids);
    }

    @Override
    public void deleteById(Long id) {
        ecoMarketClientSecretDomainService.deleteById(id);
    }

    @Override
    public Long updateInfo(EcoMarketClientSecretModel model, UserContext userContext) {
        return ecoMarketClientSecretDomainService.updateInfo(model, userContext);
    }

    @Override
    public Long addInfo(EcoMarketClientSecretModel model, UserContext userContext) {
        return ecoMarketClientSecretDomainService.addInfo(model, userContext);
    }
    
    @Override
    @DSTransactional(rollbackFor = Exception.class)
    public Long saveClientSecretDTO(ClientSecretDTO clientSecretDTO, UserContext userContext) {
        log.info("Application layer: save client secret, tenantId: {}, clientId: {}", clientSecretDTO.getTenantId(), clientSecretDTO.getClientId());
        return ecoMarketClientSecretDomainService.saveFromClientSecretDTO(clientSecretDTO, userContext);
    }
    
    @Override
    public boolean existsClientSecret(Long tenantId) {
        return ecoMarketClientSecretDomainService.existsClientSecret(tenantId);
    }
    

    

}
