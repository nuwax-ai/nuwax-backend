package com.xspaceagi.system.web.controller;

import com.xspaceagi.file.sdk.IFileAccessService;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.domain.service.UserDomainService;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.spec.common.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 免登录头像接口 {@code GET /api/user/avatar/{userId}} 的判定逻辑。
 *
 * <p>这个接口免登录，所以「谁能拿到什么」全靠本类覆盖的几条判据：只认当前租户、只放行 http(s)
 * 与站内相对路径、平台内文件必须换出带令牌的地址（否则三方拿到裸 {@code /api/f/xxx} 会 401）。
 * 这几条错了不会编译报错、也不会在正常路径上显形，只能靠断言守住。
 */
class UserControllerAvatarTest {

    private static final long TENANT = 7L;

    private static final String SITE_URL = "https://a.example.com/";

    private UserDomainService userDomainService;

    private IFileAccessService fileAccessService;

    private UserController controller;

    @BeforeEach
    void setUp() throws Exception {
        userDomainService = mock(UserDomainService.class);
        fileAccessService = mock(IFileAccessService.class);
        controller = new UserController();
        setField(controller, "userDomainService", userDomainService);
        setField(controller, "iFileAccessService", fileAccessService);

        TenantConfigDto tenantConfig = new TenantConfigDto();
        tenantConfig.setSiteUrl(SITE_URL);
        RequestContext.set(RequestContext.builder().tenantId(TENANT).tenantConfig(tenantConfig).build());
    }

    @AfterEach
    void tearDown() {
        RequestContext.remove();
    }

    // ------------------------------------------------------------ 可见性

    @Test
    void 用户不存在_404() {
        when(userDomainService.queryById(9L)).thenReturn(null);

        assertThat(controller.avatar(9L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 别的租户的用户_按不存在处理_404() {
        givenUser(user(9L, 8L, "https://third.example.com/a.jpg"));

        assertThat(controller.avatar(9L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // 不区分「不存在」与「不属于本租户」，且连头像地址都不去解析
        verify(fileAccessService, never()).getRealFileUrl(any(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void 没有请求上下文_按不可见处理_404() {
        RequestContext.remove();
        givenUser(user(9L, TENANT, "https://third.example.com/a.jpg"));

        assertThat(controller.avatar(9L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // -------------------------------------------------------- 地址形态

    @Test
    void 平台内文件_换出带令牌的地址再重定向() {
        String avatar = "/api/f/local/avatar/2026/a.png";
        givenUser(user(9L, TENANT, avatar));
        String withToken = "https://a.example.com/api/f/local/avatar/2026/a.png?ak=tok";
        when(fileAccessService.getRealFileUrl(any(), any(), anyBoolean(), anyBoolean())).thenReturn(withToken);

        assertThat(location(controller.avatar(9L))).isEqualTo(withToken);

        // 核心：必须要求带令牌。带 false 的话三方拿到裸 /api/f/xxx 会被 401 挡掉，功能等于没做
        ArgumentCaptor<Boolean> withAk = ArgumentCaptor.forClass(Boolean.class);
        verify(fileAccessService).getRealFileUrl(eq(SITE_URL), eq(avatar), withAk.capture(), eq(false));
        assertThat(withAk.getValue()).isTrue();
    }

    @Test
    void 外链头像_原样重定向() {
        String avatar = "https://third.example.com/avatar/a.jpg";
        givenUser(user(9L, TENANT, avatar));
        // 真实实现对外链是原样返回（不含 /api/f/，不进令牌分支）
        when(fileAccessService.getRealFileUrl(any(), any(), anyBoolean(), anyBoolean())).thenReturn(avatar);

        assertThat(location(controller.avatar(9L))).isEqualTo(avatar);
    }

    @Test
    void 相对路径头像_也走地址解析_不裸跳() {
        String avatar = "/upload/a.png";
        givenUser(user(9L, TENANT, avatar));
        when(fileAccessService.getRealFileUrl(any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn("https://a.example.com/upload/a.png?ak=tok");

        assertThat(location(controller.avatar(9L))).isEqualTo("https://a.example.com/upload/a.png?ak=tok");
    }

    // ------------------------------------------------------------ 默认头像

    @Test
    void 没有头像_重定向到默认图标而不是404() {
        givenUser(user(9L, TENANT, null));

        assertThat(location(controller.avatar(9L)))
                .isEqualTo("https://a.example.com/api/logo/user/%E5%BC%A0%E4%B8%89");
        verify(fileAccessService, never()).getRealFileUrl(any(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void 存的是占位图标地址_同样走默认图标() {
        givenUser(user(9L, TENANT, "https://a.example.com/api/logo/user/x"));

        assertThat(location(controller.avatar(9L)))
                .isEqualTo("https://a.example.com/api/logo/user/%E5%BC%A0%E4%B8%89");
    }

    @Test
    void 没有昵称_回落用户名() {
        User user = user(9L, TENANT, "   ");
        user.setNickName(null);
        user.setUserName("zhangsan");
        givenUser(user);

        assertThat(location(controller.avatar(9L)))
                .isEqualTo("https://a.example.com/api/logo/user/zhangsan");
    }

    @Test
    void 昵称过长_截断到16个字符() {
        User user = user(9L, TENANT, null);
        user.setNickName("abcdefghijklmnopqrstuvwxyz");
        givenUser(user);

        assertThat(location(controller.avatar(9L)))
                .isEqualTo("https://a.example.com/api/logo/user/abcdefghijklmnop");
    }

    // ------------------------------------------------------------ 危险地址

    @Test
    void 非http协议_404() {
        givenUser(user(9L, TENANT, "javascript:alert(1)"));

        assertThat(controller.avatar(9L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(fileAccessService, never()).getRealFileUrl(any(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void 协议相对地址_不当站内路径放行_404() {
        givenUser(user(9L, TENANT, "//evil.example.com/a.png"));

        assertThat(controller.avatar(9L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(fileAccessService, never()).getRealFileUrl(any(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void 头像值不是合法URI_404而不是500() {
        givenUser(user(9L, TENANT, "/upload/a b.png"));
        when(fileAccessService.getRealFileUrl(any(), any(), anyBoolean(), anyBoolean())).thenReturn("/upload/a b.png");

        assertThat(controller.avatar(9L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 地址解析抛异常_404而不是500() {
        givenUser(user(9L, TENANT, "/api/f/local/avatar/2026/a.png"));
        when(fileAccessService.getRealFileUrl(any(), any(), anyBoolean(), anyBoolean()))
                .thenThrow(new IllegalStateException("存储不可用"));

        assertThat(controller.avatar(9L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ------------------------------------------------------------------ 夹具

    private void givenUser(User user) {
        when(userDomainService.queryById(user.getId())).thenReturn(user);
    }

    private static User user(Long id, Long tenantId, String avatar) {
        User user = new User();
        user.setId(id);
        user.setTenantId(tenantId);
        user.setAvatar(avatar);
        user.setNickName("张三");
        user.setUserName("zhangsan");
        return user;
    }

    private static String location(ResponseEntity<Void> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        return response.getHeaders().getLocation().toString();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = UserController.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
