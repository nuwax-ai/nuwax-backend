package com.xspaceagi.connector.infra.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.connector.domain.entity.ConnectorConnection;
import com.xspaceagi.connector.infra.dao.mapper.ConnectorConnectionMapper;
import com.xspaceagi.connector.infra.tenant.ConnectorTenantSupport;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 用户连接存取（个人连接：显式 user_id 过滤；租户字段显式携带）。
 */
@Service
public class ConnectorConnectionRepository extends ServiceImpl<ConnectorConnectionMapper, ConnectorConnection> {

    public List<ConnectorConnection> listByUser(Long tenantId, Long userId, String providerService) {
        return ConnectorTenantSupport.withoutTenantFilter(() -> {
            LambdaQueryWrapper<ConnectorConnection> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ConnectorConnection::getTenantId, tenantId)
                    .eq(ConnectorConnection::getUserId, userId)
                    .eq(StringUtils.isNotBlank(providerService), ConnectorConnection::getProviderService, providerService)
                    .ne(ConnectorConnection::getStatus, "disconnected")
                    .orderByDesc(ConnectorConnection::getId);
            return baseMapper.selectList(wrapper);
        });
    }

    /**
     * 取用户在某 provider 下的第一个**可执行**连接（执行时连接解析：跟随会话用户）。
     * <p>
     * 「可执行」= 用户启用了（{@code enabled = 1}）**且**凭证健康（{@code status = 'active'}），
     * 两个条件缺一不可：只看 status 会把用户已经停用的连接也解析出来，让停用形同虚设；
     * 只看 enabled 会把凭证已过期的连接当成可用的，让执行带着一个死 token 去撞外部 API。
     */
    public ConnectorConnection firstExecutable(Long tenantId, Long userId, String providerService) {
        return ConnectorTenantSupport.withoutTenantFilter(() ->
                baseMapper.selectOne(new LambdaQueryWrapper<ConnectorConnection>()
                        .eq(ConnectorConnection::getTenantId, tenantId)
                        .eq(ConnectorConnection::getUserId, userId)
                        .eq(ConnectorConnection::getProviderService, providerService)
                        .eq(ConnectorConnection::getEnabled, true)
                        .eq(ConnectorConnection::getStatus, "active")
                        .orderByAsc(ConnectorConnection::getId)
                        .last("LIMIT 1")));
    }

    /**
     * 取用户在某 provider 下第一条「存在但当前不可执行」的连接（被用户停用 / 凭证过期或出错）。
     * <p>
     * 只用于在自动解析落空时**区分报错语义**。落空有三种原因，各指向完全不同的下一步动作：
     * 一个连接都没有 → 引导去建连；有连接但被自己停用 → 提示去启用；有连接且已启用但凭证过期 → 提示去重新授权。
     * 混用会把用户引偏——让一个只是停用了连接的人去重新建连，结果是又多出一条连接。
     */
    public ConnectorConnection firstBlocked(Long tenantId, Long userId, String providerService) {
        return ConnectorTenantSupport.withoutTenantFilter(() ->
                baseMapper.selectOne(new LambdaQueryWrapper<ConnectorConnection>()
                        .eq(ConnectorConnection::getTenantId, tenantId)
                        .eq(ConnectorConnection::getUserId, userId)
                        .eq(ConnectorConnection::getProviderService, providerService)
                        .and(wrapper -> wrapper
                                .eq(ConnectorConnection::getEnabled, false)
                                .or()
                                .ne(ConnectorConnection::getStatus, "active"))
                        .ne(ConnectorConnection::getStatus, "disconnected")
                        .orderByAsc(ConnectorConnection::getId)
                        .last("LIMIT 1")));
    }

    /**
     * 归属校验读取：仅本人可取。
     */
    public ConnectorConnection getOwned(Long id, Long userId) {
        return ConnectorTenantSupport.withoutTenantFilter(() ->
                baseMapper.selectOne(new LambdaQueryWrapper<ConnectorConnection>()
                        .eq(ConnectorConnection::getId, id)
                        .eq(ConnectorConnection::getUserId, userId)));
    }

    public long countByProvider(String providerService) {
        return ConnectorTenantSupport.withoutTenantFilter(() ->
                baseMapper.selectCount(new LambdaQueryWrapper<ConnectorConnection>()
                        .eq(ConnectorConnection::getProviderService, providerService)
                        .ne(ConnectorConnection::getStatus, "disconnected")));
    }

    public boolean insert(ConnectorConnection connection) {
        return ConnectorTenantSupport.withoutTenantFilter(() -> baseMapper.insert(connection) > 0);
    }

    public boolean updateById(ConnectorConnection connection) {
        return ConnectorTenantSupport.withoutTenantFilter(() -> baseMapper.updateById(connection) > 0);
    }

    public boolean deleteById(Long id) {
        return ConnectorTenantSupport.withoutTenantFilter(() -> baseMapper.deleteById(id) > 0);
    }
}
