package com.xspaceagi.agent.web.ui.controller;

import com.xspaceagi.agent.core.adapter.application.AgentApplicationService;
import com.xspaceagi.agent.core.adapter.application.ChatApplicationService;
import com.xspaceagi.agent.core.adapter.application.ConversationApplicationService;
import com.xspaceagi.agent.core.adapter.dto.AgentDetailDto;
import com.xspaceagi.agent.core.adapter.dto.AgentOutputDto;
import com.xspaceagi.agent.core.adapter.dto.ChatMessageDto;
import com.xspaceagi.agent.core.adapter.dto.ConversationDto;
import com.xspaceagi.agent.core.adapter.dto.TryReqDto;
import com.xspaceagi.agent.core.adapter.dto.config.AgentComponentConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.AgentConfigDto;
import com.xspaceagi.agent.core.adapter.repository.entity.AgentComponentConfig;
import com.xspaceagi.agent.core.adapter.repository.entity.Conversation;
import com.xspaceagi.agent.web.ui.controller.dto.SandboxAgentTaskCancelReqDto;
import com.xspaceagi.agent.web.ui.controller.dto.SandboxAgentTaskCancelResultDto;
import com.xspaceagi.agent.web.ui.controller.dto.SandboxAgentTaskDispatchReqDto;
import com.xspaceagi.agent.web.ui.controller.dto.SandboxAgentTaskDispatchResultDto;
import com.xspaceagi.agent.web.ui.controller.dto.SandboxAgentTaskItemDto;
import com.xspaceagi.agent.web.ui.controller.dto.SandboxAgentTaskListReqDto;
import com.xspaceagi.agent.web.ui.controller.dto.SandboxAgentTaskProgressDto;
import com.xspaceagi.agent.web.ui.controller.dto.SandboxAgentTaskProgressReqDto;
import com.xspaceagi.agent.web.ui.controller.dto.SandboxAgentTaskSearchReqDto;
import com.xspaceagi.system.sdk.service.dto.UserAccessKeyDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentTaskForSandboxController 单元测试。
 *
 * <p>RequestContext 是普通 ThreadLocal，直接 set/remove 即可，无需静态 mock。
 * 重点盯四件事：EXECUTING 过滤、范围（自身+子智能体）传递、归属校验收口
 * （底层 getConversationByCid / stopChat / chat 自身没有任何用户过滤）、
 * dispatch 的三道守卫（目标范围 / 续聊归属+目标一致 / 续聊不撞执行中）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentTaskForSandboxControllerTest {

    private static final Long AK_USER_ID = 100L;
    private static final Long AK_AGENT_ID = 200L;
    private static final Long SUB_AGENT_ID = 300L;
    private static final Long FOREIGN_AGENT_ID = 999L;

    @Mock
    private ConversationApplicationService conversationApplicationService;

    @Mock
    private ChatApplicationService chatApplicationService;

    @Mock
    private AgentApplicationService agentApplicationService;

    @InjectMocks
    private AgentTaskForSandboxController controller;

    @BeforeEach
    void setUp() {
        setupAgentAkContext();
        AgentComponentConfigDto sub = new AgentComponentConfigDto();
        sub.setTargetId(SUB_AGENT_ID);
        sub.setName("子智能体");
        when(agentApplicationService.queryComponentConfigListByAgentIdAndType(AK_AGENT_ID, AgentComponentConfig.Type.Agent))
                .thenReturn(Collections.singletonList(sub));
    }

    @AfterEach
    void tearDown() {
        RequestContext.remove();
    }

    private void setupAgentAkContext() {
        UserAccessKeyDto ak = new UserAccessKeyDto();
        ak.setUserId(AK_USER_ID);
        ak.setTargetType(UserAccessKeyDto.AKTargetType.Agent);
        ak.setTargetId(String.valueOf(AK_AGENT_ID));
        AgentDetailDto self = new AgentDetailDto();
        self.setName("主智能体");
        RequestContext<Object> context = new RequestContext<>();
        context.setUserId(AK_USER_ID);
        context.setUserAccessKey(ak);
        context.setAkTarget(self);
        RequestContext.set(context);
    }

    private ConversationDto conversation(Long id, Long userId, Long agentId,
                                         Conversation.ConversationTaskStatus status, String topic) {
        ConversationDto dto = new ConversationDto();
        dto.setId(id);
        dto.setUserId(userId);
        dto.setAgentId(agentId);
        dto.setTaskStatus(status);
        dto.setTopic(topic);
        return dto;
    }

    private static boolean isSuccess(ReqResult<?> result) {
        return "0000".equals(result.getCode());
    }

    private AgentConfigDto publishedConfig(Long agentId, String name) {
        AgentConfigDto config = new AgentConfigDto();
        config.setId(agentId);
        config.setName(name);
        config.setTenantId(9L);
        return config;
    }

    // ---------------------------------------------------------------- list-running

    @Test
    void listRunningFiltersExecutingAndFillsAgentNameAndScope() {
        ConversationDto executing = conversation(1L, AK_USER_ID, SUB_AGENT_ID,
                Conversation.ConversationTaskStatus.EXECUTING, "执行中任务");
        ConversationDto complete = conversation(2L, AK_USER_ID, AK_AGENT_ID,
                Conversation.ConversationTaskStatus.COMPLETE, "已完成任务");
        when(conversationApplicationService.queryLatestConversationList(
                eq(AK_USER_ID), eq(Arrays.asList(AK_AGENT_ID, SUB_AGENT_ID)), isNull(), eq(20)))
                .thenReturn(Arrays.asList(executing, complete));

        ReqResult<List<SandboxAgentTaskItemDto>> result = controller.listRunning(null);

        assertTrue(isSuccess(result));
        assertEquals(1, result.getData().size());
        SandboxAgentTaskItemDto item = result.getData().get(0);
        assertEquals(1L, item.getConversationId());
        assertEquals("EXECUTING", item.getTaskStatus());
        assertEquals("子智能体", item.getAgentName());
        // 范围必须是 自身+子智能体，keyword 为 null，limit 默认 20
        verify(conversationApplicationService).queryLatestConversationList(
                eq(AK_USER_ID), eq(Arrays.asList(AK_AGENT_ID, SUB_AGENT_ID)), isNull(), eq(20));
    }

    @Test
    void listRunningCapsLimitAt50() {
        when(conversationApplicationService.queryLatestConversationList(anyLong(), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        controller.listRunning(SandboxAgentTaskListReqDto.builder().limit(500).build());

        verify(conversationApplicationService).queryLatestConversationList(
                eq(AK_USER_ID), eq(Arrays.asList(AK_AGENT_ID, SUB_AGENT_ID)), isNull(), eq(50));
    }

    @Test
    void listRunningFallsBackToSelfOnlyWhenSubAgentQueryFails() {
        when(agentApplicationService.queryComponentConfigListByAgentIdAndType(anyLong(), any()))
                .thenThrow(new RuntimeException("boom"));
        when(conversationApplicationService.queryLatestConversationList(anyLong(), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        ReqResult<List<SandboxAgentTaskItemDto>> result = controller.listRunning(null);

        assertTrue(isSuccess(result));
        verify(conversationApplicationService).queryLatestConversationList(
                eq(AK_USER_ID), eq(Collections.singletonList(AK_AGENT_ID)), isNull(), eq(20));
    }

    // ---------------------------------------------------------------- search

    @Test
    void searchPassesKeywordAndReturnsAllStatuses() {
        ConversationDto executing = conversation(1L, AK_USER_ID, AK_AGENT_ID,
                Conversation.ConversationTaskStatus.EXECUTING, "报告A");
        ConversationDto failed = conversation(2L, AK_USER_ID, SUB_AGENT_ID,
                Conversation.ConversationTaskStatus.FAILED, "报告B");
        when(conversationApplicationService.queryLatestConversationList(
                eq(AK_USER_ID), eq(Arrays.asList(AK_AGENT_ID, SUB_AGENT_ID)), eq("报告"), eq(20)))
                .thenReturn(Arrays.asList(executing, failed));

        ReqResult<List<SandboxAgentTaskItemDto>> result =
                controller.search(SandboxAgentTaskSearchReqDto.builder().keyword("报告").build());

        assertTrue(isSuccess(result));
        assertEquals(2, result.getData().size());
        assertEquals("EXECUTING", result.getData().get(0).getTaskStatus());
        assertEquals("FAILED", result.getData().get(1).getTaskStatus());
        assertEquals("主智能体", result.getData().get(0).getAgentName());
    }

    @Test
    void searchWithBlankKeywordDegradesToRecentTasks() {
        when(conversationApplicationService.queryLatestConversationList(anyLong(), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        controller.search(SandboxAgentTaskSearchReqDto.builder().keyword("   ").build());

        verify(conversationApplicationService).queryLatestConversationList(
                eq(AK_USER_ID), eq(Arrays.asList(AK_AGENT_ID, SUB_AGENT_ID)), isNull(), eq(20));
    }

    // ---------------------------------------------------------------- progress

    @Test
    void progressReturnsMessagesForResultWhenComplete() {
        ConversationDto conv = conversation(1L, AK_USER_ID, SUB_AGENT_ID,
                Conversation.ConversationTaskStatus.COMPLETE, "写周报");
        ChatMessageDto question = new ChatMessageDto();
        question.setRole(ChatMessageDto.Role.USER);
        question.setSenderType(ChatMessageDto.SenderType.USER);
        question.setText("帮我写周报");
        ChatMessageDto answer = new ChatMessageDto();
        answer.setRole(ChatMessageDto.Role.ASSISTANT);
        answer.setSenderType(ChatMessageDto.SenderType.AGENT);
        answer.setText("周报内容……");
        conv.setMessageList(Arrays.asList(question, answer));
        when(conversationApplicationService.getConversationWithLastMessageByCid(1L)).thenReturn(conv);

        ReqResult<SandboxAgentTaskProgressDto> result =
                controller.progress(SandboxAgentTaskProgressReqDto.builder().conversationId(1L).build());

        assertTrue(isSuccess(result));
        SandboxAgentTaskProgressDto dto = result.getData();
        assertEquals("COMPLETE", dto.getTaskStatus());
        assertFalse(dto.getRunning());
        assertEquals("子智能体", dto.getAgentName());
        assertEquals(2, dto.getMessages().size());
        // 与 MasterAgentLoop 语义一致：COMPLETE 时最后一条即任务结果
        assertEquals("周报内容……", dto.getMessages().get(1).getText());
        assertEquals("ASSISTANT", dto.getMessages().get(1).getRole());
    }

    @Test
    void progressMarksRunningForExecutingTask() {
        ConversationDto conv = conversation(1L, AK_USER_ID, AK_AGENT_ID,
                Conversation.ConversationTaskStatus.EXECUTING, "跑数");
        when(conversationApplicationService.getConversationWithLastMessageByCid(1L)).thenReturn(conv);

        ReqResult<SandboxAgentTaskProgressDto> result =
                controller.progress(SandboxAgentTaskProgressReqDto.builder().conversationId(1L).build());

        assertTrue(isSuccess(result));
        assertTrue(result.getData().getRunning());
        assertEquals("EXECUTING", result.getData().getTaskStatus());
    }

    @Test
    void progressRejectsForeignUserConversation() {
        ConversationDto foreign = conversation(1L, 777L, AK_AGENT_ID,
                Conversation.ConversationTaskStatus.COMPLETE, "别人的任务");
        when(conversationApplicationService.getConversationWithLastMessageByCid(1L)).thenReturn(foreign);

        ReqResult<SandboxAgentTaskProgressDto> result =
                controller.progress(SandboxAgentTaskProgressReqDto.builder().conversationId(1L).build());

        assertFalse(isSuccess(result));
        assertEquals("任务不存在或无权访问", result.getMessage());
    }

    @Test
    void progressRejectsOutOfScopeAgentConversation() {
        ConversationDto outOfScope = conversation(1L, AK_USER_ID, FOREIGN_AGENT_ID,
                Conversation.ConversationTaskStatus.COMPLETE, "范围外智能体的任务");
        when(conversationApplicationService.getConversationWithLastMessageByCid(1L)).thenReturn(outOfScope);

        ReqResult<SandboxAgentTaskProgressDto> result =
                controller.progress(SandboxAgentTaskProgressReqDto.builder().conversationId(1L).build());

        assertFalse(isSuccess(result));
        assertEquals("任务不存在或无权访问", result.getMessage());
    }

    @Test
    void progressRejectsMissingConversation() {
        when(conversationApplicationService.getConversationWithLastMessageByCid(1L)).thenReturn(null);

        ReqResult<SandboxAgentTaskProgressDto> result =
                controller.progress(SandboxAgentTaskProgressReqDto.builder().conversationId(1L).build());

        assertFalse(isSuccess(result));
        assertEquals("任务不存在或无权访问", result.getMessage());
    }

    // ---------------------------------------------------------------- cancel

    @Test
    void cancelExecutingTaskCallsStopChatAndReportsCancelled() {
        ConversationDto conv = conversation(1L, AK_USER_ID, SUB_AGENT_ID,
                Conversation.ConversationTaskStatus.EXECUTING, "跑数");
        when(conversationApplicationService.getConversationByCid(1L)).thenReturn(conv);

        ReqResult<SandboxAgentTaskCancelResultDto> result = controller.cancel(
                SandboxAgentTaskCancelReqDto.builder().conversationId(1L).reason("不需要了").build());

        assertTrue(isSuccess(result));
        assertTrue(result.getData().getCancelled());
        assertEquals("CANCEL", result.getData().getTaskStatus());
        // stopChat 收的是字符串形式的会话ID（与 MasterAgentLoop 一致）
        verify(chatApplicationService).stopChat("1");
    }

    @Test
    void cancelFinishedTaskReportsNotCancelled() {
        ConversationDto conv = conversation(1L, AK_USER_ID, AK_AGENT_ID,
                Conversation.ConversationTaskStatus.COMPLETE, "写周报");
        when(conversationApplicationService.getConversationByCid(1L)).thenReturn(conv);

        ReqResult<SandboxAgentTaskCancelResultDto> result = controller.cancel(
                SandboxAgentTaskCancelReqDto.builder().conversationId(1L).build());

        assertTrue(isSuccess(result));
        assertFalse(result.getData().getCancelled());
        assertEquals("COMPLETE", result.getData().getTaskStatus());
        // 与 MasterAgentLoop 一致：stopChat 照调，底层对非 EXECUTING 静默无操作
        verify(chatApplicationService).stopChat("1");
    }

    @Test
    void cancelRejectsForeignConversationWithoutTouchingStopChat() {
        ConversationDto foreign = conversation(1L, 777L, AK_AGENT_ID,
                Conversation.ConversationTaskStatus.EXECUTING, "别人的任务");
        when(conversationApplicationService.getConversationByCid(1L)).thenReturn(foreign);

        ReqResult<SandboxAgentTaskCancelResultDto> result = controller.cancel(
                SandboxAgentTaskCancelReqDto.builder().conversationId(1L).build());

        assertFalse(isSuccess(result));
        assertEquals("任务不存在或无权访问", result.getMessage());
        // ★越权取消必须在 stopChat 之前被拦下
        verify(chatApplicationService, never()).stopChat(anyString());
    }

    // ---------------------------------------------------------------- dispatch

    @Test
    void dispatchCreatesConversationAndStartsExecution() {
        AgentConfigDto config = publishedConfig(SUB_AGENT_ID, "子智能体");
        when(agentApplicationService.queryPublishedConfigForExecute(SUB_AGENT_ID)).thenReturn(config);
        when(conversationApplicationService.createConversationForMasterAgentLoop(
                eq(9L), eq(AK_USER_ID), eq(SUB_AGENT_ID), eq("帮我整理季度数据"), any()))
                .thenReturn(conversation(10L, AK_USER_ID, SUB_AGENT_ID, null, "帮我整理季度数据"));
        when(chatApplicationService.chat(any(TryReqDto.class), isNull(), eq(false))).thenReturn(Flux.empty());
        // 派发后回读：chat() 同步段已把会话置 EXECUTING
        when(conversationApplicationService.getConversationByCid(10L)).thenReturn(
                conversation(10L, AK_USER_ID, SUB_AGENT_ID, Conversation.ConversationTaskStatus.EXECUTING, "帮我整理季度数据"));

        ReqResult<SandboxAgentTaskDispatchResultDto> result = controller.dispatch(
                SandboxAgentTaskDispatchReqDto.builder()
                        .targetAgentId(SUB_AGENT_ID)
                        .inputMessage("帮我整理季度数据")
                        .build());

        assertTrue(isSuccess(result));
        SandboxAgentTaskDispatchResultDto dto = result.getData();
        assertEquals(10L, dto.getConversationId());
        assertTrue(dto.getCreated());
        // subject 缺省时主题 = inputMessage（与 MasterAgentLoop 一致）
        assertEquals("帮我整理季度数据", dto.getTopic());
        assertEquals("子智能体", dto.getAgentName());
        assertEquals("EXECUTING", dto.getTaskStatus());
        assertNull(dto.getError());
        // 租户取自目标智能体配置；fire-and-forget 的 chat 调用带上了执行配置
        verify(conversationApplicationService).createConversationForMasterAgentLoop(
                eq(9L), eq(AK_USER_ID), eq(SUB_AGENT_ID), eq("帮我整理季度数据"), any());
        ArgumentCaptor<TryReqDto> captor = ArgumentCaptor.forClass(TryReqDto.class);
        verify(chatApplicationService).chat(captor.capture(), isNull(), eq(false));
        TryReqDto subReq = captor.getValue();
        assertEquals("帮我整理季度数据", subReq.getMessage());
        assertEquals(10L, subReq.getConversationId());
        assertEquals("Sandbox", subReq.getFrom());
        assertEquals(config, subReq.getAgentConfig());
        assertNull(subReq.getModelId());
    }

    @Test
    void dispatchUsesSubjectAsTopicAndSelectedModelWhenAllowOtherModel() {
        AgentConfigDto config = publishedConfig(SUB_AGENT_ID, "子智能体");
        config.setAllowOtherModel(YesOrNoEnum.Y.getKey());
        when(agentApplicationService.queryPublishedConfigForExecute(SUB_AGENT_ID)).thenReturn(config);
        when(chatApplicationService.getSelectedModelId(AK_USER_ID, SUB_AGENT_ID)).thenReturn(42L);
        when(conversationApplicationService.createConversationForMasterAgentLoop(
                eq(9L), eq(AK_USER_ID), eq(SUB_AGENT_ID), eq("季度数据"), any()))
                .thenReturn(conversation(11L, AK_USER_ID, SUB_AGENT_ID, null, "季度数据"));
        when(chatApplicationService.chat(any(TryReqDto.class), isNull(), eq(false))).thenReturn(Flux.empty());
        when(conversationApplicationService.getConversationByCid(11L)).thenReturn(
                conversation(11L, AK_USER_ID, SUB_AGENT_ID, Conversation.ConversationTaskStatus.EXECUTING, "季度数据"));

        ReqResult<SandboxAgentTaskDispatchResultDto> result = controller.dispatch(
                SandboxAgentTaskDispatchReqDto.builder()
                        .targetAgentId(SUB_AGENT_ID)
                        .inputMessage("帮我整理季度数据")
                        .subject("季度数据")
                        .build());

        assertTrue(isSuccess(result));
        assertEquals("季度数据", result.getData().getTopic());
        ArgumentCaptor<TryReqDto> captor = ArgumentCaptor.forClass(TryReqDto.class);
        verify(chatApplicationService).chat(captor.capture(), isNull(), eq(false));
        // 与 MasterAgentLoop 一致：允许换模型的智能体带上用户选定的模型
        assertEquals(42L, captor.getValue().getModelId());
    }

    @Test
    void dispatchRejectsTargetOutsideScope() {
        ReqResult<SandboxAgentTaskDispatchResultDto> result = controller.dispatch(
                SandboxAgentTaskDispatchReqDto.builder()
                        .targetAgentId(FOREIGN_AGENT_ID)
                        .inputMessage("做点事")
                        .build());

        assertFalse(isSuccess(result));
        assertEquals("目标智能体不在可派发范围内（仅本 AK 智能体或其配置的子智能体）", result.getMessage());
        // ★范围外的目标连配置都不该去取，更不能触发执行
        verify(agentApplicationService, never()).queryPublishedConfigForExecute(anyLong());
        verify(conversationApplicationService, never())
                .createConversationForMasterAgentLoop(any(), any(), any(), any(), any());
        verify(chatApplicationService, never()).chat(any(TryReqDto.class), any(), anyBoolean());
    }

    @Test
    void dispatchRejectsWhenTargetNotExecutable() {
        when(agentApplicationService.queryPublishedConfigForExecute(SUB_AGENT_ID)).thenReturn(null);

        ReqResult<SandboxAgentTaskDispatchResultDto> result = controller.dispatch(
                SandboxAgentTaskDispatchReqDto.builder()
                        .targetAgentId(SUB_AGENT_ID)
                        .inputMessage("做点事")
                        .build());

        assertFalse(isSuccess(result));
        assertEquals("目标智能体不可执行（未发布、已下线或模型不可用）", result.getMessage());
        // 不可执行时不许留下孤儿会话
        verify(conversationApplicationService, never())
                .createConversationForMasterAgentLoop(any(), any(), any(), any(), any());
        verify(chatApplicationService, never()).chat(any(TryReqDto.class), any(), anyBoolean());
    }

    @Test
    void dispatchContinuesOwnedFinishedConversation() {
        AgentConfigDto config = publishedConfig(SUB_AGENT_ID, "子智能体");
        when(agentApplicationService.queryPublishedConfigForExecute(SUB_AGENT_ID)).thenReturn(config);
        // 第一次：归属校验（COMPLETE 可续聊）；第二次：派发后回读（EXECUTING）
        when(conversationApplicationService.getConversationByCid(5L)).thenReturn(
                conversation(5L, AK_USER_ID, SUB_AGENT_ID, Conversation.ConversationTaskStatus.COMPLETE, "旧任务"),
                conversation(5L, AK_USER_ID, SUB_AGENT_ID, Conversation.ConversationTaskStatus.EXECUTING, "旧任务"));
        when(chatApplicationService.chat(any(TryReqDto.class), isNull(), eq(false))).thenReturn(Flux.empty());

        ReqResult<SandboxAgentTaskDispatchResultDto> result = controller.dispatch(
                SandboxAgentTaskDispatchReqDto.builder()
                        .targetAgentId(SUB_AGENT_ID)
                        .inputMessage("继续补充第二部分")
                        .conversationId(5L)
                        .build());

        assertTrue(isSuccess(result));
        SandboxAgentTaskDispatchResultDto dto = result.getData();
        assertFalse(dto.getCreated());
        assertEquals(5L, dto.getConversationId());
        assertEquals("旧任务", dto.getTopic());
        assertEquals("EXECUTING", dto.getTaskStatus());
        // 续聊不许新建会话
        verify(conversationApplicationService, never())
                .createConversationForMasterAgentLoop(any(), any(), any(), any(), any());
        ArgumentCaptor<TryReqDto> captor = ArgumentCaptor.forClass(TryReqDto.class);
        verify(chatApplicationService).chat(captor.capture(), isNull(), eq(false));
        assertEquals(5L, captor.getValue().getConversationId());
        assertEquals("继续补充第二部分", captor.getValue().getMessage());
    }

    @Test
    void dispatchRejectsContinuationOfForeignConversationWithoutStartingChat() {
        when(agentApplicationService.queryPublishedConfigForExecute(SUB_AGENT_ID))
                .thenReturn(publishedConfig(SUB_AGENT_ID, "子智能体"));
        when(conversationApplicationService.getConversationByCid(5L)).thenReturn(
                conversation(5L, 777L, SUB_AGENT_ID, Conversation.ConversationTaskStatus.COMPLETE, "别人的任务"));

        ReqResult<SandboxAgentTaskDispatchResultDto> result = controller.dispatch(
                SandboxAgentTaskDispatchReqDto.builder()
                        .targetAgentId(SUB_AGENT_ID)
                        .inputMessage("续聊")
                        .conversationId(5L)
                        .build());

        assertFalse(isSuccess(result));
        assertEquals("任务不存在或无权访问", result.getMessage());
        // ★越权续聊必须在触发执行之前被拦下
        verify(chatApplicationService, never()).chat(any(TryReqDto.class), any(), anyBoolean());
    }

    @Test
    void dispatchRejectsContinuationWhenTargetAgentMismatch() {
        when(agentApplicationService.queryPublishedConfigForExecute(SUB_AGENT_ID))
                .thenReturn(publishedConfig(SUB_AGENT_ID, "子智能体"));
        // 会话属于范围内的 AK 智能体自身，但和本次声明的目标不一致 —— 同样拒绝
        when(conversationApplicationService.getConversationByCid(5L)).thenReturn(
                conversation(5L, AK_USER_ID, AK_AGENT_ID, Conversation.ConversationTaskStatus.COMPLETE, "别的智能体的任务"));

        ReqResult<SandboxAgentTaskDispatchResultDto> result = controller.dispatch(
                SandboxAgentTaskDispatchReqDto.builder()
                        .targetAgentId(SUB_AGENT_ID)
                        .inputMessage("续聊")
                        .conversationId(5L)
                        .build());

        assertFalse(isSuccess(result));
        assertEquals("任务不存在或无权访问", result.getMessage());
        verify(chatApplicationService, never()).chat(any(TryReqDto.class), any(), anyBoolean());
    }

    @Test
    void dispatchRejectsContinuationOfRunningTask() {
        when(agentApplicationService.queryPublishedConfigForExecute(SUB_AGENT_ID))
                .thenReturn(publishedConfig(SUB_AGENT_ID, "子智能体"));
        when(conversationApplicationService.getConversationByCid(5L)).thenReturn(
                conversation(5L, AK_USER_ID, SUB_AGENT_ID, Conversation.ConversationTaskStatus.EXECUTING, "在跑的任务"));

        ReqResult<SandboxAgentTaskDispatchResultDto> result = controller.dispatch(
                SandboxAgentTaskDispatchReqDto.builder()
                        .targetAgentId(SUB_AGENT_ID)
                        .inputMessage("再派一次")
                        .conversationId(5L)
                        .build());

        assertFalse(isSuccess(result));
        assertEquals("任务正在执行中，不能重复派发（可等待完成或先取消）", result.getMessage());
        verify(chatApplicationService, never()).chat(any(TryReqDto.class), any(), anyBoolean());
    }

    @Test
    void dispatchReportsStartupRejectionWithReason() {
        when(agentApplicationService.queryPublishedConfigForExecute(SUB_AGENT_ID))
                .thenReturn(publishedConfig(SUB_AGENT_ID, "子智能体"));
        when(conversationApplicationService.createConversationForMasterAgentLoop(any(), any(), any(), any(), any()))
                .thenReturn(conversation(12L, AK_USER_ID, SUB_AGENT_ID, null, "做点事"));
        // chat() 的早期拒绝（如无执行权限）以 Flux.just 同步回来，error 字段可捕获
        AgentOutputDto rejection = new AgentOutputDto();
        rejection.setError("没有该智能体的执行权限");
        when(chatApplicationService.chat(any(TryReqDto.class), isNull(), eq(false)))
                .thenReturn(Flux.just(rejection));
        // 早期拒绝发生在置 EXECUTING 之前：回读仍是 CREATE
        when(conversationApplicationService.getConversationByCid(12L)).thenReturn(
                conversation(12L, AK_USER_ID, SUB_AGENT_ID, null, "做点事"));

        ReqResult<SandboxAgentTaskDispatchResultDto> result = controller.dispatch(
                SandboxAgentTaskDispatchReqDto.builder()
                        .targetAgentId(SUB_AGENT_ID)
                        .inputMessage("做点事")
                        .build());

        // 信封仍是成功（派发请求被受理），但任务没启动：状态 CREATE + error 原因
        assertTrue(isSuccess(result));
        assertEquals("CREATE", result.getData().getTaskStatus());
        assertEquals("没有该智能体的执行权限", result.getData().getError());
    }

    @Test
    void dispatchRejectsBlankInputMessage() {
        assertThrows(IllegalArgumentException.class, () -> controller.dispatch(
                SandboxAgentTaskDispatchReqDto.builder()
                        .targetAgentId(SUB_AGENT_ID)
                        .inputMessage("   ")
                        .build()));
        verify(chatApplicationService, never()).chat(any(TryReqDto.class), any(), anyBoolean());
    }

    // ---------------------------------------------------------------- AK 校验

    @Test
    void rejectsAkOfUnsupportedType() {
        UserAccessKeyDto ak = new UserAccessKeyDto();
        ak.setUserId(AK_USER_ID);
        ak.setTargetType(UserAccessKeyDto.AKTargetType.Tenant);
        ak.setTargetId(String.valueOf(AK_AGENT_ID));
        RequestContext<Object> context = new RequestContext<>();
        context.setUserId(AK_USER_ID);
        context.setUserAccessKey(ak);
        RequestContext.set(context);

        ReqResult<List<SandboxAgentTaskItemDto>> result = controller.listRunning(null);

        assertFalse(isSuccess(result));
        assertEquals("仅支持绑定智能体的访问密钥（AK）调用本接口", result.getMessage());
        verify(conversationApplicationService, never()).queryLatestConversationList(any(), any(), any(), any());
    }

    /**
     * ★核心回归：沙盒里默认拿到的那把 SANDBOX_ACCESS_KEY 是 Sandbox 型，
     * targetId = "{agentId}-{conversationId}" —— 必须认，否则技能在沙盒里根本调不通。
     * Sandbox 型不会经过 ApiKeyInterceptor 的 akTarget 赋值，自身名字得由 controller 补查。
     */
    @Test
    void acceptsSandboxAkAndParsesAgentIdBeforeDash() {
        UserAccessKeyDto ak = new UserAccessKeyDto();
        ak.setUserId(AK_USER_ID);
        ak.setTargetType(UserAccessKeyDto.AKTargetType.Sandbox);
        ak.setTargetId(AK_AGENT_ID + "-228036057097043968");
        RequestContext<Object> context = new RequestContext<>();
        context.setUserId(AK_USER_ID);
        context.setUserAccessKey(ak);
        // 不给 akTarget：真实链路里 Sandbox 型就是没有
        RequestContext.set(context);

        AgentDetailDto own = new AgentDetailDto();
        own.setName("沙盒主智能体");
        when(agentApplicationService.queryAgentDetail(AK_AGENT_ID, false)).thenReturn(own);

        ConversationDto mine = conversation(1L, AK_USER_ID, AK_AGENT_ID,
                Conversation.ConversationTaskStatus.EXECUTING, "本体的任务");
        ConversationDto sub = conversation(2L, AK_USER_ID, SUB_AGENT_ID,
                Conversation.ConversationTaskStatus.EXECUTING, "子智能体的任务");
        when(conversationApplicationService.queryLatestConversationList(
                eq(AK_USER_ID), eq(Arrays.asList(AK_AGENT_ID, SUB_AGENT_ID)), isNull(), eq(20)))
                .thenReturn(Arrays.asList(mine, sub));

        ReqResult<List<SandboxAgentTaskItemDto>> result = controller.listRunning(null);

        assertTrue(isSuccess(result));
        assertEquals(2, result.getData().size());
        // agentId 取到了破折号之前的部分（200），不是 "200-228036057097043968"
        verify(conversationApplicationService).queryLatestConversationList(
                eq(AK_USER_ID), eq(Arrays.asList(AK_AGENT_ID, SUB_AGENT_ID)), isNull(), eq(20));
        // 名字补查补上了，而不是留空
        assertEquals("沙盒主智能体", result.getData().get(0).getAgentName());
    }

    @Test
    void sandboxAkWithoutDashStillWorks() {
        UserAccessKeyDto ak = new UserAccessKeyDto();
        ak.setUserId(AK_USER_ID);
        ak.setTargetType(UserAccessKeyDto.AKTargetType.Sandbox);
        ak.setTargetId(String.valueOf(AK_AGENT_ID));
        RequestContext<Object> context = new RequestContext<>();
        context.setUserId(AK_USER_ID);
        context.setUserAccessKey(ak);
        RequestContext.set(context);
        when(conversationApplicationService.queryLatestConversationList(anyLong(), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        ReqResult<List<SandboxAgentTaskItemDto>> result = controller.listRunning(null);

        assertTrue(isSuccess(result));
    }

    @Test
    void rejectsSandboxAkWithNonNumericPrefix() {
        UserAccessKeyDto ak = new UserAccessKeyDto();
        ak.setUserId(AK_USER_ID);
        ak.setTargetType(UserAccessKeyDto.AKTargetType.Sandbox);
        ak.setTargetId("whatever-conversation");
        RequestContext<Object> context = new RequestContext<>();
        context.setUserId(AK_USER_ID);
        context.setUserAccessKey(ak);
        RequestContext.set(context);

        ReqResult<List<SandboxAgentTaskItemDto>> result = controller.listRunning(null);

        assertFalse(isSuccess(result));
        assertEquals("仅支持绑定智能体的访问密钥（AK）调用本接口", result.getMessage());
    }

    @Test
    void rejectsAkWithNonNumericTargetId() {
        UserAccessKeyDto ak = new UserAccessKeyDto();
        ak.setUserId(AK_USER_ID);
        ak.setTargetType(UserAccessKeyDto.AKTargetType.Agent);
        ak.setTargetId("not-a-number");
        RequestContext<Object> context = new RequestContext<>();
        context.setUserId(AK_USER_ID);
        context.setUserAccessKey(ak);
        RequestContext.set(context);

        ReqResult<List<SandboxAgentTaskItemDto>> result = controller.listRunning(null);

        assertFalse(isSuccess(result));
        assertEquals("仅支持绑定智能体的访问密钥（AK）调用本接口", result.getMessage());
    }

    @Test
    void rejectsWhenNoAkInContext() {
        RequestContext<Object> context = new RequestContext<>();
        context.setUserId(AK_USER_ID);
        RequestContext.set(context);

        ReqResult<List<SandboxAgentTaskItemDto>> result = controller.listRunning(null);

        assertFalse(isSuccess(result));
        assertNotEquals("0000", result.getCode());
    }

    @Test
    void taskStatusNullIsReportedAsCreate() {
        ConversationDto conv = conversation(1L, AK_USER_ID, AK_AGENT_ID, null, "新任务");
        when(conversationApplicationService.queryLatestConversationList(anyLong(), any(), any(), any()))
                .thenReturn(Collections.singletonList(conv));

        ReqResult<List<SandboxAgentTaskItemDto>> result =
                controller.search(SandboxAgentTaskSearchReqDto.builder().build());

        assertTrue(isSuccess(result));
        assertEquals("CREATE", result.getData().get(0).getTaskStatus());
        assertNotNull(result.getData().get(0).getAgentName());
        assertNull(result.getData().get(0).getCreated());
    }
}
