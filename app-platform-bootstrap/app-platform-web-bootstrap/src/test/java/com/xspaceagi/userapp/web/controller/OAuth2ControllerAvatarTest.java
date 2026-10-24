package com.xspaceagi.userapp.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.xspaceagi.file.sdk.IFileAccessService;

/**
 * OAuth2 {@code /userinfo} 与 {@code /user/search} 的头像字段——纯单元测试，不起 Spring 容器、不连库。
 * <p>
 * 为什么值得单测：库里的 {@code avatar} 是 {@code {siteUrl}/api/f/{fileKey}}，第三方没有平台登录态，
 * 直接塞进 {@code <img src>} 只会拿到 401（{@code FileManagementController} 对未登录请求强制校验 {@code ?ak=}）。
 * 也就是说「忘了换」这种错<b>不会报错、只会静默给一张破图</b>，靠联调肉眼很难发现。
 * 同时它是<b>对外协议端点</b>：换地址这件事失败也必须降级成原值，绝不能把整个身份响应打挂。
 */
class OAuth2ControllerAvatarTest {

    private static final String SITE_URL = "https://demo.example.com";

    private static final String STORED_AVATAR = SITE_URL + "/api/f/cos/avatar/2026/u-1001.png";

    private static final String SIGNED_AVATAR = SITE_URL + "/api/f/cos/avatar/2026/u-1001.png?ak=signed-ak";

    private IFileAccessService fileAccessService;

    private OAuth2Controller controller;

    @BeforeEach
    void setUp() {
        fileAccessService = mock(IFileAccessService.class);
        controller = new OAuth2Controller();
        ReflectionTestUtils.setField(controller, "iFileAccessService", fileAccessService);
    }

    // ============================================================ 转换本身

    @Test
    @DisplayName("平台文件：换成签名后的可直接访问地址，且走与门面 /file/url 相同的口径（returnOriginalUrl=true）")
    void signsPlatformFileUrl() {
        when(fileAccessService.getFileUrlWithAk(STORED_AVATAR, true)).thenReturn(SIGNED_AVATAR);

        assertEquals(SIGNED_AVATAR, accessibleAvatarUrl(STORED_AVATAR));
        // 第三个参数 true 是与门面 /file/url 一致的关键：云存储据此签发预签名直链而非平台中转地址
        verify(fileAccessService).getFileUrlWithAk(STORED_AVATAR, true);
    }

    @Test
    @DisplayName("用户自填的第三方外链：交给 service 判断（非 /api/f/ 前缀原样返回），不在这里自作主张")
    void passesThroughThirdPartyUrl() {
        String wechat = "https://thirdwx.qlogo.cn/mmopen/vi_32/abc/132";
        when(fileAccessService.getFileUrlWithAk(wechat, true)).thenReturn(wechat);

        assertEquals(wechat, accessibleAvatarUrl(wechat));
    }

    // ============================================================ 空值

    @Test
    @DisplayName("未设置头像：null / 空串 / 全空白都原样返回，且不去打扰文件服务")
    void blankAvatarIsReturnedAsIs() {
        assertNull(accessibleAvatarUrl(null));
        assertEquals("", accessibleAvatarUrl(""));
        assertEquals("   ", accessibleAvatarUrl("   "));
        verifyNoInteractions(fileAccessService);
    }

    // ============================================================ 失败降级

    @Test
    @DisplayName("签名失败：降级回原值，绝不把整个 /userinfo 打挂")
    void fallsBackWhenSigningFails() {
        when(fileAccessService.getFileUrlWithAk(anyString(), eq(true)))
                .thenThrow(new IllegalStateException("redis is down"));

        assertEquals(STORED_AVATAR, accessibleAvatarUrl(STORED_AVATAR));
    }

    @Test
    @DisplayName("签名没签出结果（返回 null）：同样回退原值，不能把「有头像」变成「没头像」")
    void nullFromServiceFallsBackToStoredValue() {
        when(fileAccessService.getFileUrlWithAk(STORED_AVATAR, true)).thenReturn(null);

        assertEquals(STORED_AVATAR, accessibleAvatarUrl(STORED_AVATAR));
    }

    // ============================================================ 落到响应体

    @Test
    @DisplayName("/userinfo 与 /user/search 共用的响应体：avatar 字段装的是换过的地址，其余字段不受影响")
    void bodyCarriesSignedAvatar() {
        when(fileAccessService.getFileUrlWithAk(STORED_AVATAR, true)).thenReturn(SIGNED_AVATAR);

        Map<String, Object> body = buildUserBody(1001L, "u1001", "小飞", STORED_AVATAR, 7L);

        assertEquals(SIGNED_AVATAR, body.get("avatar"));
        assertEquals("1001", body.get("sub"));
        assertEquals(1001L, body.get("user_id"));
        assertEquals("小飞", body.get("name"));
        assertEquals(7L, body.get("tenant_id"));
    }

    @Test
    @DisplayName("昵称为空时 name 回落用户名——头像换地址不该打乱这条既有口径")
    void bodyNameFallsBackToUserName() {
        when(fileAccessService.getFileUrlWithAk(STORED_AVATAR, true)).thenReturn(SIGNED_AVATAR);

        assertEquals("u1001", buildUserBody(1001L, "u1001", null, STORED_AVATAR, 7L).get("name"));
        assertEquals("u1001", buildUserBody(1001L, "u1001", "  ", STORED_AVATAR, 7L).get("name"));
    }

    @Test
    @DisplayName("未设置头像的用户：avatar 保持 null，不生成地址")
    void bodyKeepsNullAvatar() {
        Map<String, Object> body = buildUserBody(1001L, "u1001", "小飞", null, 7L);

        assertNull(body.get("avatar"));
        verify(fileAccessService, never()).getFileUrlWithAk(anyString(), eq(true));
    }

    // ============================================================ 反射入口

    private String accessibleAvatarUrl(String avatar) {
        return ReflectionTestUtils.invokeMethod(controller, "accessibleAvatarUrl", avatar);
    }

    private Map<String, Object> buildUserBody(Long userId, String userName, String nickName, String avatar,
                                              Long tenantId) {
        return ReflectionTestUtils.invokeMethod(controller, "buildUserBody", userId, userName, nickName, avatar,
                tenantId);
    }
}
