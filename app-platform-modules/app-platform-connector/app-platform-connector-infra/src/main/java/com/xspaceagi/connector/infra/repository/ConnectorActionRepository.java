package com.xspaceagi.connector.infra.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.connector.domain.entity.ConnectorAction;
import com.xspaceagi.connector.infra.dao.mapper.ConnectorActionMapper;
import com.xspaceagi.connector.infra.tenant.ConnectorTenantSupport;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * action 目录存取（action 无租户列，可见性跟随 provider）。
 */
@Service
public class ConnectorActionRepository extends ServiceImpl<ConnectorActionMapper, ConnectorAction> {

    public List<ConnectorAction> listByProvider(String providerService, String tag, String keyword,
                                                boolean includeDeprecated, boolean includeDisabled) {
        return ConnectorTenantSupport.withoutTenantFilter(() -> {
            LambdaQueryWrapper<ConnectorAction> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ConnectorAction::getProviderService, providerService)
                    .eq(!includeDeprecated, ConnectorAction::getDeprecated, 0)
                    .eq(!includeDisabled, ConnectorAction::getStatus, "enabled")
                    .like(StringUtils.isNotBlank(tag), ConnectorAction::getTags, "\"" + tag + "\"")
                    .and(StringUtils.isNotBlank(keyword), w -> w
                            .like(ConnectorAction::getName, keyword)
                            .or().like(ConnectorAction::getActionKey, keyword)
                            .or().like(ConnectorAction::getDescription, keyword))
                    .orderByAsc(ConnectorAction::getId);
            return baseMapper.selectList(wrapper);
        });
    }

    /**
     * 跨 provider 按可见 service 集合检索（发现型元工具用）。
     */
    public List<ConnectorAction> searchByServices(Collection<String> services, String keyword, String tag, int limit) {
        if (services == null || services.isEmpty()) {
            return List.of();
        }
        return ConnectorTenantSupport.withoutTenantFilter(() -> {
            LambdaQueryWrapper<ConnectorAction> wrapper = new LambdaQueryWrapper<>();
            wrapper.in(ConnectorAction::getProviderService, services)
                    .eq(ConnectorAction::getStatus, "enabled")
                    .eq(ConnectorAction::getDeprecated, 0)
                    .like(StringUtils.isNotBlank(tag), ConnectorAction::getTags, "\"" + tag + "\"")
                    .and(StringUtils.isNotBlank(keyword), w -> w
                            .like(ConnectorAction::getName, keyword)
                            .or().like(ConnectorAction::getActionKey, keyword)
                            .or().like(ConnectorAction::getDescription, keyword))
                    .orderByAsc(ConnectorAction::getId)
                    .last("LIMIT " + Math.max(1, limit));
            return baseMapper.selectList(wrapper);
        });
    }

    /**
     * 批量统计各 provider 的 action 数量（列表页工具数展示用）。
     */
    public Map<String, Long> countByServices(Collection<String> services) {
        if (services == null || services.isEmpty()) {
            return Map.of();
        }
        return ConnectorTenantSupport.withoutTenantFilter(() -> {
            List<Map<String, Object>> rows = baseMapper.selectMaps(new QueryWrapper<ConnectorAction>()
                    .select("provider_service", "COUNT(*) AS cnt")
                    .in("provider_service", services)
                    .groupBy("provider_service"));
            Map<String, Long> result = new HashMap<>();
            for (Map<String, Object> row : rows) {
                Object svc = row.get("provider_service");
                Object cnt = row.get("cnt");
                if (svc != null && cnt != null) {
                    result.put(String.valueOf(svc), ((Number) cnt).longValue());
                }
            }
            return result;
        });
    }

    public ConnectorAction getById(Long id) {
        return ConnectorTenantSupport.withoutTenantFilter(() -> baseMapper.selectById(id));
    }

    public ConnectorAction getByKey(String providerService, String actionKey) {
        return ConnectorTenantSupport.withoutTenantFilter(() ->
                baseMapper.selectOne(new LambdaQueryWrapper<ConnectorAction>()
                        .eq(ConnectorAction::getProviderService, providerService)
                        .eq(ConnectorAction::getActionKey, actionKey)));
    }

    public boolean insert(ConnectorAction action) {
        return ConnectorTenantSupport.withoutTenantFilter(() -> baseMapper.insert(action) > 0);
    }

    public boolean updateById(ConnectorAction action) {
        return ConnectorTenantSupport.withoutTenantFilter(() -> baseMapper.updateById(action) > 0);
    }

    public boolean deleteById(Long id) {
        return ConnectorTenantSupport.withoutTenantFilter(() -> baseMapper.deleteById(id) > 0);
    }

    public void deleteByProvider(String providerService) {
        ConnectorTenantSupport.withoutTenantFilter(() -> {
            baseMapper.delete(new LambdaQueryWrapper<ConnectorAction>()
                    .eq(ConnectorAction::getProviderService, providerService));
            return null;
        });
    }
}
