package com.xspaceagi.ctl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.xspaceagi.ctl.dto.InternalIMAgentConfigItemDto;
import com.xspaceagi.sandbox.sdk.server.ISandboxConfigRpcService;
import com.xspaceagi.sandbox.sdk.service.dto.SandboxConfigRpcDto;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.service.TenantConfigApplicationService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;

/**
 * {@link InternalApiController#getDefaultIMAgentConfig} 的单元测试——纯 mock，不起 Spring 容器、不连库。
 * <p>
 * 三个重点：
 * <ul>
 *   <li><b>值的归属</b>：配置必须按调用方声明的 {@code tenantId} 现取。这条闸门不能靠
 *       {@link RequestContext} 上挂的那份配置来兜——{@code applyTenant} 在取不到配置时会保留
 *       AuthInterceptor 按默认租户（tenantId=1）装载的那份，照读就会把别的租户的配置下发出去。</li>
 *   <li><b>解析口径</b>：{@code [{"agentId":123,"defaultMessage":""}]} → 逐项下发；
 *       空/未配置 → 空数组；<b>格式不合法 → 4000</b>（不能回空数组，否则「配置写坏了」与「没配」
 *       在调用方眼里一模一样）；缺 {@code agentId} 的条目丢弃。</li>
 *   <li><b>下发的字段</b>：{@code defaultMessage} 缺失时补空串，不下发 null。</li>
 * </ul>
 */
class InternalApiControllerDefaultIMAgentConfigTest {

    private static final long TENANT_ID = 2L;

    private static final long USER_ID = 7L;

    /** 参数/配置类失败共用的 code（与本控制器其余 BizException.of 的口径一致） */
    private static final String INVALID_PARAM = ErrorCodeEnum.INVALID_PARAM.getCode();

    /** 非业务异常兜底：本控制器统一用 ReqResult.error(message)，code 固定 0001 */
    private static final String INTERNAL_ERROR = "0001";

    private InternalApiController controller;
    private TenantConfigApplicationService tenantConfigApplicationService;
    private ISandboxConfigRpcService iSandboxConfigRpcService;

    @BeforeEach
    void setUp() {
        controller = new InternalApiController();
        tenantConfigApplicationService = mock(TenantConfigApplicationService.class);
        ReflectionTestUtils.setField(controller, "tenantConfigApplicationService", tenantConfigApplicationService);
        iSandboxConfigRpcService = mock(ISandboxConfigRpcService.class);
        ReflectionTestUtils.setField(controller, "iSandboxConfigRpcService", iSandboxConfigRpcService);
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
    @DisplayName("缺 tenantId：被拒，不查任何配置")
    void tenantIdMissingIsRejectedFirst() {
        ReqResult<List<InternalIMAgentConfigItemDto>> result = controller.getDefaultIMAgentConfig(null, null);

        assertEquals(INVALID_PARAM, result.getCode());
        assertEquals(fieldRequired("tenantId"), result.getMessage());
        verifyNoInteractions(tenantConfigApplicationService);
    }

    @Test
    @DisplayName("tenantId 非正数：同样被拒")
    void nonPositiveTenantIdIsRejected() {
        ReqResult<List<InternalIMAgentConfigItemDto>> result = controller.getDefaultIMAgentConfig(0L, null);

        assertEquals(INVALID_PARAM, result.getCode());
        assertEquals(fieldRequired("tenantId"), result.getMessage());
        verifyNoInteractions(tenantConfigApplicationService);
    }

    // ------------------------------------------------------------------ 解析口径

    @Test
    @DisplayName("已配置：按 agentId/defaultMessage 逐项解析下发，多余字段忽略")
    void itemsAreParsedInOrder() {
        when(tenantConfigApplicationService.getTenantConfig(TENANT_ID))
                .thenReturn(tenantConfig(" [{\"agentId\": 123, \"defaultMessage\": \"你好\", \"extra\": 1},"
                        + "{\"agentId\": 456, \"defaultMessage\": \"\"}] "));

        ReqResult<List<InternalIMAgentConfigItemDto>> result = controller.getDefaultIMAgentConfig(TENANT_ID, null);

        assertEquals(ReqResult.SUCCESS, result.getCode());
        assertEquals(2, result.getData().size());
        assertEquals(123L, result.getData().get(0).getAgentId());
        assertEquals("你好", result.getData().get(0).getDefaultMessage());
        assertEquals(456L, result.getData().get(1).getAgentId());
        assertEquals("", result.getData().get(1).getDefaultMessage());
    }

    @Test
    @DisplayName("defaultMessage 缺失：补空串，不下发 null")
    void missingDefaultMessageBecomesEmptyString() {
        when(tenantConfigApplicationService.getTenantConfig(TENANT_ID))
                .thenReturn(tenantConfig("[{\"agentId\": 123}]"));

        ReqResult<List<InternalIMAgentConfigItemDto>> result = controller.getDefaultIMAgentConfig(TENANT_ID, null);

        assertEquals(ReqResult.SUCCESS, result.getCode());
        assertEquals(1, result.getData().size());
        assertEquals("", result.getData().get(0).getDefaultMessage());
    }

    @Test
    @DisplayName("条目缺 agentId：整项丢弃，其余项照常下发")
    void itemWithoutAgentIdIsSkipped() {
        when(tenantConfigApplicationService.getTenantConfig(TENANT_ID))
                .thenReturn(tenantConfig("[{\"defaultMessage\": \"没有 agentId\"}, {\"agentId\": 123}]"));

        ReqResult<List<InternalIMAgentConfigItemDto>> result = controller.getDefaultIMAgentConfig(TENANT_ID, null);

        assertEquals(ReqResult.SUCCESS, result.getCode());
        assertEquals(1, result.getData().size());
        assertEquals(123L, result.getData().get(0).getAgentId());
    }

    @Test
    @DisplayName("未配置（空串/null）：回空数组，不透 null")
    void unsetValueBecomesEmptyList() {
        when(tenantConfigApplicationService.getTenantConfig(TENANT_ID)).thenReturn(tenantConfig(""));
        assertEquals(List.of(), controller.getDefaultIMAgentConfig(TENANT_ID, null).getData());

        when(tenantConfigApplicationService.getTenantConfig(TENANT_ID)).thenReturn(tenantConfig(null));
        assertEquals(List.of(), controller.getDefaultIMAgentConfig(TENANT_ID, null).getData());

        when(tenantConfigApplicationService.getTenantConfig(TENANT_ID)).thenReturn(null);
        assertEquals(List.of(), controller.getDefaultIMAgentConfig(TENANT_ID, null).getData());
    }

    @Test
    @DisplayName("★配置不是合法 JSON 数组：报 4000，不回空数组（否则「配置写坏了」被当成「没配」）")
    void malformedValueIsRejectedNotSilentlyEmpty() {
        when(tenantConfigApplicationService.getTenantConfig(TENANT_ID)).thenReturn(tenantConfig("{agentId: 123"));

        ReqResult<List<InternalIMAgentConfigItemDto>> result = controller.getDefaultIMAgentConfig(TENANT_ID, null);

        assertEquals(INVALID_PARAM, result.getCode());
        assertTrue(result.getMessage().contains("defaultIMAgentConfig"), result.getMessage());
        assertNull(result.getData());
    }

    @Test
    @DisplayName("配置是合法 JSON 但不是数组（对象/字符串）：同样报 4000")
    void validJsonButNotArrayIsRejected() {
        when(tenantConfigApplicationService.getTenantConfig(TENANT_ID))
                .thenReturn(tenantConfig("{\"agentId\": 123}"));

        ReqResult<List<InternalIMAgentConfigItemDto>> result = controller.getDefaultIMAgentConfig(TENANT_ID, null);

        assertEquals(INVALID_PARAM, result.getCode());
    }

    // ------------------------------------------------------------------ 归属闸门

    @Test
    @DisplayName("★不读请求上下文里那份配置：applyTenant 取不到时会保留默认租户那份，照读就跨租户泄露")
    void valueComesFromDeclaredTenantNotFromRequestContext() {
        // 上下文里挂着「默认租户」的配置（AuthInterceptor 按 tenantId=1 装载的那份）
        RequestContext.get().setTenantConfig(tenantConfig("[{\"agentId\": 1, \"defaultMessage\": \"默认租户的\"}]"));
        when(tenantConfigApplicationService.getTenantConfig(TENANT_ID))
                .thenReturn(tenantConfig("[{\"agentId\": 123, \"defaultMessage\": \"本租户的\"}]"));

        ReqResult<List<InternalIMAgentConfigItemDto>> result = controller.getDefaultIMAgentConfig(TENANT_ID, null);

        assertEquals(ReqResult.SUCCESS, result.getCode());
        assertEquals(123L, result.getData().get(0).getAgentId());
        assertEquals("本租户的", result.getData().get(0).getDefaultMessage());
    }

    // ------------------------------------------------------------------ userId：个人沙盒

    /**
     * ★本用例当前恒红，且不是本轮改动造成的：断言要求「保留未在线的（5001）、排除在线的（5002）」，
     * 而实现是 {@code removeIf(dto -> !dto.isOnline())} ⇒ 恰好相反（保留在线的）。成因是 057c54f48
     * 把谓词取反时只改了代码，javadoc「未在线」与这条断言都没跟着改（原始版本 7468b856b 是
     * {@code removeIf(SandboxConfigRpcDto::isOnline)}，与断言一致）。翻转谓词会改变线上下发内容，
     * 属产品语义决定，不在本轮「修空列表必崩」的授权范围内 —— 断言原样未动，等裁定。
     */
    @Test
    @DisplayName("★传 userId：该用户未在线的个人沙盒被拼进结果（在线的那台排除）")
    void userSandboxesAreAppendedWhenUserIdGiven() {
        // ★桩用不可变列表：真实实现的非空集来自 Collectors.toList()，但用 List.of() 能顺带钉住
        // 「控制器不许改动 RPC 返回的列表对象」——改了就会在这里抛 UnsupportedOperationException
        when(iSandboxConfigRpcService.queryUserConfigs(USER_ID))
                .thenReturn(List.of(sandbox("我的电脑", 5001L, false), sandbox("另一台", 5002L, true)));
        when(tenantConfigApplicationService.getTenantConfig(TENANT_ID))
                .thenReturn(tenantConfig("[{\"agentId\": 123, \"defaultMessage\": \"租户默认\"}]"));

        ReqResult<List<InternalIMAgentConfigItemDto>> result = controller.getDefaultIMAgentConfig(TENANT_ID, USER_ID);

        assertEquals(ReqResult.SUCCESS, result.getCode());
        assertEquals(2, result.getData().size(), "在线的沙盒不该出现：" + result.getData());
        assertEquals(5001L, result.getData().get(0).getAgentId());
        assertTrue(result.getData().get(0).getDefaultMessage().contains("我的电脑"),
                "文案里要带上沙盒名，客户端靠它区分是哪台机器：" + result.getData().get(0).getDefaultMessage());
        assertEquals(123L, result.getData().get(1).getAgentId(), "租户配置那份仍在");
    }

    @Test
    @DisplayName("★不传 userId：完全不查沙盒，只回租户配置那份（= 加参数之前的行为）")
    void withoutUserIdSandboxesAreNotQueried() {
        when(tenantConfigApplicationService.getTenantConfig(TENANT_ID))
                .thenReturn(tenantConfig("[{\"agentId\": 123}]"));

        ReqResult<List<InternalIMAgentConfigItemDto>> result = controller.getDefaultIMAgentConfig(TENANT_ID, null);

        assertEquals(ReqResult.SUCCESS, result.getCode());
        assertEquals(1, result.getData().size());
        verifyNoInteractions(iSandboxConfigRpcService);
    }

    @Test
    @DisplayName("★userId 给了但名下没有沙盒：只回租户配置那份，不报错（空集必须是真实形态 List.of()）")
    void userWithoutSandboxStillReturnsTenantItems() {
        // ★桩必须是真实实现的空集形态：SandboxConfigRpcServiceImpl#convertToRpcDtoList 空集返回 List.of()，
        // 而 List.of().removeIf 在 Java 9+ 无条件抛 UnsupportedOperationException（与列表空不空、谓词命不命中无关）。
        // 早先用 new ArrayList<>() 桩「比实现友好」，把这条必现缺陷盖成了绿的。
        when(iSandboxConfigRpcService.queryUserConfigs(USER_ID)).thenReturn(List.of());
        when(tenantConfigApplicationService.getTenantConfig(TENANT_ID))
                .thenReturn(tenantConfig("[{\"agentId\": 123}]"));

        ReqResult<List<InternalIMAgentConfigItemDto>> result = controller.getDefaultIMAgentConfig(TENANT_ID, USER_ID);

        assertEquals(ReqResult.SUCCESS, result.getCode(), "签名要求「没沙盒就只回租户那份」，不能整条失败：" + result.getMessage());
        assertEquals(1, result.getData().size());
        assertEquals(123L, result.getData().get(0).getAgentId());
    }

    @Test
    @DisplayName("★RPC 契约未承诺非空：返回 null、或列表里混 null 元素，都不报错")
    void rpcContractNullIsTolerated() {
        when(tenantConfigApplicationService.getTenantConfig(TENANT_ID))
                .thenReturn(tenantConfig("[{\"agentId\": 123}]"));

        // ① 整个列表为 null（接口签名只写 List<...>，没承诺非空）
        when(iSandboxConfigRpcService.queryUserConfigs(USER_ID)).thenReturn(null);
        ReqResult<List<InternalIMAgentConfigItemDto>> nullList = controller.getDefaultIMAgentConfig(TENANT_ID, USER_ID);
        assertEquals(ReqResult.SUCCESS, nullList.getCode(), "列表为 null 不该整条失败：" + nullList.getMessage());
        assertEquals(1, nullList.getData().size());

        // ② 列表里混入 null 元素：跳过后不炸。只钉「跳过空元素 + 恰好一台被在线判据过滤掉」，
        //    不押注是保留在线还是保留离线——那是既有语义分歧（见下方 userSandboxesAreAppendedWhenUserIdGiven），
        //    翻转方向不该让这条用例跟着红。
        when(iSandboxConfigRpcService.queryUserConfigs(USER_ID))
                .thenReturn(Arrays.asList(null, sandbox("我的电脑", 5001L, false), sandbox("在线的", 5002L, true)));
        ReqResult<List<InternalIMAgentConfigItemDto>> nullElement = controller.getDefaultIMAgentConfig(TENANT_ID, USER_ID);
        assertEquals(ReqResult.SUCCESS, nullElement.getCode());
        assertEquals(2, nullElement.getData().size(), "null 元素被跳过、两台里过滤掉一台 + 租户配置：" + nullElement.getData());
        assertTrue(nullElement.getData().stream().allMatch(item -> item.getAgentId() != null),
                "不该把 null 元素带成 null agentId：" + nullElement.getData());
    }

    @Test
    @DisplayName("沙盒查询抛异常：收敛成 0001，不外抛（沙盒挂了不该让整条配置接口不可用）")
    void sandboxQueryFailureBecomesErrorResult() {
        when(iSandboxConfigRpcService.queryUserConfigs(USER_ID))
                .thenThrow(new IllegalStateException("sandbox rpc down"));

        ReqResult<List<InternalIMAgentConfigItemDto>> result = controller.getDefaultIMAgentConfig(TENANT_ID, USER_ID);

        assertEquals(INTERNAL_ERROR, result.getCode());
        assertEquals("sandbox rpc down", result.getMessage());
    }

    // ------------------------------------------------------------------ 异常兜底

    @Test
    @DisplayName("配置读取抛异常：收敛成 0001 并把原因放进 message，不外抛")
    void unexpectedErrorBecomesErrorResult() {
        when(tenantConfigApplicationService.getTenantConfig(TENANT_ID))
                .thenThrow(new IllegalStateException("redis down"));

        ReqResult<List<InternalIMAgentConfigItemDto>> result = controller.getDefaultIMAgentConfig(TENANT_ID, null);

        assertEquals(INTERNAL_ERROR, result.getCode());
        assertEquals("redis down", result.getMessage());
    }

    // ------------------------------------------------------------------ 辅助

    private static TenantConfigDto tenantConfig(String defaultIMAgentConfig) {
        TenantConfigDto dto = new TenantConfigDto();
        dto.setDefaultIMAgentConfig(defaultIMAgentConfig);
        return dto;
    }

    private static SandboxConfigRpcDto sandbox(String name, Long agentId, boolean online) {
        SandboxConfigRpcDto dto = new SandboxConfigRpcDto();
        dto.setName(name);
        dto.setAgentId(agentId);
        dto.setOnline(online);
        return dto;
    }

    private static String fieldRequired(String fieldName) {
        return String.format(BizExceptionCodeEnum.fieldRequiredButEmpty.getMessageZh(), fieldName);
    }
}
