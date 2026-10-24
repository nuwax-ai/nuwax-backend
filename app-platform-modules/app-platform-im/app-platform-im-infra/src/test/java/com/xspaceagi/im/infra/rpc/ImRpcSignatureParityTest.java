package com.xspaceagi.im.infra.rpc;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ★★与 IM 服务端签名实现（{@code com.nuwax.im.core.web.rpc.ImRpcSignature}）的<b>逐向量比对</b>。
 *
 * <h2>为什么必须有这个测试</h2>
 *
 * <p>签名算法在两侧各有一份实现，这是<b>刻意</b>的（IM 侧的 {@code nuwax-im-core-http-sdk}
 * 受 R10 零依赖约束，无法被平台侧引用）。代价是两份代码可能各自演化，
 * 而漂移的表现是 {@code IM_90004} —— 契约规定验签失败<b>不透露原因</b>，
 * 于是「分隔符从 {@code \n} 改成 {@code \r\n}」和「密钥配错了」在日志里长得一模一样。
 *
 * <p>本项目里的固定向量测试（{@link ImHmacSignerTest}）只能证明「和独立推导一致」；
 * 本测试进一步证明「和<b>正在跑的那份服务端代码</b>一致」——
 * 直接 {@code javac} 编译 IM 仓库里的<b>真实源文件</b>再反射调用，
 * 不是抄一遍算法（抄一遍就退化成了第三个可能出错的手抄本）。
 *
 * <h2>★为什么会 skip 而不是 fail</h2>
 *
 * <p>IM 仓库是<b>另一个</b> checkout，CI 上通常不存在。目录找不到时用
 * {@link org.junit.jupiter.api.Assumptions#assumeTrue} 跳过 ——
 * 跳过是诚实的「没测」，fail 会让这条测试在 CI 上变成长期噪声进而被删掉。
 *
 * <p>指定路径：系统属性 {@code nuwax.im.home} 或环境变量 {@code NUWAX_IM_HOME}。
 */
class ImRpcSignatureParityTest {

    private static final String RELATIVE_SOURCE = "nuwax-im-modules/nuwax-im-core/nuwax-im-core-web/"
            + "src/main/java/com/nuwax/im/core/web/rpc/ImRpcSignature.java";
    private static final String FQCN = "com.nuwax.im.core.web.rpc.ImRpcSignature";

    private static Class<?> serverSide;

    @BeforeAll
    static void compileServerSideImplementation() throws Exception {
        Path source = locateSource();
        assumeTrue(source != null,
                "未找到 IM 仓库的 ImRpcSignature.java（用 -Dnuwax.im.home=<path> 指定），跳过双向比对");

        Path outDir = Files.createTempDirectory("im-rpc-parity-");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assumeTrue(compiler != null, "当前 JRE 没有 javac（需 JDK 运行测试），跳过双向比对");

        int exit = compiler.run(null, null, null,
                "-d", outDir.toString(),
                "-encoding", "UTF-8",
                source.toString());
        assertThat(exit).as("IM 侧 ImRpcSignature.java 应能独立编译（它只依赖 JDK）").isZero();

        serverSide = new URLClassLoader(new URL[]{outDir.toUri().toURL()}, null).loadClass(FQCN);
    }

    private static Path locateSource() {
        String home = System.getProperty("nuwax.im.home");
        if (home == null || home.isBlank()) {
            home = System.getenv("NUWAX_IM_HOME");
        }
        if (home == null || home.isBlank()) {
            // 开发机上的常见位置（本仓库与 nuwax-im 是同级 checkout）
            home = Paths.get(System.getProperty("user.home"), "Projects", "nuwax-im").toString();
        }
        Path candidate = Paths.get(home).resolve(RELATIVE_SOURCE);
        return Files.isRegularFile(candidate) ? candidate : null;
    }

    @Test
    @DisplayName("同一组向量，SDK 侧与 IM 服务端算出完全相同的签名")
    void sameVectorProducesSameSignature() throws Exception {
        Method serverSign = serverSide.getMethod("sign", String.class, String.class,
                String.class, String.class, byte[].class);
        Method serverSigningString = serverSide.getMethod("signingString", String.class,
                String.class, String.class, String.class);
        Method serverSha256Hex = serverSide.getMethod("sha256Hex", byte[].class);

        List<byte[]> bodies = List.of(
                new byte[0],
                "{}".getBytes(StandardCharsets.UTF_8),
                // 中文 + 转义 + 换行：最容易被「字符集/转义处理不一致」打中的输入
                "{\"text\":\"你好，世界\\n第二行 \\\"引号\\\"\"}".getBytes(StandardCharsets.UTF_8),
                // 纯二进制：证明两侧都是对【字节】取摘要，而不是先转成字符串
                new byte[]{0, 1, 2, (byte) 0xFF, (byte) 0x80, 127, -1, 10, 13});

        String[] secrets = {"test-secret-平台-密钥", "s", "a".repeat(200)};
        String[] nonces = {"n0nce-abc-123", "0", "N".repeat(64), ""};

        for (byte[] body : bodies) {
            assertThat(ImHmacSigner.sha256Hex(body))
                    .as("body 摘要应一致")
                    .isEqualTo(serverSha256Hex.invoke(null, (Object) body));
        }

        for (String secret : secrets) {
            for (String nonce : nonces) {
                String bodyHash = ImHmacSigner.sha256Hex(bodies.get(2));
                assertThat(ImHmacSigner.signingString("app-platform-im", "1700000000000", nonce, bodyHash))
                        .as("签名串应逐字符一致（secret=%s nonce=%s）", mask(secret), nonce)
                        .isEqualTo(serverSigningString.invoke(null,
                                "app-platform-im", "1700000000000", nonce, bodyHash));

                assertThat(ImHmacSigner.sign(secret, "app-platform-im", 1700000000000L, nonce, bodies.get(2)))
                        .as("签名应一致（secret=%s nonce=%s）", mask(secret), nonce)
                        .isEqualTo(serverSign.invoke(null, secret, "app-platform-im",
                                "1700000000000", nonce, bodies.get(2)));
            }
        }
    }

    @Test
    @DisplayName("matches 的判定也一致（含大小写与 null）")
    void matchesAgreesWithServerSide() throws Exception {
        Method serverMatches = serverSide.getMethod("matches", String.class, String.class);

        String lower = "406e4158654d0f51d800353933c5eb165b118873d70463004081e6a3f7fa374f";
        String upper = lower.toUpperCase();
        String other = "deadbeef";

        String[][] cases = {{lower, upper}, {upper, lower}, {lower, lower}, {lower, other}, {null, lower}, {lower, null}};
        for (String[] c : cases) {
            assertThat(ImHmacSigner.matches(c[0], c[1]))
                    .as("matches(%s, %s) 两侧判定应一致", c[0], c[1])
                    .isEqualTo(serverMatches.invoke(null, c[0], c[1]));
        }
    }

    /** 密钥只以长度示人 —— 断言失败时的输出会被贴进 issue / 聊天里。 */
    private static String mask(String secret) {
        return secret == null ? "null" : "len=" + secret.length();
    }
}
