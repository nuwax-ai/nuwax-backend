package com.xspaceagi.agent.core.infra.rpc;

import com.xspaceagi.eco.market.sdk.reponse.ClientSecretResponse;
import com.xspaceagi.eco.market.sdk.request.ClientSecretRequest;
import com.xspaceagi.eco.market.sdk.service.IEcoMarketRpcService;
import com.xspaceagi.system.spec.cache.SimpleJvmHashCache;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class MarketClientRpcService {

    @Resource
    private IEcoMarketRpcService ecoMarketRpcService;

    public ClientSecretResponse queryClientSecret(ClientSecretRequest request) {
        Object clientSecret = SimpleJvmHashCache.getHash("client_secret", request.getTenantId().toString());
        if (clientSecret != null) {
            return (ClientSecretResponse) clientSecret;
        }
        ClientSecretResponse clientSecretResponse = null;
        try {
            clientSecretResponse = ecoMarketRpcService.queryClientSecret(request);
        } catch (Exception e) {
            log.warn("Exception while querying client secret", e);
            return null;
        }
        if (clientSecretResponse != null) {
            SimpleJvmHashCache.putHash("client_secret", request.getTenantId().toString(), clientSecretResponse, 7200);
            return clientSecretResponse;
        }
        clientSecretResponse = new ClientSecretResponse();
        clientSecretResponse.setClientSecret("Unregistered");
        return clientSecretResponse;
    }
}
