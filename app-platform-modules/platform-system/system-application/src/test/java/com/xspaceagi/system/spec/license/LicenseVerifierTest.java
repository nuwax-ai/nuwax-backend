package com.xspaceagi.system.spec.license;

import com.xspaceagi.system.spec.enums.EditionEnum;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 授权码校验：Ed25519 验签 / 版本提取 / 到期与宽限 / 旧式 AES 兼容分支。
 */
class LicenseVerifierTest {

    /** 测试自建密钥对：签发用私钥，验签把公钥通过 override 传进去（不碰内置公钥） */
    private static KeyPair keyPair;
    private static KeyPair otherKeyPair;

    /**
     * 旧式 AES 授权串的密钥与 IV —— 取自历史实现（当时硬编码在源码里，已随 jar 公开）。
     * 测试自己加密，用来证明这种串现在一定被判无效。
     */
    private static final String LEGACY_HEX_KEY = "a0189de032115b6b7031a8fb5194ed68b3c716d3e09c21592fe4187b4586048b";
    private static final String LEGACY_HEX_IV = "0ac97ac7d1081ebae42bfd987c5739eb";

    @BeforeAll
    static void genKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        keyPair = generator.generateKeyPair();
        otherKeyPair = generator.generateKeyPair();
    }

    // ------------------------------------------------------------ 签名授权码

    @Test
    void 签名有效_能取到产品版本() {
        LicenseResult result = verify(issue(payload("aisp", future(365), null)));

        assertThat(result.getStatus()).isEqualTo(LicenseStatus.VALID);
        assertThat(result.getEdition()).isEqualTo(EditionEnum.AISP);
        assertThat(result.isUsable()).isTrue();
        assertThat(result.getPayload().getEdition()).isEqualTo("aisp");
    }

    @Test
    void 签名有效_aios也认() {
        assertThat(verify(issue(payload("aios", future(365), null))).getEdition()).isEqualTo(EditionEnum.AIOS);
    }

    @Test
    void 载荷被改动_验签失败且不贡献版本() {
        String legit = issue(payload("aisp", future(365), null));
        String[] parts = legit.split("\\.");
        String tamperedPayload = base64Url(payload("aios", future(3650), null).getBytes(StandardCharsets.UTF_8));
        String tampered = "NUWAX1." + tamperedPayload + "." + parts[2];

        LicenseResult result = verify(tampered);
        assertThat(result.getStatus()).isEqualTo(LicenseStatus.INVALID);
        // ★ 关键：验签没过就不能采信它声明的版本与到期日
        assertThat(result.getEdition()).isNull();
        assertThat(result.getPayload()).isNull();
    }

    @Test
    void 用别的密钥签_验签失败() throws Exception {
        String payloadPart = base64Url(payload("aisp", future(365), null).getBytes(StandardCharsets.UTF_8));
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(otherKeyPair.getPrivate());
        signer.update(payloadPart.getBytes(StandardCharsets.US_ASCII));
        String forged = "NUWAX1." + payloadPart + "." + base64Url(signer.sign());

        LicenseResult result = verify(forged);
        assertThat(result.getStatus()).isEqualTo(LicenseStatus.INVALID);
        assertThat(result.getEdition()).isNull();
    }

    @Test
    void 三段结构不合法() {
        assertThat(verify("NUWAX1.只有两段").getStatus()).isEqualTo(LicenseStatus.INVALID);
        assertThat(verify("NUWAX1.a.b.c").getStatus()).isEqualTo(LicenseStatus.INVALID);
        assertThat(verify("NUWAX1.!!!.???").getStatus()).isEqualTo(LicenseStatus.INVALID);
    }

    @Test
    void 载荷缺少到期日() {
        assertThat(verify(issue("{\"v\":1,\"edition\":\"aios\"}")).getStatus()).isEqualTo(LicenseStatus.INVALID);
        assertThat(verify(issue("{\"v\":1,\"edition\":\"aios\",\"expireAt\":\"不是日期\"}")).getStatus())
                .isEqualTo(LicenseStatus.INVALID);
    }

    // -------------------------------------------------------- 到期与宽限期

    @Test
    void 已过期_超出宽限期() {
        LicenseResult result = verify(issue(payload("aisp", past(30), 15)));

        assertThat(result.getStatus()).isEqualTo(LicenseStatus.EXPIRED);
        assertThat(result.isUsable()).isFalse();
        // 过期串仍是验签通过的串，版本照读（放行与否由调用方按档位决定）
        assertThat(result.getEdition()).isEqualTo(EditionEnum.AISP);
    }

    @Test
    void 已过期_仍在宽限期内() {
        LicenseResult result = verify(issue(payload("aisp", past(3), 15)));

        assertThat(result.getStatus()).isEqualTo(LicenseStatus.GRACE);
        assertThat(result.isInGrace()).isTrue();
        assertThat(result.isUsable()).isTrue();
    }

    @Test
    void 无宽限期配置_过期即失效() {
        assertThat(verify(issue(payload("aisp", past(1), null))).getStatus()).isEqualTo(LicenseStatus.EXPIRED);
    }

    // ---------------------------------------------------------- 设备绑定

    @Test
    void 载荷绑定了设备_本机命中则通过() {
        String license = issue(payloadWithDevices("aios", future(365), null,
                "[\"machine-id:AAA\",\"mac-en0:BBB\"]"));

        LicenseResult hitFirst = LicenseVerifier.verify(license, publicKeyBase64(), List.of("machine-id:AAA"));
        assertThat(hitFirst.getStatus()).isEqualTo(LicenseStatus.VALID);
        assertThat(hitFirst.getPayload()).as("命中就该采信载荷").isNotNull();

        // 绑多台机器：命中其中任意一台即可（列表是「允许的集合」，不是「必须全中」）
        LicenseResult hitSecond = LicenseVerifier.verify(license, publicKeyBase64(),
                List.of("dmi-uuid:ZZZ", "mac-en0:BBB"));
        assertThat(hitSecond.getStatus()).isEqualTo(LicenseStatus.VALID);
    }

    @Test
    void 载荷绑定了设备_本机不命中则绑定不符且不贡献版本() {
        String license = issue(payloadWithDevices("aisp", future(365), null, "[\"machine-id:AAA\"]"));

        LicenseResult result = LicenseVerifier.verify(license, publicKeyBase64(), List.of("machine-id:OTHER"));

        assertThat(result.getStatus()).isEqualTo(LicenseStatus.MISMATCH);
        assertThat(result.isPresent()).as("配了授权串，不是社区版形态").isTrue();
        assertThat(result.isUsable()).isFalse();
        // ★ 关键：拷到别的机器后连产品版本都贡献不了，否则「拷一份授权串」就等于换版本
        assertThat(result.getPayload()).isNull();
        assertThat(result.getEdition()).isNull();
        // 原因里要能看到本机指纹，客户拿它来换发即可，不必先教他跑命令
        assertThat(result.getReason()).contains("本机候选指纹").contains("machine-id:OTHER");
    }

    @Test
    void 载荷绑定了设备_本机一个指纹都没探测到时也不放行() {
        // 容器里 machine-id/主板 UUID/网卡都取不到时是「验不了」，不是「通过」—— fail-closed
        String license = issue(payloadWithDevices("aios", future(365), null, "[\"machine-id:AAA\"]"));

        assertThat(LicenseVerifier.verify(license, publicKeyBase64(), List.of()).getStatus())
                .isEqualTo(LicenseStatus.MISMATCH);
        assertThat(LicenseVerifier.verify(license, publicKeyBase64(), null).getStatus())
                .isEqualTo(LicenseStatus.MISMATCH);
    }

    @Test
    void 载荷没绑设备_任何机器都通过() {
        // 老授权串（无 bind 字段）与显式空列表，都等同「不绑定」，不做设备校验
        assertThat(verify(issue(payload("aios", future(365), null))).getStatus()).isEqualTo(LicenseStatus.VALID);
        assertThat(LicenseVerifier.verify(issue(payloadWithDevices("aios", future(365), null, "[]")),
                publicKeyBase64(), List.of("whatever")).getStatus()).isEqualTo(LicenseStatus.VALID);
        assertThat(LicenseVerifier.verify(issue(payloadWithDevices("aios", future(365), null, "null")),
                publicKeyBase64(), List.of()).getStatus()).isEqualTo(LicenseStatus.VALID);
    }

    @Test
    void 绑定不符优先于过期判定() {
        // 已经不在这台机器上了，「还能用到几号」没有意义，报「绑定不符」才有指向性
        String license = issue(payloadWithDevices("aios", past(30), 15, "[\"machine-id:AAA\"]"));

        LicenseResult result = LicenseVerifier.verify(license, publicKeyBase64(), List.of("machine-id:OTHER"));
        assertThat(result.getStatus()).isEqualTo(LicenseStatus.MISMATCH);
    }

    @Test
    void 设备指纹_同来源同值稳定_不同来源不互相冒充() {
        String once = DeviceFingerprint.of("machine-id", "abcdef");
        assertThat(DeviceFingerprint.of("machine-id", "abcdef")).as("同输入必须同输出").isEqualTo(once);
        assertThat(once).startsWith("machine-id:").doesNotContain("abcdef");
        // 来源名吃进哈希：不同来源即使原始值相同也不该撞出同一个指纹
        assertThat(DeviceFingerprint.of("dmi-uuid", "abcdef")).isNotEqualTo(once);
        assertThat(DeviceFingerprint.of("machine-id", "abcdefg")).isNotEqualTo(once);
    }

    @Test
    void 本机指纹探测_不抛异常且格式规范() {
        List<String> detected = DeviceFingerprint.detect();
        // 不假设探测到几个（CI 上可能一个都没有），但探测到的一定是「来源:base64url」形态
        for (String device : detected) {
            assertThat(device).matches("[a-z0-9-]+:[A-Za-z0-9_-]{22}");
        }
        assertThat(detected).as("不该有重复指纹").doesNotHaveDuplicates();
    }

    // ------------------------------------------------------ 旧式 AES 授权串

    @Test
    void 旧式AES授权串_一律无效() throws Exception {
        // 不管日期多新：旧格式已不兼容（密钥随历史 jar 公开、无版本位），只认签名串
        for (String legacy : new String[]{legacyAes("20990101"), legacyAes("20000101")}) {
            LicenseResult result = LicenseVerifier.verify(legacy, publicKeyBase64());
            assertThat(result.getStatus()).isEqualTo(LicenseStatus.INVALID);
            assertThat(result.getEdition()).isNull();
            assertThat(result.getPayload()).isNull();
            assertThat(result.getReason()).contains("旧式 AES 授权码已不再支持");
            assertThat(result.isPresent()).isTrue();
            assertThat(result.isUsable()).isFalse();
        }
    }

    @Test
    void 既不是签名串也解不开的乱串() {
        assertThat(verify("随便一串东西").getStatus()).isEqualTo(LicenseStatus.INVALID);
        assertThat(verify("aGVsbG8gd29ybGQ=").getStatus()).isEqualTo(LicenseStatus.INVALID);
    }

    // ------------------------------------------------------------ 未配置

    @Test
    void 未配置授权串_按社区版处理() {
        for (String blank : new String[]{null, "", "   "}) {
            LicenseResult result = LicenseVerifier.verify(blank, null);
            assertThat(result.getStatus()).isEqualTo(LicenseStatus.ABSENT);
            assertThat(result.isPresent()).isFalse();
            assertThat(result.isUsable()).isFalse();
            assertThat(result.getEdition()).isNull();
        }
    }

    // -------------------------------------------------------- 到期日解析

    @Test
    void 到期日解析兼容历史几种写法() {
        assertThat(LicenseVerifier.parseInstant("20270101")).isNotNull();
        assertThat(LicenseVerifier.parseInstant("2027-01-01")).isNotNull();
        assertThat(LicenseVerifier.parseInstant("2027/01/01")).isNotNull();
        assertThat(LicenseVerifier.parseInstant("2027-01-01 12:00:00")).isNotNull();
        assertThat(LicenseVerifier.parseInstant("2027-01-01T12:00:00Z")).isNotNull();
        assertThat(LicenseVerifier.parseInstant("2027-01-01T12:00:00+08:00")).isNotNull();
        // 签发工具与 Instant.toString() 的天然形态：带小数秒（点号是小数秒，不是日期分隔符）
        assertThat(LicenseVerifier.parseInstant("2027-10-08T18:42:45.244042Z")).isNotNull();
        assertThat(LicenseVerifier.parseInstant("2027-10-08T18:42:45.244Z")).isNotNull();
        assertThat(LicenseVerifier.parseInstant("2027-10-08T18:42:45Z")).isNotNull();
        assertThat(LicenseVerifier.parseInstant("2027-01-01 12:00:00.123")).isNotNull();

        assertThat(LicenseVerifier.parseInstant(null)).isNull();
        assertThat(LicenseVerifier.parseInstant("")).isNull();
        assertThat(LicenseVerifier.parseInstant("明天")).isNull();
    }

    // ------------------------------------------------------------------ 辅助

    private static LicenseResult verify(String content) {
        return LicenseVerifier.verify(content, publicKeyBase64());
    }

    private static String issue(String payloadJson) {
        try {
            String payloadPart = base64Url(payloadJson.getBytes(StandardCharsets.UTF_8));
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(keyPair.getPrivate());
            signer.update(payloadPart.getBytes(StandardCharsets.US_ASCII));
            return "NUWAX1." + payloadPart + "." + base64Url(signer.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String payload(String edition, String expireAt, Integer graceDays) {
        StringBuilder json = new StringBuilder("{\"v\":1,\"kid\":\"k1\",\"licId\":\"NWX-TEST\",\"customer\":\"测试客户\"")
                .append(",\"edition\":\"").append(edition).append("\",\"expireAt\":\"").append(expireAt).append("\"");
        if (graceDays != null) {
            json.append(",\"graceDays\":").append(graceDays);
        }
        return json.append("}").toString();
    }

    /** 带 {@code bind.devices} 的载荷；{@code devicesJson} 直接给 JSON 数组字面量（或 {@code null} 表示不带 bind） */
    private static String payloadWithDevices(String edition, String expireAt, Integer graceDays, String devicesJson) {
        String base = payload(edition, expireAt, graceDays);
        if (devicesJson == null) {
            return base;
        }
        return base.substring(0, base.length() - 1) + ",\"bind\":{\"devices\":" + devicesJson + "}}";
    }

    private static String future(int days) {
        return Instant.now().plus(days, ChronoUnit.DAYS).toString();
    }

    private static String past(int days) {
        return Instant.now().minus(days, ChronoUnit.DAYS).toString();
    }

    private static String publicKeyBase64() {
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 按旧实现的算法造一个旧式授权串（AES-256-CBC，明文是到期日字符串） */
    private static String legacyAes(String plainExpireDate) throws Exception {
        byte[] key = hexToBytes(LEGACY_HEX_KEY);
        byte[] iv = hexToBytes(LEGACY_HEX_IV);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return Base64.getEncoder().encodeToString(cipher.doFinal(plainExpireDate.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] hexToBytes(String hex) {
        byte[] data = new byte[hex.length() / 2];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) ((Character.digit(hex.charAt(i * 2), 16) << 4)
                    + Character.digit(hex.charAt(i * 2 + 1), 16));
        }
        return data;
    }
}
