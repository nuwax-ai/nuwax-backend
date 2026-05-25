package com.xspaceagi.pay.infra.repository;

import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xspaceagi.pay.domain.model.PayOrderModel;
import com.xspaceagi.pay.domain.model.PayOrderPageSlice;
import com.xspaceagi.pay.domain.repository.PayOrderRepository;
import com.xspaceagi.pay.infra.dao.entity.PayOrder;
import com.xspaceagi.pay.infra.dao.mapper.PayOrderMapper;
import com.xspaceagi.pay.sdk.enums.PayChannel;
import com.xspaceagi.pay.sdk.enums.PaymentStatus;
import com.xspaceagi.pay.spec.enums.PayBizNotifyStatus;
import com.xspaceagi.pay.spec.enums.PayOrderGatewaySyncStatus;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class PayOrderRepositoryImpl implements PayOrderRepository {

    private final PayOrderMapper mapper;

    @Override
    public Optional<PayOrderModel> findById(Long id) {
        return Optional.ofNullable(mapper.selectById(id)).map(this::toDomain);
    }

    @Override
    public Optional<PayOrderModel> findByTenantIdAndId(long tenantId, long id) {
        return Optional.ofNullable(
                        mapper.selectOne(
                                Wrappers.lambdaQuery(PayOrder.class)
                                        .eq(PayOrder::getId, id)
                                        .eq(PayOrder::getTenantId, tenantId)))
                .map(this::toDomain);
    }

    @Override
    public Optional<PayOrderModel> findByTenantIdAndBizOrderNo(long tenantId, String bizOrderNo) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.lambdaQuery(PayOrder.class).eq(PayOrder::getTenantId, tenantId).eq(PayOrder::getBizOrderNo, bizOrderNo))).map(this::toDomain);
    }

    @Override
    public Optional<PayOrderModel> findLatestByTenantIdAndBizOrderNo(long tenantId, String bizOrderNo) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.lambdaQuery(PayOrder.class).eq(PayOrder::getTenantId, tenantId).eq(PayOrder::getBizOrderNo, bizOrderNo.trim()).orderByDesc(PayOrder::getId).last("LIMIT 1"))).map(this::toDomain);
    }

    @Override
    public Optional<PayOrderModel> findByTenantIdAndGatewayPaymentOrderNo(long tenantId, String gatewayPaymentOrderNo) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.lambdaQuery(PayOrder.class).eq(PayOrder::getTenantId, tenantId).eq(PayOrder::getGatewayPaymentOrderNo, gatewayPaymentOrderNo))).map(this::toDomain);
    }

    @Override
    public List<PayOrderModel> listByBizNotifyAndGatewaySyncAndGatewayNoPresent(PayBizNotifyStatus bizNotifyStatus, PayOrderGatewaySyncStatus gatewaySyncStatus) {
        return mapper.selectList(Wrappers.lambdaQuery(PayOrder.class).eq(PayOrder::getBizNotifyStatus, bizNotifyStatus).eq(PayOrder::getGatewaySyncStatus, gatewaySyncStatus).isNotNull(PayOrder::getGatewayPaymentOrderNo)).stream().map(this::toDomain).toList();
    }

    @Override
    public List<PayOrderModel> listGatewayReconcileCandidates(Date createdAfter, int limit) {
        LambdaQueryWrapper<PayOrder> w =
                Wrappers.lambdaQuery(PayOrder.class)
                        .eq(PayOrder::getGatewaySyncStatus, PayOrderGatewaySyncStatus.SUCCESS)
                        .isNotNull(PayOrder::getGatewayPaymentOrderNo)
                        .ge(createdAfter != null, PayOrder::getCreated, createdAfter)
                        .and(q -> q.isNull(PayOrder::getGatewayOrderStatus)
                                .or()
                                .in(
                                        PayOrder::getGatewayOrderStatus,
                                        PaymentStatus.PENDING.name(),
                                        PaymentStatus.INIT.name()))
                        .orderByAsc(PayOrder::getModified)
                        .last("LIMIT " + Math.max(1, limit));
        return mapper.selectList(w).stream().map(this::toDomain).toList();
    }

    @Override
    public List<PayOrderModel> listGatewaySyncFailureCandidates(Date createdAfter, Date pendingStaleBefore, int limit) {
        LambdaQueryWrapper<PayOrder> w =
                Wrappers.lambdaQuery(PayOrder.class)
                        .ne(PayOrder::getGatewaySyncStatus, PayOrderGatewaySyncStatus.SUCCESS)
                        .and(q -> q.isNull(PayOrder::getGatewayPaymentOrderNo).or().eq(PayOrder::getGatewayPaymentOrderNo, ""))
                        .and(q -> q.isNull(PayOrder::getBizNotifyStatus)
                                .or()
                                .ne(PayOrder::getBizNotifyStatus, PayBizNotifyStatus.NOTIFIED))
                        .ge(createdAfter != null, PayOrder::getCreated, createdAfter)
                        .and(q -> q.eq(PayOrder::getGatewaySyncStatus, PayOrderGatewaySyncStatus.FAILED)
                                .or(p -> p.eq(PayOrder::getGatewaySyncStatus, PayOrderGatewaySyncStatus.PENDING)
                                        .and(stale -> stale.and(m -> m.isNotNull(PayOrder::getModified)
                                                        .lt(PayOrder::getModified, pendingStaleBefore))
                                                .or(m -> m.isNull(PayOrder::getModified)
                                                        .lt(PayOrder::getCreated, pendingStaleBefore)))))
                        .orderByAsc(PayOrder::getModified)
                        .last("LIMIT " + Math.max(1, limit));
        return mapper.selectList(w).stream().map(this::toDomain).toList();
    }

    @Override
    public PayOrderPageSlice pageByTenantAndFilters(
            long tenantId,
            Date createdStart,
            Date createdEnd,
            String gatewayOrderStatus,
            PayChannel payChannel,
            String bizOrderNo,
            String gatewayPaymentOrderNo,
            int page,
            int pageSize) {
        Page<PayOrder> mpPage = new Page<>(page, pageSize);
        LambdaQueryWrapper<PayOrder> w =
                Wrappers.lambdaQuery(PayOrder.class)
                        .eq(PayOrder::getTenantId, tenantId)
                        .ge(createdStart != null, PayOrder::getCreated, createdStart)
                        .le(createdEnd != null, PayOrder::getCreated, createdEnd)
                        .eq(gatewayOrderStatus != null, PayOrder::getGatewayOrderStatus, gatewayOrderStatus)
                        .eq(payChannel != null, PayOrder::getPayChannel, payChannel)
                        .eq(bizOrderNo != null, PayOrder::getBizOrderNo, bizOrderNo)
                        .eq(gatewayPaymentOrderNo != null, PayOrder::getGatewayPaymentOrderNo, gatewayPaymentOrderNo)
                        .orderByDesc(PayOrder::getId);
        Page<PayOrder> out = mapper.selectPage(mpPage, w);
        return new PayOrderPageSlice(out.getRecords().stream().map(this::toDomain).toList(), out.getTotal());
    }

    @Override
    public PayOrderModel save(PayOrderModel order) {
        PayOrder entity = toEntity(order);
        if (entity.getId() == null) {
            mapper.insert(entity);
        } else {
            mapper.updateById(entity);
        }
        return toDomain(entity);
    }

    @Override
    public int casUpdateGatewaySyncFromFailedToPending(long id, long tenantId, String bizOrderNo) {
        return mapper.update(null, Wrappers.lambdaUpdate(PayOrder.class)
                .set(PayOrder::getGatewaySyncStatus, PayOrderGatewaySyncStatus.PENDING)
                .set(PayOrder::getGatewayOrderStatus, null)
                .set(PayOrder::getGatewayLastError, null)
                .eq(PayOrder::getId, id)
                .eq(PayOrder::getTenantId, tenantId).eq(PayOrder::getBizOrderNo, bizOrderNo)
                .eq(PayOrder::getGatewaySyncStatus, PayOrderGatewaySyncStatus.FAILED));
    }

    @Override
    public boolean casTransitionBizNotifyStatus(long id, PayBizNotifyStatus expectedCurrent, PayBizNotifyStatus newStatus) {
        return mapper.update(null, Wrappers.lambdaUpdate(PayOrder.class)
                        .set(PayOrder::getBizNotifyStatus, newStatus)
                        .eq(PayOrder::getId, id)
                        .eq(PayOrder::getBizNotifyStatus, expectedCurrent))
                == 1;
    }

    private PayOrderModel toDomain(PayOrder e) {
        return PayOrderModel.builder()
                .id(e.getId())
                .tenantId(e.getTenantId())
                .bizOrderNo(e.getBizOrderNo())
                .bizScene(e.getBizScene())
                .orderAmount(e.getOrderAmount())
                .subject(e.getSubject())
                .ext(e.getExt())
                .payMode(e.getPayMode())
                .payChannel(e.getPayChannel())
                .platformFee(e.getPlatformFee())
                .providerFee(e.getProviderFee())
                .netAmount(e.getNetAmount())
                .gatewayPaymentOrderNo(e.getGatewayPaymentOrderNo())
                .gatewaySyncStatus(e.getGatewaySyncStatus())
                .bizNotifyStatus(e.getBizNotifyStatus())
                .gatewayLastError(e.getGatewayLastError())
                .gatewayOrderStatus(e.getGatewayOrderStatus())
                .paidAt(e.getPaidAt())
                .created(e.getCreated())
                .modified(e.getModified())
                .build();
    }

    private PayOrder toEntity(PayOrderModel m) {
        PayOrder e = new PayOrder();
        e.setId(m.getId());
        e.setTenantId(m.getTenantId());
        e.setBizOrderNo(m.getBizOrderNo());
        e.setBizScene(m.getBizScene());
        e.setOrderAmount(m.getOrderAmount());
        e.setSubject(m.getSubject());
        e.setExt(m.getExt());
        e.setPayMode(m.getPayMode());
        e.setPayChannel(m.getPayChannel());
        e.setPlatformFee(m.getPlatformFee());
        e.setProviderFee(m.getProviderFee());
        e.setNetAmount(m.getNetAmount());
        e.setGatewayPaymentOrderNo(m.getGatewayPaymentOrderNo());
        e.setGatewaySyncStatus(m.getGatewaySyncStatus());
        e.setBizNotifyStatus(m.getBizNotifyStatus());
        e.setGatewayLastError(m.getGatewayLastError());
        e.setGatewayOrderStatus(m.getGatewayOrderStatus());
        e.setPaidAt(m.getPaidAt());
        e.setCreated(m.getCreated());
        e.setModified(m.getModified());
        return e;
    }
}
