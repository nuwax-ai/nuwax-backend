package com.xspaceagi.connector.infra.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.connector.domain.entity.ConnectorOauthState;
import com.xspaceagi.connector.infra.dao.mapper.ConnectorOauthStateMapper;
import com.xspaceagi.connector.infra.tenant.ConnectorTenantSupport;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * OAuth state 存取：一次性原子消费（防重放）+ TTL 清理。
 */
@Service
public class ConnectorOauthStateRepository extends ServiceImpl<ConnectorOauthStateMapper, ConnectorOauthState> {

    public boolean insert(ConnectorOauthState state) {
        return ConnectorTenantSupport.withoutTenantFilter(() -> baseMapper.insert(state) > 0);
    }

    /**
     * 原子消费：仅当未消费且未过期时置 consumed=1 并返回实体；否则返回 null。
     * 依赖单条 UPDATE 的行级原子性防重放。
     */
    public ConnectorOauthState consumeAtomically(String stateHash) {
        return ConnectorTenantSupport.withoutTenantFilter(() -> {
            LambdaUpdateWrapper<ConnectorOauthState> update = new LambdaUpdateWrapper<>();
            update.eq(ConnectorOauthState::getStateHash, stateHash)
                    .eq(ConnectorOauthState::getConsumed, 0)
                    .gt(ConnectorOauthState::getExpiresAt, new Date())
                    .set(ConnectorOauthState::getConsumed, 1);
            if (baseMapper.update(null, update) != 1) {
                return null;
            }
            return baseMapper.selectOne(new LambdaQueryWrapper<ConnectorOauthState>()
                    .eq(ConnectorOauthState::getStateHash, stateHash));
        });
    }

    /**
     * 读取未消费且未过期的 state，<b>不置 consumed</b>；查不到返回 null。
     * <p>
     * 设备码（扫码）流程需要它：轮询期间同一行要被反复读取，只有拿到令牌的那一次才走
     * {@link #consumeAtomically(String)}。授权码流程用不到——回调即消费。
     */
    public ConnectorOauthState findUnconsumed(String stateHash) {
        return ConnectorTenantSupport.withoutTenantFilter(() ->
                baseMapper.selectOne(new LambdaQueryWrapper<ConnectorOauthState>()
                        .eq(ConnectorOauthState::getStateHash, stateHash)
                        .eq(ConnectorOauthState::getConsumed, 0)
                        .gt(ConnectorOauthState::getExpiresAt, new Date())));
    }

    /**
     * 区分 state 不存在/已消费 与 已过期（给回调页更准确的错误提示）。
     */
    public boolean exists(String stateHash) {
        return ConnectorTenantSupport.withoutTenantFilter(() ->
                baseMapper.selectCount(new LambdaQueryWrapper<ConnectorOauthState>()
                        .eq(ConnectorOauthState::getStateHash, stateHash)) > 0);
    }

    /**
     * 清理过期 state（回调时顺带执行，低频）。
     */
    public void purgeExpired() {
        ConnectorTenantSupport.withoutTenantFilter(() -> {
            baseMapper.delete(new LambdaQueryWrapper<ConnectorOauthState>()
                    .lt(ConnectorOauthState::getExpiresAt, new Date()));
            return null;
        });
    }
}
