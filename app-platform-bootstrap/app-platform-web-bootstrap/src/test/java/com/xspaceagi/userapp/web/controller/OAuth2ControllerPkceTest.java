package com.xspaceagi.userapp.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xspaceagi.userapp.application.oauth2.OAuth2GrantContext;

/**
 * OAuth2 PKCE（RFC 7636）的单元测试——纯静态方法反射调用，不起 Spring 容器、不连库。
 * <p>
 * PKCE 的失效方式同样是静默的：校验写松 ⇒ 授权码被截获就能换令牌（PKCE 存在的唯一意义没了）；
 * 校验写严 ⇒ 合规客户端换不到令牌，而报错只有 {@code invalid_grant}，接入方看不出是自己错了还是平台错了。
 * 因此这里既测「对的必须过」，也测「错的必须不过」。
 */
class OAuth2ControllerPkceTest {

    /**
     * RFC 7636 Appendix B 的官方测试向量。
     * 用它而不是自己算一遍 SHA-256：自算的话「实现」与「期望值」出自同一套理解，
     * 两边一起错也测不出来；官方向量是唯一不依赖本仓库实现的外部基准。
     */
    private static final String RFC_VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";

    private static final String RFC_S256_CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

    private static final String METHOD_S256 = "S256";

    private static final String METHOD_PLAIN = "plain";

    private static boolean verify(OAuth2GrantContext context, String codeVerifier) {
        Boolean result = ReflectionTestUtils.invokeMethod(OAuth2Controller.class, "verifyPkce", context, codeVerifier);
        assertNotNull(result, "verifyPkce 返回了 null");
        return result;
    }

    private static OAuth2GrantContext context(String challenge, String method) {
        OAuth2GrantContext context = new OAuth2GrantContext();
        context.setClientId("nwxa_test");
        context.setUserId(1001L);
        context.setTenantId(1L);
        context.setScope("profile");
        context.setGrantType("authorization_code");
        context.setIssuedAt(1_700_000_000L);
        context.setCodeChallenge(challenge);
        context.setCodeChallengeMethod(method);
        return context;
    }

    // ================================================================== S256

    @Test
    @DisplayName("S256：RFC 7636 官方向量必须通过")
    void s256AcceptsRfcVector() {
        assertTrue(verify(context(RFC_S256_CHALLENGE, METHOD_S256), RFC_VERIFIER),
                "RFC 7636 Appendix B 的 verifier/challenge 配对未被接受，S256 实现有误");
    }

    @Test
    @DisplayName("S256：verifier 差一个字符就必须失败")
    void s256RejectsWrongVerifier() {
        OAuth2GrantContext context = context(RFC_S256_CHALLENGE, METHOD_S256);
        assertFalse(verify(context, RFC_VERIFIER.substring(0, RFC_VERIFIER.length() - 1) + "L"),
                "末位不同的 verifier 通过了校验");
        assertFalse(verify(context, RFC_VERIFIER.toLowerCase()), "大小写不同的 verifier 通过了校验");
        assertFalse(verify(context, "A" + RFC_VERIFIER.substring(1)), "首位不同的 verifier 通过了校验");
    }

    @Test
    @DisplayName("S256：challenge 是正确值的前缀 / 加长版都必须失败（防 startsWith 式实现）")
    void s256RejectsPartialChallenge() {
        assertFalse(verify(context(RFC_S256_CHALLENGE.substring(0, 42), METHOD_S256), RFC_VERIFIER));
        assertFalse(verify(context(RFC_S256_CHALLENGE + "A", METHOD_S256), RFC_VERIFIER));
    }

    @Test
    @DisplayName("S256：challenge 带首尾空白也应通过（authorize 端存的是 trim 后的值，这里是双保险）")
    void s256TrimsChallenge() {
        assertTrue(verify(context("  " + RFC_S256_CHALLENGE + "  ", METHOD_S256), RFC_VERIFIER));
        assertTrue(verify(context(RFC_S256_CHALLENGE, METHOD_S256), "  " + RFC_VERIFIER + "  "),
                "verifier 首尾空白应被 trim（表单提交常带换行）");
    }

    // ================================================================== plain

    @Test
    @DisplayName("plain：challenge 即 verifier 原文")
    void plainComparesVerbatim() {
        assertTrue(verify(context(RFC_VERIFIER, METHOD_PLAIN), RFC_VERIFIER));
        assertFalse(verify(context(RFC_VERIFIER, METHOD_PLAIN), RFC_VERIFIER + "x"));
    }

    @Test
    @DisplayName("method 缺省（null）按 plain 处理，与 RFC 7636 §4.3 一致")
    void missingMethodDefaultsToPlain() {
        assertTrue(verify(context(RFC_VERIFIER, null), RFC_VERIFIER), "method 为空时应按 plain 校验");
        assertFalse(verify(context(RFC_S256_CHALLENGE, null), RFC_VERIFIER),
                "method 为空却按 S256 校验了：authorize 端此时存的应是 plain challenge，两边口径必须一致");
    }

    @Test
    @DisplayName("method 大小写敏感：小写 s256 不按 S256 处理（authorize 端本就会拒，这里是 fail-safe）")
    void methodIsCaseSensitive() {
        assertFalse(verify(context(RFC_S256_CHALLENGE, "s256"), RFC_VERIFIER),
                "小写 s256 被当成 S256 了；RFC 6749/7636 的 method 取值大小写敏感");
    }

    // ================================================================== verifier 合法性（RFC 7636 §4.1）

    @Test
    @DisplayName("verifier 长度必须落在 43~128，越界即拒")
    void verifierLengthBounds() {
        String tooShort = "a".repeat(42);
        String min = "a".repeat(43);
        String max = "a".repeat(128);
        String tooLong = "a".repeat(129);
        assertFalse(verify(context(min, METHOD_PLAIN), tooShort), "42 字符的 verifier 通过了（下限失效）");
        assertTrue(verify(context(min, METHOD_PLAIN), min), "43 字符的 verifier 被拒（下限过严）");
        assertTrue(verify(context(max, METHOD_PLAIN), max), "128 字符的 verifier 被拒（上限过严）");
        assertFalse(verify(context(min, METHOD_PLAIN), tooLong), "129 字符的 verifier 通过了（上限失效）");
    }

    @Test
    @DisplayName("verifier 只允许 unreserved 字符集（ALPHA / DIGIT / - . _ ~），Base64 的 + / = 与空白都要拒")
    void verifierCharsetIsRestricted() {
        String legal = "abc-._~def" + "0".repeat(33);   // 43 位，全部合法字符
        assertEquals(43, legal.length());
        assertTrue(verify(context(legal, METHOD_PLAIN), legal), "合法字符集被误拒");
        for (String illegal : new String[] {
                "abc+ef" + "0".repeat(37),      // '+' 是 Base64 标准字母表的字符，不属 unreserved
                "abc/ef" + "0".repeat(37),
                "abc=ef" + "0".repeat(37),
                "abc ef" + "0".repeat(37),      // 空格
                "abc\tef" + "0".repeat(36),     // Tab
                "abc\nef" + "0".repeat(36),     // 换行（注意：trim 只去首尾，中间的去不掉）
                "abcéef" + "0".repeat(36) }) {  // 非 ASCII
            assertFalse(verify(context(illegal, METHOD_PLAIN), illegal),
                    "非法 verifier 通过了校验：" + illegal.replace("\t", "\\t").replace("\n", "\\n"));
        }
    }

    @Test
    @DisplayName("verifier 为 null / 空 / 纯空白时判失败，不抛异常")
    void missingVerifierIsRejectedQuietly() {
        OAuth2GrantContext context = context(RFC_S256_CHALLENGE, METHOD_S256);
        assertFalse(verify(context, null), "缺 code_verifier 却通过了——PKCE 被绕过");
        assertFalse(verify(context, ""));
        assertFalse(verify(context, "   "));
        assertFalse(verify(context, "\n\t"));
    }

    @Test
    @DisplayName("授权码里没有 challenge 时判失败（调用方须先判空跳过，这里是纵深防御）")
    void missingChallengeIsRejected() {
        assertFalse(verify(context(null, METHOD_S256), RFC_VERIFIER));
        assertFalse(verify(context("", METHOD_S256), RFC_VERIFIER));
        assertFalse(verify(context("   ", METHOD_S256), RFC_VERIFIER));
        assertFalse(verify(context(null, null), null));
    }

    // ================================================================== authorize 与 token 两端口径一致

    @Test
    @DisplayName("authorize 的长度常量与 token 的正则边界一致（改一处忘另一处会让合规客户端换不到令牌）")
    void authorizeLengthConstantsMatchTokenPattern() {
        Integer min = (Integer) ReflectionTestUtils.getField(OAuth2Controller.class, "PKCE_MIN_LENGTH");
        Integer max = (Integer) ReflectionTestUtils.getField(OAuth2Controller.class, "PKCE_MAX_LENGTH");
        Pattern pattern = (Pattern) ReflectionTestUtils.getField(OAuth2Controller.class, "PKCE_VERIFIER_PATTERN");
        assertNotNull(min);
        assertNotNull(max);
        assertNotNull(pattern);
        assertEquals(43, min, "RFC 7636 §4.1 规定 verifier 最短 43 位");
        assertEquals(128, max, "RFC 7636 §4.1 规定 verifier 最长 128 位");
        // authorize 允许的长度，token 端必须同样接受
        assertTrue(pattern.matcher("a".repeat(min)).matches(), "authorize 放行的最短长度被 token 端正则拒了");
        assertTrue(pattern.matcher("a".repeat(max)).matches(), "authorize 放行的最长长度被 token 端正则拒了");
        assertFalse(pattern.matcher("a".repeat(min - 1)).matches());
        assertFalse(pattern.matcher("a".repeat(max + 1)).matches());
    }

    // ================================================================== 授权上下文的序列化兼容

    /**
     * 与 {@code OAuth2AuthorizationServiceImpl} 同配置（默认 ObjectMapper，
     * {@code FAIL_ON_UNKNOWN_PROPERTIES = true}），确保测出的兼容性与生产一致。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("PKCE 字段能经 Redis 的 JSON 往返保留（丢了就等于 PKCE 静默失效）")
    void pkceFieldsSurviveJsonRoundTrip() throws Exception {
        String json = MAPPER.writeValueAsString(context(RFC_S256_CHALLENGE, METHOD_S256));
        assertTrue(json.contains("codeChallenge"), "序列化结果里没有 codeChallenge 字段");
        OAuth2GrantContext restored = MAPPER.readValue(json, OAuth2GrantContext.class);
        assertEquals(RFC_S256_CHALLENGE, restored.getCodeChallenge());
        assertEquals(METHOD_S256, restored.getCodeChallengeMethod());
        assertEquals("nwxa_test", restored.getClientId());
        assertEquals(1001L, restored.getUserId());
        assertTrue(verify(restored, RFC_VERIFIER), "往返后的上下文校验不过");
    }

    @Test
    @DisplayName("存量授权码（JSON 里没有 PKCE 字段）反序列化后两字段为 null，不抛异常")
    void legacyContextWithoutPkceFieldsStillDeserializes() throws Exception {
        String legacyJson = "{\"projectId\":7,\"clientId\":\"nwxa_old\",\"userId\":1001,\"tenantId\":1,"
                + "\"redirectUri\":\"https://app.example.com/cb\",\"scope\":\"profile\","
                + "\"grantType\":\"authorization_code\",\"issuedAt\":1700000000}";
        OAuth2GrantContext restored = MAPPER.readValue(legacyJson, OAuth2GrantContext.class);
        assertNull(restored.getCodeChallenge(), "存量授权码应解出 null，token 端据此跳过 PKCE 校验");
        assertNull(restored.getCodeChallengeMethod());
        assertEquals("nwxa_old", restored.getClientId());
    }

    @Test
    @DisplayName("遇到未知字段不抛异常（滚动发布时老实例读到新实例签发的令牌不能判为无效）")
    void unknownFieldsAreIgnored() throws Exception {
        String forwardJson = "{\"clientId\":\"nwxa_new\",\"userId\":1001,\"codeChallenge\":\"" + RFC_S256_CHALLENGE
                + "\",\"codeChallengeMethod\":\"S256\",\"someFieldFromTheFuture\":\"x\"}";
        OAuth2GrantContext restored = MAPPER.readValue(forwardJson, OAuth2GrantContext.class);
        assertEquals("nwxa_new", restored.getClientId());
        assertEquals(RFC_S256_CHALLENGE, restored.getCodeChallenge());
    }
}
