package com.xspaceagi.eco.market.domain.specification;

import com.xspaceagi.eco.market.domain.service.IEcoMarketClientSecretDomainService;
import com.xspaceagi.eco.market.sdk.model.ClientSecretDTO;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.EcoMarketException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Slf4j
@Service
public class EcoMarkerSecretWrapper {

    @Resource
    @Lazy
    private IEcoMarketClientSecretDomainService ecoMarketClientSecretDomainService;

    /**
     * 获取客户端密钥
     * 
     * @return 客户端密钥DTO
     */
    public ClientSecretDTO obtainClientSecretOrRegister(Long tenantId) {
        if (Objects.isNull(tenantId)) {
            log.error("Tenant ID cannot be empty");
            throw EcoMarketException.build(BizExceptionCodeEnum.fieldRequiredButEmpty, "租户ID");
        }

        var secret = ecoMarketClientSecretDomainService.getByTenantId(tenantId);
        // 检查是否存在
        if (Objects.isNull(secret)) {
            log.info("Client secret missing, starting registration: tenantId={}", tenantId);
            // 注册客户端
            var tenantName = "EcoMarket-Client-" + tenantId;

            var description = "生态市场客户端-" + tenantName;

            return this.registerClientSecret(tenantId, tenantName, description);
        } else {
            // 已存在，直接获取
            return secret;
        }

    }

    /**
     * 获取客户端密钥
     *
     * @return 客户端密钥DTO
     */
    public ClientSecretDTO registerClientSecret(Long tenantId, String name, String description) {
        if (Objects.isNull(tenantId)) {
            log.error("Tenant ID cannot be empty");
            throw EcoMarketException.build(BizExceptionCodeEnum.fieldRequiredButEmpty, "租户ID");
        }

        var secret = ecoMarketClientSecretDomainService.getByTenantId(tenantId);
        // 检查是否存在
        if (Objects.isNull(secret)) {
            log.info("Client secret missing, starting registration: tenantId={}", tenantId);
            // 注册客户端
            return ecoMarketClientSecretDomainService.getOrRegisterClientSecret(
                    tenantId,
                    name,
                    description);
        } else {
            // 已存在，直接获取
            return secret;
        }

    }

}
