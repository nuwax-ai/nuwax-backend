package com.xspaceagi.im.sdk.rpc;

import java.util.Set;

/**
 * ★SDK 自己的异常类（02 §12.2.5）—— <b>不依赖 IM 的 {@code ImBizException}</b>
 * （那是 {@code nuwax-im-common} 的类，引入即破坏 R10 零依赖）。
 *
 * <h2>★★失败三分类（02 §12.2.6）—— 调用方必须按 {@link #isRetryable()} 分流</h2>
 *
 * <pre>
 *   可重试（retryable=true） ：传输层失败（连接超时/拒绝、响应不可解析）
 *                             + 服务端明确的"暂时性"码（见 RETRYABLE_CODES）
 *   不可重试（retryable=false）：业务被拒（IM_2xxxx / IM_3xxxx）、参数与鉴权错误
 *                             （IM_90001 / IM_90004 / IM_90006）、本地序列化失败
 *   ★IM_20005（幂等命中）不抛异常 —— 契约规定"视为成功"，见 {@link #isIdempotentHit()}
 * </pre>
 *
 * <h2>★★为什么"未知错误码默认不可重试"</h2>
 *
 * <p>IM 的码表有 {@code Retry} 维度（{@code ImErrorCode}），但本 SDK 零依赖、读不到它，
 * 只能内置一份<b>白名单</b>。白名单的失败方向必须是"不可重试"，因为两种误判的代价不对称：
 * <ul>
 *   <li>把"可重试"误判成"不可重试" ⇒ 调用方少重试一次，<b>问题可见</b>
 *       （日志里有码、有人工介入点）；</li>
 *   <li>把"不可重试"误判成"可重试" ⇒ 业务已被拒（例如群已满 {@code IM_20101}）
 *       却进入重试循环，配合调用方的退避策略就是一条<b>持续打 IM 的流量</b>，
 *       而且因为每次都同结果，日志里只有一片"重试中"，<b>根因被淹没</b>。</li>
 * </ul>
 *
 * <h2>★{@code message} 里能放什么</h2>
 *
 * <p>只放服务端 {@code ReqResult.message} 原文（契约保证其中不含 token/secret/签名原文）
 * 与 SDK 侧自述的传输层描述。★<b>绝不</b>把请求体、签名、secret、响应体全文塞进来 ——
 * 异常对象最常见的去向恰好就是日志。
 */
public class ImRpcException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 成功码（与 {@code ReqResult.SUCCESS} 一致）。 */
    public static final String CODE_SUCCESS = "0000";

    /** ★幂等命中：契约规定"视为成功"，{@link ImRpcClient} <b>不</b>为它抛异常。 */
    public static final String CODE_IDEMPOTENT_HIT = "IM_20005";

    /**
     * ★IM 的兜底码（{@code ImErrorCode.UNKNOWN}，{@code Retry.YES}）。
     * SDK 侧"网络不可达 / 响应不可解析 / client 已关闭"这类<b>非服务端应答</b>的失败用它。
     */
    public static final String CODE_UNKNOWN = "0001";

    /**
     * ★可重试码白名单（取自 {@code ImErrorCode} 中 {@code Retry.YES} 且<b>会出现在
     * RPC 响应里</b>的那些）。
     *
     * <pre>
     *   0001         系统异常（含本 SDK 的传输层失败）
     *   IM_90002     服务繁忙（幂等占位进行中 / 服务端过载）
     *   IM_90005     内部调用超配额（退避后可重试）
     *   IM_30004     操作过于频繁（按 userId 频控）
     *   IM_30301     搜索鉴权不可用（fail-closed 的"稍后重试"）
     * </pre>
     *
     * ★注意 {@code IM_90004}（验签失败）<b>不在</b>表里：它在码表里是 {@code Retry.NO}，
     * 重试只会得到同样的拒绝，且重试会掩盖"密钥配错了"这个唯一根因。
     */
    private static final Set<String> RETRYABLE_CODES =
            Set.of(CODE_UNKNOWN, "IM_90002", "IM_90005", "IM_30004", "IM_30301");

    private final String code;
    private final String displayCode;
    private final String tid;
    private final boolean retryable;
    private final int httpStatus;
    private final boolean localProblem;

    private ImRpcException(String code, String displayCode, String message, String tid,
                               boolean retryable, int httpStatus, boolean localProblem, Throwable cause) {
        super(buildMessage(code, message, tid, httpStatus), cause);
        this.code = code;
        this.displayCode = displayCode;
        this.tid = tid;
        this.retryable = retryable;
        this.httpStatus = httpStatus;
        this.localProblem = localProblem;
    }

    // ================================================================== 工厂

    /** ★服务端应答里的业务错误（HTTP 200 + {@code code != 0000}）。分类依据是码表白名单。 */
    public static ImRpcException biz(String code, String displayCode, String message,
                                         String tid, int httpStatus) {
        return new ImRpcException(code, displayCode, message, tid,
                isRetryableCode(code), httpStatus, false, null);
    }

    /** ★传输层失败：连接拒绝 / 读超时 / 连接重置。请求<b>已经发出去了</b>（可能已被处理）。 */
    public static ImRpcException network(String detail, Throwable cause) {
        return new ImRpcException(CODE_UNKNOWN, null,
                "IM 服务不可达（" + detail + "）", null, true, -1, false, cause);
    }

    /** ★线程在等待响应时被中断（停机、上游取消）。中断标志由调用方恢复。 */
    public static ImRpcException interrupted(Throwable cause) {
        return new ImRpcException(CODE_UNKNOWN, null,
                "调用 IM 时线程被中断", null, true, -1, false, cause);
    }

    /** ★HTTP 非 2xx（契约下只有框架级错误会走到这里：404/405/413/500）。 */
    public static ImRpcException httpStatus(int status, String envelopeCode, String tid) {
        // ★状态码 5xx/408/429 属服务端暂时性故障 ⇒ 可重试；其余 4xx 是"请求本身不对" ⇒ 不可重试。
        boolean retryable = status >= 500 || status == 408 || status == 429;
        String code = envelopeCode != null && !envelopeCode.isBlank() ? envelopeCode : CODE_UNKNOWN;
        return new ImRpcException(code, null,
                "IM 返回 HTTP " + status + "（期望 200 —— 契约要求业务错误也用 HTTP 200）",
                tid, retryable, status, false, null);
    }

    /** ★响应不是合法 {@code ReqResult} 信封（契约被破坏 / 被中间层截断）。 */
    public static ImRpcException malformed(String detail, Throwable cause) {
        return new ImRpcException(CODE_UNKNOWN, null,
                "IM 响应不符合 ReqResult 契约（" + detail + "）", null, true, 200, false, cause);
    }

    /** ★请求体无法序列化 —— 本地 DTO 问题，重试必然同样失败，故<b>不可重试</b>。 */
    public static ImRpcException serialization(Throwable cause) {
        return new ImRpcException(CODE_UNKNOWN, null,
                "请求体无法序列化为 JSON", null, false, -1, true, cause);
    }

    /** ★client 已关闭（{@code close()} 之后调用）。 */
    public static ImRpcException closed() {
        return new ImRpcException(CODE_UNKNOWN, null,
                "ImRpcClient 已关闭，无法继续调用", null, false, -1, true, null);
    }

    /**
     * ★本地配置缺失 / 非法 —— <b>请求根本没发出去</b>。
     *
     * <p>与 {@link #biz} 的关键区别：这条错误里带得动「缺的是哪一项」。
     * 配置缺失时若直接发请求，服务端只会回一个不区分原因的 {@code IM_90004}（验签失败按契约
     * 不透露细节），排查只能靠猜 —— 所以宁可在本地就拦下来说清楚。
     */
    public static ImRpcException notConfigured(String detail) {
        return new ImRpcException(CODE_UNKNOWN, null,
                "IM RPC 未正确配置：" + detail, null, false, -1, true, null);
    }

    /** ★适配层被显式关闭（{@code im.adapter.enabled=false}）。★是<b>拒绝</b>，不是静默跳过。 */
    public static ImRpcException disabled() {
        return new ImRpcException(CODE_UNKNOWN, null,
                "IM RPC 适配已关闭（im.adapter.enabled=false）", null, false, -1, true, null);
    }

    // ================================================================== 访问器

    /** ★服务端错误码原文（IM_xxxxx）；传输层失败为 {@value #CODE_UNKNOWN}。 */
    public String getCode() {
        return code;
    }

    /** 面向用户展示的码（可空，服务端可选返回）。 */
    public String getDisplayCode() {
        return displayCode;
    }

    /** ★链路追踪 ID：排障时把它给到 IM 侧就能定位到那一次调用。传输层失败时为 null。 */
    public String getTid() {
        return tid;
    }

    /** ★true ⇒ 可带<b>同一个 bizMsgId</b> 重试（幂等闸门保证不重复执行）。 */
    public boolean isRetryable() {
        return retryable;
    }

    /** HTTP 状态码；未发生 HTTP 往返（连接失败/序列化失败）时为 -1。 */
    public int getHttpStatus() {
        return httpStatus;
    }

    /**
     * ★true ⇒ <b>请求从未离开本进程</b>：配置缺失、适配被关闭、序列化失败、client 已关闭。
     *
     * <p>与 {@link #isRetryable()} 的区别在于「谁该负责」：
     * <ul>
     *   <li>{@code isLocalProblem()=true} ⇒ 是<b>平台侧配置/代码</b>的问题，IM 什么都没收到。
     *       把它和「上游故障」混为一谈会让排查方向彻底跑偏（去查 IM 日志，而那边一条记录都没有）；</li>
     *   <li>{@link #network(String, Throwable)}（{@code localProblem=false}）⇒ 请求<b>已经发出去</b>，
     *       可能已被 IM 处理 —— 这正是幂等键存在的意义。</li>
     * </ul>
     */
    public boolean isLocalProblem() {
        return localProblem;
    }

    /** ★恒为 false：IM_20005 由 {@link ImRpcClient} 当成功返回，不会构造异常。 */
    public boolean isIdempotentHit() {
        return CODE_IDEMPOTENT_HIT.equals(code);
    }

    /**
     * ★码 → 可重试。规则见类注释 —— <b>白名单外一律不可重试</b>。
     * 公开为静态方法，方便调用方在拿到 {@code ReqResultDto}（而非异常）时复用同一判定。
     */
    public static boolean isRetryableCode(String code) {
        return code != null && RETRYABLE_CODES.contains(code);
    }

    private static String buildMessage(String code, String message, String tid, int httpStatus) {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(code).append("] ");
        sb.append(message == null ? "（无描述）" : message);
        if (tid != null) {
            sb.append(" tid=").append(tid);
        }
        if (httpStatus >= 0) {
            sb.append(" http=").append(httpStatus);
        }
        return sb.toString();
    }
}
