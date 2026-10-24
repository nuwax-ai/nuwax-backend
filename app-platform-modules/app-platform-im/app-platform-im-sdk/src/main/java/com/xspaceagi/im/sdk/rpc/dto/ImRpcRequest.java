package com.xspaceagi.im.sdk.rpc.dto;

/**
 * ★所有 RPC 请求的公共形态（契约 {@code RpcRequestBase}）。
 *
 * <h2>★★这个接口不只是"好看"——它是一道<b>编译期</b>保险</h2>
 *
 * <p>15 个请求 record 全部 {@code implements ImRpcRequest}，而 record 的访问器方法
 * 恰好就是 {@code userId()/tenantId()/traceId()/bizMsgId()}。于是：
 * <ul>
 *   <li>任何一个请求 DTO 漏掉基础字段（比如新加请求时忘了 {@code bizMsgId}）
 *       ⇒ <b>编译不过</b>，而不是等到运行时收到 {@code IM_90001} 才发现；</li>
 *   <li>调用方可以写通用的封装（统一注入 tenantId、统一重试、统一埋点），
 *       不必对 13 个类型各写一遍。</li>
 * </ul>
 *
 * <h2>★为什么 {@code bizMsgId} 在这里是"必填"，而读操作却不校验它</h2>
 *
 * <p>契约把 {@code bizMsgId} 放进基类，是给<b>写操作</b>用的幂等键；
 * 读操作（{@code messages/get}、{@code messages/read-counts}、{@code conversations/members/list}）
 * 虽然同样继承基类，但服务端跳过幂等闸门（{@code ImRpcAuthFilter.READ_ONLY_PATHS}），
 * 缺失<b>不会</b>报错。⇒ 调用方对读操作可以传 {@code null}，
 * 但本接口保留该访问器，以便"同一套封装同时处理读写"。
 *
 * <p>★返回值全部是<b>包装类型</b>：{@code null} 表示未设置，与 0 严格区分。
 */
public interface ImRpcRequest {

    /** ★以谁的身份执行（必填）。服务端 {@code runAs} 切换身份后走与 {@code /api} 相同的应用服务。 */
    Long userId();

    /** ★租户 ID（一期必填；缺失会让服务端多一次平台反查）。 */
    Long tenantId();

    /** 链路追踪透传（可空；非空时同时用作 HTTP 头 {@code X-Request-Id}）。 */
    String traceId();

    /** ★幂等键（写操作必填，≤64）。重试时<b>必须复用同一个值</b>，否则等于没有幂等。 */
    String bizMsgId();
}
