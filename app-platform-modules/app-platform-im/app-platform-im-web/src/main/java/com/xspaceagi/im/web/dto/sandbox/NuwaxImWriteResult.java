package com.xspaceagi.im.web.dto.sandbox;

/**
 * 沙箱 IM 技能 API <b>写</b>端点的统一响应体：{@code { bizMsgId, data }}。
 *
 * <h2>★为什么写端点要包一层，而不是直接返回 SDK 的 DTO</h2>
 *
 * <p>IM 的写操作靠 {@code bizMsgId} 幂等。而 {@code bizMsgId} 可能由<b>本层生成</b>
 * （调用方没传时）—— 若响应在网络层丢失，调用方手里就既没有结果、也没有幂等键，
 * 重试只能赌「上次到底成没成」。
 *
 * <p>把它回显出来，调用方就拿到了<b>安全重试的凭据</b>：用同一个 {@code bizMsgId} 重发，
 * 已经生效的那次会命中 IM 幂等（{@code code=IM_20005}，按契约视为成功），
 * 不会产生第二条消息。
 *
 * <p>★{@code data} 里的 {@code idempotentHit=true} 表示「这次是重放，消息是上一次发出去的」——
 * 判断「要不要再发一次」请看它，别只看 HTTP 200。
 *
 * <p>只读端点（详情 / 已读数 / 消息列表 / 成员列表）不加这层包装：它们没有幂等语义，
 * 多一个字段只会让调用方误以为可以重试。
 *
 * @param <T> SDK 返回的数据类型；{@code void} 类端点（撤回 / 改摘要 / 移成员）为 {@code null}
 */
public record NuwaxImWriteResult<T>(String bizMsgId, T data) {

    public static <T> NuwaxImWriteResult<T> of(String bizMsgId, T data) {
        return new NuwaxImWriteResult<>(bizMsgId, data);
    }
}
