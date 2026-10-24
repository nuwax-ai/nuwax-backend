package com.xspaceagi.im.sdk.rpc;

import com.xspaceagi.im.sdk.rpc.dto.ImCustomEventPushRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImCustomEventPushResultDto;

/**
 * Nuwax IM 自定义事件 RPC 服务（契约 {@code /internal/im/rpc/events/push}，1 个端点）。
 */
public interface IImEventRpcService {

    /**
     * {@code POST /internal/im/rpc/events/push} —— 推送一条用户自定义事件。
     *
     * <h2>★★这是<b>写</b>操作，但「成功」不等于「送达」</h2>
     *
     * <ul>
     *   <li>{@code bizMsgId} 必填，且它<b>同时就是帧体的 {@code eventId}</b>；</li>
     *   <li>帧取 P1 背压优先级、收件人离线即收不到、<b>不落库、无补偿</b>；</li>
     *   <li>投递失败<b>不报错</b>，返回三个 0（见 {@link ImCustomEventPushResultDto#nobodyReached()}）。</li>
     * </ul>
     *
     * <p>⇒ 需要「一定送达」的业务必须<b>自建对账</b>，不能把本方法当可靠信道用。
     */
    ImCustomEventPushResultDto pushCustomEvent(ImCustomEventPushRequest req);
}
