package com.xspaceagi.connector.infra.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.connector.domain.entity.ConnectorCredential;
import com.xspaceagi.connector.infra.dao.mapper.ConnectorCredentialMapper;
import com.xspaceagi.connector.infra.tenant.ConnectorTenantSupport;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * 凭证行存取（表无租户列，全部豁免租户拦截；仅被 Vault 服务使用）。
 */
@Service
public class ConnectorCredentialRepository extends ServiceImpl<ConnectorCredentialMapper, ConnectorCredential> {

    /**
     * 插入凭证行，返回自增 id。
     */
    public long insert(String credType, String ciphertext, Date expiresAt) {
        return ConnectorTenantSupport.withoutTenantFilter(() -> {
            ConnectorCredential entity = new ConnectorCredential();
            entity.setCredType(credType);
            entity.setCiphertext(ciphertext);
            entity.setExpiresAt(expiresAt);
            entity.setRevision(0L);
            Date now = new Date();
            entity.setCreated(now);
            entity.setModified(now);
            baseMapper.insert(entity);
            return entity.getId();
        });
    }

    public ConnectorCredential getById(long id) {
        return ConnectorTenantSupport.withoutTenantFilter(() ->
                baseMapper.selectOne(new LambdaQueryWrapper<ConnectorCredential>()
                        .eq(ConnectorCredential::getId, id)));
    }

    /**
     * 乐观锁写回（刷新轮换）：仅当 revision 匹配时更新，返回是否成功。
     */
    public boolean updateWithRevision(long id, String ciphertext, Date expiresAt, long expectedRevision) {
        return ConnectorTenantSupport.withoutTenantFilter(() -> {
            LambdaUpdateWrapper<ConnectorCredential> wrapper = new LambdaUpdateWrapper<>();
            wrapper.eq(ConnectorCredential::getId, id)
                    .eq(ConnectorCredential::getRevision, expectedRevision)
                    .set(ConnectorCredential::getCiphertext, ciphertext)
                    .set(ConnectorCredential::getExpiresAt, expiresAt)
                    .set(ConnectorCredential::getRevision, expectedRevision + 1)
                    .set(ConnectorCredential::getModified, new Date());
            return baseMapper.update(null, wrapper) == 1;
        });
    }

    public void deleteById(long id) {
        ConnectorTenantSupport.withoutTenantFilter(() -> {
            baseMapper.deleteById(id);
            return null;
        });
    }
}
