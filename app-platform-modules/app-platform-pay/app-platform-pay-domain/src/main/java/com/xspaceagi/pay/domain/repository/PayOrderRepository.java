package com.xspaceagi.pay.domain.repository;

import com.xspaceagi.pay.domain.model.PayOrderModel;
import com.xspaceagi.pay.domain.model.PayOrderPageSlice;
import com.xspaceagi.pay.spec.enums.PayBizNotifyStatus;
import com.xspaceagi.pay.sdk.enums.PayChannel;
import com.xspaceagi.pay.spec.enums.PayOrderGatewaySyncStatus;
import java.util.Date;
import java.util.List;
import java.util.Optional;

public interface PayOrderRepository {

    Optional<PayOrderModel> findById(Long id);

    /** 调度/内部任务按主键加载时显式带租户，避免多租户插件与线程上下文不一致时误判为「无此单」。 */
    Optional<PayOrderModel> findByTenantIdAndId(long tenantId, long id);

    Optional<PayOrderModel> findByTenantIdAndBizOrderNo(long tenantId, String bizOrderNo);

    Optional<PayOrderModel> findLatestByTenantIdAndBizOrderNo(long tenantId, String bizOrderNo);

    Optional<PayOrderModel> findByTenantIdAndGatewayPaymentOrderNo(long tenantId, String gatewayPaymentOrderNo);

    List<PayOrderModel> listByBizNotifyAndGatewaySyncAndGatewayNoPresent(
            PayBizNotifyStatus bizNotifyStatus, PayOrderGatewaySyncStatus gatewaySyncStatus);

    /**
     * 网关状态补偿候选：已同步网关、有网关单号，且本地 {@code gatewayOrderStatus} 仍未终态（不限制 {@code bizNotifyStatus}）。
     *
     * @param createdAfter 仅包含该时间之后创建的订单
     * @param limit 单次扫描上限
     */
    List<PayOrderModel> listGatewayReconcileCandidates(Date createdAfter, int limit);

    /**
     * 网关建单未成功补偿候选：无网关单号、同步非 SUCCESS，且本地尚未 FAILED+NOTIFIED 闭环。
     * {@link PayOrderGatewaySyncStatus#PENDING} 仅当 {@code modified/created} 早于 {@code pendingStaleBefore} 才入选（避免与进行中的建单抢跑）。
     */
    List<PayOrderModel> listGatewaySyncFailureCandidates(Date createdAfter, Date pendingStaleBefore, int limit);

    PayOrderPageSlice pageByTenantAndFilters(
            long tenantId,
            Date createdStart,
            Date createdEnd,
            String gatewayOrderStatus,
            PayChannel payChannel,
            String bizOrderNo,
            String gatewayPaymentOrderNo,
            int page,
            int pageSize);

    PayOrderModel save(PayOrderModel order);

    /**
     * 将 FAILED 同步状态 CAS 为 PENDING，用于失败重试防并发。
     *
     * @return 影响行数，1 表示抢占成功
     */
    int casUpdateGatewaySyncFromFailedToPending(long id, long tenantId, String bizOrderNo);

    /**
     * 业务通知状态 CAS 迁移（如 POLLING → NOTIFIED / TIMEOUT）。
     *
     * @return true 表示当前实例赢得竞争
     */
    boolean casTransitionBizNotifyStatus(long id, PayBizNotifyStatus expectedCurrent, PayBizNotifyStatus newStatus);
}
