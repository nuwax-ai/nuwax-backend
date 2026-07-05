package com.xspaceagi.eco.market.sdk.service;

import com.xspaceagi.eco.market.sdk.model.ClientSecretDTO;

/**
 * 根据租户信息,自动注册生态市场的密钥
 */
public interface IEcoMarketSecretRpcService {

    /**
     * 注册客户端
     *
     * @param tenantId    租户ID
     * @param name        客户端名称
     * @param description 描述
     * @return 客户端密钥
     */
    ClientSecretDTO registerClient(Long tenantId, String name, String description);

    ClientSecretDTO getByTenantId(Long tenantId);

}
