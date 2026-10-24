package com.xspaceagi.connector.infra.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.connector.domain.entity.ConnectorOauthConfig;
import com.xspaceagi.connector.infra.dao.mapper.ConnectorOauthConfigMapper;
import com.xspaceagi.connector.infra.tenant.ConnectorTenantSupport;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * OAuth App 配置存取（表无租户列：platform 行全局共享，byo 行按 owner_id 隔离）。
 */
@Service
public class ConnectorOauthConfigRepository extends ServiceImpl<ConnectorOauthConfigMapper, ConnectorOauthConfig> {

    /**
     * 精确取：provider + scopeType + ownerId。
     */
    public ConnectorOauthConfig getExact(String providerService, String scopeType, Long ownerId) {
        return ConnectorTenantSupport.withoutTenantFilter(() ->
                baseMapper.selectOne(new LambdaQueryWrapper<ConnectorOauthConfig>()
                        .eq(ConnectorOauthConfig::getProviderService, providerService)
                        .eq(ConnectorOauthConfig::getScopeType, scopeType)
                        .eq(ConnectorOauthConfig::getOwnerId, ownerId)));
    }

    /**
     * 用户视角解析：优先本人 BYO，回退平台公共 App。
     */
    public ConnectorOauthConfig resolveForUser(String providerService, Long userId) {
        ConnectorOauthConfig byo = getExact(providerService, "byo", userId);
        if (byo != null) {
            return byo;
        }
        return getExact(providerService, "platform", 0L);
    }

    public List<ConnectorOauthConfig> listByProvider(String providerService) {
        return ConnectorTenantSupport.withoutTenantFilter(() ->
                baseMapper.selectList(new LambdaQueryWrapper<ConnectorOauthConfig>()
                        .eq(ConnectorOauthConfig::getProviderService, providerService)));
    }

    public boolean insert(ConnectorOauthConfig config) {
        return ConnectorTenantSupport.withoutTenantFilter(() -> baseMapper.insert(config) > 0);
    }

    public boolean updateById(ConnectorOauthConfig config) {
        return ConnectorTenantSupport.withoutTenantFilter(() -> baseMapper.updateById(config) > 0);
    }

    public boolean deleteById(Long id) {
        return ConnectorTenantSupport.withoutTenantFilter(() -> baseMapper.deleteById(id) > 0);
    }
}
