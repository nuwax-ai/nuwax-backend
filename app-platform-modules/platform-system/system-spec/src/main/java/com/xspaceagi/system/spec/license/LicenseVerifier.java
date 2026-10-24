package com.xspaceagi.system.spec.license;

import com.alibaba.fastjson2.JSON;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Base64;
import java.util.Collection;
import java.util.List;

/**
 * 授权码校验：Ed25519 验签 + 载荷解析 + 到期判定。
 *
 * <p>授权串形态（一行，可直接填进 yml）：
 * <pre>NUWAX1.&lt;base64url(payload)&gt;.&lt;base64url(Ed25519 签名)&gt;</pre>
 * 签名对象是中间那段 base64url(payload) 的 ASCII 字节。
 *
 * <p>为什么是签名而不是对称加密：AES 的钥匙必须在验方手里才能开门，于是验方也能自己配一把
 * 钥匙去签发；Ed25519 的私钥只在公司签发机（{@code tools/license-issuer}），客户端只有公钥，
 * 能验不能签。
 *
 * <p><b>设备绑定</b>（2026-10-09 起）：载荷 {@code bind.devices} 声明允许的机器指纹集合，本机命中任意
 * 一个才通过，否则 {@link LicenseStatus#MISMATCH} —— 授权串拷贝到别的机器不生效。指纹怎么算见
 * {@link DeviceFingerprint}；未声明该字段的授权串不做设备校验。
 *
 * <p><b>不兼容旧式 AES 授权串</b>（2026-10-09 起）：历史那套「对称密钥硬编码在 jar 里、解出来只有一个到期日」
 * 的授权码一律判为无效 —— 它的密钥已随 jar 公开，谁都能自签一个 2099 年，且不含产品版本位。
 * 存量客户必须用 {@code tools/license-issuer} 换发签名授权码（见方案 §4.8）。
 */
public final class LicenseVerifier {

    public static final String PREFIX = "NUWAX1.";

    /** 内置验签公钥（kid=k1，SPKI DER 的 Base64）。公钥可以公开，编进 jar 无妨。 */
    public static final String BUILTIN_PUBLIC_KEY = "MCowBQYDK2VwAyEAlmP9vncOcOriQ4pP/fIf6fr1E4V1kpt1Fb0v3GTHexI=";

    /** {@code yyyy-MM-dd HH:mm:ss}，秒后的小数部分可有可无 */
    private static final DateTimeFormatter DATE_TIME = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .toFormatter();

    /**
     * 校验授权串（设备指纹按本机实际探测，见 {@link DeviceFingerprint#detect()}）。
     *
     * @param content           配置项 {@code license} 的值，可空
     * @param publicKeyOverride 覆盖内置公钥的 Base64 SPKI，空则用 {@link #BUILTIN_PUBLIC_KEY}
     */
    public static LicenseResult verify(String content, String publicKeyOverride) {
        return verify(content, publicKeyOverride, DeviceFingerprint.detect());
    }

    /**
     * 校验授权串。
     *
     * @param content           配置项 {@code license} 的值，可空
     * @param publicKeyOverride 覆盖内置公钥的 Base64 SPKI，空则用 {@link #BUILTIN_PUBLIC_KEY}
     * @param localDevices      本机候选设备指纹；授权码声明了 {@code bind.devices} 时必须有交集，
     *                          否则判 {@link LicenseStatus#MISMATCH}（单测靠这个参数注入，不依赖跑测机器）
     */
    public static LicenseResult verify(String content, String publicKeyOverride, Collection<String> localDevices) {
        if (StringUtils.isBlank(content)) {
            return LicenseResult.of(LicenseStatus.ABSENT, "未配置授权串，按社区版形态运行");
        }
        String trimmed = content.trim();
        if (!trimmed.startsWith(PREFIX)) {
            // 不解析、不猜测：不是签名串即无效（历史 AES 串走的就是这里）
            return LicenseResult.of(LicenseStatus.INVALID,
                    "授权码格式不合法：应以 " + PREFIX + " 开头（旧式 AES 授权码已不再支持，"
                            + "请用 tools/license-issuer 换发签名授权码）");
        }
        return verifySigned(trimmed, publicKeyOverride, localDevices);
    }

    // ------------------------------------------------------------ 签名授权码

    private static LicenseResult verifySigned(String content, String publicKeyOverride,
                                              Collection<String> localDevices) {
        String[] parts = content.split("\\.");
        if (parts.length != 3) {
            return LicenseResult.of(LicenseStatus.INVALID, "授权码格式不合法：应为 NUWAX1.<payload>.<signature> 三段");
        }
        byte[] payloadBytes;
        byte[] signature;
        try {
            payloadBytes = base64UrlDecode(parts[1]);
            signature = base64UrlDecode(parts[2]);
        } catch (IllegalArgumentException e) {
            return LicenseResult.of(LicenseStatus.INVALID, "授权码 base64url 解码失败");
        }

        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(resolvePublicKey(publicKeyOverride));
            verifier.update(parts[1].getBytes(StandardCharsets.US_ASCII));
            if (!verifier.verify(signature)) {
                return LicenseResult.of(LicenseStatus.INVALID, "授权码验签失败：内容被改动，或不是本公司签发的授权");
            }
        } catch (Exception e) {
            return LicenseResult.of(LicenseStatus.INVALID, "授权码验签异常：" + e.getMessage());
        }

        LicensePayload payload;
        try {
            payload = JSON.parseObject(new String(payloadBytes, StandardCharsets.UTF_8), LicensePayload.class);
        } catch (Exception e) {
            return LicenseResult.of(LicenseStatus.INVALID, "授权码载荷解析失败");
        }
        if (payload == null || StringUtils.isBlank(payload.getExpireAt())) {
            return LicenseResult.of(LicenseStatus.INVALID, "授权码载荷缺少 expireAt");
        }

        // 绑定校验放在到期判定之前：这串根本不属于本机时，「还能用到几号」没有意义
        LicenseResult mismatch = checkDeviceBinding(payload, localDevices);
        if (mismatch != null) {
            return mismatch;
        }

        Instant expireAt = parseInstant(payload.getExpireAt());
        if (expireAt == null) {
            return LicenseResult.of(LicenseStatus.INVALID, "授权码 expireAt 无法解析：" + payload.getExpireAt());
        }

        int graceDays = payload.getGraceDays() == null ? 0 : Math.max(0, payload.getGraceDays());
        Instant deadline = expireAt.plusSeconds(graceDays * 86400L);
        Instant now = Instant.now();

        LicenseStatus status;
        String reason;
        if (now.isAfter(deadline)) {
            status = LicenseStatus.EXPIRED;
            reason = String.format("授权已过期：%s（宽限 %d 天，截止 %s）", expireAt, graceDays, deadline);
        } else if (now.isAfter(expireAt)) {
            status = LicenseStatus.GRACE;
            reason = String.format("授权已到期，处于宽限期：原到期 %s，宽限截止 %s", expireAt, deadline);
        } else {
            status = LicenseStatus.VALID;
            reason = String.format("授权有效，%s 到期", expireAt);
        }

        LicenseResult result = LicenseResult.of(status, reason);
        result.setPayload(payload);
        return result;
    }

    // -------------------------------------------------------------- 设备绑定

    /**
     * 校验 {@code bind.devices}：授权码没绑设备（字段缺失或空列表）时一律通过 —— 老授权串照旧可用。
     *
     * <p>绑了就必须命中：本机候选指纹与绑定集合求交集。本机探测不到任何标识（如容器里既没有
     * machine-id 也没有主板 UUID、网卡也读不到）时同样判不符 —— 那是「验不了」，不是「通过」。
     *
     * @return 不符时返回 {@link LicenseStatus#MISMATCH} 的结果（**不带载荷**，避免拷到别的机器还能改版本）；
     * 相符或未绑定时返回 {@code null}
     */
    private static LicenseResult checkDeviceBinding(LicensePayload payload, Collection<String> localDevices) {
        List<String> bound = payload.getBind() == null ? null : payload.getBind().getDevices();
        if (bound == null || bound.isEmpty()) {
            return null;
        }
        Collection<String> local = localDevices == null ? List.of() : localDevices;
        for (String device : bound) {
            if (local.contains(device)) {
                return null;
            }
        }
        return LicenseResult.of(LicenseStatus.MISMATCH, String.format(
                "授权码已绑定设备，本机不在绑定范围内：授权号=%s，客户=%s，本机候选指纹=%s。"
                        + "把本机指纹发给签发方加进授权码（签发见 tools/license-issuer 的 fingerprint 子命令），"
                        + "或重新签发一份不做设备绑定的授权码",
                payload.getLicId(), payload.getCustomer(), local));
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 解析载荷里的 {@code expireAt}。签发工具产出的是 ISO 形态（{@code Instant.toString()}），
     * 这里另外容忍几种手写写法，免得手工签的串因为格式差一点被整条判无效：
     * 8 位纯数字（{@code 20270101}）、ISO（含小数秒与偏移）、{@code yyyy-MM-dd HH:mm:ss}、纯日期；
     * 纯日期形态下分隔符 {@code /} 与 {@code .} 归一成 {@code -}。
     *
     * <p>⚠️ 带时间部分的串**不能**把点号当分隔符：{@code Instant.toString()} 产出的小数秒
     * （{@code ...T18:42:45.244042Z}）会被归一成 {@code ...T18-42-45-244042Z} 而解析失败。
     *
     * @return 解析不出返回 null（调用方按无效处理，不静默放行）
     */
    public static Instant parseInstant(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        try {
            String value = raw.trim();
            boolean hasTime = value.contains("T") || value.contains(":");
            String normalized = value.replace('/', '-');
            if (!hasTime) {
                normalized = normalized.replace('.', '-');
            }
            if (normalized.matches("\\d{8}")) {
                return LocalDate.parse(normalized, DateTimeFormatter.BASIC_ISO_DATE)
                        .atStartOfDay(ZoneId.systemDefault()).toInstant();
            }
            if (normalized.contains("T")) {
                if (normalized.endsWith("Z")) {
                    return Instant.parse(normalized);
                }
                return OffsetDateTime.parse(normalized, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
            }
            if (normalized.contains(" ")) {
                return LocalDateTime.parse(normalized, DATE_TIME).atZone(ZoneId.systemDefault()).toInstant();
            }
            return LocalDate.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE)
                    .atStartOfDay(ZoneId.systemDefault()).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    private static PublicKey resolvePublicKey(String override) throws Exception {
        String base64 = StringUtils.isNotBlank(override) ? override.trim() : BUILTIN_PUBLIC_KEY;
        return KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
    }

    private static byte[] base64UrlDecode(String value) {
        int pad = (4 - value.length() % 4) % 4;
        return Base64.getUrlDecoder().decode(pad == 0 ? value : value + "=".repeat(pad));
    }

    private LicenseVerifier() {
    }
}
