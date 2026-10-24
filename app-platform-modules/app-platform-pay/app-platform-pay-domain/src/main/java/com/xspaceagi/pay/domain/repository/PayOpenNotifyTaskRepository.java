package com.xspaceagi.pay.domain.repository;

import com.xspaceagi.pay.domain.model.PayOpenNotifyTaskModel;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/** 开放支付 webhook 通知任务仓储 */
public interface PayOpenNotifyTaskRepository {

    PayOpenNotifyTaskModel save(PayOpenNotifyTaskModel task);

    Optional<PayOpenNotifyTaskModel> findByTenantIdAndGatewayOrderNo(long tenantId, String gatewayOrderNo);

    Optional<PayOpenNotifyTaskModel> findById(long id);

    /** 待处理扫描：WAIT_PAY（查订单是否 PAID）或 PENDING 且到达重试时间 */
    List<PayOpenNotifyTaskModel> listRunnable(int limit);

    /** 清理长期未支付的等待任务（订单早已非终态/超时） */
    List<PayOpenNotifyTaskModel> listStaleWaiting(Date createdBefore, int limit);
}
