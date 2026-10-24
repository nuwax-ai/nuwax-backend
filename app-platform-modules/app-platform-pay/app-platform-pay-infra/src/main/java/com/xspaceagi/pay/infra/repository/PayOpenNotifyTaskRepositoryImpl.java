package com.xspaceagi.pay.infra.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xspaceagi.pay.domain.model.PayOpenNotifyTaskModel;
import com.xspaceagi.pay.domain.repository.PayOpenNotifyTaskRepository;
import com.xspaceagi.pay.infra.dao.entity.PayOpenNotifyTask;
import com.xspaceagi.pay.infra.dao.mapper.PayOpenNotifyTaskMapper;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PayOpenNotifyTaskRepositoryImpl implements PayOpenNotifyTaskRepository {

    private final PayOpenNotifyTaskMapper mapper;

    /** WAIT_PAY 扫描光标（进程内轮转；重启回 0 不影响正确性） */
    private final java.util.concurrent.atomic.AtomicLong lastWaitPayCursor = new java.util.concurrent.atomic.AtomicLong(0);

    @Override
    public PayOpenNotifyTaskModel save(PayOpenNotifyTaskModel model) {
        PayOpenNotifyTask entity = toEntity(model);
        if (entity.getId() == null) {
            mapper.insert(entity);
            model.setId(entity.getId());
        } else {
            mapper.updateById(entity);
        }
        return model;
    }

    @Override
    public Optional<PayOpenNotifyTaskModel> findByTenantIdAndGatewayOrderNo(long tenantId, String gatewayOrderNo) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.<PayOpenNotifyTask>lambdaQuery()
                .eq(PayOpenNotifyTask::getTenantId, tenantId)
                .eq(PayOpenNotifyTask::getGatewayOrderNo, gatewayOrderNo)
                .last("limit 1"))).map(this::toModel);
    }

    @Override
    public Optional<PayOpenNotifyTaskModel> findById(long id) {
        return Optional.ofNullable(mapper.selectById(id)).map(this::toModel);
    }

    @Override
    public List<PayOpenNotifyTaskModel> listRunnable(int limit) {
        // 两段查询防饥饿 + WAIT_PAY 轮转：lastWaitPayId 光标确保每 tick 消费不同批次，
        // 不再永远返回同一组最老 id（消 >25 行饥饿）
        Date now = new Date();
        List<PayOpenNotifyTaskModel> result = new java.util.ArrayList<>();
        int half = Math.max(1, limit / 2);
        mapper.selectList(Wrappers.<PayOpenNotifyTask>lambdaQuery()
                .eq(PayOpenNotifyTask::getStatus, PayOpenNotifyTaskModel.NOTIFY_WAIT_PAY)
                .gt(PayOpenNotifyTask::getId, lastWaitPayCursor.get())
                .orderByAsc(PayOpenNotifyTask::getId)
                .last("limit " + half))
                .forEach(e -> {
                    result.add(toModel(e));
                    lastWaitPayCursor.set(e.getId());
                });
        // 光标回卷：扫完一轮从 0 重来
        if (result.stream().noneMatch(t -> PayOpenNotifyTaskModel.NOTIFY_WAIT_PAY.equals(t.getStatus()))) {
            lastWaitPayCursor.set(0L);
        }
        mapper.selectList(Wrappers.<PayOpenNotifyTask>lambdaQuery()
                .eq(PayOpenNotifyTask::getStatus, PayOpenNotifyTaskModel.NOTIFY_RETRY)
                .le(PayOpenNotifyTask::getNextRetryTime, now)
                .orderByAsc(PayOpenNotifyTask::getNextRetryTime)
                .last("limit " + half))
                .forEach(e -> result.add(toModel(e)));
        return result;
    }

    @Override
    public List<PayOpenNotifyTaskModel> listStaleWaiting(Date createdBefore, int limit) {
        return mapper.selectList(Wrappers.<PayOpenNotifyTask>lambdaQuery()
                .eq(PayOpenNotifyTask::getStatus, PayOpenNotifyTaskModel.NOTIFY_WAIT_PAY)
                .lt(PayOpenNotifyTask::getCreated, createdBefore)
                .last("limit " + limit)).stream().map(this::toModel).collect(Collectors.toList());
    }

    private PayOpenNotifyTask toEntity(PayOpenNotifyTaskModel m) {
        PayOpenNotifyTask e = new PayOpenNotifyTask();
        e.setId(m.getId());
        e.setTenantId(m.getTenantId());
        e.setOrderId(m.getOrderId());
        e.setGatewayOrderNo(m.getGatewayOrderNo());
        e.setBizOrderNo(m.getBizOrderNo());
        e.setOpenProjectId(m.getOpenProjectId());
        e.setNotifyUrl(m.getNotifyUrl());
        e.setPayload(m.getPayload());
        e.setSignKeySnapshot(m.getSignKeySnapshot());
        e.setStatus(m.getStatus());
        e.setRetryCount(m.getRetryCount());
        e.setNextRetryTime(m.getNextRetryTime());
        e.setLastError(m.getLastError());
        e.setCreated(m.getCreated());
        e.setModified(m.getModified());
        return e;
    }

    private PayOpenNotifyTaskModel toModel(PayOpenNotifyTask e) {
        PayOpenNotifyTaskModel m = new PayOpenNotifyTaskModel();
        m.setId(e.getId());
        m.setTenantId(e.getTenantId());
        m.setOrderId(e.getOrderId());
        m.setGatewayOrderNo(e.getGatewayOrderNo());
        m.setBizOrderNo(e.getBizOrderNo());
        m.setOpenProjectId(e.getOpenProjectId());
        m.setNotifyUrl(e.getNotifyUrl());
        m.setPayload(e.getPayload());
        m.setSignKeySnapshot(e.getSignKeySnapshot());
        m.setStatus(e.getStatus());
        m.setRetryCount(e.getRetryCount());
        m.setNextRetryTime(e.getNextRetryTime());
        m.setLastError(e.getLastError());
        m.setCreated(e.getCreated());
        m.setModified(e.getModified());
        return m;
    }
}
