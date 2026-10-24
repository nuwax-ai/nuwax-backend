package com.xspaceagi.system.spec.license;

import com.xspaceagi.system.spec.enums.EditionEnum;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端口径回归：编进 jar 的内置公钥必须能验通签发工具（{@code tools/license-issuer}）产出的串。
 *
 * <p>串由 k1 私钥离线签发，固定不变（本地联调样例，非真实客户授权）。这里**刻意不校验有效期**：
 * 哪天过了期，版本与客户信息仍应读得出来，测试不该跟着日期变红。
 *
 * <p>这一条守的是最要命的一环 —— 内置公钥与签发私钥配不上对，客户拿到串一律「验签失败」。
 */
class BuiltinPublicKeyTest {

    private static final String ISSUED = "NUWAX1.eyJ2IjoxLCJraWQiOiJrMSIsImxpY0lkIjoiTldYLTIwMjYtMDAwMSIsImN1c3RvbWVyIjoi"
            + "5pys5Zyw6IGU6LCDIiwicHJvamVjdCI6IuWls-WosuaZuuiDveS9k-W5s-WPsCIsImVkaXRpb24iOiJhaW9zIiwidGllciI6ImNvbW1l"
            + "cmNpYWwiLCJpc3N1ZWRBdCI6IjIwMjYtMTAtMDhUMjM6NTk6NTlaIiwiZXhwaXJlQXQiOiIyMDI3LTEwLTA4VDIzOjU5OjU5WiIsImdy"
            + "YWNlRGF5cyI6MTV9.Vdr2awAO1whqlIqj5goZSld5WcDYx3g26ywDajsUfB-yNc4MtKG7cGejwrLWPhsww5jU-kNjDxX38aTxks8vDg";

    @Test
    void 内置公钥能验通签发工具产出的串并读出版本() {
        // publicKeyOverride 传 null ⇒ 走 LicenseVerifier.BUILTIN_PUBLIC_KEY
        LicenseResult result = LicenseVerifier.verify(ISSUED, null);

        assertThat(result.getPayload()).as("验签失败：内置公钥与签发私钥不配对").isNotNull();
        assertThat(result.getEdition()).isEqualTo(EditionEnum.AIOS);
        assertThat(result.getPayload().getKid()).isEqualTo("k1");
        assertThat(result.getPayload().getLicId()).isEqualTo("NWX-2026-0001");
        assertThat(result.getPayload().getTier()).isEqualTo("commercial");
        assertThat(result.getPayload().getGraceDays()).isEqualTo(15);
        assertThat(result.getStatus()).isIn(LicenseStatus.VALID, LicenseStatus.GRACE, LicenseStatus.EXPIRED);
    }
}
