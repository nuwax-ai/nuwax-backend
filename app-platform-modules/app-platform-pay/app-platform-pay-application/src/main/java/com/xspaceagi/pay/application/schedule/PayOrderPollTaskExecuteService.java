package com.xspaceagi.pay.application.schedule;

import com.xspaceagi.system.sdk.service.AbstractTaskExecuteService;
import com.xspaceagi.system.sdk.service.dto.ScheduleTaskDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import jakarta.annotation.Resource;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service("payOrderPollTaskExecuteService")
public class PayOrderPollTaskExecuteService extends AbstractTaskExecuteService {

    @Resource
    private PayOrderPollRunner payOrderPollRunner;

    @Override
    protected boolean execute(ScheduleTaskDto scheduleTask) {
        Long payOrderId = resolvePayOrderId(scheduleTask.getParams());
        if (payOrderId == null) {
            return true;
        }
        long maxExec = scheduleTask.getMaxExecTimes() != null ? scheduleTask.getMaxExecTimes() : PayOrderPollRunner.MAX_POLL_TICKS;
        long execTimes = scheduleTask.getExecTimes() != null ? scheduleTask.getExecTimes() : 0L;
        Long tenantId = resolveTenantId(scheduleTask.getParams());
        if (tenantId != null) {
            try {
                RequestContext.setThreadTenantId(tenantId);
                return payOrderPollRunner.pollOnce(payOrderId, tenantId, execTimes, maxExec);
            } finally {
                RequestContext.remove();
            }
        }
        return TenantFunctions.callWithIgnoreCheck(() -> payOrderPollRunner.pollOnce(payOrderId, null, execTimes, maxExec));
    }

    private static Long resolvePayOrderId(Object params) {
        if (params == null) {
            return null;
        }
        if (params instanceof Map<?, ?> map) {
            Object v = map.get("payOrderId");
            if (v instanceof Number n) {
                return n.longValue();
            }
            if (v instanceof String s && !s.isBlank()) {
                try {
                    return Long.parseLong(s.trim());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static Long resolveTenantId(Object params) {
        if (params == null) {
            return null;
        }
        if (params instanceof Map<?, ?> map) {
            Object v = map.get("tenantId");
            if (v instanceof Number n) {
                return n.longValue();
            }
            if (v instanceof String s && !s.isBlank()) {
                try {
                    return Long.parseLong(s.trim());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }
}
