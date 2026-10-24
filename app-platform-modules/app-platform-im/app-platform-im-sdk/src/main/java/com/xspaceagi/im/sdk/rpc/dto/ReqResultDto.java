package com.xspaceagi.im.sdk.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xspaceagi.im.sdk.rpc.ImRpcException;

/**
 * ★统一信封（与 {@code com.nuwax.im.common.api.ReqResult} 字段逐个一致，契约 {@code ReqResult} schema）。
 *
 * <h2>★为什么 SDK 自带一份（而不用公共模块里的那份）</h2>
 *
 * <p>R10：本构件必须零 {@code com.nuwax.im:*} 依赖 —— 平台侧引入 SDK 时
 * 不该被顺带拖进 IM 的 common/domain/web 一整套。漂移由契约测试兜住。
 *
 * <h2>★为什么加 {@code @JsonIgnoreProperties(ignoreUnknown = true)}</h2>
 *
 * <p>服务端可能返回比本类<b>更多</b>的字段（例如幂等命中时给 {@code data} 对象补
 * {@code idempotentHit}，而某些 data 类型在契约里没有声明该字段）。
 * 不加这个注解，Jackson 会在"服务端加了个新字段"时直接抛
 * {@code UnrecognizedPropertyException} —— 一次<b>向后兼容的服务端演进</b>
 * 就变成所有老客户端的硬故障。宽容解析在这里是正确取向：
 * 新增字段不影响老客户端对已知字段的读取。
 *
 * <h2>★data 的泛型</h2>
 *
 * <p>用 {@code ReqResultDto<ImMessageDto>} 这样的参数化类型配合
 * {@code TypeReference} 反序列化；void 端点的 data 恒为 {@code null}
 * （契约硬约定，★幂等命中时也是 null，命中与否由 {@code code=IM_20005} 表达）。
 *
 * @param code        业务码：{@code 0000} 成功、{@code IM_20005} 幂等命中、其余为错误
 * @param displayCode 面向用户展示的码（可空）
 * @param message     错误描述（成功时通常为 null；契约保证不含 token/secret/签名原文）
 * @param data        业务数据（void 端点恒为 null）
 * @param tid         链路追踪 ID（回显 X-Request-Id 或服务端生成）
 * @param <T>         data 的具体类型
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReqResultDto<T>(String code,
                              String displayCode,
                              String message,
                              T data,
                              String tid) {

    /** ★严格成功（{@code code == 0000}）。 */
    public boolean isSuccess() {
        return ImRpcException.CODE_SUCCESS.equals(code);
    }

    /** ★幂等命中（{@code code == IM_20005}）：契约规定视为成功，但"本次没有真的执行"。 */
    public boolean isIdempotentHit() {
        return ImRpcException.CODE_IDEMPOTENT_HIT.equals(code);
    }

    /** ★成功或幂等命中（调用方只关心"这件事成了"时用这个）。 */
    public boolean isOk() {
        return isSuccess() || isIdempotentHit();
    }

    /** ★该码是否可重试 —— 与 {@link ImRpcException#isRetryableCode(String)} 同一份白名单。 */
    public boolean isRetryable() {
        return ImRpcException.isRetryableCode(code);
    }
}
