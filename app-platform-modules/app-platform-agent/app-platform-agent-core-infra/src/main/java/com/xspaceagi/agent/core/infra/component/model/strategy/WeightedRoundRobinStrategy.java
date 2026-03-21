package com.xspaceagi.agent.core.infra.component.model.strategy;

import com.xspaceagi.agent.core.adapter.dto.config.ModelConfigDto;
import com.xspaceagi.agent.core.infra.rpc.MarketClientRpcService;
import com.xspaceagi.eco.market.sdk.reponse.ClientSecretResponse;
import com.xspaceagi.eco.market.sdk.request.ClientSecretRequest;
import com.xspaceagi.system.spec.utils.RedisUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class WeightedRoundRobinStrategy implements ApiSelectStrategy {

    private RedisUtil redisUtil;

    private MarketClientRpcService marketClientRpcService;

    @Autowired
    public void setRedisUtil(RedisUtil redisUtil) {
        this.redisUtil = redisUtil;
    }

    @Autowired
    public void setMarketClientRpcService(MarketClientRpcService marketClientRpcService) {
        this.marketClientRpcService = marketClientRpcService;
    }

    @Override
    public ModelConfigDto.ApiInfo selectApi(ModelConfigDto modelConfigDto) {
        // 根据modelConfigDto.getApiInfoList()中的weight进行了权重计算，然后生成新的 List<ApiInfo>
        if (modelConfigDto.getApiInfoList() != null && modelConfigDto.getApiInfoList().size() == 1) {
            return replaceKey(modelConfigDto, modelConfigDto.getApiInfoList().get(0));
        }
        List<ModelConfigDto.ApiInfo> apiInfoList = new ArrayList<>();
        modelConfigDto.getApiInfoList().forEach(apiInfo -> {
            for (int i = 0; i < apiInfo.getWeight(); i++) {
                apiInfoList.add(replaceKey(modelConfigDto, apiInfo));
            }
        });
        Long index = redisUtil.increment("roundRobinIndex-" + modelConfigDto.getId(), 1);
        return apiInfoList.get((int) (index % apiInfoList.size()));
    }

    public ModelConfigDto.ApiInfo replaceKey(ModelConfigDto modelConfigDto, ModelConfigDto.ApiInfo apiInfo) {
        if (apiInfo.getKey().contains("TENANT_SECRET")) {
            ClientSecretRequest clientSecretRequest = new ClientSecretRequest();
            clientSecretRequest.setTenantId(modelConfigDto.getTenantId());
            ClientSecretResponse clientSecretResponse = marketClientRpcService.queryClientSecret(clientSecretRequest);
            apiInfo.setKey(apiInfo.getKey().replace("TENANT_SECRET", clientSecretResponse.getClientSecret()));
        }
        return apiInfo;
    }

}
