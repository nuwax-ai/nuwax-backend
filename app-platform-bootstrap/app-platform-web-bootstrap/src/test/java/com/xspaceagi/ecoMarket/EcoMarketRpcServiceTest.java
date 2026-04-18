package com.xspaceagi.ecoMarket;

import com.xspaceagi.eco.market.sdk.reponse.ClientSecretResponse;
import com.xspaceagi.eco.market.sdk.request.ClientSecretRequest;
import com.xspaceagi.eco.market.sdk.service.IEcoMarketRpcService;
import com.xspaceagi.system.spec.common.RequestContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * EcoMarketRpcService 集成测试类
 */
@Slf4j
@SpringBootTest
public class EcoMarketRpcServiceTest {

    @Resource
    private IEcoMarketRpcService ecoMarketRpcService;

    /**
     * 测试查询客户端密钥功能 - 成功场景
     */
    @Test
    public void testQueryClientSecret_Success() {
        try {
            RequestContext.setThreadTenantId(1L);

            // Arrange
            ClientSecretRequest request = new ClientSecretRequest();
            request.setTenantId(1L);

            // Act
            ClientSecretResponse response = ecoMarketRpcService.queryClientSecret(request);

            log.info("Client secret query result, response={}", response);

            // Assert
            assertNotNull(response, "响应不应为空");
            if (response != null) {
                assertNotNull(response.getTenantId(), "租户ID不应为空");
                assertEquals(1L, response.getTenantId(), "租户ID应该匹配");
                log.info("Client secret: id={}, name={}, clientId={}, tenantId={}",
                        response.getId(), response.getName(), response.getClientId(), response.getTenantId());
            }

        } catch (Exception e) {
            log.error("Exception while testing client secret query", e);
            throw e;
        } finally {
            RequestContext.remove();
        }
    }

}