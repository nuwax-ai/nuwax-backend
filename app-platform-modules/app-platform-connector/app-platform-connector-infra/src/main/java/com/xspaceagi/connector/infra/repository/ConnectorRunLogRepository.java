package com.xspaceagi.connector.infra.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.connector.domain.entity.ConnectorRunLog;
import com.xspaceagi.connector.infra.dao.mapper.ConnectorRunLogMapper;
import com.xspaceagi.connector.infra.tenant.ConnectorTenantSupport;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 执行审计存取：按条数上限在写入时滚动删除（同 open-connector runs 保留策略）。
 */
@Service
public class ConnectorRunLogRepository extends ServiceImpl<ConnectorRunLogMapper, ConnectorRunLog> {

    /** 单连接保留条数上限 */
    public static final int RETENTION_PER_CONNECTION = 200;

    /**
     * 写入并滚动修剪：超出上限时删除最旧记录。
     */
    public void insertAndTrim(ConnectorRunLog runLog) {
        ConnectorTenantSupport.withoutTenantFilter(() -> {
            baseMapper.insert(runLog);
            if (runLog.getConnectionId() != null) {
                trim(runLog.getConnectionId());
            }
            return null;
        });
    }

    private void trim(Long connectionId) {
        Long total = baseMapper.selectCount(new LambdaQueryWrapper<ConnectorRunLog>()
                .eq(ConnectorRunLog::getConnectionId, connectionId));
        long overflow = total - RETENTION_PER_CONNECTION;
        if (overflow <= 0) {
            return;
        }
        List<ConnectorRunLog> oldest = baseMapper.selectList(new LambdaQueryWrapper<ConnectorRunLog>()
                .select(ConnectorRunLog::getId)
                .eq(ConnectorRunLog::getConnectionId, connectionId)
                .orderByAsc(ConnectorRunLog::getId)
                .last("LIMIT " + overflow));
        if (!oldest.isEmpty()) {
            baseMapper.deleteBatchIds(oldest.stream().map(ConnectorRunLog::getId).toList());
        }
    }

    /**
     * 审计查询（管理侧）。
     */
    public IPage<ConnectorRunLog> page(Long connectionId, Long userId, String status,
                                       long pageNum, long pageSize) {
        return ConnectorTenantSupport.withoutTenantFilter(() -> {
            LambdaQueryWrapper<ConnectorRunLog> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(connectionId != null, ConnectorRunLog::getConnectionId, connectionId)
                    .eq(userId != null, ConnectorRunLog::getUserId, userId)
                    .eq(status != null, ConnectorRunLog::getStatus, status)
                    .orderByDesc(ConnectorRunLog::getId);
            return baseMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        });
    }
}
