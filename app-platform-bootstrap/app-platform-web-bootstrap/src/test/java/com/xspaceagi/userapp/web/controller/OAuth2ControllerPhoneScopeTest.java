package com.xspaceagi.userapp.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.xspaceagi.file.sdk.IFileAccessService;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.spec.common.RequestContext;

/**
 * OAuth2 {@code /userinfo} 的 {@code phone} 字段——字段级权限，纯单元测试，不起 Spring 容器、不连库。
 * <p>
 * 关键约束：{@code user:phone} 只控制「响应里多不多一个字段」，不控制端点本身。
 * 普通登录态（非 {@code oa-} 令牌）没有 OAuth2 scope，必须保持既有响应不变。
 */
class OAuth2ControllerPhoneScopeTest {

    private static final String SITE_URL = "https://demo.example.com";

    private static final String PHONE = "13800000000";

    private OAuth2Controller controller;

    @BeforeEach
    void setUp() {
        controller = new OAuth2Controller();
        IFileAccessService fileAccessService = mock(IFileAccessService.class);
        when(fileAccessService.getFileUrlWithAk(anyString(), eq(true))).thenReturn(null);
        ReflectionTestUtils.setField(controller, "iFileAccessService", fileAccessService);
    }

    @AfterEach
    void tearDown() {
        RequestContext.remove();
    }

    @Test
    @DisplayName("scope 含 user:phone：/userinfo 增加 phone 字段")
    void phoneIsReturnedWhenScopeGranted() {
        bindRequest("profile,user:phone");

        Map<String, Object> body = userInfoBody();

        assertEquals(PHONE, body.get("phone"));
        // 原有字段不受影响
        assertEquals("1001", body.get("sub"));
        assertEquals("小飞", body.get("name"));
    }

    @Test
    @DisplayName("只授予 profile：不返回 phone 字段")
    void phoneIsAbsentWithoutPhoneScope() {
        bindRequest("profile");

        Map<String, Object> body = userInfoBody();

        assertFalse(body.containsKey("phone"));
        assertEquals("1001", body.get("sub"));
    }

    @Test
    @DisplayName("普通登录态（无 OAuth2 scope）：行为保持原样，不返回 phone")
    void phoneIsAbsentForNonOAuthSession() {
        bindRequest(null);

        Map<String, Object> body = userInfoBody();

        assertFalse(body.containsKey("phone"));
        assertNull(body.get("phone"));
    }

    private void bindRequest(String oauth2Scope) {
        UserDto user = new UserDto();
        user.setId(1001L);
        user.setUserName("u1001");
        user.setNickName("小飞");
        user.setPhone(PHONE);
        user.setTenantId(7L);

        TenantConfigDto tenantConfig = new TenantConfigDto();
        tenantConfig.setSiteUrl(SITE_URL);

        RequestContext<Object> context = new RequestContext<>();
        context.setUser(user);
        context.setTenantConfig(tenantConfig);
        context.setOauth2Scope(oauth2Scope);
        RequestContext.set(context);
    }

    private Map<String, Object> userInfoBody() {
        return ReflectionTestUtils.invokeMethod(controller, "userInfoBody");
    }
}
