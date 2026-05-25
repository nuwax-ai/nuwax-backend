package com.xspaceagi.eco.market.domain.service.impl;

import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.google.common.eventbus.EventBus;
import com.xspaceagi.eco.market.domain.client.EcoMarketServerApiService;
import com.xspaceagi.eco.market.domain.model.EcoMarketClientSecretModel;
import com.xspaceagi.eco.market.domain.repository.IEcoMarketClientSecretRepository;
import com.xspaceagi.eco.market.domain.service.IEcoMarketClientSecretDomainService;
import com.xspaceagi.eco.market.sdk.model.ClientSecretDTO;
import com.xspaceagi.eco.market.spec.util.EcoMarketCaffeineCacheUtil;
import com.xspaceagi.pay.spec.gateway.PayGatewayOutboundCacheEvictSupport;
import com.xspaceagi.pay.spec.gateway.PayGatewayOutboundCacheEvictor;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.event.PullMessageEvent;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.EcoMarketException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Slf4j
@Service
public class EcoMarketClientSecretDomainService implements IEcoMarketClientSecretDomainService {

    @Resource
    private IEcoMarketClientSecretRepository ecoMarketClientSecretRepository;

    @Resource
    private EcoMarketServerApiService ecoMarketServerApiService;


    @Resource
    private EventBus eventBus;

    @Autowired(required = false)
    private PayGatewayOutboundCacheEvictor payGatewayOutboundCacheEvictor;

    @Override
    public EcoMarketClientSecretModel queryOneInfoById(Long id) {
        return this.ecoMarketClientSecretRepository.queryOneInfoById(id);
    }

    @Override
    public List<EcoMarketClientSecretModel> queryListByIds(List<Long> ids) {
        return this.ecoMarketClientSecretRepository.queryListByIds(ids);
    }

    @DSTransactional(rollbackFor = Exception.class)
    @Override
    public void deleteById(Long id) {
        EcoMarketClientSecretModel existing = queryOneInfoById(id);
        this.ecoMarketClientSecretRepository.deleteById(id);
        if (existing != null) {
            evictPayGatewayOutboundCache(existing.getTenantId());
        }
    }

    @Override
    public Long updateInfo(EcoMarketClientSecretModel model, UserContext userContext) {
        Long id = this.ecoMarketClientSecretRepository.updateInfo(model, userContext);
        evictPayGatewayOutboundCache(model.getTenantId());
        return id;
    }

    @DSTransactional(rollbackFor = Exception.class)
    @Override
    public Long addInfo(EcoMarketClientSecretModel model, UserContext userContext) {
        Long id = this.ecoMarketClientSecretRepository.addInfo(model, userContext);
        evictPayGatewayOutboundCache(model.getTenantId());
        return id;
    }

    @DSTransactional(rollbackFor = Exception.class)
    @Override
    public Long saveFromClientSecretDTO(ClientSecretDTO clientSecretDTO, UserContext userContext) {
        if (clientSecretDTO == null) {
            return null;
        }

        log.info("Save client secret, tenantId: {}, clientId: {}", clientSecretDTO.getTenantId(), clientSecretDTO.getClientId());

        // 先查询是否已存在该租户的密钥
        EcoMarketClientSecretModel existingModel = queryByTenantId(clientSecretDTO.getTenantId());

        // 构建模型对象
        EcoMarketClientSecretModel model = EcoMarketClientSecretModel.builder()
                .name(clientSecretDTO.getName())
                .description(clientSecretDTO.getDescription())
                .clientId(clientSecretDTO.getClientId())
                .clientSecret(clientSecretDTO.getClientSecret())
                .tenantId(clientSecretDTO.getTenantId())
                .build();

        Long id;
        if (existingModel != null) {
            // 已存在，则更新
            model.setId(existingModel.getId());
            id = updateInfo(model, userContext);
            log.info("Client secret updated, id: {}", id);
        } else {
            // 不存在，则新增
            id = addInfo(model, userContext);
            log.info("Client secret created, id: {}", id);
        }

        return id;
    }

    private void evictPayGatewayOutboundCache(Long tenantId) {
        PayGatewayOutboundCacheEvictSupport.evictIfPresent(payGatewayOutboundCacheEvictor, tenantId);
    }

    @Override
    public EcoMarketClientSecretModel queryByTenantId(Long tenantId) {
        if (tenantId == null) {
            return null;
        }

        // 通过仓库层查询
        return this.ecoMarketClientSecretRepository.queryByTenantId(tenantId);
    }

    @Override
    public EcoMarketClientSecretModel queryByClientId(String clientId) {
        if (clientId == null) {
            return null;
        }

        // 通过仓库层查询
        return this.ecoMarketClientSecretRepository.queryByClientId(clientId);
    }

    @Override
    public boolean existsClientSecret(Long tenantId) {
        return queryByTenantId(tenantId) != null;
    }

    @Override
    public ClientSecretDTO getOrRegisterClientSecret(Long tenantId, String name, String description) {
        if (Objects.isNull(tenantId)) {
            log.error("Tenant ID cannot be empty");
            throw EcoMarketException.build(BizExceptionCodeEnum.fieldRequiredButEmpty, "租户ID");
        }
        // 先查询是否存在
        EcoMarketClientSecretModel model = queryByTenantId(tenantId);
        if (model != null) {
            return getByTenantId(tenantId);
        }

        // 不存在，调用服务器端API注册
        log.info("Client secret missing, registering on server: tenantId={}, name={}", tenantId, name);

        // 调用服务器API注册客户端
        // 主动生成租户的clientId, 并保存到内存中, 用于后续的注册.注:
        // 服务器不一定认客户端给的clientId,正常情况会使用客户端给的clientId, 如果客户端给的clientId不合法,
        // 则使用生成的clientId返回
        String clientId = EcoMarketCaffeineCacheUtil.getTenantClientIdWithCache(tenantId);
        ClientSecretDTO clientSecretDTO = ecoMarketServerApiService.registerClient(name, description, tenantId,
                clientId);

        // 保存到本地
        UserContext userContext = UserContext.builder()
                .tenantId(tenantId)
                .userId(0L)
                .userName("system")
                .build();
        saveFromClientSecretDTO(clientSecretDTO, userContext);

        // 注册成功后, 触发EventBus事件，异步拉取消息
        var pullMessageEvent = PullMessageEvent.builder()
                .userId(0L)
                .tenantId(RequestContext.get().getTenantId())
                .clientId(clientId)
                .build();
        eventBus.post(pullMessageEvent);

        return clientSecretDTO;
    }

    @Override
    public ClientSecretDTO getByTenantId(Long tenantId) {
        EcoMarketClientSecretModel model = queryByTenantId(tenantId);
        if (model == null) {
            return null;
        }

        return ClientSecretDTO.builder()
                .id(model.getId())
                .name(model.getName())
                .description(model.getDescription())
                .clientId(model.getClientId())
                .clientSecret(model.getClientSecret())
                .tenantId(model.getTenantId())
                .build();
    }

    @Override
    public ClientSecretDTO getByClientId(String clientId) {
        EcoMarketClientSecretModel model = queryByClientId(clientId);
        if (model == null) {
            return null;
        }

        return ClientSecretDTO.builder()
                .id(model.getId())
                .name(model.getName())
                .description(model.getDescription())
                .clientId(model.getClientId())
                .clientSecret(model.getClientSecret())
                .tenantId(model.getTenantId())
                .build();
    }

    @Override
    public boolean validateClientSecret(String clientId, String clientSecret) {
        EcoMarketClientSecretModel model = queryByClientId(clientId);
        if (model == null || clientSecret == null) {
            return false;
        }

        return clientSecret.equals(model.getClientSecret());
    }

    @Override
    public List<EcoMarketClientSecretModel> queryAllList() {
        return this.ecoMarketClientSecretRepository.queryAllList();
    }
}
