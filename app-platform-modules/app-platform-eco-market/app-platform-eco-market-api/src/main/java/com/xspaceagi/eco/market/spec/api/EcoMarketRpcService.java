package com.xspaceagi.eco.market.spec.api;

import com.xspaceagi.eco.market.domain.service.IEcoMarketClientSecretDomainService;
import com.xspaceagi.eco.market.sdk.reponse.ClientSecretResponse;
import com.xspaceagi.eco.market.sdk.request.ClientSecretRequest;
import com.xspaceagi.eco.market.sdk.service.IEcoMarketRpcService;
import com.xspaceagi.system.domain.log.LogRecordPrint;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 生态市场RPC服务
 */
@Slf4j
@Service
public class EcoMarketRpcService implements IEcoMarketRpcService {

    @Resource
    private IEcoMarketClientSecretDomainService ecoMarketClientSecretDomainService;

    @LogRecordPrint(content = "查询客户端密钥")
    @Override
    public ClientSecretResponse queryClientSecret(ClientSecretRequest request) {
        var tenantId = request.getTenantId();
        var clientSecret = ecoMarketClientSecretDomainService.queryByTenantId(tenantId);
        if (clientSecret == null) {
            return null;
        }
        return clientSecret.toClientSecretResponse();
    }

}
