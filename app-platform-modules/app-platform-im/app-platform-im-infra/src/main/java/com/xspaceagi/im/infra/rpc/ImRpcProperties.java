package com.xspaceagi.im.infra.rpc;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Nuwax IM 内部 RPC 适配配置（前缀 {@code im.adapter}）。
 *
 * <h2>★★{@link #toString()} 手写，{@code secret} 永不出现</h2>
 *
 * <p>Lombok 的 {@code @Data} 会自动生成 {@code toString()} 把<b>所有</b>字段拼进去 —— 包括
 * {@code secret}。于是任何一行「顺手打一下配置」的日志、参数校验失败时的异常信息、
 * 甚至 IDE 调试器的变量视图导出，都会把 <b>HMAC 密钥落到日志文件里</b>，
 * 而日志的流转范围（采集 / 归档 / 转发 / 第三方）远超配置文件。
 * ⇒ 下面覆写 {@code toString()}，{@code secret} 一律打成 {@code ***}。
 *
 * <h2>★{@code baseUrl} 必须是「<b>调用方</b>能访问到的那个地址」</h2>
 *
 * <p>IM 的 {@code /internal/im/rpc/**} <b>不经公网前门</b>（nginx 只挂了
 * {@code /api/instant-message/} 与 {@code /instant-message/}），且服务端配了
 * IP 白名单 {@code 10.0.0.0/8} + {@code 192.168.0.0/16}。
 * ⇒ 这里填内网直连地址（形如 {@code http://192.168.31.101:18092}），
 * <b>不要</b>填对外域名 —— 填错的表现是 HTTP 405（nginx 拒 POST）或 404，
 * 而不是「验签失败」。
 */
@Data
@Component
@ConfigurationProperties(prefix = "nuwax.im.adapter")
public class ImRpcProperties {

    /** 是否启用 IM RPC 适配。关闭后调用一律抛 {@code ImRpcException}（<b>拒绝</b>，不是静默跳过）。 */
    private boolean enabled = false;

    /**
     * IM 业务平面地址（内网直连，如 {@code http://192.168.31.101:18092}；末尾斜杠会被去掉）。
     *
     * <p>★填对外域名（{@code https://testagent.xspaceagi.com}）会得到 HTTP 405 ——
     * 那个域名下 {@code /internal} 没有出口。
     */
    private String baseUrl = "";

    /** 调用方标识（决定配额与密钥索引）。本模块固定用 {@code app-platform-im}。 */
    private String appId = "app-platform-im";

    /** 与 {@code appId} 一一对应的 HMAC 密钥。★绝不入日志、绝不入库、绝不回显。 */
    private String secret = "";

    /** 契约版本，当前固定 {@code v1}；服务端不识别 → {@code IM_90006}。 */
    private String apiVersion = "v1";

    /** 单次请求超时（读）。RPC 端点最慢的写操作在 P99 上是百毫秒级，3s 足够。 */
    private Duration timeout = Duration.ofSeconds(3);

    /** 连接超时；默认与 {@link #timeout} 一致。 */
    private Duration connectTimeout = Duration.ofSeconds(3);

    /** ★手写覆盖 Lombok：{@code secret} 不出现。改这里之前请先读类注释。 */
    @Override
    public String toString() {
        return "ImRpcProperties[enabled=" + enabled
                + ", baseUrl=" + baseUrl
                + ", appId=" + appId
                + ", secret=***"
                + ", apiVersion=" + apiVersion
                + ", timeout=" + timeout
                + ", connectTimeout=" + connectTimeout + "]";
    }

    /** ★配置是否完整到「可以发请求」。缺任何一项都不该发出去白挨一次 {@code IM_90004}。 */
    public boolean usable() {
        return enabled
                && baseUrl != null && !baseUrl.isBlank()
                && appId != null && !appId.isBlank()
                && secret != null && !secret.isBlank();
    }
}
