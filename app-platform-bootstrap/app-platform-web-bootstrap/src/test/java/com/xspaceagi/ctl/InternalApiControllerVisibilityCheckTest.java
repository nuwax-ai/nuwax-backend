package com.xspaceagi.ctl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.xspaceagi.agent.core.adapter.application.PublishApplicationService;
import com.xspaceagi.agent.core.adapter.dto.PublishedDto;
import com.xspaceagi.agent.core.adapter.dto.PublishedQueryDto;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.ctl.dto.InternalAgentVisibilityCheckDto;
import com.xspaceagi.ctl.dto.InternalAgentVisibilityCheckRequest;
import com.xspaceagi.system.application.dto.SpaceDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.application.service.TenantConfigApplicationService;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.sdk.permission.IUserDataPermissionRpcService;
import com.xspaceagi.system.sdk.service.dto.UserDataPermissionDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.page.SuperPage;

/**
 * {@link InternalApiController#checkAgentVisibility} 的单元测试——纯 mock，不起 Spring 容器、不连库。
 * <p>
 * 重点是三件事：
 * <ol>
 *   <li><b>口径必须与 {@code /agent/search} 同款</b>：下发给发布查询的参数（targetType、targetIds、
 *       空间范围、showRecommend=false）与收口规则（accessControl=1 走数据权限、管理员豁免、
 *       取不到权限数据按无权限处理）逐条钉住。口径漂了，调用方拿到的答案就和用户在广场里看到的
 *       不是一回事，而这种偏差不会报错、只会静默给错答案。</li>
 *   <li><b>「视角给错了」必须是报错而不是空结果</b>：空结果的含义是「这些智能体他一个都用不了」，
 *       若参数错误也回空结果，调用方会把配置问题读成业务结论。用户域查询豁免租户过滤，
 *       跨租户收口只能自己做，这里单独钉一条。</li>
 *   <li><b>结果集是子集而非全量</b>：未发布 / 不在可见范围的 id 不能因为「查不到」就报错或漏判，
 *       只能不出现在结果里；返回顺序按请求顺序（调用方要拿它对回自己那份列表）。</li>
 * </ol>
 */
class InternalApiControllerVisibilityCheckTest {

    private static final long TENANT_ID = 1L;
    private static final long USER_ID = 10086L;
    private static final long SPACE_ID = 500L;
    private static final long OTHER_SPACE_ID = 600L;

    /** 参数/归属类失败共用的 code（与控制器里其余 BizException.of 的口径一致） */
    private static final String INVALID_PARAM = ErrorCodeEnum.INVALID_PARAM.getCode();

    /** 与控制器私有常量 MAX_VISIBILITY_BATCH_SIZE / MAX_PAGE_SIZE 对齐 */
    private static final int MAX_VISIBILITY_BATCH_SIZE = 50;
    private static final int PAGE_SIZE = 100;

    private InternalApiController controller;
    private UserApplicationService userApplicationService;
    private SpaceApplicationService spaceApplicationService;
    private PublishApplicationService publishApplicationService;
    private IUserDataPermissionRpcService userDataPermissionRpcService;
    private TenantConfigApplicationService tenantConfigApplicationService;

    @BeforeEach
    void setUp() {
        controller = new InternalApiController();
        userApplicationService = mock(UserApplicationService.class);
        spaceApplicationService = mock(SpaceApplicationService.class);
        publishApplicationService = mock(PublishApplicationService.class);
        userDataPermissionRpcService = mock(IUserDataPermissionRpcService.class);
        tenantConfigApplicationService = mock(TenantConfigApplicationService.class);
        ReflectionTestUtils.setField(controller, "userApplicationService", userApplicationService);
        ReflectionTestUtils.setField(controller, "spaceApplicationService", spaceApplicationService);
        ReflectionTestUtils.setField(controller, "publishApplicationService", publishApplicationService);
        ReflectionTestUtils.setField(controller, "userDataPermissionRpcService", userDataPermissionRpcService);
        ReflectionTestUtils.setField(controller, "tenantConfigApplicationService", tenantConfigApplicationService);
        // applyTenant 会往请求上下文里写租户；真实请求里这份上下文由 AuthInterceptor 落下
        RequestContext<Object> context = new RequestContext<>();
        context.setLang("zh-CN");
        RequestContext.set(context);
    }

    @AfterEach
    void tearDown() {
        RequestContext.remove();
    }

    // ------------------------------------------------------------------ 入参闸门

    @Test
    @DisplayName("缺 tenantId：在碰用户/发布查询之前就被拒")
    void tenantIdMissingIsRejectedFirst() {
        ReqResult<InternalAgentVisibilityCheckDto> result = controller.checkAgentVisibility(null, request(USER_ID, 1L, 2L));

        assertEquals(INVALID_PARAM, result.getCode());
        assertEquals(fieldRequired("tenantId"), result.getMessage());
        verifyNoInteractions(userApplicationService);
        verifyNoInteractions(publishApplicationService);
    }

    @Test
    @DisplayName("请求体为空：被拒")
    void nullBodyIsRejected() {
        ReqResult<InternalAgentVisibilityCheckDto> result = controller.checkAgentVisibility(TENANT_ID, null);

        assertEquals(INVALID_PARAM, result.getCode());
        assertEquals("请求体不能为空", result.getMessage());
        verifyNoInteractions(publishApplicationService);
    }

    @Test
    @DisplayName("缺 userId：被拒（视角无法自行推导，必须由调用方给准）")
    void userIdMissingIsRejected() {
        ReqResult<InternalAgentVisibilityCheckDto> result =
                controller.checkAgentVisibility(TENANT_ID, request(null, 1L, 2L));

        assertEquals(INVALID_PARAM, result.getCode());
        assertEquals(fieldRequired("userId"), result.getMessage());
        verifyNoInteractions(publishApplicationService);
    }

    @Test
    @DisplayName("agentIds 超过 50：被拒且不打发布表（上限比通用批量查询小，别把判定接口当批量查询用）")
    void tooManyAgentIdsIsRejected() {
        List<Long> tooMany = IntStream.rangeClosed(1, MAX_VISIBILITY_BATCH_SIZE + 1).mapToObj(Long::valueOf).toList();

        ReqResult<InternalAgentVisibilityCheckDto> result =
                controller.checkAgentVisibility(TENANT_ID, request(USER_ID, tooMany));

        assertEquals(INVALID_PARAM, result.getCode());
        assertEquals("批量查询数量超出上限 " + MAX_VISIBILITY_BATCH_SIZE, result.getMessage());
        // 上限是纯内存校验，连用户都不该去查（先做廉价校验，别让超限入参换来一次 DB 往返）
        verifyNoInteractions(userApplicationService);
        verifyNoInteractions(publishApplicationService);
    }

    @Test
    @DisplayName("agentIds 为空（或全是 null）：空结果，不算参数错误，也不打发布表")
    void emptyAgentIdsReturnsEmptyWithoutQuerying() {
        when(userApplicationService.queryById(USER_ID)).thenReturn(user(User.Role.User));

        ReqResult<InternalAgentVisibilityCheckDto> empty =
                controller.checkAgentVisibility(TENANT_ID, request(USER_ID, List.of()));
        ReqResult<InternalAgentVisibilityCheckDto> allNull =
                controller.checkAgentVisibility(TENANT_ID, request(USER_ID, java.util.Arrays.<Long>asList(null, null)));

        assertEquals(ErrorCodeEnum.SUCCESS.getCode(), empty.getCode());
        assertTrue(empty.getData().getVisibleAgentIds().isEmpty());
        assertTrue(allNull.getData().getVisibleAgentIds().isEmpty());
        verifyNoInteractions(publishApplicationService);
    }

    // ------------------------------------------------------------------ 视角闸门：userId ∈ tenantId

    @Test
    @DisplayName("用户不存在：被拒而不是回空结果（空结果会被读成「他什么都用不了」）")
    void unknownUserIsRejected() {
        ReqResult<InternalAgentVisibilityCheckDto> result =
                controller.checkAgentVisibility(TENANT_ID, request(USER_ID, 1L, 2L));

        assertEquals(INVALID_PARAM, result.getCode());
        assertEquals(BizExceptionCodeEnum.systemUserNotFound.getMessageZh(), result.getMessage());
        verifyNoInteractions(publishApplicationService);
    }

    @Test
    @DisplayName("★用户属于别的租户：被拒且不打发布表（用户域查询豁免租户过滤，这条只能自己收口）")
    void crossTenantUserIsRejected() {
        when(userApplicationService.queryById(USER_ID)).thenReturn(user(2L, User.Role.User));

        ReqResult<InternalAgentVisibilityCheckDto> result =
                controller.checkAgentVisibility(TENANT_ID, request(USER_ID, 1L, 2L));

        assertEquals(INVALID_PARAM, result.getCode());
        assertEquals(BizExceptionCodeEnum.systemUserNotFound.getMessageZh(), result.getMessage());
        verifyNoInteractions(publishApplicationService);
    }

    // ------------------------------------------------------------------ 可见口径（= /agent/search 同款）

    @Test
    @DisplayName("★下发查询与 /agent/search 同款：限定 targetIds、按该用户空间收口、关掉推荐置顶")
    void queryCarriesSameVisibilityScopeAsSearch() {
        givenGeneralUserWithSpace();
        when(publishApplicationService.queryPublishedList(any()))
                .thenReturn(page(1, List.of(published(7L, 0, Published.PublishScope.Space))));

        controller.checkAgentVisibility(TENANT_ID, request(USER_ID, 7L));

        PublishedQueryDto query = capturedQuery();
        assertEquals(Published.TargetType.Agent, query.getTargetType());
        assertEquals(List.of(7L), query.getTargetIds());
        assertEquals(List.of(SPACE_ID), query.getSpaceIds());
        assertEquals(Boolean.FALSE, query.getShowRecommend(), "推荐置顶是前端展示语义，内部判定必须关掉");
        assertEquals(Integer.valueOf(1), query.getPage());
        assertEquals(Integer.valueOf(PAGE_SIZE), query.getPageSize());
    }

    @Test
    @DisplayName("结果只回请求的那几个 id，且顺序跟请求走（调用方要拿它对回自己的列表）")
    void visibleSubsetFollowsRequestOrder() {
        givenGeneralUserWithSpace();
        when(publishApplicationService.queryPublishedList(any())).thenReturn(page(1, List.of(
                published(1L, 0, Published.PublishScope.Space),
                published(3L, 0, Published.PublishScope.Tenant))));

        ReqResult<InternalAgentVisibilityCheckDto> result =
                controller.checkAgentVisibility(TENANT_ID, request(USER_ID, 3L, 2L, 1L));

        assertEquals(ErrorCodeEnum.SUCCESS.getCode(), result.getCode());
        assertEquals(List.of(3L, 1L), result.getData().getVisibleAgentIds());
    }

    @Test
    @DisplayName("未发布 / 不在可见范围：不回错、也不出现（发布表查不到就是不可见）")
    void nothingPublishedMeansNotVisible() {
        givenGeneralUserWithSpace();
        when(publishApplicationService.queryPublishedList(any())).thenReturn(page(1, List.of()));

        ReqResult<InternalAgentVisibilityCheckDto> result =
                controller.checkAgentVisibility(TENANT_ID, request(USER_ID, 1L, 2L));

        assertEquals(ErrorCodeEnum.SUCCESS.getCode(), result.getCode());
        assertTrue(result.getData().getVisibleAgentIds().isEmpty());
    }

    @Test
    @DisplayName("★accessControl=1 且拿不到该用户的数据权限：按无权限处理（fail-closed，不外泄受控资源）")
    void accessControlledAgentIsHiddenWithoutDataPermission() {
        givenGeneralUserWithSpace();
        when(userDataPermissionRpcService.getUserDataPermission(USER_ID)).thenReturn(null);
        when(publishApplicationService.queryPublishedList(any())).thenReturn(page(1, List.of(
                published(1L, 1, Published.PublishScope.Tenant),
                published(2L, 0, Published.PublishScope.Tenant))));

        ReqResult<InternalAgentVisibilityCheckDto> result =
                controller.checkAgentVisibility(TENANT_ID, request(USER_ID, 1L, 2L));

        assertEquals(List.of(2L), result.getData().getVisibleAgentIds(), "受控且无授权的 1 必须被滤掉，不受控的 2 保留");
    }

    @Test
    @DisplayName("accessControl=1 但数据权限里有该 id：可见（agentIds 或 pageAgentIds 任一命中即可）")
    void accessControlledAgentIsVisibleWithDataPermission() {
        givenGeneralUserWithSpace();
        UserDataPermissionDto permission = new UserDataPermissionDto();
        permission.setPageAgentIds(List.of(1L));
        when(userDataPermissionRpcService.getUserDataPermission(USER_ID)).thenReturn(permission);
        when(publishApplicationService.queryPublishedList(any())).thenReturn(page(1, List.of(
                published(1L, 1, Published.PublishScope.Tenant),
                published(2L, 1, Published.PublishScope.Tenant))));

        ReqResult<InternalAgentVisibilityCheckDto> result =
                controller.checkAgentVisibility(TENANT_ID, request(USER_ID, 1L, 2L));

        assertEquals(List.of(1L), result.getData().getVisibleAgentIds());
    }

    @Test
    @DisplayName("★管理员：不受数据权限限制，且根本不查数据权限")
    void adminBypassesDataPermission() {
        UserDto admin = user(TENANT_ID, User.Role.Admin);
        when(userApplicationService.queryById(USER_ID)).thenReturn(admin);
        when(spaceApplicationService.queryListByUserId(USER_ID)).thenReturn(List.of(space(SPACE_ID, TENANT_ID)));
        when(publishApplicationService.queryPublishedList(any()))
                .thenReturn(page(1, List.of(published(1L, 1, Published.PublishScope.Tenant))));

        ReqResult<InternalAgentVisibilityCheckDto> result =
                controller.checkAgentVisibility(TENANT_ID, request(USER_ID, 1L));

        assertEquals(List.of(1L), result.getData().getVisibleAgentIds());
        verify(userDataPermissionRpcService, never()).getUserDataPermission(any());
    }

    @Test
    @DisplayName("用户没有任何空间：空间列表为空（域层退化成只查租户系统广场），不能因此报错")
    void userWithoutSpaceStillChecksTenantPlaza() {
        UserDto general = user(TENANT_ID, User.Role.User);
        when(userApplicationService.queryById(USER_ID)).thenReturn(general);
        when(spaceApplicationService.queryListByUserId(USER_ID)).thenReturn(List.of());
        when(publishApplicationService.queryPublishedList(any()))
                .thenReturn(page(1, List.of(published(1L, 0, Published.PublishScope.Tenant))));

        ReqResult<InternalAgentVisibilityCheckDto> result =
                controller.checkAgentVisibility(TENANT_ID, request(USER_ID, 1L));

        assertEquals(List.of(1L), result.getData().getVisibleAgentIds());
        assertTrue(capturedQuery().getSpaceIds().isEmpty());
    }

    @Test
    @DisplayName("空间行属于别的租户：按不可见空间处理，不下发给发布查询（空间查询同样自己收口）")
    void crossTenantSpaceIsNotIncluded() {
        givenGeneralUserWithSpace();
        when(spaceApplicationService.queryListByUserId(USER_ID))
                .thenReturn(List.of(space(SPACE_ID, TENANT_ID), space(OTHER_SPACE_ID, 2L)));
        when(publishApplicationService.queryPublishedList(any())).thenReturn(page(1, List.of()));

        controller.checkAgentVisibility(TENANT_ID, request(USER_ID, 1L));

        assertEquals(List.of(SPACE_ID), capturedQuery().getSpaceIds());
    }

    // ------------------------------------------------------------------ 翻页（别被同一智能体的多条发布记录挤丢）

    @Test
    @DisplayName("★首页返回满页（同一智能体在多个空间各有发布行）时继续翻页，后面的 id 不能判成不可见")
    void paginationContinuesWhenFirstPageIsFull() {
        givenGeneralUserWithSpace();
        List<Long> requested = IntStream.rangeClosed(1, MAX_VISIBILITY_BATCH_SIZE).mapToObj(Long::valueOf).toList();
        when(publishApplicationService.queryPublishedList(any())).thenAnswer(invocation -> {
            PublishedQueryDto query = invocation.getArgument(0);
            if (query.getPage() == 1) {
                // 只有 1~25 个智能体，每个 4 条发布记录 —— 正好占满一页，26~50 被挤到下一页
                List<PublishedDto> rows = new ArrayList<>();
                for (int repeat = 0; repeat < 4; repeat++) {
                    for (long agentId = 1L; agentId <= 25L; agentId++) {
                        rows.add(published(agentId, 0, Published.PublishScope.Space));
                    }
                }
                return page(1, rows);
            }
            List<PublishedDto> rest = new ArrayList<>();
            for (long agentId = 26L; agentId <= MAX_VISIBILITY_BATCH_SIZE; agentId++) {
                rest.add(published(agentId, 0, Published.PublishScope.Space));
            }
            return page(query.getPage(), rest);
        });

        ReqResult<InternalAgentVisibilityCheckDto> result =
                controller.checkAgentVisibility(TENANT_ID, request(USER_ID, requested));

        assertEquals(requested, result.getData().getVisibleAgentIds(), "26~50 必须靠翻页判出来，不能被首页挤掉");
        ArgumentCaptor<PublishedQueryDto> captor = ArgumentCaptor.forClass(PublishedQueryDto.class);
        verify(publishApplicationService, times(2)).queryPublishedList(captor.capture());
        assertEquals(List.of(1, 2), captor.getAllValues().stream().map(PublishedQueryDto::getPage).toList());
    }

    @Test
    @DisplayName("首页没满页：不再多查一次（正常情况只有一次发布查询）")
    void singlePageStopsImmediately() {
        givenGeneralUserWithSpace();
        when(publishApplicationService.queryPublishedList(any()))
                .thenReturn(page(1, List.of(published(1L, 0, Published.PublishScope.Tenant))));

        controller.checkAgentVisibility(TENANT_ID, request(USER_ID, 1L, 2L, 3L));

        verify(publishApplicationService, times(1)).queryPublishedList(any());
    }

    // ------------------------------------------------------------------ 辅助

    /** 普通用户 + 一个自己的空间（走「空间 ∪ 租户广场」那条分支的最小前提） */
    private void givenGeneralUserWithSpace() {
        UserDto general = user(TENANT_ID, User.Role.User);
        when(userApplicationService.queryById(USER_ID)).thenReturn(general);
        when(spaceApplicationService.queryListByUserId(USER_ID)).thenReturn(List.of(space(SPACE_ID, TENANT_ID)));
    }

    /** 取本次调用下发给发布查询的参数（只查一次时用） */
    private PublishedQueryDto capturedQuery() {
        ArgumentCaptor<PublishedQueryDto> captor = ArgumentCaptor.forClass(PublishedQueryDto.class);
        verify(publishApplicationService, times(1)).queryPublishedList(captor.capture());
        return captor.getValue();
    }

    private static UserDto user(User.Role role) {
        return user(TENANT_ID, role);
    }

    private static UserDto user(Long tenantId, User.Role role) {
        UserDto user = new UserDto();
        user.setId(USER_ID);
        user.setTenantId(tenantId);
        user.setRole(role);
        return user;
    }

    private static SpaceDto space(Long spaceId, Long tenantId) {
        SpaceDto space = new SpaceDto();
        space.setId(spaceId);
        space.setTenantId(tenantId);
        return space;
    }

    /** 一条发布记录；name 给非空值，免得图标兜底逻辑掺进来 */
    private static PublishedDto published(Long agentId, Integer accessControl, Published.PublishScope scope) {
        PublishedDto dto = new PublishedDto();
        dto.setTargetId(agentId);
        dto.setTenantId(TENANT_ID);
        dto.setAccessControl(accessControl);
        dto.setScope(scope);
        dto.setName("agent-" + agentId);
        dto.setSpaceId(scope == Published.PublishScope.Tenant ? null : SPACE_ID);
        return dto;
    }

    private static SuperPage<PublishedDto> page(int pageNo, List<PublishedDto> records) {
        return new SuperPage<>(pageNo, PAGE_SIZE, records.size(), records);
    }

    /** 必填项缺失的文案，形如「参数'tenantId'不能为空」 */
    private static String fieldRequired(String fieldName) {
        return String.format(BizExceptionCodeEnum.fieldRequiredButEmpty.getMessageZh(), fieldName);
    }

    private static InternalAgentVisibilityCheckRequest request(Long userId, Long... agentIds) {
        return request(userId, List.of(agentIds));
    }

    private static InternalAgentVisibilityCheckRequest request(Long userId, List<Long> agentIds) {
        InternalAgentVisibilityCheckRequest request = new InternalAgentVisibilityCheckRequest();
        request.setUserId(userId);
        request.setAgentIds(agentIds);
        return request;
    }
}
