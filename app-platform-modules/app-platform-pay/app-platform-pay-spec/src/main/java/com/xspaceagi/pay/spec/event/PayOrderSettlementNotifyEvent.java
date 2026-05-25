package com.xspaceagi.pay.spec.event;

import com.xspaceagi.pay.spec.dto.PayResultNotifyPayload;
import lombok.Value;

/**
 * 支付结果通知事件，由支付模块发布；其他模块通过 {@code @EventListener} 订阅。
 *
 * <p><b>订阅示例</b></p>
 *
 * <pre>{@code
 *     // 按场景过滤：仅处理订阅智能体单
 *     @EventListener(condition = "#event.payload.bizScene == 'SUBSCRIBE_AGENT'")
 *     public void onSubscribeAgentSettled(PayOrderSettlementNotifyEvent event) {
 *         PayResultNotifyPayload p = event.getPayload();
 *     }
 *
 *     // 或方法内判断多场景
 *     @EventListener
 *     public void onSubscribeAgent(PayOrderSettlementNotifyEvent event) {
 *         if (!"SUBSCRIBE_AGENT".equals(event.getPayload().getBizScene())) {
 *             return;
 *         }
 *     }
 * }</pre>
 *
 * <p>监听逻辑若较重，可在方法上再加 {@code @Async}（需启用异步支持），并保持幂等。</p>
 */
@Value
public class PayOrderSettlementNotifyEvent {

    PayResultNotifyPayload payload;
}
