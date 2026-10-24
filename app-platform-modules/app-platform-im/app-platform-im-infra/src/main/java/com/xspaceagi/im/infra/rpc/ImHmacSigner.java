package com.xspaceagi.im.infra.rpc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * HMAC-SHA256 签名器 —— 生成 {@code X-Im-Signature}。
 *
 * <pre>
 *   签名串          = appId + "\n" + timestamp + "\n" + nonce + "\n" + hex(sha256(body))
 *   X-Im-Signature  = hex(HmacSHA256(secret, 签名串))
 * </pre>
 *
 * <h2>★★这是与 IM 侧<b>逐字对齐</b>的一份实现</h2>
 *
 * <p>服务端一份（{@code com.nuwax.im.core.web.rpc.ImRpcSignature}），本模块一份。
 * 两份实现由<b>固定签名向量测试</b>锁定：{@code ImHmacSignerTest} 里硬编码了
 * {@code secret/appId/timestamp/nonce/body → 期望签名}，任何一侧改了签名串拼接方式、
 * 十六进制大小写或字符集，都会立刻变红。
 *
 * <h2>★字符集与编码细节（漂移最常发生的地方）</h2>
 *
 * <ul>
 *   <li>所有文本按 <b>UTF-8</b> 编码 —— 两端不一致时中文 appId/body 会签名不符；</li>
 *   <li>十六进制输出<b>小写</b>；</li>
 *   <li>分隔符是 <b>{@code \n}（0x0A）</b>，不是 {@code \r\n}、不是空格；</li>
 *   <li>body 为 {@code null} 时按<b>空字节数组</b>取摘要（与「空 body」等价）。</li>
 * </ul>
 *
 * <h2>★为什么一定要签 body 摘要</h2>
 *
 * <p>只签 header 三件套的话，同一时间窗（±300s）内 body 可被中间人任意改写
 * （「给张三发 100」→「给李四发 10000」）而签名依旧有效。
 */
public final class ImHmacSigner {

    private ImHmacSigner() {
    }

    /**
     * 一步到位：算出 {@code X-Im-Signature} 的值。
     *
     * @param secret    与 appId 对应的密钥
     * @param appId     调用方标识
     * @param timestamp 毫秒时间戳（服务端要求 13 位、|now-ts| ≤ 300s）
     * @param nonce     随机串（≤64，服务端 SETNX 去重，同一 nonce 用第二次 → IM_90004）
     * @param payload   请求体原始字节（★必须是<b>发出去的那串字节</b>，不能拿「重新序列化的对象」顶替）
     */
    public static String sign(String secret, String appId, long timestamp, String nonce, byte[] payload) {
        return hmacSha256Hex(secret,
                signingString(appId, Long.toString(timestamp), nonce, sha256Hex(payload)));
    }

    /** 签名串（{@code appId⏎ts⏎nonce⏎bodyHash}）。公开出来供测试逐段核对。 */
    public static String signingString(String appId, String timestamp, String nonce, String bodyHashHex) {
        return appId + "\n" + timestamp + "\n" + nonce + "\n" + bodyHashHex;
    }

    /** {@code hex(sha256(payload))}；{@code payload == null} 视作空数组。 */
    public static String sha256Hex(byte[] payload) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(payload == null ? new byte[0] : payload));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，走到这里说明 JRE 被裁剪过 —— 属环境问题，不是调用问题。
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** {@code hex(HmacSHA256(secret, data))}。异常信息<b>不带 secret</b>。 */
    public static String hmacSha256Hex(String secret, String data) {
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("HMAC 密钥为空（检查 im.adapter.secret）");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("HmacSHA256 不可用", e);
        } catch (InvalidKeyException e) {
            throw new IllegalStateException("HMAC 密钥非法", e);
        }
    }

    /**
     * constant-time 比较（大小写不敏感）。
     *
     * <p>用 {@link MessageDigest#isEqual} 而不是 {@code String.equals}：
     * 后者「前缀命中即继续、不命中即返回」的短路行为会被计时攻击逐字节猜出正确签名。
     * 本方法用于桩服务端 / 自测，客户端自身不校验响应签名。
     */
    public static boolean matches(String expected, String presented) {
        if (expected == null || presented == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.toLowerCase().getBytes(StandardCharsets.UTF_8),
                presented.toLowerCase().getBytes(StandardCharsets.UTF_8));
    }
}
