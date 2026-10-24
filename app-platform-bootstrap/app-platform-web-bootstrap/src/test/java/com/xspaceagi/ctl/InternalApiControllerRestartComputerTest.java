package com.xspaceagi.ctl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;

import com.xspaceagi.agent.core.adapter.application.ConversationApplicationService;
import com.xspaceagi.agent.core.adapter.dto.ConversationDto;
import com.xspaceagi.agent.core.infra.rpc.ComputerPodClient;
import com.xspaceagi.ctl.dto.InternalComputerRestartRequest;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.TenantConfigApplicationService;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;

/**
 * {@link InternalApiController#restartComputer} 的单元测试——纯 mock，不起 Spring 容器、不连库、
 * <b>不会真的去重启任何容器</b>。
 * <p>
 * 重点不在「成功路径能不能跑通」，而在两道归属闸门是否真的挡得住：内部接口没有登录态，
 * 「是谁的电脑」完全由调用方声明，容器又只按 project_id(userId) + cId 定位、不会二次判归属——
 * 所以「userId 属于该租户」与「会话属于该 userId」这两条一旦少一条，就成了「拿着内部密钥重启
 * 任意用户容器」的口子。这里逐条钉住它们（被拒时必须<b>没有</b>触达容器服务）。
 * <p>
 * 另一半是响应口径。平台侧就能判定的失败统一走本控制器的既有口径：{@code code=4000}
 * （{@link ErrorCodeEnum#INVALID_PARAM}）+ message 说明具体原因——三种拒绝共用一个 code，
 * <b>message 是调用方唯一的区分手段</b>，所以这里对文案也逐条断言。容器侧给不出 code 的三种情况
 * （响应体为空 / 4xx 且下游没带 code / 5xx 抛异常）则收敛成 9309，绝不能把 {@code code=null}
 * 透传给按 code 分支的调用方；容器自己给出的码则原样透传，不二次翻译。
 */
class InternalApiControllerRestartComputerTest {

    private static final long TENANT_ID = 1L;
    private static final long USER_ID = 10086L;
    private static final long CONVERSATION_ID = 227861195531485140L;

    /** 参数/归属类失败共用的 code（与控制器里其余 42 处 BizException.of 的口径一致） */
    private static final String INVALID_PARAM = ErrorCodeEnum.INVALID_PARAM.getCode();

    private InternalApiController controller;
    private UserApplicationService userApplicationService;
    private ConversationApplicationService conversationApplicationService;
    private TenantConfigApplicationService tenantConfigApplicationService;
    private ComputerPodClient computerPodClient;

    @BeforeEach
    void setUp() {
        controller = new InternalApiController();
        userApplicationService = mock(UserApplicationService.class);
        conversationApplicationService = mock(ConversationApplicationService.class);
        tenantConfigApplicationService = mock(TenantConfigApplicationService.class);
        computerPodClient = mock(ComputerPodClient.class);
        ReflectionTestUtils.setField(controller, "userApplicationService", userApplicationService);
        ReflectionTestUtils.setField(controller, "conversationApplicationService", conversationApplicationService);
        ReflectionTestUtils.setField(controller, "tenantConfigApplicationService", tenantConfigApplicationService);
        ReflectionTestUtils.setField(controller, "computerPodClient", computerPodClient);
        // applyTenant 会往请求上下文里写租户；真实请求里这份上下文由 AuthInterceptor 落下。
        // lang 固定成 zh-CN，好让文案断言是确定的那一份（不设 lang 时按英文模板给）
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
    @DisplayName("缺 tenantId：在碰用户/容器之前就被拒")
    void tenantIdMissingIsRejectedFirst() {
        ReqResult<Object> result = controller.restartComputer(null, request(USER_ID, CONVERSATION_ID));

        assertEquals(INVALID_PARAM, result.getCode());
        assertEquals(fieldRequired("tenantId"), result.getMessage());
        verifyNoInteractions(userApplicationService);
        verifyNoInteractions(conversationApplicationService);
        verifyNoInteractions(computerPodClient);
    }

    @Test
    @DisplayName("请求体为空：被拒，不触达容器")
    void nullBodyIsRejected() {
        ReqResult<Object> result = controller.restartComputer(TENANT_ID, null);

        assertEquals(INVALID_PARAM, result.getCode());
        assertEquals("请求体不能为空", result.getMessage());
        verifyNoInteractions(computerPodClient);
    }

    @Test
    @DisplayName("缺 conversationId：被拒，不触达容器")
    void conversationIdMissingIsRejected() {
        ReqResult<Object> result = controller.restartComputer(TENANT_ID, request(USER_ID, null));

        assertEquals(INVALID_PARAM, result.getCode());
        assertEquals(fieldRequired("conversationId"), result.getMessage());
        verifyNoInteractions(computerPodClient);
    }

    @Test
    @DisplayName("缺 userId：被拒，不触达容器（userId 无法自行推导，必须由调用方给准）")
    void userIdMissingIsRejected() {
        ReqResult<Object> result = controller.restartComputer(TENANT_ID, request(null, CONVERSATION_ID));

        assertEquals(INVALID_PARAM, result.getCode());
        assertEquals(fieldRequired("userId"), result.getMessage());
        verifyNoInteractions(computerPodClient);
    }

    // ------------------------------------------------------------------ 归属闸门一：userId ∈ tenantId

    @Test
    @DisplayName("用户不存在：被拒，不触达容器")
    void unknownUserIsRejected() {
        ReqResult<Object> result = controller.restartComputer(TENANT_ID, request(USER_ID, CONVERSATION_ID));

        assertEquals(INVALID_PARAM, result.getCode());
        assertEquals(BizExceptionCodeEnum.systemUserNotFound.getMessageZh(), result.getMessage());
        verifyNoInteractions(computerPodClient);
    }

    @Test
    @DisplayName("★用户属于别的租户：被拒，不触达容器（用户域查询豁免租户过滤，这条必须自己收口）")
    void crossTenantUserIsRejected() {
        when(userApplicationService.queryById(USER_ID)).thenReturn(user(2L));

        ReqResult<Object> result = controller.restartComputer(TENANT_ID, request(USER_ID, CONVERSATION_ID));

        assertEquals(INVALID_PARAM, result.getCode());
        assertEquals(BizExceptionCodeEnum.systemUserNotFound.getMessageZh(), result.getMessage());
        verifyNoInteractions(conversationApplicationService);
        verifyNoInteractions(computerPodClient);
    }

    // ------------------------------------------------------------------ 归属闸门二：会话 ∈ userId

    @Test
    @DisplayName("★会话不属于该 userId：被拒且绝不触达容器（否则可重启别人的容器）")
    void conversationNotOwnedByUserIsRejected() {
        when(userApplicationService.queryById(USER_ID)).thenReturn(user(TENANT_ID));
        when(conversationApplicationService.getConversation(USER_ID, CONVERSATION_ID)).thenReturn(null);

        ReqResult<Object> result = controller.restartComputer(TENANT_ID, request(USER_ID, CONVERSATION_ID));

        assertEquals(INVALID_PARAM, result.getCode());
        assertEquals(BizExceptionCodeEnum.agentConversationNotFound.getMessageZh(), result.getMessage());
        // 归属校验必须用「声明的那两个 id」去查，不能换成别的
        verify(conversationApplicationService).getConversation(USER_ID, CONVERSATION_ID);
        verifyNoInteractions(computerPodClient);
    }

    // ------------------------------------------------------------------ 透传口径

    @Test
    @DisplayName("成功：容器服务 0000 / message / data 原样透传，且重启参数用调用方声明的 userId")
    void containerSuccessIsPassedThrough() {
        Map<String, Object> containerData = Map.of("containerId", (Object) "pod-abc", "state", "running");
        givenOwnedConversation();
        when(computerPodClient.restart(CONVERSATION_ID, USER_ID, "prod"))
                .thenReturn(Map.of("code", "0000", "message", "success", "data", containerData, "tid", "t-1"));

        ReqResult<Object> result = controller.restartComputer(TENANT_ID, request(USER_ID, CONVERSATION_ID, "prod"));

        assertEquals("0000", result.getCode());
        assertTrue(result.isSuccess());
        assertEquals(containerData, result.getData());
        verify(computerPodClient).restart(CONVERSATION_ID, USER_ID, "prod");
    }

    @Test
    @DisplayName("容器服务返回失败码：信封 code 就是它，不二次翻译（容器说失败绝不能被平台说成成功）")
    void containerFailureCodeIsPassedThrough() {
        givenOwnedConversation();
        when(computerPodClient.restart(CONVERSATION_ID, USER_ID, null))
                .thenReturn(Map.of("code", "9308", "message", "pod restart rejected"));

        ReqResult<Object> result = controller.restartComputer(TENANT_ID, request(USER_ID, CONVERSATION_ID));

        assertEquals("9308", result.getCode());
        assertEquals("pod restart rejected", result.getMessage());
    }

    @Test
    @DisplayName("容器 4xx 且错误体没带 code：收敛成 9309，下游原文留在 message")
    void containerClientErrorWithoutCodeIsNormalized() {
        givenOwnedConversation();
        // ComputerPodClient#parseClientErr 的实际形态：只有 success/message，没有 code
        when(computerPodClient.restart(CONVERSATION_ID, USER_ID, null))
                .thenReturn(Map.of("success", false, "message", "400 Bad Request: pod not found"));

        ReqResult<Object> result = controller.restartComputer(TENANT_ID, request(USER_ID, CONVERSATION_ID));

        assertEquals(BizExceptionCodeEnum.agentContainerServiceCallFailed.getCode(), result.getCode());
        assertNotNull(result.getMessage());
        assertTrue(result.getMessage().contains("pod not found"), "下游原文应保留在 message 里：" + result.getMessage());
    }

    @Test
    @DisplayName("容器服务 5xx/连不上：RestClientException 收敛成 9309，而不是落到 0001")
    void containerTransportFailureIsNormalized() {
        givenOwnedConversation();
        when(computerPodClient.restart(CONVERSATION_ID, USER_ID, null))
                .thenThrow(new RestClientException("Connection refused: sandbox server"));

        ReqResult<Object> result = controller.restartComputer(TENANT_ID, request(USER_ID, CONVERSATION_ID));

        assertEquals(BizExceptionCodeEnum.agentContainerServiceCallFailed.getCode(), result.getCode());
        assertTrue(result.getMessage().contains("Connection refused"), "异常原文应保留：" + result.getMessage());
    }

    @Test
    @DisplayName("容器服务响应体为空：收敛成 9309，不透传 code=null")
    void nullContainerResponseIsNormalized() {
        givenOwnedConversation();
        when(computerPodClient.restart(CONVERSATION_ID, USER_ID, null)).thenReturn(null);

        ReqResult<Object> result = controller.restartComputer(TENANT_ID, request(USER_ID, CONVERSATION_ID));

        assertEquals(BizExceptionCodeEnum.agentContainerServiceCallFailed.getCode(), result.getCode());
        assertNull(result.getData());
    }

    // ------------------------------------------------------------------ 辅助

    /** 会话确实属于该用户（两道归属闸门都放行的最小前提） */
    private void givenOwnedConversation() {
        when(userApplicationService.queryById(USER_ID)).thenReturn(user(TENANT_ID));
        when(conversationApplicationService.getConversation(USER_ID, CONVERSATION_ID))
                .thenReturn(ConversationDto.builder().id(CONVERSATION_ID).build());
    }

    private static UserDto user(Long tenantId) {
        UserDto user = new UserDto();
        user.setId(USER_ID);
        user.setTenantId(tenantId);
        return user;
    }

    /** 必填项缺失的文案，形如「参数'tenantId'不能为空」 */
    private static String fieldRequired(String fieldName) {
        return String.format(BizExceptionCodeEnum.fieldRequiredButEmpty.getMessageZh(), fieldName);
    }

    private static InternalComputerRestartRequest request(Long userId, Long conversationId) {
        return request(userId, conversationId, null);
    }

    private static InternalComputerRestartRequest request(Long userId, Long conversationId, String appStage) {
        InternalComputerRestartRequest request = new InternalComputerRestartRequest();
        request.setUserId(userId);
        request.setConversationId(conversationId);
        request.setAppStage(appStage);
        return request;
    }
}
