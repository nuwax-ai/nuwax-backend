package com.xspaceagi.bill.infra.dao.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.bill.infra.dao.entity.BillResourceStat;
import com.xspaceagi.bill.infra.dao.mapper.BillResourceStatMapper;
import com.xspaceagi.bill.infra.dao.service.IBillResourceStatService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

@Service
public class BillResourceStatServiceImpl extends ServiceImpl<BillResourceStatMapper, BillResourceStat> implements IBillResourceStatService {

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void appendStat(BillResourceStat stat) {
        BillResourceStat existing = lambdaQuery()
                .eq(BillResourceStat::getTenantId, stat.getTenantId())
                .eq(BillResourceStat::getUserId, stat.getUserId())
                .eq(BillResourceStat::getType, stat.getType())
                .eq(BillResourceStat::getTargetType, stat.getTargetType())
                .eq(BillResourceStat::getTargetId, stat.getTargetId())
                .eq(BillResourceStat::getDt, stat.getDt())
                .one();
        if (existing == null) {
            stat.setCreated(new Date());
            save(stat);
        } else {
            existing.setCallCount(safeAddLong(existing.getCallCount(), stat.getCallCount()));
            existing.setCallFailedCount(safeAddLong(existing.getCallFailedCount(), stat.getCallFailedCount()));
            existing.setCreditAmount(safeAdd(existing.getCreditAmount(), stat.getCreditAmount()));
            existing.setFeeAmount(safeAdd(existing.getFeeAmount(), stat.getFeeAmount()));
            existing.setCacheInputTokens(safeAddLong(existing.getCacheInputTokens(), stat.getCacheInputTokens()));
            existing.setInputTokens(safeAddLong(existing.getInputTokens(), stat.getInputTokens()));
            existing.setOutputTokens(safeAddLong(existing.getOutputTokens(), stat.getOutputTokens()));
            existing.setModified(new Date());
            updateById(existing);
        }
    }

    @Override
    public List<BillResourceStat> queryStats(Long tenantId, Long userId, String type,
                                              String targetType, Long targetId,
                                              String dtStart, String dtEnd,
                                              int offset, int limit) {
        return lambdaQuery()
                .eq(tenantId != null, BillResourceStat::getTenantId, tenantId)
                .eq(userId != null, BillResourceStat::getUserId, userId)
                .eq(type != null, BillResourceStat::getType, type)
                .eq(targetType != null, BillResourceStat::getTargetType, targetType)
                .eq(targetId != null, BillResourceStat::getTargetId, targetId)
                .ge(dtStart != null, BillResourceStat::getDt, dtStart)
                .le(dtEnd != null, BillResourceStat::getDt, dtEnd)
                .orderByDesc(BillResourceStat::getDt)
                .last("LIMIT " + offset + ", " + limit)
                .list();
    }

    @Override
    public Long countStats(Long tenantId, Long userId, String type,
                            String targetType, Long targetId,
                            String dtStart, String dtEnd) {
        return lambdaQuery()
                .eq(tenantId != null, BillResourceStat::getTenantId, tenantId)
                .eq(userId != null, BillResourceStat::getUserId, userId)
                .eq(type != null, BillResourceStat::getType, type)
                .eq(targetType != null, BillResourceStat::getTargetType, targetType)
                .eq(targetId != null, BillResourceStat::getTargetId, targetId)
                .ge(dtStart != null, BillResourceStat::getDt, dtStart)
                .le(dtEnd != null, BillResourceStat::getDt, dtEnd)
                .count();
    }

    private BigDecimal safeAdd(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) return BigDecimal.ZERO;
        if (a == null) return b;
        if (b == null) return a;
        return a.add(b);
    }

    private Long safeAddLong(Long a, Long b) {
        if (a == null && b == null) return 0L;
        if (a == null) return b;
        if (b == null) return a;
        return a + b;
    }
}