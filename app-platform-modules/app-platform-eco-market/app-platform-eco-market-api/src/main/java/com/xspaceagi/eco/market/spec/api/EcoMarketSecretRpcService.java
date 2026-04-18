package com.xspaceagi.eco.market.spec.api;

import com.xspaceagi.eco.market.domain.specification.EcoMarkerSecretWrapper;
import com.xspaceagi.eco.market.sdk.model.ClientSecretDTO;
import com.xspaceagi.eco.market.sdk.service.IEcoMarketSecretRpcService;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.EcoMarketException;
import com.xspaceagi.system.domain.log.LogRecordPrint;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class EcoMarketSecretRpcService implements IEcoMarketSecretRpcService {

    @Resource
    private EcoMarkerSecretWrapper ecoMarkerSecretWrapper;


    @LogRecordPrint(content = "注册生态市场的密钥")
    @Override
    public ClientSecretDTO registerClient(Long tenantId, String name, String description) {
        if (Objects.isNull(tenantId)) {
            throw EcoMarketException.build(BizExceptionCodeEnum.fieldRequiredButEmpty, "租户ID");
        }

        return ecoMarkerSecretWrapper.registerClientSecret(tenantId, name, description);
    }

}