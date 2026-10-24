package com.xspaceagi.system.spec.license;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 授权码内层载荷（{@code NUWAX1.<base64url(payload)>.<sig>} 里的 payload）。
 *
 * <p>字段即授权维度；新增字段一律可选（老授权串缺字段时按默认值处理，不做严格校验）。
 */
@Data
public class LicensePayload {

    /** 载荷版本，当前 1 */
    private Integer v;

    /** 密钥标识，对应内置公钥表，用于将来轮换 */
    private String kid;

    /** 授权号，如 NWX-2026-0001 */
    private String licId;

    /** 授权给谁（客户名，可追溯） */
    private String customer;

    /** 项目/系统名 */
    private String project;

    /**
     * 产品版本：aios / aisp（与 {@code app.edition} 同取值域）。
     * <p>授权串里带了就以它为准，没有则回落到配置项 {@code app.edition}。
     */
    @Schema(example = "aios")
    private String edition;

    /**
     * 授权档位：community / commercial / oem。预留字段，当前平台侧不参与任何判定。
     * <p>注意与 {@link #edition} 区分：edition 是「哪个产品」，tier 是「哪一档授权」。
     */
    private String tier;

    /** 签发时间（ISO-8601，UTC） */
    private String issuedAt;

    /** 到期时间（ISO-8601，UTC） */
    private String expireAt;

    /** 宽限天数：到期后仍可用，但全端告警 */
    private Integer graceDays;

    /** 绑定信息 */
    private Bind bind;

    @Data
    public static class Bind {
        /**
         * 绑定的域名 / IP，支持 {@code *.example.com} 前缀通配；空表示不绑定。
         *
         * <p>⚠️ <b>平台侧尚未校验这一项</b>（2026-10-09）：域名只有在请求上下文里才拿得到，
         * 而同一个部署常被多个域名/内网直连访问，逐请求拒绝容易误伤自己。当前只用
         * {@link #devices} 做机器绑定 —— 待确认要不要启用域名绑定（见方案 §4.2）。
         */
        private List<String> hosts;

        /**
         * 绑定的设备指纹集合（{@code 来源:哈希}，见 {@code DeviceFingerprint}）；
         * 本机命中其中任意一个即通过。{@code null} 或空表示不绑定设备。
         */
        private List<String> devices;
    }
}
