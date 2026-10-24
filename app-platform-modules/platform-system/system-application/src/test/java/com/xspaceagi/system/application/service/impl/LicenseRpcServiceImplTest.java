package com.xspaceagi.system.application.service.impl;

import com.xspaceagi.system.spec.enums.EditionEnum;
import com.xspaceagi.system.spec.license.LicenseResult;
import com.xspaceagi.system.spec.license.LicenseStatus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 授权码读取（{@link LicenseRpcServiceImpl}）：结论 + 载荷 + 缓存。
 *
 * <p>缓存的核心语义是<b>按授权串隔离</b>：平台是多租户的，同一个 bean 会被不同租户的授权串调用，
 * 共用一个结论就会把先到租户的产品版本借给后到的租户。
 *
 * <p>授权串在测试里现签现验（Ed25519 临时密钥 + 反射注入公钥），不依赖签发机的 k1 私钥，
 * 也不受真授权码到期日影响。
 */
class LicenseRpcServiceImplTest {

    /**
     * 远未来：测「有效」用，写死字面量以保证任何时间跑结果都一样
     */
    private static final String FAR_FUTURE = "2099-01-01T00:00:00Z";

    /**
     * 本类自用的签发密钥对
     */
    private static final KeyPair KEY_PAIR = generateKeyPair();

    // ---------------------------------------------------------- 授权串缓存

    @Test
    void 有效授权串_取到结论与声明的版本() throws Exception {
        LicenseRpcServiceImpl impl = service();
        String content = license(payload("NWX-0001", "甲公司", "aisp", FAR_FUTURE, 7));

        LicenseResult result = impl.getResult(content);

        assertThat(result.getStatus()).isEqualTo(LicenseStatus.VALID);
        assertThat(result.isPresent()).isTrue();
        assertThat(result.isUsable()).isTrue();
        assertThat(result.getPayload().getLicId()).isEqualTo("NWX-0001");
        assertThat(result.getPayload().getCustomer()).isEqualTo("甲公司");
        assertThat(result.getEdition()).isEqualTo(EditionEnum.AISP);
        assertThat(impl.getLicensedEdition(content)).isEqualTo(EditionEnum.AISP);
    }

    @Test
    void 未配置授权串_按社区版形态且取不到版本() throws Exception {
        LicenseRpcServiceImpl impl = service();

        for (String blank : new String[]{null, "", "   "}) {
            LicenseResult result = impl.getResult(blank);
            assertThat(result.getStatus()).isEqualTo(LicenseStatus.ABSENT);
            assertThat(result.isPresent()).isFalse();
            assertThat(result.isUsable()).isFalse();
            assertThat(result.getEdition()).isNull();
            assertThat(impl.getLicensedEdition(blank)).isNull();
        }
    }

    @Test
    void 同一授权串_命中缓存返回同一个结论() throws Exception {
        LicenseRpcServiceImpl impl = service();
        String content = license(payload("NWX-0002", "甲公司", "aisp", FAR_FUTURE, 0));

        // 每次验签都会造一个新的 LicenseResult，对象同一即证明第二次没重新验签
        assertThat(impl.getResult(content)).isSameAs(impl.getResult(content));
    }

    @Test
    void 空白串共用一档缓存_不因来源不同而反复验签() throws Exception {
        LicenseRpcServiceImpl impl = service();

        assertThat(impl.getResult(null)).isSameAs(impl.getResult("   "));
    }

    // ------------------------------------------------ 按授权串隔离（本次修复的核心）

    @Test
    void 两个租户的授权串共用同一个bean_结论互不干扰() throws Exception {
        LicenseRpcServiceImpl impl = service();
        String tenantA = license(payload("NWX-A", "甲公司", "aisp", FAR_FUTURE, 0));
        String tenantB = license(payload("NWX-B", "乙公司", "aios", FAR_FUTURE, 0));

        // 交错多轮：先到的租户不能把自己的结论（尤其是产品版本）借给后到的租户
        for (int round = 0; round < 3; round++) {
            LicenseResult a = impl.getResult(tenantA);
            LicenseResult b = impl.getResult(tenantB);

            assertThat(a.getPayload().getCustomer()).isEqualTo("甲公司");
            assertThat(a.getEdition()).isEqualTo(EditionEnum.AISP);
            assertThat(b.getPayload().getCustomer()).isEqualTo("乙公司");
            assertThat(b.getEdition()).isEqualTo(EditionEnum.AIOS);
        }
    }

    @Test
    void 未配置的租户_不会被已配置的租户带成已授权() throws Exception {
        LicenseRpcServiceImpl impl = service();

        impl.getResult(license(payload("NWX-A", "甲公司", "aisp", FAR_FUTURE, 0)));

        assertThat(impl.getResult(null).getStatus()).isEqualTo(LicenseStatus.ABSENT);
        assertThat(impl.getResult("").getEdition()).isNull();
    }

    @Test
    void 授权串被换掉_立刻按新串给结论() throws Exception {
        LicenseRpcServiceImpl impl = service();

        String old = license(payload("NWX-A", "甲公司", "aisp", FAR_FUTURE, 0));
        String next = license(payload("NWX-B", "甲公司", "aios", FAR_FUTURE, 0));

        assertThat(impl.getResult(old).getEdition()).isEqualTo(EditionEnum.AISP);
        assertThat(impl.getResult(next).getEdition()).isEqualTo(EditionEnum.AIOS);
        assertThat(impl.getResult(old).getEdition()).isEqualTo(EditionEnum.AISP);
    }

    @Test
    void 并发下不同授权串的结论不串台() throws Exception {
        LicenseRpcServiceImpl impl = service();
        String tenantA = license(payload("NWX-A", "甲公司", "aisp", FAR_FUTURE, 0));
        String tenantB = license(payload("NWX-B", "乙公司", "aios", FAR_FUTURE, 0));

        int threads = 8;
        int rounds = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                boolean first = i % 2 == 0;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int round = 0; round < rounds; round++) {
                        LicenseResult result = impl.getResult(first ? tenantA : tenantB);
                        EditionEnum expectEdition = first ? EditionEnum.AISP : EditionEnum.AIOS;
                        String expectCustomer = first ? "甲公司" : "乙公司";
                        String actualCustomer = result.getPayload() == null
                                ? "无载荷" : result.getPayload().getCustomer();
                        if (result.getStatus() != LicenseStatus.VALID
                                || result.getEdition() != expectEdition
                                || !expectCustomer.equals(actualCustomer)) {
                            return String.format("拿到了别人的结论：%s / %s / %s",
                                    result.getStatus(), result.getEdition(), actualCustomer);
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<String> future : futures) {
                assertThat(future.get(30, TimeUnit.SECONDS)).isNull();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // -------------------------------------------------------------- 分档结论

    @Test
    void 到期日在昨天且在宽限期内_判宽限并放行() throws Exception {
        LicenseRpcServiceImpl impl = service();
        String content = license(payload("NWX-0003", "甲公司", "aisp",
                Instant.now().minus(1, ChronoUnit.DAYS).toString(), 30));

        LicenseResult result = impl.getResult(content);

        assertThat(result.getStatus()).isEqualTo(LicenseStatus.GRACE);
        assertThat(result.isUsable()).isTrue();
        assertThat(result.isInGrace()).isTrue();
        // 过期与否是放行策略，不该让产品版本在运行期飘
        assertThat(result.getEdition()).isEqualTo(EditionEnum.AISP);
    }

    @Test
    void 过期且超出宽限期_判过期但版本仍以授权串为准() throws Exception {
        LicenseRpcServiceImpl impl = service();
        String content = license(payload("NWX-0004", "甲公司", "aisp", "2020-01-01T00:00:00Z", 30));

        LicenseResult result = impl.getResult(content);

        assertThat(result.getStatus()).isEqualTo(LicenseStatus.EXPIRED);
        assertThat(result.isUsable()).isFalse();
        assertThat(result.getEdition()).isEqualTo(EditionEnum.AISP);
    }

    @Test
    void 授权串未声明版本_取不到版本() throws Exception {
        // 老授权串（或不填 edition 的）形态：平台侧取不到版本，两个商业版开关都不开
        LicenseRpcServiceImpl impl = service();
        String content = license(payload("NWX-0005", "甲公司", null, FAR_FUTURE, 0));

        LicenseResult result = impl.getResult(content);

        assertThat(result.getStatus()).isEqualTo(LicenseStatus.VALID);
        assertThat(result.getEdition()).isNull();
        assertThat(impl.getLicensedEdition(content)).isNull();
    }

    @Test
    void 授权串里的版本认不出_取不到版本() throws Exception {
        LicenseRpcServiceImpl impl = service();

        assertThat(impl.getResult(license(payload("NWX-0006", "甲公司", "不认识的版本", FAR_FUTURE, 0)))
                .getEdition()).isNull();
    }

    @Test
    void 设备绑定不符_不采信载荷里的版本() throws Exception {
        LicenseRpcServiceImpl impl = service();
        // 绑定的是一个本机不可能命中的指纹：无论跑在什么机器上都是 MISMATCH
        String content = license("{\"v\":1,\"licId\":\"NWX-0007\",\"customer\":\"丙公司\",\"edition\":\"aisp\","
                + "\"expireAt\":\"" + FAR_FUTURE + "\","
                + "\"bind\":{\"devices\":[\"machine-id:test-only-not-this-machine\"]}}");

        LicenseResult result = impl.getResult(content);

        assertThat(result.getStatus()).isEqualTo(LicenseStatus.MISMATCH);
        // 拷到别的机器也改不出产品版本
        assertThat(result.getEdition()).isNull();
    }

    // ------------------------------------------------------------ 无效授权串

    @Test
    void 旧式AES授权串_判无效并在原因里给出换发指引() throws Exception {
        LicenseRpcServiceImpl impl = service();

        LicenseResult result = impl.getResult("3f2a1b0c9d8e7f60");

        assertThat(result.getStatus()).isEqualTo(LicenseStatus.INVALID);
        assertThat(result.getReason()).contains("NUWAX1.").contains("license-issuer");
    }

    @Test
    void 段数不对_判无效() throws Exception {
        LicenseRpcServiceImpl impl = service();

        assertThat(impl.getResult("NUWAX1.只有两段").getStatus()).isEqualTo(LicenseStatus.INVALID);
    }

    @Test
    void 内容被改动过_判无效() throws Exception {
        LicenseRpcServiceImpl impl = service();
        String[] parts = license(payload("NWX-0008", "甲公司", "aisp", FAR_FUTURE, 0)).split("\\.");

        assertThat(impl.getResult(parts[0] + "." + parts[1] + "." + parts[2] + "AA").getStatus())
                .isEqualTo(LicenseStatus.INVALID);
    }

    @Test
    void 别家密钥签的授权串_在本平台判无效() throws Exception {
        LicenseRpcServiceImpl impl = service();
        String foreign = license(payload("NWX-0009", "甲公司", "aisp", FAR_FUTURE, 0),
                generateKeyPair().getPrivate());

        assertThat(impl.getResult(foreign).getStatus()).isEqualTo(LicenseStatus.INVALID);
    }

    // -------------------------------------------------------------- 版本解析

    @Test
    void 版本解析_大小写分隔符与别名不敏感() {
        assertThat(EditionEnum.parse("AIOS")).isEqualTo(EditionEnum.AIOS);
        assertThat(EditionEnum.parse(" AISP ")).isEqualTo(EditionEnum.AISP);
        assertThat(EditionEnum.parse("ai-service-platform")).isEqualTo(EditionEnum.AISP);
        assertThat(EditionEnum.parse("ai_service")).isEqualTo(EditionEnum.AISP);
    }

    @Test
    void 版本解析_空值与认不出返回null() {
        assertThat(EditionEnum.parse(null)).isNull();
        assertThat(EditionEnum.parse("")).isNull();
        assertThat(EditionEnum.parse("   ")).isNull();
        assertThat(EditionEnum.parse("不认识的版本")).isNull();
        assertThat(EditionEnum.codes()).isEqualTo("aios / aisp");
    }

    // ------------------------------------------------------------------ 夹具

    private static LicenseRpcServiceImpl service() throws Exception {
        return service(Base64.getEncoder().encodeToString(KEY_PAIR.getPublic().getEncoded()));
    }

    /**
     * @param publicKeyBase64 覆盖验签公钥（Base64 SPKI），对应 {@code license-verify.public-key}
     */
    private static LicenseRpcServiceImpl service(String publicKeyBase64) throws Exception {
        LicenseRpcServiceImpl impl = new LicenseRpcServiceImpl();
        setField(impl, "publicKey", publicKeyBase64);
        return impl;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = LicenseRpcServiceImpl.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /**
     * 用本类密钥对签一串不绑设备的授权码
     */
    private static String license(String payloadJson) {
        return license(payloadJson, KEY_PAIR.getPrivate());
    }

    private static String license(String payloadJson, PrivateKey privateKey) {
        try {
            String payload = base64Url(payloadJson.getBytes(StandardCharsets.UTF_8));
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKey);
            signer.update(payload.getBytes(StandardCharsets.US_ASCII));
            return "NUWAX1." + payload + "." + base64Url(signer.sign());
        } catch (Exception e) {
            throw new IllegalStateException("签发测试授权码失败", e);
        }
    }

    /**
     * 构造载荷 JSON；{@code edition} / {@code graceDays} 传 null 表示授权码不声明该字段
     */
    private static String payload(String licId, String customer, String edition, String expireAt, Integer graceDays) {
        StringBuilder json = new StringBuilder("{\"v\":1,\"kid\":\"test\",\"licId\":\"").append(licId)
                .append("\",\"customer\":\"").append(customer)
                .append("\",\"expireAt\":\"").append(expireAt).append('"');
        if (edition != null) {
            json.append(",\"edition\":\"").append(edition).append('"');
        }
        if (graceDays != null) {
            json.append(",\"graceDays\":").append(graceDays);
        }
        return json.append('}').toString();
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static KeyPair generateKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("生成 Ed25519 测试密钥失败", e);
        }
    }
}
