package com.xspaceagi.im.infra.rpc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ImHmacSigner} 的固定向量测试。
 *
 * <h2>★★这些期望值不是「跑一遍 Java 把输出粘过来」</h2>
 *
 * <p>那样做只能证明「代码没变」，不能证明「算对了」—— 拼错分隔符、用错大小写，
 * 生成的和期望的一起错，测试照样绿。
 *
 * <p>本类的期望值由一段<b>独立实现</b>推导（Python 的 {@code hashlib}/{@code hmac}，
 * 直接照契约描述拼接签名串），两套实现只有「都按契约来」这一个交集。
 */
class ImHmacSignerTest {

    /** 含中文 —— 顺手锁住 UTF-8。两端字符集不一致时中文密钥/body 会签名不符。 */
    private static final String SECRET = "test-secret-平台-密钥";
    private static final String APP_ID = "app-platform-im";
    private static final long TIMESTAMP = 1700000000000L;
    private static final String NONCE = "n0nce-abc-123";

    @Test
    @DisplayName("固定向量：签名与独立实现（Python hmac）逐字符一致")
    void signMatchesIndependentImplementation() {
        byte[] body = "{\"userId\":1,\"bizMsgId\":\"b1\",\"msgType\":\"text\"}"
                .getBytes(StandardCharsets.UTF_8);

        assertThat(ImHmacSigner.sha256Hex(body))
                .isEqualTo("c6a8d73cb4c5c4d40795398a8dfcba40f539bbcf90eb0442bd79ee511450e522");
        assertThat(ImHmacSigner.signingString(APP_ID, Long.toString(TIMESTAMP), NONCE,
                ImHmacSigner.sha256Hex(body)))
                .isEqualTo("app-platform-im\n1700000000000\nn0nce-abc-123\n"
                        + "c6a8d73cb4c5c4d40795398a8dfcba40f539bbcf90eb0442bd79ee511450e522");
        assertThat(ImHmacSigner.sign(SECRET, APP_ID, TIMESTAMP, NONCE, body))
                .isEqualTo("406e4158654d0f51d800353933c5eb165b118873d70463004081e6a3f7fa374f");
    }

    @Test
    @DisplayName("空 body 与 null body 等价（都按空字节数组取摘要）")
    void nullPayloadEqualsEmptyPayload() {
        // 期望值同样是独立推导的（sha256("") 是公开常量）。
        assertThat(ImHmacSigner.sha256Hex(null))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(ImHmacSigner.sha256Hex(new byte[0])).isEqualTo(ImHmacSigner.sha256Hex(null));
        assertThat(ImHmacSigner.sign(SECRET, APP_ID, TIMESTAMP, NONCE, null))
                .isEqualTo("8b6e11d98a15d86413419c73eaf28a759198471db2dbc720a0476b3de26c9ad1")
                .isEqualTo(ImHmacSigner.sign(SECRET, APP_ID, TIMESTAMP, NONCE, new byte[0]));
    }

    @Test
    @DisplayName("分隔符是 \\n(0x0A)：换成 \\r\\n 或空格必然算出别的签名")
    void separatorIsLineFeed() {
        String lf = ImHmacSigner.sign(SECRET, APP_ID, TIMESTAMP, NONCE, null);

        // 这条断言的意义在于「万一有人为了好看把 \\n 改成 \\r\\n」，本测试会红，
        // 而不是等到线上收到不区分原因的 IM_90004 才发现。
        assertThat(ImHmacSigner.signingString("a", "b", "c", "d")).isEqualTo("a\nb\nc\nd");
        assertThat(lf).doesNotContain("\r");
    }

    @Test
    @DisplayName("十六进制是小写")
    void hexIsLowerCase() {
        assertThat(ImHmacSigner.sign(SECRET, APP_ID, TIMESTAMP, NONCE, null))
                .isEqualTo(ImHmacSigner.sign(SECRET, APP_ID, TIMESTAMP, NONCE, null).toLowerCase());
    }

    @Test
    @DisplayName("签名内容随 body / appId / timestamp / nonce 任一变化而变")
    void signatureIsSensitiveToEveryComponent() {
        String base = ImHmacSigner.sign(SECRET, APP_ID, TIMESTAMP, NONCE, "x".getBytes(StandardCharsets.UTF_8));

        assertThat(ImHmacSigner.sign(SECRET, APP_ID, TIMESTAMP, NONCE, "y".getBytes(StandardCharsets.UTF_8)))
                .as("body 改一个字节签名必须变 —— 否则 body 可被中间人任意改写")
                .isNotEqualTo(base);
        assertThat(ImHmacSigner.sign(SECRET, "other-app", TIMESTAMP, NONCE, "x".getBytes(StandardCharsets.UTF_8)))
                .isNotEqualTo(base);
        assertThat(ImHmacSigner.sign(SECRET, APP_ID, TIMESTAMP + 1, NONCE, "x".getBytes(StandardCharsets.UTF_8)))
                .isNotEqualTo(base);
        assertThat(ImHmacSigner.sign(SECRET, APP_ID, TIMESTAMP, "other-nonce", "x".getBytes(StandardCharsets.UTF_8)))
                .isNotEqualTo(base);
    }

    @Test
    @DisplayName("matches 大小写不敏感；null 一律 false")
    void matchesIsCaseInsensitive() {
        String upper = "406E4158654D0F51D800353933C5EB165B118873D70463004081E6A3F7FA374F";
        String lower = "406e4158654d0f51d800353933c5eb165b118873d70463004081e6a3f7fa374f";

        assertThat(ImHmacSigner.matches(lower, upper)).isTrue();
        assertThat(ImHmacSigner.matches(upper, lower)).isTrue();
        assertThat(ImHmacSigner.matches(lower, lower)).isTrue();
        assertThat(ImHmacSigner.matches(lower, "deadbeef")).isFalse();
        assertThat(ImHmacSigner.matches(null, lower)).isFalse();
        assertThat(ImHmacSigner.matches(lower, null)).isFalse();
    }

    @Test
    @DisplayName("密钥为空时抛错，且异常信息里不带密钥内容")
    void emptySecretIsRejectedWithoutLeaking() {
        assertThatThrownBy(() -> ImHmacSigner.hmacSha256Hex("", "data"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HMAC 密钥为空");
        assertThatThrownBy(() -> ImHmacSigner.hmacSha256Hex(null, "data"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
