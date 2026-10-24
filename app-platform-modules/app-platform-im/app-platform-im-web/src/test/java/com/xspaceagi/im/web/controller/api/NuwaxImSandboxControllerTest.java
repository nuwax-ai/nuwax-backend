package com.xspaceagi.im.web.controller.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.xspaceagi.im.sdk.rpc.IImAgentRpcService;
import com.xspaceagi.im.sdk.rpc.IImContactRpcService;
import com.xspaceagi.im.sdk.rpc.IImConversationRpcService;
import com.xspaceagi.im.sdk.rpc.IImEventRpcService;
import com.xspaceagi.im.sdk.rpc.IImMessageRpcService;
import com.xspaceagi.im.sdk.rpc.ImRpcException;
import com.xspaceagi.im.sdk.rpc.dto.ImAgentPendingClearDto;
import com.xspaceagi.im.sdk.rpc.dto.ImAgentPendingClearRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImAgentPendingListDto;
import com.xspaceagi.im.sdk.rpc.dto.ImAgentPendingListRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImCardBody;
import com.xspaceagi.im.sdk.rpc.dto.ImCardMessageRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImContactItemDto;
import com.xspaceagi.im.sdk.rpc.dto.ImContactListDto;
import com.xspaceagi.im.sdk.rpc.dto.ImContactListRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImConversationDetailDto;
import com.xspaceagi.im.sdk.rpc.dto.ImConversationDto;
import com.xspaceagi.im.sdk.rpc.dto.ImConversationPageDto;
import com.xspaceagi.im.sdk.rpc.dto.ImGroupMessageRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImCustomEventPushResultDto;
import com.xspaceagi.im.sdk.rpc.dto.ImMemberAddRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImMemberListRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImMessageDetailDto;
import com.xspaceagi.im.sdk.rpc.dto.ImMessageDto;
import com.xspaceagi.im.sdk.rpc.dto.ImMessageGetRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImMessageListRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImMessagePageDto;
import com.xspaceagi.im.sdk.rpc.dto.ImMessageRevokeRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImP2pMessageRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImReadCountsRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImSystemMessageRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImSystemNotifyFailedDto;
import com.xspaceagi.im.sdk.rpc.dto.ImSystemNotifyItemDto;
import com.xspaceagi.im.sdk.rpc.dto.ImSystemNotifyRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImSystemNotifyResultDto;
import com.xspaceagi.im.sdk.rpc.dto.MemberAddResultDto;
import com.xspaceagi.im.sdk.rpc.dto.MemberPageDto;
import com.xspaceagi.im.sdk.rpc.dto.ReadCountItemDto;
import com.xspaceagi.im.web.dto.sandbox.NuwaxImSandboxRequests;
import com.xspaceagi.im.web.dto.sandbox.NuwaxImWriteResult;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.dto.VirtualIdentityDto;
import com.xspaceagi.system.application.service.VirtualIdentityApplicationService;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.sdk.service.dto.UserAccessKeyDto;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.exception.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link NuwaxImSandboxController} 的测试 —— 全部走 Mockito 桩掉的四个 RPC 接口。
 *
 * <h2>本类真正想锁住的是四件事</h2>
 *
 * <ol>
 *   <li><b>身份只来自登录态</b>：{@code userId} 取自 {@code RequestContext}，
 *       且请求 DTO 里<b>根本没有</b>这个字段（编译期事实，见
 *       {@link #noRequestRecordCanCarryAnIdentity()}）；</li>
 *   <li><b>幂等键回显</b>：调用方给了就原样回，没给就生成一个并回给调用方 ——
 *       否则「响应丢了之后用同一个键重试」这条唯一的补偿路径走不通；</li>
 *   <li><b>错误码翻译</b>：{@link ImRpcException} 的两种分类 →
 *       3612（本地配置，重试无用）/ 3613（上游暂时性，可重试）/ 3614（上游业务拒绝）。
 *       这三条分错了，用户会在「重试一百次也不会好」的错误上一直重试。</li>
 *   <li><b>管理员闸门</b>：{@code /message/send-system}、{@code /message/system-notify}
 *       （★DEV-291）与 {@code /event/push} 是 21 个端点里仅有的三个管理员端点
 *       （非管理员 {@code 4030}），且闸门必须在<b>方法第一行</b>。
 *       这两条线见 {@link #dangerousEndpointsSitBehindTheAdminGate()} 与
 *       {@link #adminGateRunsBeforeParameterValidation()}。</li>
 * </ol>
 *
 * <p>★另有一条横跨多端点的线：<b>actor 是「AK 归属人」还是「AK 里的智能体虚拟身份」</b>。
 * 发送端点里私聊按收件人、群发按群键，其余（含卡片）统一走
 * {@code agentActorUserId} —— 因为 IM 那侧每个 convId 范围的操作都以
 * {@code requireActiveMember} 开头，而群代理不是群成员。见
 * {@link #revokeActsAsAgentVirtualUser()} 与 {@link #groupMessageAndMemberQueriesActAsAgentVirtualUser()}。
 */
class NuwaxImSandboxControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final long USER_ID = 1001L;
    private static final long TENANT_ID = 66L;

    /** AK 的 ext 里携带的智能体虚拟身份（IM 造会话时用的那个 id，形态上是个雪花号）。 */
    private static final long VIRTUAL_USER_ID = 6600000000001L;

    /**
     * 兜底那一路查到的智能体虚拟身份 id。
     *
     * <p>★刻意取一个<b>普通十位数</b>（与真人同域）而不是 {@code AGENT_OFFSET + agentId} 那种 13 位令牌：
     * 平台铸造的虚拟身份就是这个形态，而「只看 id 段位猜类型」在这条路上必然猜错 ——
     * 类型是数据（IM 的 {@code im_subject}），只能问 IM。
     */
    private static final long AGENT_VIRTUAL_USER_ID = 1789550866L;

    private IImMessageRpcService messageRpc;
    private IImConversationRpcService conversationRpc;
    private IImContactRpcService contactRpc;
    private IImEventRpcService eventRpc;
    private IImAgentRpcService agentRpc;
    /** 兜底解析智能体虚拟身份时用的只读查询（AK 没带 AGENT_VIRTUAL_USER_ID 那一支才会被调到）。 */
    private VirtualIdentityApplicationService virtualIdentity;
    private NuwaxImSandboxController controller;

    @BeforeEach
    void setUp() {
        messageRpc = mock(IImMessageRpcService.class);
        conversationRpc = mock(IImConversationRpcService.class);
        contactRpc = mock(IImContactRpcService.class);
        eventRpc = mock(IImEventRpcService.class);
        agentRpc = mock(IImAgentRpcService.class);
        virtualIdentity = mock(VirtualIdentityApplicationService.class);
        controller = new NuwaxImSandboxController();
        ReflectionTestUtils.setField(controller, "imMessageRpcService", messageRpc);
        ReflectionTestUtils.setField(controller, "imConversationRpcService", conversationRpc);
        ReflectionTestUtils.setField(controller, "imContactRpcService", contactRpc);
        ReflectionTestUtils.setField(controller, "imEventRpcService", eventRpc);
        ReflectionTestUtils.setField(controller, "imAgentRpcService", agentRpc);
        ReflectionTestUtils.setField(controller, "virtualIdentityApplicationService", virtualIdentity);
    }

    @AfterEach
    void tearDown() {
        RequestContext.remove();
    }

    // ================================================================== 身份

    @Test
    @DisplayName("★userId / tenantId 一律取自 RequestContext，不来自请求体")
    void identityComesFromRequestContext() {
        loginAs(USER_ID, TENANT_ID);
        when(messageRpc.sendP2pMessage(any())).thenReturn(messageDto());

        controller.sendP2p(new NuwaxImSandboxRequests.SendP2p(
                2002L, "text", JSON.createObjectNode().put("text", "hi"), null, null, "trace-1"));

        ArgumentCaptor<ImP2pMessageRequest> captor = ArgumentCaptor.forClass(ImP2pMessageRequest.class);
        org.mockito.Mockito.verify(messageRpc).sendP2pMessage(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(USER_ID);
        assertThat(captor.getValue().tenantId()).isEqualTo(TENANT_ID);
        assertThat(captor.getValue().toUserId()).isEqualTo(2002L);
        assertThat(captor.getValue().traceId()).isEqualTo("trace-1");
    }

    // ------------------------------------------- 单聊 actor：收件人=自己 → 智能体虚拟身份

    @Test
    @DisplayName("★★单聊收件人=自己（IM 私聊里智能体回话）→ actor 换成 AK 里的 AGENT_VIRTUAL_USER_ID，收件人不变")
    void selfAddressedP2pSpeaksAsAgentVirtualUser() {
        loginAsAk(USER_ID, TENANT_ID, Map.of("AGENT_VIRTUAL_USER_ID", 6600000000001L, "IS_GROUP_MESSAGE", false));
        when(messageRpc.sendP2pMessage(any())).thenReturn(messageDto());

        controller.sendP2p(new NuwaxImSandboxRequests.SendP2p(
                USER_ID, "text", textNode(), null, null, null));

        ImP2pMessageRequest sent = captureP2p();
        assertThat(sent.userId()).isEqualTo(6600000000001L);
        // ★收件人必须【仍是本人】：换成虚拟身份后这是"虚拟用户 → 真人"的正常单聊；
        //   若这里被一起改掉，消息就会发进"虚拟用户 ↔ 虚拟用户"这个不存在的会话。
        assertThat(sent.toUserId()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("★收件人不是自己 → 一律用登录态本人，ext 里的虚拟身份【不参与】（不能被顺手也用上）")
    void otherAddressedP2pKeepsRealUser() {
        loginAsAk(USER_ID, TENANT_ID, Map.of("AGENT_VIRTUAL_USER_ID", 6600000000001L));
        when(messageRpc.sendP2pMessage(any())).thenReturn(messageDto());

        controller.sendP2p(new NuwaxImSandboxRequests.SendP2p(
                2002L, "text", textNode(), null, null, null));

        assertThat(captureP2p().userId()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("★收件人=自己但 AK 没带虚拟身份（非 IM 触发 / IM 侧还是 DEV-280 之前）→ 原样交 IM 判拒，不自己造身份")
    void selfAddressedP2pWithoutVirtualIdentityStaysRealUser() {
        loginAsAk(USER_ID, TENANT_ID, Map.of("IS_GROUP_MESSAGE", false));
        when(messageRpc.sendP2pMessage(any())).thenReturn(messageDto());

        controller.sendP2p(new NuwaxImSandboxRequests.SendP2p(
                USER_ID, "text", textNode(), null, null, null));

        assertThat(captureP2p().userId()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("★ext 里的虚拟身份是 Integer 形态（小值经 Jackson 反序列化）也必须取到 —— 只判 Long 会静默漏")
    void virtualIdentityAcceptsIntegerJsonNumber() {
        loginAsAk(USER_ID, TENANT_ID, Map.of("AGENT_VIRTUAL_USER_ID", 7001));
        when(messageRpc.sendP2pMessage(any())).thenReturn(messageDto());

        controller.sendP2p(new NuwaxImSandboxRequests.SendP2p(
                USER_ID, "text", textNode(), null, null, null));

        assertThat(captureP2p().userId()).isEqualTo(7001L);
    }

    @Test
    @DisplayName("★★ext 里的虚拟身份不可信时【拒绝使用】而不是截断：Double / 非数字串 / 0 都退回本人")
    void untrustedVirtualIdentityNeverProducesAWrongActor() {
        List<Object> badValues = List.of(7001.9D, "abc", 0, -1, "");
        for (Object bad : badValues) {
            loginAsAk(USER_ID, TENANT_ID, Map.of("AGENT_VIRTUAL_USER_ID", bad));
            when(messageRpc.sendP2pMessage(any())).thenReturn(messageDto());

            controller.sendP2p(new NuwaxImSandboxRequests.SendP2p(
                    USER_ID, "text", textNode(), null, null, null));

            assertThat(captureP2p().userId())
                    .as("ext 值 %s 不可信，必须以本人身份发出（再由 IM 判拒），绝不能截断成 7001", bad)
                    .isEqualTo(USER_ID);
        }
    }

    @Test
    @DisplayName("★ext 字段不是 map（或整个 AK 缺失，如登录态直连）→ 走本人身份，不抛异常")
    void extOfUnexpectedShapeIsIgnored() {
        // ext 是 String：声明类型是 Object，别处写入的形态不受本模块控制
        loginAsAk(USER_ID, TENANT_ID, "not-a-map");
        when(messageRpc.sendP2pMessage(any())).thenReturn(messageDto());
        controller.sendP2p(new NuwaxImSandboxRequests.SendP2p(USER_ID, "text", textNode(), null, null, null));
        assertThat(captureP2p().userId()).isEqualTo(USER_ID);

        // 完全没有 AK（登录态直连 /api/nuwax-im/**）：RequestContext 里是 null
        loginAs(USER_ID, TENANT_ID);
        controller.sendP2p(new NuwaxImSandboxRequests.SendP2p(USER_ID, "text", textNode(), null, null, null));
        assertThat(captureP2p().userId()).isEqualTo(USER_ID);
    }

    // --------------------- 单聊 actor 兜底：AK 没带虚拟身份时，按 agentId 自己解析（定时任务 / 主动推送）

    @Test
    @DisplayName("★★收件人=自己且 AK 没带虚拟身份（非 IM 触发）→ 按 targetId 反解 agentId、查平台虚拟身份、"
            + "IM 通讯录认它是智能体 ⇒ actor 换成该虚拟身份，收件人仍是本人")
    void selfAddressedP2pFallsBackToVirtualIdentityResolvedFromAkTarget() {
        loginAsAkWithTarget(USER_ID, TENANT_ID, "1596-228036057097043968", Map.of());
        when(virtualIdentity.findVirtualIdentity(TENANT_ID, User.ACCOUNT_TYPE_AGENT, "1596"))
                .thenReturn(agentVirtualIdentity(AGENT_VIRTUAL_USER_ID));
        when(contactRpc.listContacts(any())).thenReturn(new ImContactListDto(
                List.of(contact(2002L, 1, "张三"), agentContact(AGENT_VIRTUAL_USER_ID, 1)), false, false));
        when(messageRpc.sendP2pMessage(any())).thenReturn(messageDto());

        controller.sendP2p(new NuwaxImSandboxRequests.SendP2p(
                USER_ID, "text", textNode(), null, null, "trace-fallback"));

        ImP2pMessageRequest sent = captureP2p();
        assertThat(sent.userId()).isEqualTo(AGENT_VIRTUAL_USER_ID);
        // ★收件人必须仍是本人：换的只是"谁在说"，换了收件人消息就会落进一个不存在的会话
        assertThat(sent.toUserId()).isEqualTo(USER_ID);
        // ★通讯录必须以【收件人本人】为 actor 去查（查智能体自己的通讯录恒为空，没有鉴别力）
        ArgumentCaptor<ImContactListRequest> captor = ArgumentCaptor.forClass(ImContactListRequest.class);
        org.mockito.Mockito.verify(contactRpc).listContacts(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(USER_ID);
        assertThat(captor.getValue().tenantId()).isEqualTo(TENANT_ID);
        assertThat(captor.getValue().traceId()).isEqualTo("trace-fallback");
    }

    @Test
    @DisplayName("★targetId 是【裸 agentId】形态（CustomPageChatFluxServiceImpl 那条铸造腿）也要认 —— "
            + "只判 contains(\"-\") 会在这条腿上静默漏解")
    void selfAddressedP2pFallbackAcceptsBareAgentIdTarget() {
        loginAsAkWithTarget(USER_ID, TENANT_ID, "1596", Map.of());
        when(virtualIdentity.findVirtualIdentity(TENANT_ID, User.ACCOUNT_TYPE_AGENT, "1596"))
                .thenReturn(agentVirtualIdentity(AGENT_VIRTUAL_USER_ID));
        when(contactRpc.listContacts(any())).thenReturn(new ImContactListDto(
                List.of(agentContact(AGENT_VIRTUAL_USER_ID, 1)), false, false));
        when(messageRpc.sendP2pMessage(any())).thenReturn(messageDto());

        controller.sendP2p(new NuwaxImSandboxRequests.SendP2p(USER_ID, "text", textNode(), null, null, null));

        assertThat(captureP2p().userId()).isEqualTo(AGENT_VIRTUAL_USER_ID);
    }

    @Test
    @DisplayName("★★通讯录这一关不可省：智能体不在收件人通讯录 / 只是待同意 / IM 判它是真人 —— 三种都不切身份")
    void selfAddressedP2pFallbackRequiresAnAcceptedAgentContact() {
        // ① 压根不在通讯录里
        assertFallbackStaysRealUser(new ImContactListDto(List.of(contact(2002L, 1, "张三")), false, false),
                "通讯录里没有这个智能体");
        // ② 在，但只是「我申请对方 / 对方申请我」—— 关系没建立
        assertFallbackStaysRealUser(new ImContactListDto(List.of(agentContact(AGENT_VIRTUAL_USER_ID, 3)), false, false),
                "只是待同意的申请");
        assertFallbackStaysRealUser(new ImContactListDto(List.of(agentContact(AGENT_VIRTUAL_USER_ID, 2)), false, false),
                "只是待同意的申请");
        // ③ 在、也已同意，但 IM 判它是【真人】（subjectType=1）——★这是最关键的一种：
        //    平台 user 表有这一行 ≠ IM 的 im_subject 有这一行；少了这一关，消息会以 sender_type=1
        //    静默发出去（不报错、前端显示成真人），身份错了却"成功"。
        assertFallbackStaysRealUser(new ImContactListDto(
                        List.of(new ImContactItemDto(AGENT_VIRTUAL_USER_ID, 1, "u1", "某人", null, null, "某人",
                                1, 1, null, null)), false, false),
                "IM 侧没把它登记成智能体（im_subject 缺行会被当真人）");
        // ④ 空列表（IM 对不存在的 actor 也返回成功 + 空列表 ⇒ 空不能当"存在"用）
        assertFallbackStaysRealUser(new ImContactListDto(List.of(), false, false), "通讯录为空");
    }

    @Test
    @DisplayName("★平台没有这个智能体的虚拟身份行 → 不切身份，且【不再多打一次 IM 通讯录】")
    void selfAddressedP2pFallbackStopsAtPlatformLookupWhenNoVirtualIdentity() {
        loginAsAkWithTarget(USER_ID, TENANT_ID, "1596-228036057097043968", Map.of());
        when(virtualIdentity.findVirtualIdentity(any(), any(), any())).thenReturn(null);
        when(messageRpc.sendP2pMessage(any())).thenReturn(messageDto());

        controller.sendP2p(new NuwaxImSandboxRequests.SendP2p(USER_ID, "text", textNode(), null, null, null));

        assertThat(captureP2p().userId()).isEqualTo(USER_ID);
        verifyNoInteractions(contactRpc);
    }

    @Test
    @DisplayName("★查询按【租户 + account_type=agent + principal_ref_id=agentId】收口 —— "
            + "A 租户的 AK 拿不到 B 租户的智能体身份，也不会去查 account_type=user 的行；"
            + "反解只截到【第一个】'-'（后面的横杠属于 conversationId）")
    void selfAddressedP2pFallbackQueriesAgentPrincipalWithinTenant() {
        loginAsAkWithTarget(USER_ID, TENANT_ID, "1596-x-y", Map.of());
        when(virtualIdentity.findVirtualIdentity(any(), any(), any())).thenReturn(null);
        when(messageRpc.sendP2pMessage(any())).thenReturn(messageDto());

        controller.sendP2p(new NuwaxImSandboxRequests.SendP2p(USER_ID, "text", textNode(), null, null, null));

        org.mockito.Mockito.verify(virtualIdentity)
                .findVirtualIdentity(TENANT_ID, User.ACCOUNT_TYPE_AGENT, "1596");
    }

    @Test
    @DisplayName("★★targetId 解不出 agentId（缺失 / 非数字 / 以 '-' 开头 / 0）→ 一律不猜、不回退成"
            + "「不带 agentId 也去查」，原样交 IM 判拒")
    void selfAddressedP2pFallbackNeverGuessesAnAgentId() {
        // ★"1596-x-y" 不在这一列：按样板只截到【第一个】'-'，它是合法形态（agentId=1596），
        //   由 selfAddressedP2pFallbackQueriesAgentPrincipalWithinTenant 钉住
        for (String bad : new String[]{null, "", "   ", "abc-1", "-9", "0-1"}) {
            loginAsAkWithTarget(USER_ID, TENANT_ID, bad, Map.of());
            when(messageRpc.sendP2pMessage(any())).thenReturn(messageDto());

            controller.sendP2p(new NuwaxImSandboxRequests.SendP2p(USER_ID, "text", textNode(), null, null, null));

            assertThat(captureP2p().userId())
                    .as("targetId=%s 解不出 agentId，必须以本人身份发出（再由 IM 判拒）", bad)
                    .isEqualTo(USER_ID);
        }
        // ★一次都不许去查：解不出 agentId 时任何查询都等于"随便找个智能体的身份"
        verifyNoInteractions(virtualIdentity, contactRpc);
    }

    @Test
    @DisplayName("★通讯录读取失败（IM 不可达 / 被拒）→ 按未命中处理：不切身份，也【不】把异常抛给调用方")
    void selfAddressedP2pFallbackSurvivesContactProbeFailure() {
        loginAsAkWithTarget(USER_ID, TENANT_ID, "1596-228036057097043968", Map.of());
        when(virtualIdentity.findVirtualIdentity(TENANT_ID, User.ACCOUNT_TYPE_AGENT, "1596"))
                .thenReturn(agentVirtualIdentity(AGENT_VIRTUAL_USER_ID));
        when(contactRpc.listContacts(any()))
                .thenThrow(ImRpcException.network("ConnectException", new java.net.ConnectException("refused")));
        when(messageRpc.sendP2pMessage(any())).thenReturn(messageDto());

        controller.sendP2p(new NuwaxImSandboxRequests.SendP2p(USER_ID, "text", textNode(), null, null, null));

        assertThat(captureP2p().userId()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("★AK 已带 AGENT_VIRTUAL_USER_ID（IM 私聊回话链路）→ 兜底【一次都不跑】，直接用 ext 里的那个")
    void akExtVirtualIdentityShortCircuitsTheFallback() {
        loginAsAkWithTarget(USER_ID, TENANT_ID, "1596-228036057097043968",
                Map.of("AGENT_VIRTUAL_USER_ID", VIRTUAL_USER_ID));
        when(messageRpc.sendP2pMessage(any())).thenReturn(messageDto());

        controller.sendP2p(new NuwaxImSandboxRequests.SendP2p(USER_ID, "text", textNode(), null, null, null));

        assertThat(captureP2p().userId()).isEqualTo(VIRTUAL_USER_ID);
        verifyNoInteractions(virtualIdentity, contactRpc);
    }

    /** 兜底那一路的公共前置：AK 绑着 agentId=1596，且平台确实有它的虚拟身份行。 */
    private void assertFallbackStaysRealUser(ImContactListDto contacts, String why) {
        loginAsAkWithTarget(USER_ID, TENANT_ID, "1596-228036057097043968", Map.of());
        when(virtualIdentity.findVirtualIdentity(TENANT_ID, User.ACCOUNT_TYPE_AGENT, "1596"))
                .thenReturn(agentVirtualIdentity(AGENT_VIRTUAL_USER_ID));
        when(contactRpc.listContacts(any())).thenReturn(contacts);
        when(messageRpc.sendP2pMessage(any())).thenReturn(messageDto());

        controller.sendP2p(new NuwaxImSandboxRequests.SendP2p(USER_ID, "text", textNode(), null, null, null));

        assertThat(captureP2p().userId())
                .as("%s ⇒ 不切身份，原样交 IM 判拒（IM_20106）", why)
                .isEqualTo(USER_ID);
    }

    // ------------------------------------------- 群发 actor：群发起 → 智能体虚拟身份；用户发起 → 本人

    @Test
    @DisplayName("★★群发起（ext 带 GROUP_VIRTUAL_USER_ID，即 IM 群聊分支触发）→ actor = AGENT_VIRTUAL_USER_ID，发往的群不变")
    void groupTriggeredSendGroupSpeaksAsAgentVirtualUser() {
        loginAsAk(USER_ID, TENANT_ID, Map.of(
                "GROUP_VIRTUAL_USER_ID", 660001L,
                "AGENT_VIRTUAL_USER_ID", 6600000000001L,
                "GROUP_CONV_ID", 7L));
        when(messageRpc.sendGroupMessage(any())).thenReturn(messageDto());

        controller.sendGroup(groupRequest(7L));

        ImGroupMessageRequest sent = captureGroup();
        assertThat(sent.userId()).isEqualTo(6600000000001L);
        // ★群必须还是请求里的那个群：换身份只换"谁在说"，不换"说给哪儿"
        assertThat(sent.convId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("★用户单聊过程中要发群消息（ext 无 GROUP_VIRTUAL_USER_ID）→ actor 一律是登录态本人，"
            + "ext 里就算带着 AGENT_VIRTUAL_USER_ID 也不许用")
    void userInitiatedSendGroupKeepsRealUser() {
        // ★这个 ext 形态是 DEV-282 之后的真实形态：私聊分支也会送 AGENT_VIRTUAL_USER_ID，
        //   所以"ext 里有虚拟身份"绝不能当成"这是群发起的"证据 —— 判据只能是群标记键在不在。
        loginAsAk(USER_ID, TENANT_ID, Map.of("AGENT_VIRTUAL_USER_ID", 6600000000001L));
        when(messageRpc.sendGroupMessage(any())).thenReturn(messageDto());

        controller.sendGroup(groupRequest(7L));

        assertThat(captureGroup().userId()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("★★群发起但 AK 没带 AGENT_VIRTUAL_USER_ID → actor = null（交给 IM 以 IM_90001 大声拒），"
            + "绝不回退成本人 —— 回退会把消息静默挂到别人名下")
    void groupTriggeredWithoutVirtualIdentityRefusesInsteadOfMisattributing() {
        // ★必须带 GROUP_CONV_ID：真实的群场景 ext 恒有它（IM 在群聊分支里无条件 put），
        //   少了它现在会被闸门①按 fail-closed 先拒掉，这条用例就测不到 actor=null 这件事了。
        loginAsAk(USER_ID, TENANT_ID, Map.of(
                "GROUP_VIRTUAL_USER_ID", 660001L,
                "GROUP_CONV_ID", 7L));
        when(messageRpc.sendGroupMessage(any())).thenReturn(messageDto());

        controller.sendGroup(groupRequest(7L));

        assertThat(captureGroup().userId())
                .as("回退成 %s 会让群里的其他人看到一条『用户本人发的』消息，比失败更糟", USER_ID)
                .isNull();
    }

    @Test
    @DisplayName("★GROUP_VIRTUAL_USER_ID 的值是 null 仍然算群发起（IM 契约：键存在即群，不是判值）")
    void groupKeyPresentWithNullValueStillCountsAsGroup() {
        // ★不能用 Map.of：它不允许 null 值，而"键在、值是 null"正是这条契约要覆盖的形态
        Map<String, Object> ext = new LinkedHashMap<>();
        ext.put("GROUP_VIRTUAL_USER_ID", null);
        ext.put("GROUP_CONV_ID", 7L);
        ext.put("AGENT_VIRTUAL_USER_ID", 6600000000001L);
        loginAsAk(USER_ID, TENANT_ID, ext);
        when(messageRpc.sendGroupMessage(any())).thenReturn(messageDto());

        controller.sendGroup(groupRequest(7L));

        assertThat(captureGroup().userId())
                .as("把判据写成 extMap.get(KEY) != null 就会在这里退回成本人")
                .isEqualTo(6600000000001L);
    }

    @Test
    @DisplayName("★★群分支下虚拟身份不可信（Double / 非数字串 / 0 / 负数 / 空串）→ 一律 null，既不截断也不回退")
    void untrustedVirtualIdentityInGroupBranchNeverProducesAWrongActor() {
        List<Object> badValues = List.of(7001.9D, "abc", 0, -1, "");
        for (Object bad : badValues) {
            loginAsAk(USER_ID, TENANT_ID, Map.of(
                    "GROUP_VIRTUAL_USER_ID", 660001L,
                    "GROUP_CONV_ID", 7L,
                    "AGENT_VIRTUAL_USER_ID", bad));
            when(messageRpc.sendGroupMessage(any())).thenReturn(messageDto());

            controller.sendGroup(groupRequest(7L));

            assertThat(captureGroup().userId())
                    .as("群分支下 ext 值 %s 不可信：既不能截断成 7001，也不能退回 %s", bad, USER_ID)
                    .isNull();
        }
    }

    @Test
    @DisplayName("★群发时 ext 不是 map / 完全没有 AK（登录态直连）→ 视作无群标记，用本人身份且不抛异常")
    void sendGroupWithoutUsableExtFallsBackToRealUser() {
        loginAsAk(USER_ID, TENANT_ID, "not-a-map");
        when(messageRpc.sendGroupMessage(any())).thenReturn(messageDto());
        controller.sendGroup(groupRequest(7L));
        assertThat(captureGroup().userId()).isEqualTo(USER_ID);

        loginAs(USER_ID, TENANT_ID);
        controller.sendGroup(groupRequest(7L));
        assertThat(captureGroup().userId()).isEqualTo(USER_ID);
    }

    // ------------------------------------- 卡片 actor：故意【不】跟群发共用规则（别再"统一"它）

    /**
     * ★★卡片（{@code send-card}）的 actor 是 {@code agentActorUserId}，<b>不是</b>
     * {@code groupActorUserId} —— 这一条曾经被我改错过，用例就是防它被再改一次。
     *
     * <p>看着很诱人：「卡片和群发都是往某个会话里发」，那就该同族。但两者的判据来源不同 ——
     * IM 侧 {@code ImMessageApplicationServiceImpl#doSend} 对每条消息先做
     * {@code requireActiveMember(convId, selfId)}：<b>发送者必须是目标会话的活跃成员</b>。
     *
     * <p>而调用方能拿到的 {@code convId}，全部来自本控制器里那些<b>以 {@code agentActorUserId}
     * 行使</b>的端点（{@code conversation/list}、{@code conversation/p2p}、{@code message/list}……）
     * ⇒ 这些会话都是<b>智能体自己参与的</b>。它拿不到「H↔张三」那种第三方会话的 id：
     * {@code conversation/p2p} 只会返回「智能体↔张三」，{@code list} 只会返回智能体自己的列表。
     *
     * <p>于是把卡片改判成「无群键 ⇒ 本人」，两个方向同时变坏：
     * <pre>
     * 往「智能体↔主人」的私聊发卡片 → 变成【主人自己发的消息】（会话里凭空多出一条他没发的消息）
     * 往「智能体所在的群」发卡片     → 主人多半不是那个群的成员 ⇒ NOT_CONV_MEMBER，能力直接没了
     * </pre>
     * 而改之前担心的那个场景（私聊链路里"给张三发张卡片"落成智能体发的）<b>本来就到不了</b>：
     * 智能体不是 H↔张三 的成员，IM 会以 {@code NOT_CONV_MEMBER} 大声拒掉。
     *
     * <p>★群发不一样：群链路下 {@code currentUserId} 是群代理，按 IM 定义「不进群、不是成员」，
     * 拿它发群消息必然失败 —— 所以群发<b>必须</b>靠「ext 有没有群键」判。
     * 两条规则判据不同，不可互换。
     */
    @Test
    @DisplayName("★★卡片·私聊链路 → actor 仍是智能体虚拟身份（目标会话只能是智能体自己参与的会话）")
    void cardKeepsAgentVirtualIdentityInP2pTriggeredRun() {
        loginAsAk(USER_ID, TENANT_ID, Map.of("AGENT_VIRTUAL_USER_ID", VIRTUAL_USER_ID));
        when(messageRpc.sendCardMessage(any())).thenReturn(messageDto());

        controller.sendCard(cardRequest(7L));

        // 改成 groupActorUserId 会立刻变红（无群键 ⇒ 返回 USER_ID）。
        assertThat(captureCard().userId()).isEqualTo(VIRTUAL_USER_ID);
        assertThat(captureCard().convId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("★★卡片·群链路 → actor 同样是智能体虚拟身份（群代理不是成员，用它会 NOT_CONV_MEMBER）")
    void cardUsesAgentVirtualIdentityInGroupTriggeredRun() {
        // ★2026-10-05 起因闸门①也管卡片，群场景的 ext 必须带上 GROUP_CONV_ID 且
        //   与请求里的 convId 一致 —— 真实 IM 在群分支无条件送这个键，缺它不是合法形态。
        loginAsAk(USER_ID, TENANT_ID, Map.of(
                "GROUP_VIRTUAL_USER_ID", 660001L,
                "GROUP_CONV_ID", 7L,
                "AGENT_VIRTUAL_USER_ID", VIRTUAL_USER_ID));
        when(messageRpc.sendCardMessage(any())).thenReturn(messageDto());

        controller.sendCard(cardRequest(7L));

        assertThat(captureCard().userId()).isEqualTo(VIRTUAL_USER_ID);
    }

    @Test
    @DisplayName("★卡片·AK 没带 AGENT_VIRTUAL_USER_ID → 回退登录态本人（与其余 agentActorUserId 端点一致，不是 null）")
    void cardFallsBackToTheRealUserWithoutVirtualIdentity() {
        // 与群发那条【刻意不同】：群发缺虚拟身份时返回 null（IM_90001 大声拒），卡片回退本人。
        // 差别在于"回退本人"对卡片是可用且合理的（本人就在自己的会话里），对群发则是伪装。
        // ★GROUP_CONV_ID 也一起给：闸门①（2026-10-05 起管卡片）在群场景读不到它时 fail-closed，
        //   这条用例要测的是 actor 回退，不是被闸门拦下。
        loginAsAk(USER_ID, TENANT_ID, Map.of(
                "GROUP_VIRTUAL_USER_ID", 660001L,
                "GROUP_CONV_ID", 7L));
        when(messageRpc.sendCardMessage(any())).thenReturn(messageDto());

        controller.sendCard(cardRequest(7L));

        assertThat(captureCard().userId()).isEqualTo(USER_ID);
    }

    // --------------------- 非发送端点 actor：撤回 / 群消息查询 / 群员查询（agentActorUserId）

    /**
     * ★★这一节的由来：21 个端点里凡是不发送、又<b>按 convId 定位</b>的（撤回、消息详情、消息列表、
     * 已读人数、群员列表……）在 IM 侧都以 {@code requireActiveMember} 开头，
     * 而 4.10 群链路的 AK 归属人是<b>群代理</b> —— 群代理按 IM 的定义「不进群、不是成员」。
     *
     * <p>⇒ 拿 {@code currentUserId()}（群代理）去撤群消息、查群消息、查群员，通通以
     * {@code NOT_CONV_MEMBER} 失败。<b>这不是权限收紧，是端点根本不可用。</b>
     * 修法见 {@code agentActorUserId}：AK 的 ext 带 {@code AGENT_VIRTUAL_USER_ID} 就用它。
     */

    @Test
    @DisplayName("★★群触发链路下撤回以【智能体虚拟身份】行使 —— 那条消息当初正是以它发的，换回来才撤得掉")
    void revokeActsAsAgentVirtualUser() {
        loginAsAk(USER_ID, TENANT_ID, Map.of(
                "GROUP_VIRTUAL_USER_ID", 660001L,
                "AGENT_VIRTUAL_USER_ID", VIRTUAL_USER_ID,
                "GROUP_CONV_ID", 7L));

        controller.revoke(new NuwaxImSandboxRequests.Revoke(7L, 9001L, "误发", null, null));

        ImMessageRevokeRequest sent = captureRevoke();
        assertThat(sent.userId()).as("★以群代理的身份撤回必被 IM 判 NOT_CONV_MEMBER")
                .isEqualTo(VIRTUAL_USER_ID);
        assertThat(sent.tenantId()).isEqualTo(TENANT_ID);
        // ★换身份只换「谁在撤」，不换「撤哪个群的哪条」
        assertThat(sent.convId()).isEqualTo(7L);
        assertThat(sent.msgId()).isEqualTo(9001L);
        assertThat(sent.reason()).isEqualTo("误发");
    }

    @Test
    @DisplayName("★★群消息查询（get / list / read-counts）与群员查询（members/list）同样走智能体虚拟身份")
    void groupMessageAndMemberQueriesActAsAgentVirtualUser() {
        // ★imConvId 必须一起给：群场景下 IM 无条件 put 这个键，真实 AK 不可能缺它，
        //   而闸门④（群场景下 convId 必须 == 本次触发会话）正是拿它做判据 —— 缺了会 4030。
        loginAsAk(USER_ID, TENANT_ID, Map.of(
                "GROUP_VIRTUAL_USER_ID", 660001L,
                "AGENT_VIRTUAL_USER_ID", VIRTUAL_USER_ID,
                "imConvId", 7L));
        when(messageRpc.getMessage(any())).thenReturn(detailDto());
        when(messageRpc.listMessagesByTimeRange(any())).thenReturn(pageDto());
        when(messageRpc.getReadCounts(any())).thenReturn(List.of(readCountItem()));
        when(conversationRpc.listMembers(any())).thenReturn(new MemberPageDto(1, false, List.of()));

        controller.getMessage(new NuwaxImSandboxRequests.GetMessage(7L, 9001L, null));
        controller.listMessages(new NuwaxImSandboxRequests.ListMessages(7L, null, null, null, 20, null));
        controller.readCounts(new NuwaxImSandboxRequests.ReadCounts(7L, List.of(9001L), null));
        controller.listMembers(new NuwaxImSandboxRequests.ListMembers(7L, 10, null));

        // ★四处都得改：漏掉任何一处，那个端点就是「技能文档里写着有、真调必失败」。
        ArgumentCaptor<ImMessageGetRequest> get = ArgumentCaptor.forClass(ImMessageGetRequest.class);
        org.mockito.Mockito.verify(messageRpc).getMessage(get.capture());
        assertThat(get.getValue().userId()).as("messages/get 的 actor").isEqualTo(VIRTUAL_USER_ID);
        assertThat(get.getValue().convId()).as("查的还是那个群").isEqualTo(7L);

        ArgumentCaptor<ImMessageListRequest> list = ArgumentCaptor.forClass(ImMessageListRequest.class);
        org.mockito.Mockito.verify(messageRpc).listMessagesByTimeRange(list.capture());
        assertThat(list.getValue().userId()).as("messages/list 的 actor").isEqualTo(VIRTUAL_USER_ID);
        assertThat(list.getValue().limit()).isEqualTo(20);

        ArgumentCaptor<ImReadCountsRequest> counts = ArgumentCaptor.forClass(ImReadCountsRequest.class);
        org.mockito.Mockito.verify(messageRpc).getReadCounts(counts.capture());
        assertThat(counts.getValue().userId()).as("messages/read-counts 的 actor").isEqualTo(VIRTUAL_USER_ID);
        assertThat(counts.getValue().msgIds()).containsExactly(9001L);

        ArgumentCaptor<ImMemberListRequest> members = ArgumentCaptor.forClass(ImMemberListRequest.class);
        org.mockito.Mockito.verify(conversationRpc).listMembers(members.capture());
        assertThat(members.getValue().userId()).as("conversations/members/list 的 actor")
                .isEqualTo(VIRTUAL_USER_ID);
    }

    @Test
    @DisplayName("★AK 不带 AGENT_VIRTUAL_USER_ID（手工 AK / 普通用户调用）→ 一律以 AK 归属人行使，语义与改动前一致")
    void withoutAgentVirtualIdentityNonSendEndpointsStayTheAkOwner() {
        loginAsAk(USER_ID, TENANT_ID, Map.of("IS_GROUP_MESSAGE", false));
        when(messageRpc.getMessage(any())).thenReturn(detailDto());
        when(conversationRpc.listMembers(any())).thenReturn(new MemberPageDto(1, false, List.of()));

        controller.revoke(new NuwaxImSandboxRequests.Revoke(7L, 9001L, null, null, null));
        controller.getMessage(new NuwaxImSandboxRequests.GetMessage(7L, 9001L, null));
        controller.listMembers(new NuwaxImSandboxRequests.ListMembers(7L, 10, null));

        assertThat(captureRevoke().userId()).as("撤回").isEqualTo(USER_ID);

        ArgumentCaptor<ImMessageGetRequest> get = ArgumentCaptor.forClass(ImMessageGetRequest.class);
        org.mockito.Mockito.verify(messageRpc).getMessage(get.capture());
        assertThat(get.getValue().userId()).as("消息详情").isEqualTo(USER_ID);

        ArgumentCaptor<ImMemberListRequest> members = ArgumentCaptor.forClass(ImMemberListRequest.class);
        org.mockito.Mockito.verify(conversationRpc).listMembers(members.capture());
        assertThat(members.getValue().userId()).as("群员列表").isEqualTo(USER_ID);

        // 完全没有 AK（登录态直连 /api/nuwax-im/**）同样是本人 —— RequestContext 里是 null
        loginAs(USER_ID, TENANT_ID);
        controller.revoke(new NuwaxImSandboxRequests.Revoke(7L, 9002L, null, null, null));
        assertThat(captureRevoke().userId()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("★★与 groupActorUserId 的有意不同：群键在、智能体键缺失时【回退本人】而不是 null")
    void missingAgentVirtualIdentityFallsBackToTheOwnerNotToNull() {
        // 发送端点在这种 ext 下用 groupActorUserId ⇒ actor = null（交给 IM 以 IM_90001 大声拒）。
        // 查询端点若照抄这个返回值，构造出的就是 userId=null 的请求 —— 错误信息会退化成
        // 「缺少 userId」这种与真实病因（AK 没带虚拟身份）无关的抱怨。
        loginAsAk(USER_ID, TENANT_ID, Map.of("GROUP_VIRTUAL_USER_ID", 660001L));

        controller.revoke(new NuwaxImSandboxRequests.Revoke(7L, 9001L, null, null, null));

        assertThat(captureRevoke().userId())
                .as("回退本人仍然是【大声失败】（本人若不在群里 → NOT_CONV_MEMBER），但错误信息指向真实病因")
                .isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("★★群场景下虚拟身份不可信时（Double / 非数字串 / 0 / 负数）回退本人，绝不截断成另一个人的身份")
    void untrustedAgentVirtualIdentityNeverProducesAWrongActor() {
        // ★注意 "6600000000001"（数字形态的字符串）不在此列 —— toUserId 有意接受它：
        //   ext 声明类型是 Object，JSON 里的数字在某些写入路径上就是字符串形态，拒掉会变成
        //   「偶尔不切换身份」。这里列的是【转换必然失败】或【结果非法】的那几类。
        List<Object> badValues = List.of(6600000000001.9D, "abc", 0, -1, "");
        for (Object bad : badValues) {
            // ★带群键 ⇒ 走群场景分支，才存在"去读虚拟身份"这一步（私聊场景根本不读它）。
            loginAsAk(USER_ID, TENANT_ID, Map.of(
                    "GROUP_VIRTUAL_USER_ID", 660001L,
                    "AGENT_VIRTUAL_USER_ID", bad));

            controller.revoke(new NuwaxImSandboxRequests.Revoke(7L, 9001L, null, null, null));

            // ★截断（Double → 6600000000001）比失败更糟：它会以【另一个真实用户】的身份撤消息，
            //   而 IM 那边看到的是一个完全合法的请求。
            assertThat(captureRevoke().userId())
                    .as("ext 值 %s 不可信，必须回退本人", bad)
                    .isEqualTo(USER_ID);
        }
    }

    @Test
    @DisplayName("★群场景下数字形态的字符串算可信值（JSON 数字在部分写入路径上就是字符串形态），按本人回退会变成「偶尔不切身份」")
    void numericStringAgentVirtualIdentityIsAccepted() {
        loginAsAk(USER_ID, TENANT_ID, Map.of(
                "GROUP_VIRTUAL_USER_ID", 660001L,
                "AGENT_VIRTUAL_USER_ID", "6600000000001"));

        controller.revoke(new NuwaxImSandboxRequests.Revoke(7L, 9001L, null, null, null));

        assertThat(captureRevoke().userId()).isEqualTo(VIRTUAL_USER_ID);
    }

    @Test
    @DisplayName("★同一张 AK 上两条规则各自独立：群场景查询/撤回=虚拟身份，群发=虚拟身份")
    void theActorRulesCoexistOnOneAccessKey() {
        loginAsAk(USER_ID, TENANT_ID, Map.of(
                "GROUP_VIRTUAL_USER_ID", 660001L,
                "GROUP_CONV_ID", 7L,
                "AGENT_VIRTUAL_USER_ID", VIRTUAL_USER_ID));
        when(messageRpc.sendGroupMessage(any())).thenReturn(messageDto());

        controller.revoke(new NuwaxImSandboxRequests.Revoke(7L, 9001L, null, null, null));
        controller.sendGroup(groupRequest(7L));

        assertThat(captureRevoke().userId()).as("撤回 → scenarioActorUserId（群场景）").isEqualTo(VIRTUAL_USER_ID);
        assertThat(captureGroup().userId()).as("群发 → groupActorUserId").isEqualTo(VIRTUAL_USER_ID);
        // ★原来这条用例还在同一张群 AK 上断言「私聊发给别人 = 本人」，
        //   现在那一步已经被闸门②直接拒掉（群场景不许 p2p）—— 见 requireP2pSendIsAllowed。
        //   「私聊场景的 p2p 仍是本人」由下面 privateChatScenarioStillSpeaksAsTheUser 覆盖。
    }

    @Test
    @DisplayName("★私聊场景的 p2p 仍是本人：顺手把规则「统一」成一条，收件人看到的发送者就不再是跟他聊天的那个人")
    void privateChatScenarioStillSpeaksAsTheUser() {
        loginAsAk(USER_ID, TENANT_ID, Map.of(
                "IS_GROUP_MESSAGE", false,
                "AGENT_VIRTUAL_USER_ID", VIRTUAL_USER_ID));
        when(messageRpc.sendP2pMessage(any())).thenReturn(messageDto());

        controller.sendP2p(new NuwaxImSandboxRequests.SendP2p(2002L, "text", textNode(), null, null, null));

        assertThat(captureP2p().userId()).as("私聊发给别人 → p2pActorUserId = 本人").isEqualTo(USER_ID);
    }

    // ===================================== 群场景两道「混乱代理」闸门（2026-09-23 加固）

    /**
     * ★★闸门①的正例：群场景 + 目标群就是触发群 ⇒ 放行，且 actor 与 convId 都不被改写。
     *
     * <p>这条与 {@link #groupTriggeredSendGroupSpeaksAsAgentVirtualUser} 的区别是它<b>显式</b>钉住
     * 「放行」这件事 —— 闸门写错方向（把判据写成 {@code !=} 或忘了 return）时，
     * 那条老用例只会因为「没抛异常」而通过，这条也一样，但这条的存在让「闸门是否放行了本该放行的」
     * 有了一条名字直接对得上的用例。
     */
    @Test
    @DisplayName("★闸门①正例：群场景且 convId == GROUP_CONV_ID ⇒ 正常发出，消息落进触发群")
    void groupScenarioSendingBackToTriggerGroupIsAllowed() {
        loginAsAk(USER_ID, TENANT_ID, Map.of(
                "GROUP_VIRTUAL_USER_ID", 660001L,
                "GROUP_CONV_ID", 7L,
                "AGENT_VIRTUAL_USER_ID", VIRTUAL_USER_ID));
        when(messageRpc.sendGroupMessage(any())).thenReturn(messageDto());

        controller.sendGroup(groupRequest(7L));

        ImGroupMessageRequest sent = captureGroup();
        assertThat(sent.userId()).isEqualTo(VIRTUAL_USER_ID);
        assertThat(sent.convId()).isEqualTo(7L);
    }

    /**
     * ★★闸门①的核心用例：智能体在别处也是成员，但「本次运行的授权范围」只有触发群。
     *
     * <p>不抛的话后果是：甲在群 A 里（甲不在群 B）能让智能体把消息投进群 B ——
     * IM 的 {@code requireActiveMember(B, 智能体虚拟身份)} 会通过（智能体确实在 B 里）。
     */
    @Test
    @DisplayName("★★闸门①：群场景下往【非触发群】发 ⇒ 4030 拒绝，且一个 RPC 都不发")
    void groupScenarioSendingToAnotherGroupIsRejected() {
        loginAsAk(USER_ID, TENANT_ID, Map.of(
                "GROUP_VIRTUAL_USER_ID", 660001L,
                "GROUP_CONV_ID", 7L,
                "AGENT_VIRTUAL_USER_ID", VIRTUAL_USER_ID));
        when(messageRpc.sendGroupMessage(any())).thenReturn(messageDto());

        assertThatThrownBy(() -> controller.sendGroup(groupRequest(999L)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    BizException biz = (BizException) e;
                    assertThat(biz.getCode()).as("对外是 PERMISSION_DENIED 的码").isEqualTo("4030");
                    assertThat(biz.getMessage())
                            .as("消息里要同时给出两个 convId，否则排查时看不出「谁跟谁不一致」")
                            .contains("7").contains("999");
                });
        verifyNoInteractions(messageRpc, conversationRpc, contactRpc, eventRpc);
    }

    /**
     * ★★闸门①的 fail-closed 分支：判成群场景却读不到 {@code GROUP_CONV_ID} ⇒ 拒。
     *
     * <p>放行等于「删掉这一个键即可绕过闸门」。真实 IM 不会送这种 ext
     * （该键在群聊分支里无条件 put），所以出现即异常，两种成因（版本漂移 / 手工造 AK）
     * 都该被看见 —— 日志措辞把两者区分开。
     */
    @Test
    @DisplayName("★★闸门① fail-closed：群场景 ext 里没有 GROUP_CONV_ID ⇒ 4030 拒绝，绝不默认放行")
    void groupScenarioWithoutTriggerConvIdIsRejected() {
        loginAsAk(USER_ID, TENANT_ID, Map.of(
                "GROUP_VIRTUAL_USER_ID", 660001L,
                "AGENT_VIRTUAL_USER_ID", VIRTUAL_USER_ID));
        when(messageRpc.sendGroupMessage(any())).thenReturn(messageDto());

        assertThatThrownBy(() -> controller.sendGroup(groupRequest(7L)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo("4030"));
        verifyNoInteractions(messageRpc, conversationRpc, contactRpc, eventRpc);
    }

    /**
     * ★★闸门①的「键在但值不可信」分支：{@code GROUP_CONV_ID} 写了个 Double / 非数字串 ⇒ 当作读不到。
     *
     * <p>与虚拟身份那条口径一致（{@link #untrustedVirtualIdentityInGroupBranchNeverProducesAWrongActor}）：
     * 宁可拒，也不能把 {@code 7.9} 截断成 {@code 7} 去跟请求比 —— 那等于把闸门交给浮点误差。
     */
    @Test
    @DisplayName("★★闸门①：GROUP_CONV_ID 值不可信（Double / 非数字串 / 0 / 负数）⇒ 一律按读不到处理并拒绝")
    void untrustedTriggerConvIdIsTreatedAsMissing() {
        List<Object> badValues = List.of(7.9D, "abc", "7.9", 0, -7, "");
        for (Object bad : badValues) {
            Map<String, Object> ext = new LinkedHashMap<>();
            ext.put("GROUP_VIRTUAL_USER_ID", 660001L);
            ext.put("GROUP_CONV_ID", bad);
            ext.put("AGENT_VIRTUAL_USER_ID", VIRTUAL_USER_ID);
            loginAsAk(USER_ID, TENANT_ID, ext);
            when(messageRpc.sendGroupMessage(any())).thenReturn(messageDto());

            assertThatThrownBy(() -> controller.sendGroup(groupRequest(7L)))
                    .as("GROUP_CONV_ID = %s 若被截断成 7 就会放行", bad)
                    .isInstanceOf(BizException.class);
        }
    }

    /**
     * ★★闸门①<b>也管卡片</b>（2026-10-05 端点全量审计后补挂）。
     *
     * <p>补挂前 {@code /message/send-card} 是这条口径上唯一的漏网：它的 actor 走
     * {@link #agentActorUserId}（群场景 = 智能体虚拟身份），{@code convId} 由请求体决定，
     * 而 IM 侧 doSend 的第一道同样是 {@code requireActiveMember(convId, selfId)}
     * —— 判的是「<b>智能体</b>在不在目标会话」，与 send-group 逐字同形。
     * ⇒ 甲在群 A 里（甲不在群 B）能让智能体把一张<b>卡片</b>（正文与按钮都由甲提供）投进群 B。
     */
    @Test
    @DisplayName("★★闸门①也管卡片：群场景下往【非触发群】发卡片 ⇒ 4030，且一个 RPC 都不发")
    void groupScenarioSendingCardToAnotherConvIsRejected() {
        loginAsAk(USER_ID, TENANT_ID, Map.of(
                "GROUP_VIRTUAL_USER_ID", 660001L,
                "GROUP_CONV_ID", 7L,
                "AGENT_VIRTUAL_USER_ID", VIRTUAL_USER_ID));
        when(messageRpc.sendCardMessage(any())).thenReturn(messageDto());

        assertThatThrownBy(() -> controller.sendCard(cardRequest(999L)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    BizException biz = (BizException) e;
                    assertThat(biz.getCode()).isEqualTo("4030");
                    assertThat(biz.getMessage()).contains("7").contains("999");
                });
        verifyNoInteractions(messageRpc, conversationRpc, contactRpc, eventRpc);
    }

    @Test
    @DisplayName("★闸门①对卡片的正例：群场景且 convId == GROUP_CONV_ID ⇒ 卡片照常发出，落进触发群")
    void groupScenarioSendingCardBackToTriggerConvIsAllowed() {
        // ★正例不能省：闸门写错方向（把判据写成 != 或忘了 return）时，
        //   上面那条拒绝用例照样会「绿」，只有这条会红。
        loginAsAk(USER_ID, TENANT_ID, Map.of(
                "GROUP_VIRTUAL_USER_ID", 660001L,
                "GROUP_CONV_ID", 7L,
                "AGENT_VIRTUAL_USER_ID", VIRTUAL_USER_ID));
        when(messageRpc.sendCardMessage(any())).thenReturn(messageDto());

        assertThat(controller.sendCard(cardRequest(7L)).getCode()).isEqualTo("0000");
        assertThat(captureCard().convId()).isEqualTo(7L);
    }

    /**
     * ★★闸门②：群场景下 {@code send-p2p} 一律拒 —— 否则会以<b>群代理</b>身份给任意人发私聊。
     *
     * <p>为什么这条比闸门①更急：IM 的 {@code sendP2p} 会为「群代理 ↔ 任意用户」<b>自动建会话</b>
     * 并把群代理记为成员，成员校验必然通过，收件人也可以是完全无关的人。
     */
    @Test
    @DisplayName("★★闸门②：群场景下调 send-p2p ⇒ 4030 拒绝，一个 RPC 都不发")
    void groupScenarioCallingP2pIsRejected() {
        loginAsAk(USER_ID, TENANT_ID, Map.of(
                "GROUP_VIRTUAL_USER_ID", 660001L,
                "GROUP_CONV_ID", 7L,
                "AGENT_VIRTUAL_USER_ID", VIRTUAL_USER_ID));
        when(messageRpc.sendP2pMessage(any())).thenReturn(messageDto());

        assertThatThrownBy(() -> controller.sendP2p(
                new NuwaxImSandboxRequests.SendP2p(2002L, "text", textNode(), null, null, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo("4030"));
        verifyNoInteractions(messageRpc, conversationRpc, contactRpc, eventRpc);
    }

    /**
     * ★★闸门②必须用「群场景的<b>超集</b>」判据，这一条钉住取超集的那个理由。
     *
     * <p>IM 的群聊分支里 {@code GROUP_CONV_ID} 是<b>无条件</b> put、群虚拟键是<b>有条件</b>的
     * （取不到群代理 id 时只 WARN 不 put；注释写明常态不可达，留着是防有人放松上游闸门）。
     * 那个形态下 AK 归属人<b>仍然是群代理</b>，p2p 火口照样是通的
     * ⇒ 判据若只看群虚拟键，这里就会漏。
     */
    @Test
    @DisplayName("★★闸门②：只有 GROUP_CONV_ID、没有群虚拟键（IM 那个理论形态）⇒ 仍然拒，不能漏")
    void groupScenarioWithOnlyConvIdKeyStillBlocksP2p() {
        loginAsAk(USER_ID, TENANT_ID, Map.of(
                "GROUP_CONV_ID", 7L,
                "AGENT_VIRTUAL_USER_ID", VIRTUAL_USER_ID));
        when(messageRpc.sendP2pMessage(any())).thenReturn(messageDto());

        assertThatThrownBy(() -> controller.sendP2p(
                new NuwaxImSandboxRequests.SendP2p(2002L, "text", textNode(), null, null, null)))
                .isInstanceOf(BizException.class);
        verifyNoInteractions(messageRpc, conversationRpc, contactRpc, eventRpc);
    }

    /**
     * ★★两道闸门都<b>不能</b>误伤私聊场景 —— 尤其不能误伤「智能体在私聊里回话」那条腿。
     *
     * <p>私聊场景 actor 是本人或「只回发给它的那个人」，驱动者与发送者是同一个人，
     * 没有权限落差可利用；在那里收窄只会砍掉正当能力。
     */
    @Test
    @DisplayName("★★两道闸门都不误伤私聊场景：send-p2p 回给本人仍走虚拟身份，send-group 仍以本人身份发出")
    void privateChatScenarioIsNotAffectedByTheGroupGates() {
        loginAsAk(USER_ID, TENANT_ID, Map.of(
                "IS_GROUP_MESSAGE", false,
                "AGENT_VIRTUAL_USER_ID", VIRTUAL_USER_ID));
        when(messageRpc.sendP2pMessage(any())).thenReturn(messageDto());
        when(messageRpc.sendGroupMessage(any())).thenReturn(messageDto());

        // ★智能体在私聊里回话：收件人 = 本人 ⇒ 换成虚拟身份（IM_20106 那条腿）
        controller.sendP2p(new NuwaxImSandboxRequests.SendP2p(USER_ID, "text", textNode(), null, null, null));
        assertThat(captureP2p().userId()).isEqualTo(VIRTUAL_USER_ID);

        // ★用户在私聊里顺手发条群消息：actor = 本人，目标群随便哪个都行（由 IM 按本人成员资格判）
        controller.sendGroup(groupRequest(999L));
        assertThat(captureGroup().userId()).isEqualTo(USER_ID);
    }

    /**
     * ★★「没有可用的 ext」这一档同样不能被闸门误伤，且<b>不能因缺 AK 而 NPE</b>。
     *
     * <p>两道闸门都要读 ext 才能判场景，读法是 {@link UserAccessKeyDto} →
     * 而登录态直连那条路 {@code RequestContext} 里可能压根没有 AK，
     * 甚至 {@code RequestContext} 整体为 {@code null}（{@code RequestContext.remove()} 之后）——
     * 那种情况下 {@code currentAccessKey()} 必须是全函数（返回 null），
     * 否则「没登录」会从 {@code 4010} 退化成 NPE 5000
     * （{@link #missingLoginIsRejectedBeforeAnyRpc} 钉着这一条）。
     */
    @Test
    @DisplayName("★闸门读 ext 时既不因 ext 不是 map 而炸，也不因 AK 整份缺失而 NPE")
    void gatesTolerateMissingExtAndMissingAccessKey() {
        // ext 不是 map（历史/异常数据形态）
        loginAsAk(USER_ID, TENANT_ID, "not-a-map");
        when(messageRpc.sendP2pMessage(any())).thenReturn(messageDto());
        when(messageRpc.sendGroupMessage(any())).thenReturn(messageDto());
        controller.sendP2p(new NuwaxImSandboxRequests.SendP2p(2002L, "text", textNode(), null, null, null));
        assertThat(captureP2p().userId()).isEqualTo(USER_ID);
        controller.sendGroup(groupRequest(999L));
        assertThat(captureGroup().userId()).isEqualTo(USER_ID);

        // AK 整份缺失（登录态直连）：不抛 NPE，走到 currentUserId() 那条正常路径
        loginAs(USER_ID, TENANT_ID);
        controller.sendP2p(new NuwaxImSandboxRequests.SendP2p(2002L, "text", textNode(), null, null, null));
        assertThat(captureP2p().userId()).isEqualTo(USER_ID);
        controller.sendGroup(groupRequest(999L));
        assertThat(captureGroup().userId()).isEqualTo(USER_ID);
    }

    // ------------------------- 私聊场景：13 个端点一律以【用户本人】行使（管控口径，2026-09-23 定）

    /**
     * ★★私聊场景（ext 里<b>没有</b> {@code GROUP_VIRTUAL_USER_ID}）下，除发送类以外的 13 个端点
     * <b>一律以 AK 归属人本人</b>行使 —— 即便这块 AK 明确带着 {@code AGENT_VIRTUAL_USER_ID}。
     *
     * <p><b>为什么必须是本人（管控口径，不是"漏切了身份"）</b>：私聊场景的语义是「用户本人在跟
     * 智能体说话」，智能体的每个动作都应落在<b>这个用户在 IM 里看得见、管得着的那块资源边界内</b>。
     * 若私聊场景也切虚拟身份，{@code /contact/list} 返回的会变成<b>智能体自己</b>作为独立 IM 账号的
     * 通讯录、{@code /conversation/list} 是它自己的会话列表 —— 那是用户在界面上看不到、也无从审计的
     * 另一块独立资源，用户根本不知道智能体读到了谁。
     *
     * <p><b>这张用例的对照面是 {@link #groupMessageAndMemberQueriesActAsAgentVirtualUser}</b>：
     * 两张 AK 唯一的差别就是 ext 里有没有那个群键，行使身份却<b>完全相反</b> —— 这正是
     * {@code scenarioActorUserId}（而非 {@code agentActorUserId}）存在的全部理由。
     * 谁把它改回「有虚拟身份就用」，这条立刻变红。
     *
     * <p>用反射取 {@code userId()} 是因为 13 个 RPC 请求 DTO 类型各不相同，逐个写 captor 会变成
     * 13 段同样形状的样板；逐次调用后立即取该 mock 的<b>最近一次</b>入参，顺序即对应关系。
     */
    @Test
    @DisplayName("★★私聊场景 → 13 个端点一律以【用户本人】行使（含 /contact/list，群场景才用虚拟身份）")
    void p2pScenarioEndpointsAlwaysActAsTheUser() throws Exception {
        // ★管理员 + 带 AK：/event/push 前置了 requireAdmin()，普通登录态会 4030。
        loginAsAdminAk(USER_ID, TENANT_ID, Map.of("AGENT_VIRTUAL_USER_ID", VIRTUAL_USER_ID));
        when(messageRpc.getMessage(any())).thenReturn(detailDto());
        when(messageRpc.getReadCounts(any())).thenReturn(List.of(readCountItem()));
        when(messageRpc.listMessagesByTimeRange(any())).thenReturn(pageDto());
        when(conversationRpc.createGroup(any())).thenReturn(new ImConversationDto(
                7L, 2, "群", null, USER_ID, 1, 2000, true, 1L, false));
        when(conversationRpc.addMembers(any())).thenReturn(memberAddResultDto());
        when(conversationRpc.listMembers(any())).thenReturn(new MemberPageDto(1, false, List.of()));
        when(conversationRpc.getOrCreateP2p(any())).thenReturn(new ImConversationDto(
                7L, 1, null, null, USER_ID, 2, 2000, true, 1L, false));
        when(conversationRpc.listConversations(any())).thenReturn(new ImConversationPageDto(List.of(), false, null));
        when(contactRpc.listContacts(any())).thenReturn(new ImContactListDto(List.of(), false, false));
        when(eventRpc.pushCustomEvent(any())).thenReturn(new ImCustomEventPushResultDto(1, 1, 1));

        controller.revoke(new NuwaxImSandboxRequests.Revoke(7L, 9001L, null, null, null));
        assertActorIsTheUser("/message/revoke", lastArg(messageRpc));
        controller.getMessage(new NuwaxImSandboxRequests.GetMessage(7L, 9001L, null));
        assertActorIsTheUser("/message/get", lastArg(messageRpc));
        controller.readCounts(new NuwaxImSandboxRequests.ReadCounts(7L, List.of(9001L), null));
        assertActorIsTheUser("/message/read-counts", lastArg(messageRpc));
        controller.listMessages(new NuwaxImSandboxRequests.ListMessages(7L, null, null, null, 20, null));
        assertActorIsTheUser("/message/list", lastArg(messageRpc));
        controller.createGroup(new NuwaxImSandboxRequests.CreateGroup(
                "群", null, null, null, null, null, null, null));
        assertActorIsTheUser("/conversation/create-group", lastArg(conversationRpc));
        controller.addMembers(new NuwaxImSandboxRequests.AddMembers(7L, List.of(2L), null, null, null, null));
        assertActorIsTheUser("/conversation/members/add", lastArg(conversationRpc));
        controller.removeMembers(new NuwaxImSandboxRequests.RemoveMembers(7L, List.of(2L), null, null, null));
        assertActorIsTheUser("/conversation/members/remove", lastArg(conversationRpc));
        controller.listMembers(new NuwaxImSandboxRequests.ListMembers(7L, 10, null));
        assertActorIsTheUser("/conversation/members/list", lastArg(conversationRpc));
        controller.getOrCreateP2p(new NuwaxImSandboxRequests.GetOrCreateP2p(2L, null, null));
        assertActorIsTheUser("/conversation/p2p", lastArg(conversationRpc));
        controller.updateDigest(new NuwaxImSandboxRequests.UpdateDigest(7L, "d", null, null));
        assertActorIsTheUser("/conversation/digest", lastArg(conversationRpc));
        controller.listConversations(new NuwaxImSandboxRequests.ListConversations(null, 20, null));
        assertActorIsTheUser("/conversation/list", lastArg(conversationRpc));
        // ★用户点名的那个端点：查的是「我的通讯录」，绝不能变成「智能体自己的通讯录」。
        controller.listContacts(new NuwaxImSandboxRequests.ListContacts(null));
        assertActorIsTheUser("/contact/list（用户点名：不能查智能体自己的通讯录）", lastArg(contactRpc));
        controller.pushEvent(new NuwaxImSandboxRequests.PushEvent(
                List.of(2L), null, "evt", Map.of("k", "v"), null, null));
        assertActorIsTheUser("/event/push", lastArg(eventRpc));
    }

    // -------------------------------------------------- 撤回：看一眼「这条是谁发的」

    /**
     * 这一组盯的是 {@code revokeActorUserId} 多出来的那一步：撤回前先回读 {@code senderId}。
     *
     * <p>没有它，私聊场景就只能二选一，「用户自己发出去的」和「智能体自己回话的」里必然有一类撤不掉。
     * 有它之后 {@link #p2pRevokeOfTheAgentsOwnMessageActsAsTheVirtualUser} 与
     * {@link #p2pRevokeOfTheMessageTheUserSentActsAsTheUser} 同时成立。
     */
    @Test
    @DisplayName("★★用户点名的场景：私聊里让智能体「撤回我刚给张三发的那条」—— 回读到发送者是本人，就以本人身份撤")
    void p2pRevokeOfTheMessageTheUserSentActsAsTheUser() {
        // ★不带群键 ⇒ 私聊场景（scenarioActorUserId 给本人），但 AK 上仍有虚拟身份 ⇒ 存在歧义，必须回读。
        loginAsAk(USER_ID, TENANT_ID, Map.of("AGENT_VIRTUAL_USER_ID", VIRTUAL_USER_ID));
        // 目标消息是【本人】发的（detailDto() 的 senderId 就是 USER_ID）。
        when(messageRpc.getMessage(any())).thenReturn(detailDto());

        controller.revoke(new NuwaxImSandboxRequests.Revoke(7L, 9001L, "发错了", null, null));

        // ① 回读本身用的是场景身份（私聊 = 本人）——本人看得见自己的会话，读得回来。
        ArgumentCaptor<ImMessageGetRequest> read = ArgumentCaptor.forClass(ImMessageGetRequest.class);
        org.mockito.Mockito.verify(messageRpc).getMessage(read.capture());
        assertThat(read.getValue().userId()).as("回读用的也必须是本人，别用虚拟身份去读").isEqualTo(USER_ID);
        assertThat(read.getValue().convId()).isEqualTo(7L);
        assertThat(read.getValue().msgId()).isEqualTo(9001L);

        // ② 回读到发送者=本人 ⇒ operator 保持本人（换回虚拟身份会被 IM 判 NO_PERMISSION）。
        ImMessageRevokeRequest sent = captureRevoke();
        assertThat(sent.userId()).as("★这条是本人发的，operator 必须是本人").isEqualTo(USER_ID);
        assertThat(sent.convId()).isEqualTo(7L);
        assertThat(sent.msgId()).isEqualTo(9001L);
        assertThat(sent.reason()).isEqualTo("发错了");
    }

    @Test
    @DisplayName("★私聊里撤回【智能体自己的回话】—— 回读到发送者是虚拟身份，operator 换成虚拟身份")
    void p2pRevokeOfTheAgentsOwnMessageActsAsTheVirtualUser() {
        loginAsAk(USER_ID, TENANT_ID, Map.of("AGENT_VIRTUAL_USER_ID", VIRTUAL_USER_ID));
        // 目标消息是【智能体】发的（它当初走 p2pActorUserId 的虚拟身份分支发出去）。
        when(messageRpc.getMessage(any())).thenReturn(detailDtoFrom(VIRTUAL_USER_ID));

        controller.revoke(new NuwaxImSandboxRequests.Revoke(7L, 9001L, null, null, null));

        assertThat(captureRevoke().userId())
                .as("★发送者是虚拟身份 ⇒ operator 必须是虚拟身份，否则 IM 判 NO_PERMISSION")
                .isEqualTo(VIRTUAL_USER_ID);
    }

    @Test
    @DisplayName("★回读不到消息时不臆测身份 —— 退回场景身份，让 IM 用真实的 NOT_FOUND / NO_PERMISSION 大声拒")
    void p2pRevokeWithUnreadableMessageFallsBackToTheScenarioActor() {
        loginAsAk(USER_ID, TENANT_ID, Map.of("AGENT_VIRTUAL_USER_ID", VIRTUAL_USER_ID));
        when(messageRpc.getMessage(any())).thenReturn(null);

        controller.revoke(new NuwaxImSandboxRequests.Revoke(7L, 9001L, null, null, null));

        assertThat(captureRevoke().userId()).as("读不到就按私聊场景的本人走，不猜").isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("★★群场景 / 无虚拟身份场景【不】多读这一次 —— 两个身份键同值（或本就没有虚拟身份），无歧义可言")
    void revokeOnlyPaysForTheExtraReadWhenIdentitiesDiffer() {
        // 场景一：群场景。scenarioActorUserId 返回的正是 agentSubjectId 那个虚拟身份，
        // 与 agentVirtualUserId 同值 ⇒ 没有可歧义的两个候选，跳过回读。
        loginAsAk(USER_ID, TENANT_ID, Map.of(
                "GROUP_VIRTUAL_USER_ID", 660001L,
                "AGENT_VIRTUAL_USER_ID", VIRTUAL_USER_ID,
                "GROUP_CONV_ID", 7L));
        controller.revoke(new NuwaxImSandboxRequests.Revoke(7L, 9001L, null, null, null));
        assertThat(captureRevoke().userId()).isEqualTo(VIRTUAL_USER_ID);
        org.mockito.Mockito.verify(messageRpc, org.mockito.Mockito.never()).getMessage(any());

        // 场景二：不带虚拟身份（手工 AK / 登录态直连）。只有一个候选身份 ⇒ 同样跳过回读。
        tearDown();
        loginAsAk(USER_ID, TENANT_ID, Map.of());
        controller.revoke(new NuwaxImSandboxRequests.Revoke(7L, 9001L, null, null, null));
        assertThat(captureRevoke().userId()).as("无虚拟身份 ⇒ 以 AK 归属人撤").isEqualTo(USER_ID);
        org.mockito.Mockito.verify(messageRpc, org.mockito.Mockito.never()).getMessage(any());
    }

    @Test
    @DisplayName("★★21 个请求 record 全都不含 userId / tenantId —— 身份不可伪造是【编译期事实】而非约定")
    void noRequestRecordCanCarryAnIdentity() {
        List<Class<?>> records = List.of(
                NuwaxImSandboxRequests.SendP2p.class,
                NuwaxImSandboxRequests.SendGroup.class,
                NuwaxImSandboxRequests.SendSystem.class,
                NuwaxImSandboxRequests.SystemNotify.class,
                NuwaxImSandboxRequests.SendCard.class,
                NuwaxImSandboxRequests.Revoke.class,
                NuwaxImSandboxRequests.GetMessage.class,
                NuwaxImSandboxRequests.ReadCounts.class,
                NuwaxImSandboxRequests.ListMessages.class,
                NuwaxImSandboxRequests.CreateGroup.class,
                NuwaxImSandboxRequests.AddMembers.class,
                NuwaxImSandboxRequests.RemoveMembers.class,
                NuwaxImSandboxRequests.ListMembers.class,
                NuwaxImSandboxRequests.GetOrCreateP2p.class,
                NuwaxImSandboxRequests.UpdateDigest.class,
                NuwaxImSandboxRequests.ListConversations.class,
                NuwaxImSandboxRequests.GetConversationDetail.class,
                NuwaxImSandboxRequests.ListContacts.class,
                NuwaxImSandboxRequests.PushEvent.class,
                NuwaxImSandboxRequests.PendingList.class,
                NuwaxImSandboxRequests.PendingClear.class);

        // ★21 = 21 个 IM RPC 端点，一一对应（2026-09-23：system / push 拿回来；DEV-285：+2 排队区；
        //   DEV-288：+1 会话详情；DEV-291：+1 系统通知）。★至此平台侧与服务端 ImInternalRpcController
        //   的端点集合一一对齐。
        //   其中 SendSystem / SystemNotify / PushEvent 对应的端点在控制器上【前置了管理员闸门】，
        //   但那属于「谁能碰到这个端点」，与「请求体能不能指定身份」是两件事 ——
        //   把 userId 加进这三个 record 依然会红，因为闸门管的是调用者、不管被冒充者。
        //   ★SystemNotify 尤其值得在这里过一遍：底层 IM DTO（ImSystemNotifyRequest）【有】userId 字段，
        //     最容易被"顺手补全"到请求体里。但那里它只是审计归属 + 租户上下文来源
        //     （发送身份由 IM 固定成合成账号「系统通知」），本层由控制器填当前登录人，
        //     调用方永远给不了 —— 这条不变量靠的就是下面这条断言，而不是靠注释。
        assertThat(records).as("请求 record 个数应与契约端点个数一致").hasSize(21);

        // ★这条断言的价值在于「以后有人为了省事把 userId 加回请求体」时会红。
        //   传错 userId 不会报任何错 —— 它会以那个人的身份把消息发出去，事后只能靠人工核对。
        assertThat(records).allSatisfy(record -> assertThat(
                Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName))
                .as("%s 不应含有身份字段", record.getSimpleName())
                .doesNotContain("userId", "tenantId", "senderId", "fromUserId", "operatorId"));
    }

    @Test
    @DisplayName("没有登录态 → 4010 / userNotLoggedIn，且一个 RPC 都不发")
    void missingLoginIsRejectedBeforeAnyRpc() {
        RequestContext.remove();

        assertThatThrownBy(() -> controller.sendP2p(new NuwaxImSandboxRequests.SendP2p(
                2002L, "text", textNode(), null, null, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    BizException biz = (BizException) e;
                    assertThat(biz.getCode()).isEqualTo("4010");
                    assertThat(biz.getMsgName()).isEqualTo("userNotLoggedIn");
                });

        verifyNoInteractions(messageRpc, conversationRpc, contactRpc, eventRpc);
    }

    @Test
    @DisplayName("★tenantId 回退链：context.tenantId 为空时取 userContext.tenantId（沙箱 AK 链路只写后者）")
    void tenantIdFallsBackToUserContext() {
        RequestContext<Object> context = RequestContext.<Object>builder().userId(USER_ID).build();
        context.setUserContext(UserContext.builder().userId(USER_ID).tenantId(TENANT_ID).build());
        RequestContext.set(context);

        when(messageRpc.sendP2pMessage(any())).thenReturn(messageDto());
        controller.sendP2p(new NuwaxImSandboxRequests.SendP2p(
                2002L, "text", textNode(), null, null, null));

        ArgumentCaptor<ImP2pMessageRequest> captor = ArgumentCaptor.forClass(ImP2pMessageRequest.class);
        org.mockito.Mockito.verify(messageRpc).sendP2pMessage(captor.capture());
        assertThat(captor.getValue().tenantId()).as("★漏掉这个回退会让每次调用都多一次 IM 反查平台").isEqualTo(TENANT_ID);
    }

    @Test
    @DisplayName("两处都拿不到 tenantId 时传 null（IM 会自己反查），而不是硬失败")
    void tenantIdMayBeNull() {
        RequestContext<Object> context = RequestContext.<Object>builder().userId(USER_ID).build();
        context.setUserContext(UserContext.builder().userId(USER_ID).build());
        RequestContext.set(context);

        when(messageRpc.sendP2pMessage(any())).thenReturn(messageDto());
        controller.sendP2p(new NuwaxImSandboxRequests.SendP2p(
                2002L, "text", textNode(), null, null, null));

        ArgumentCaptor<ImP2pMessageRequest> captor = ArgumentCaptor.forClass(ImP2pMessageRequest.class);
        org.mockito.Mockito.verify(messageRpc).sendP2pMessage(captor.capture());
        assertThat(captor.getValue().tenantId()).isNull();
    }

    // ================================================================== 幂等键

    @Test
    @DisplayName("★调用方给了 bizMsgId 就用它并原样回显（重试场景必须由调用方给）")
    void callerSuppliedBizMsgIdIsEchoed() {
        loginAs(USER_ID, TENANT_ID);
        when(messageRpc.sendP2pMessage(any())).thenReturn(messageDto());

        ReqResult<NuwaxImWriteResult<ImMessageDto>> result = controller.sendP2p(
                new NuwaxImSandboxRequests.SendP2p(2002L, "text", textNode(), null, "my-key-1", null));

        assertThat(result.getCode()).isEqualTo("0000");
        assertThat(result.getData().bizMsgId()).isEqualTo("my-key-1");

        ArgumentCaptor<ImP2pMessageRequest> captor = ArgumentCaptor.forClass(ImP2pMessageRequest.class);
        org.mockito.Mockito.verify(messageRpc).sendP2pMessage(captor.capture());
        assertThat(captor.getValue().bizMsgId()).isEqualTo("my-key-1");
    }

    @Test
    @DisplayName("★没给 bizMsgId 就生成一个并【回显】—— 不回显的话调用方拿到超时后无法安全重试")
    void generatedBizMsgIdIsReturnedToTheCaller() {
        loginAs(USER_ID, TENANT_ID);
        when(messageRpc.sendP2pMessage(any())).thenReturn(messageDto());

        ReqResult<NuwaxImWriteResult<ImMessageDto>> result = controller.sendP2p(
                new NuwaxImSandboxRequests.SendP2p(2002L, "text", textNode(), null, null, null));

        assertThat(result.getData().bizMsgId()).matches("[0-9a-f]{32}");

        ArgumentCaptor<ImP2pMessageRequest> captor = ArgumentCaptor.forClass(ImP2pMessageRequest.class);
        org.mockito.Mockito.verify(messageRpc).sendP2pMessage(captor.capture());
        assertThat(captor.getValue().bizMsgId()).as("发给 IM 的必须是同一个键").isEqualTo(result.getData().bizMsgId());
    }

    @Test
    @DisplayName("每次调用的自动 bizMsgId 都不同（否则第二次发消息会被 IM 判成幂等重放、静默不发）")
    void generatedBizMsgIdsAreUnique() {
        loginAs(USER_ID, TENANT_ID);
        when(messageRpc.sendP2pMessage(any())).thenReturn(messageDto());

        String first = controller.sendP2p(new NuwaxImSandboxRequests.SendP2p(
                2002L, "text", textNode(), null, null, null)).getData().bizMsgId();
        String second = controller.sendP2p(new NuwaxImSandboxRequests.SendP2p(
                2002L, "text", textNode(), null, null, null)).getData().bizMsgId();

        assertThat(first).isNotEqualTo(second);
    }

    // ================================================================== 写 / 只读端点的返回形状

    @Test
    @DisplayName("★写端点包 NuwaxImWriteResult（带 bizMsgId）；只读端点直接返回 DTO（不带可重试的暗示）")
    void writeEndpointsAreWrappedButReadEndpointsAreNot() {
        loginAs(USER_ID, TENANT_ID);
        when(messageRpc.getMessage(any())).thenReturn(detailDto());
        when(messageRpc.getReadCounts(any())).thenReturn(List.of(readCountItem()));
        when(messageRpc.listMessagesByTimeRange(any())).thenReturn(pageDto());
        when(conversationRpc.listMembers(any())).thenReturn(new MemberPageDto(1, false, List.of()));
        when(conversationRpc.listConversations(any())).thenReturn(new ImConversationPageDto(List.of(), false, null));
        when(contactRpc.listContacts(any())).thenReturn(new ImContactListDto(List.of(), false, false));
        when(conversationRpc.getConversationDetail(any())).thenReturn(conversationDetailDto());

        // 只读：data 就是 SDK 的 DTO 本身，调用方不会误以为「可以带 bizMsgId 重试」
        assertThat(controller.getMessage(new NuwaxImSandboxRequests.GetMessage(7L, 9001L, null)).getData())
                .isInstanceOf(ImMessageDetailDto.class);
        assertThat(controller.readCounts(new NuwaxImSandboxRequests.ReadCounts(7L, List.of(9001L), null)).getData())
                .hasSize(1);
        assertThat(controller.listMessages(new NuwaxImSandboxRequests.ListMessages(7L, null, null, null, 20, null))
                .getData()).isInstanceOf(ImMessagePageDto.class);
        assertThat(controller.listMembers(new NuwaxImSandboxRequests.ListMembers(7L, 10, null)).getData())
                .isInstanceOf(MemberPageDto.class);
        assertThat(controller.listConversations(new NuwaxImSandboxRequests.ListConversations(null, 20, null))
                .getData()).isInstanceOf(ImConversationPageDto.class);
        assertThat(controller.listContacts(new NuwaxImSandboxRequests.ListContacts(null)).getData())
                .isInstanceOf(ImContactListDto.class);
        // ★DEV-288：会话详情也是只读 —— 它返回的 DTO 里【没有】bizMsgId，重试时不必原样回填任何东西
        assertThat(controller.getConversationDetail(new NuwaxImSandboxRequests.GetConversationDetail(7L, null))
                .getData()).isInstanceOf(ImConversationDetailDto.class);
    }

    @Test
    @DisplayName("★★联系人端点【不替调用方筛状态】—— 待同意(2/3)的条目必须原样透出，否则「平台查到的」比「界面上看到的」少")
    void contactEndpointDoesNotFilterByStatus() {
        loginAs(USER_ID, TENANT_ID);
        // 三态各一条：已互为联系人 / 对方申请我 / 我申请对方
        when(contactRpc.listContacts(any())).thenReturn(new ImContactListDto(List.of(
                contact(2002L, 1, "已加"),
                contact(2003L, 2, "对方申请我"),
                contact(2004L, 3, "我申请对方")), false, false));

        ImContactListDto data = controller.listContacts(
                new NuwaxImSandboxRequests.ListContacts(null)).getData();

        assertThat(data.size()).as("★在控制器里筛掉待同意项 = 平台口径与 IM/界面口径分叉").isEqualTo(3);
        assertThat(data.acceptedOnly()).as("筛选是调用方的事，SDK 只提供便捷方法").hasSize(1);
    }

    @Test
    @DisplayName("void 端点（撤回 / 移出成员 / 改摘要）也回显 bizMsgId，data 为 null")
    void voidEndpointsStillEchoBizMsgId() {
        loginAs(USER_ID, TENANT_ID);

        ReqResult<NuwaxImWriteResult<Void>> result = controller.revoke(
                new NuwaxImSandboxRequests.Revoke(7L, 9001L, "误发", "revoke-key", null));

        assertThat(result.getData().bizMsgId()).isEqualTo("revoke-key");
        assertThat(result.getData().data()).isNull();
    }

    // ================================================================== 参数校验

    @Test
    @DisplayName("★body 是 JSON null（NullNode）时也要拦下 —— 只判 Java null 会放它过去，最后变成 IM 侧的「消息体为空」")
    void jsonNullBodyIsRejectedLocally() {
        loginAs(USER_ID, TENANT_ID);

        assertThatThrownBy(() -> controller.sendP2p(
                new NuwaxImSandboxRequests.SendP2p(2002L, "text", NullNode.getInstance(), null, null, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    assertThat(((BizException) e).getCode()).isEqualTo("4000");
                    assertThat(((BizException) e).getMsgName()).isEqualTo("fieldRequiredButEmpty");
                });

        assertThatThrownBy(() -> controller.sendP2p(
                new NuwaxImSandboxRequests.SendP2p(2002L, "text", null, null, null, null)))
                .isInstanceOf(BizException.class);

        verifyNoInteractions(messageRpc, conversationRpc, contactRpc, eventRpc);
    }

    @Test
    @DisplayName("必填项缺失一律本地拦下（4000），不把参数错误推到 IM 侧")
    void requiredFieldValidationHappensLocally() {
        // ★必须是管理员：send-system / push-event 的校验发生在 requireAdmin()【之后】，
        //   用普通登录态跑这两条会先撞 4030（闸门拦下）而不是 4000（参数缺失），
        //   那样这两条用例就变成了在测闸门、根本没测到参数校验。
        loginAsAdmin(USER_ID, TENANT_ID);

        // ★每条用例带名字：上一条断言失败时只说「没抛异常」，没有名字就得把 13 个用例挨个重跑一遍才知道是哪个。
        Map<String, Runnable> cases = new LinkedHashMap<>();
        cases.put("send-p2p: toUserId 缺失", () -> controller.sendP2p(
                new NuwaxImSandboxRequests.SendP2p(null, "text", textNode(), null, null, null)));
        cases.put("send-p2p: msgType 全空格（只判 null 会放过去）", () -> controller.sendP2p(
                new NuwaxImSandboxRequests.SendP2p(2002L, " ", textNode(), null, null, null)));
        // ★SendGroup 曾经有个 sendAsAgent 字段，已删除（控制器从未读它，2026-09-23）——群发的 actor
        //   只由 AK 的 ext 决定（见 groupActorUserId），调用方本来就无从指定。理由写在 record 的 javadoc 上。
        //   另注：本用例走的是 loginAsAdmin（没有 AK）⇒ ext 读不到 ⇒ actor = 本人，正是这里要的前提。
        cases.put("send-group: convId 缺失", () -> controller.sendGroup(
                new NuwaxImSandboxRequests.SendGroup(null, "text", textNode(), null, null, null, null, null, null)));
        cases.put("read-counts: msgIds 空数组", () -> controller.readCounts(
                new NuwaxImSandboxRequests.ReadCounts(7L, List.of(), null)));
        cases.put("read-counts: msgIds 为 null", () -> controller.readCounts(
                new NuwaxImSandboxRequests.ReadCounts(7L, null, null)));
        cases.put("add-members: userIds 空数组", () -> controller.addMembers(
                new NuwaxImSandboxRequests.AddMembers(7L, List.of(), null, null, null, null)));
        cases.put("remove-members: userIds 空数组", () -> controller.removeMembers(
                new NuwaxImSandboxRequests.RemoveMembers(7L, List.of(), null, null, null)));
        cases.put("create-group: name 全空格", () -> controller.createGroup(
                new NuwaxImSandboxRequests.CreateGroup("  ", null, null, null, null, null, null, null)));
        cases.put("send-card: card 缺失", () -> controller.sendCard(
                new NuwaxImSandboxRequests.SendCard(7L, null, null, null, null)));
        // 注意 digest 可以为空（= 清空摘要），故这里必填的只有 convId
        cases.put("update-digest: convId 缺失", () -> controller.updateDigest(
                new NuwaxImSandboxRequests.UpdateDigest(null, "d", null, null)));
        // ★send-system / push-event 于 2026-09-23 恢复（前置管理员闸门），这三条随之回来。
        cases.put("send-system: convId 与 toUserId 同时缺失", () -> controller.sendSystem(
                new NuwaxImSandboxRequests.SendSystem(null, null, null, "text", textNode(), null, null, null)));
        cases.put("push-event: userIds 空且 convId 缺失（两个投递目标都没给）", () -> controller.pushEvent(
                new NuwaxImSandboxRequests.PushEvent(List.of(), null, "evt", Map.of(), null, null)));
        cases.put("push-event: eventType 缺失", () -> controller.pushEvent(
                new NuwaxImSandboxRequests.PushEvent(List.of(2L), null, "  ", Map.of(), null, null)));

        cases.forEach((name, runnable) -> assertThatThrownBy(runnable::run)
                .as("%s 应被本地拦下", name)
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).as("%s 的错误码", name).isEqualTo("4000")));

        verifyNoInteractions(messageRpc, conversationRpc, contactRpc, eventRpc);
    }

    @Test
    @DisplayName("★send-system：convId 与 toUserId 两种投递目标都能用，且 actor 一律取自登录态（管理员）")
    void sendSystemAcceptsEitherTarget() {
        loginAsAdmin(USER_ID, TENANT_ID);
        when(messageRpc.sendSystemMessage(any())).thenReturn(messageDto());

        // ① 群内发：给 convId
        controller.sendSystem(new NuwaxImSandboxRequests.SendSystem(
                7L, null, 3, "text", textNode(), null, "sys-key-1", null));
        ImSystemMessageRequest byConv = captureSystem();
        assertThat(byConv.convId()).isEqualTo(7L);
        assertThat(byConv.toUserId()).isNull();
        assertThat(byConv.userId()).as("actor 只能来自登录态").isEqualTo(USER_ID);
        assertThat(byConv.tenantId()).isEqualTo(TENANT_ID);
        assertThat(byConv.senderType()).as("senderType 原样透传，控制器不替调用方改").isEqualTo(3);

        // ② 单聊发：给 toUserId
        controller.sendSystem(new NuwaxImSandboxRequests.SendSystem(
                null, 2002L, 2, "text", textNode(), null, "sys-key-2", null));
        ImSystemMessageRequest byUser = captureSystem();
        assertThat(byUser.convId()).isNull();
        assertThat(byUser.toUserId()).isEqualTo(2002L);
        assertThat(byUser.senderType()).isEqualTo(2);
    }

    @Test
    @DisplayName("★send-system：senderType 缺省时不替调用方填值（让 IM 决定默认，避免两份默认值分叉）")
    void sendSystemLeavesSenderTypeToImWhenOmitted() {
        loginAsAdmin(USER_ID, TENANT_ID);
        when(messageRpc.sendSystemMessage(any())).thenReturn(messageDto());

        controller.sendSystem(new NuwaxImSandboxRequests.SendSystem(
                7L, null, null, "text", textNode(), null, null, null));

        assertThat(captureSystem().senderType()).isNull();
    }

    // ================================================================== 错误翻译

    @Test
    @DisplayName("★本地配置问题（isLocalProblem）→ 3612；绝不报成「IM 抖了，稍后重试」")
    void localProblemMapsToNotConfigured() {
        loginAs(USER_ID, TENANT_ID);
        when(messageRpc.sendP2pMessage(any()))
                .thenThrow(ImRpcException.notConfigured("im.adapter.secret 未配置"));

        assertThatThrownBy(() -> controller.sendP2p(new NuwaxImSandboxRequests.SendP2p(
                2002L, "text", textNode(), null, null, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    BizException biz = (BizException) e;
                    assertThat(biz.getCode()).isEqualTo("3612");
                    assertThat(biz.getMsgName()).isEqualTo("imRpcNotConfigured");
                });
    }

    @Test
    @DisplayName("★适配被显式关闭（disabled）同样是 3612 —— 它也是本地的、重试无用的")
    void disabledAdapterAlsoMapsToNotConfigured() {
        loginAs(USER_ID, TENANT_ID);
        when(messageRpc.sendP2pMessage(any())).thenThrow(ImRpcException.disabled());

        assertThatThrownBy(() -> controller.sendP2p(new NuwaxImSandboxRequests.SendP2p(
                2002L, "text", textNode(), null, null, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo("3612"));
    }

    @Test
    @DisplayName("★上游暂时性故障（isRetryable）→ 3613，文案里含「重试」")
    void retryableMapsToUnavailable() {
        loginAs(USER_ID, TENANT_ID);
        when(messageRpc.sendP2pMessage(any()))
                .thenThrow(ImRpcException.biz("IM_90002", null, "服务繁忙", "tid-1", 200));

        // ★只断言 code/msgName，【不】断言 message 文案：文案走 i18n，同一份代码在中文 locale 下
        //   出中文、在英文 locale 下出英文（CI 默认 en）。断言文案等于让测试随运行环境的 locale 变红绿。
        assertThatThrownBy(() -> controller.sendP2p(new NuwaxImSandboxRequests.SendP2p(
                2002L, "text", textNode(), null, null, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    BizException biz = (BizException) e;
                    assertThat(biz.getCode()).isEqualTo("3613");
                    assertThat(biz.getMsgName()).isEqualTo("imRpcUnavailable");
                });
    }

    @Test
    @DisplayName("★上游业务拒绝 → 3614，并把上游原文带上（重试无用，得改请求）")
    void businessRejectionMapsToRejectedWithUpstreamText() {
        loginAs(USER_ID, TENANT_ID);
        when(messageRpc.sendP2pMessage(any()))
                .thenThrow(ImRpcException.biz("IM_20101", "GROUP_FULL", "群成员已满", "tid-2", 200));

        assertThatThrownBy(() -> controller.sendP2p(new NuwaxImSandboxRequests.SendP2p(
                2002L, "text", textNode(), null, null, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    BizException biz = (BizException) e;
                    assertThat(biz.getCode()).isEqualTo("3614");
                    assertThat(biz.getMsgName()).isEqualTo("imRpcRejected");
                    assertThat(biz.getMessage()).contains("群成员已满");
                });
    }

    @Test
    @DisplayName("★★顺序陷阱：网络不可达（retryable 且码值同为 0001）必须落到 3613，不能被误判成 3612")
    void networkFailureIsNotReportedAsMisconfiguration() {
        loginAs(USER_ID, TENANT_ID);
        when(messageRpc.sendP2pMessage(any()))
                .thenThrow(ImRpcException.network("ConnectException", new java.net.ConnectException("refused")));

        // 3912 与 3613 的区别就是「谁该去看日志」：3612 是平台配置错了（IM 一条记录都没有），
        // 3613 是 IM 侧的故障（该去查 IM）。判反了，排查方向整个跑偏。
        assertThatThrownBy(() -> controller.sendP2p(new NuwaxImSandboxRequests.SendP2p(
                2002L, "text", textNode(), null, null, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getMsgName()).isEqualTo("imRpcUnavailable"));
    }

    @Test
    @DisplayName("★异常信息里不带 secret / 签名（BizException 最终会进响应体与日志）")
    void rejectionMessageDoesNotLeakCredentials() {
        loginAs(USER_ID, TENANT_ID);
        when(messageRpc.sendP2pMessage(any()))
                .thenThrow(ImRpcException.biz("IM_90004", null, "验签失败", null, 200));

        assertThatThrownBy(() -> controller.sendP2p(new NuwaxImSandboxRequests.SendP2p(
                2002L, "text", textNode(), null, null, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("secret", "Signature", "Bearer"));
    }

    // ================================================================== 覆盖所有端点的冒烟

    @Test
    @DisplayName("21 个端点都能正常返回 0000（防止某个端点的装配/署名写错）")
    void allEndpointsReturnSuccess() {
        // ★管理员 + 带 AK：send-system / system-notify / push-event 前置了 requireAdmin()
        //   （普通登录态会 4030），而 agent/pending/* 又要求 AK 携带智能体虚拟身份（缺了会 4030）
        //   —— 一条登录态同时满足两者，才跑得动全部 21 个端点。
        //   ★还带 imConvId=7：排队区两个端点还有闸门③（convId 必须 == 本次触发会话），
        //   缺了它会 4030（其它端点不看这个键，加它不影响它们）。
        loginAsAdminAk(USER_ID, TENANT_ID,
                Map.of("AGENT_VIRTUAL_USER_ID", VIRTUAL_USER_ID, "imConvId", 7L));
        when(messageRpc.sendP2pMessage(any())).thenReturn(messageDto());
        when(messageRpc.sendGroupMessage(any())).thenReturn(messageDto());
        when(messageRpc.sendCardMessage(any())).thenReturn(messageDto());
        when(messageRpc.getMessage(any())).thenReturn(detailDto());
        when(messageRpc.getReadCounts(any())).thenReturn(List.of(readCountItem()));
        when(messageRpc.listMessagesByTimeRange(any())).thenReturn(pageDto());
        when(conversationRpc.createGroup(any())).thenReturn(new ImConversationDto(
                7L, 2, "群", null, USER_ID, 1, 2000, true, 1L, false));
        when(conversationRpc.addMembers(any())).thenReturn(memberAddResultDto());
        when(conversationRpc.listMembers(any())).thenReturn(new MemberPageDto(1, false, List.of()));
        when(conversationRpc.getOrCreateP2p(any())).thenReturn(new ImConversationDto(
                7L, 1, null, null, USER_ID, 2, 2000, true, 1L, false));
        when(conversationRpc.listConversations(any())).thenReturn(new ImConversationPageDto(List.of(), false, null));
        when(conversationRpc.getConversationDetail(any())).thenReturn(conversationDetailDto());
        when(contactRpc.listContacts(any())).thenReturn(new ImContactListDto(List.of(), false, false));
        when(messageRpc.sendSystemMessage(any())).thenReturn(messageDto());
        when(messageRpc.sendSystemNotify(any())).thenReturn(systemNotifyResultDto());
        when(eventRpc.pushCustomEvent(any())).thenReturn(new ImCustomEventPushResultDto(1, 1, 1));
        when(agentRpc.listPending(any())).thenReturn(new ImAgentPendingListDto(List.of(), 0));
        when(agentRpc.clearPending(any())).thenReturn(new ImAgentPendingClearDto(1, List.of(9001L)));

        JsonNode body = textNode();
        String[] codes = {
                controller.sendP2p(new NuwaxImSandboxRequests.SendP2p(2L, "text", body, null, null, null)).getCode(),
                controller.sendGroup(new NuwaxImSandboxRequests.SendGroup(
                        7L, "text", body, null, null, null, null, null, null)).getCode(),
                controller.sendSystem(new NuwaxImSandboxRequests.SendSystem(
                        7L, null, null, "text", body, null, null, null)).getCode(),
                controller.sendSystemNotify(new NuwaxImSandboxRequests.SystemNotify(
                        List.of(2L, 3L), null, body, null, null, null)).getCode(),
                controller.sendCard(new NuwaxImSandboxRequests.SendCard(
                        7L, new com.xspaceagi.im.sdk.rpc.dto.ImCardBody("t", "d", null, null, null, null, null),
                        null, null, null)).getCode(),
                controller.revoke(new NuwaxImSandboxRequests.Revoke(7L, 9001L, null, null, null)).getCode(),
                controller.getMessage(new NuwaxImSandboxRequests.GetMessage(7L, 9001L, null)).getCode(),
                controller.readCounts(new NuwaxImSandboxRequests.ReadCounts(7L, List.of(9001L), null)).getCode(),
                controller.listMessages(new NuwaxImSandboxRequests.ListMessages(
                        7L, null, null, null, 20, null)).getCode(),
                controller.createGroup(new NuwaxImSandboxRequests.CreateGroup(
                        "群", null, null, null, null, null, null, null)).getCode(),
                controller.addMembers(new NuwaxImSandboxRequests.AddMembers(
                        7L, List.of(2L), null, null, null, null)).getCode(),
                controller.removeMembers(new NuwaxImSandboxRequests.RemoveMembers(
                        7L, List.of(2L), null, null, null)).getCode(),
                controller.listMembers(new NuwaxImSandboxRequests.ListMembers(7L, 10, null)).getCode(),
                controller.getOrCreateP2p(new NuwaxImSandboxRequests.GetOrCreateP2p(2L, null, null)).getCode(),
                controller.updateDigest(new NuwaxImSandboxRequests.UpdateDigest(7L, "d", null, null)).getCode(),
                controller.listConversations(new NuwaxImSandboxRequests.ListConversations(null, 20, null)).getCode(),
                controller.getConversationDetail(new NuwaxImSandboxRequests.GetConversationDetail(7L, null)).getCode(),
                controller.listContacts(new NuwaxImSandboxRequests.ListContacts(null)).getCode(),
                controller.pushEvent(new NuwaxImSandboxRequests.PushEvent(
                        List.of(2L), null, "evt", Map.of("k", "v"), null, null)).getCode(),
                controller.listPending(new NuwaxImSandboxRequests.PendingList(7L, null)).getCode(),
                controller.clearPending(new NuwaxImSandboxRequests.PendingClear(
                        7L, List.of(9001L), null, null)).getCode()
                };

        assertThat(codes).hasSize(21).allSatisfy(code -> assertThat(code).isEqualTo("0000"));
    }

    // ================================================================== 排队区（★DEV-285）

    @Test
    @DisplayName("★★排队区两个端点：actor 恒为智能体虚拟身份（不是登录用户），且 convId 原样透传")
    void pendingEndpointsSpeakAsTheAgentSubject() {
        // ★故意让「登录用户 ≠ 虚拟身份」，否则这条测试分不出用的是哪一个。
        // ★imConvId = 7 = 请求里的 convId：闸门③要求两者相等（见 pendingConvMustBeTheTriggerConv）。
        loginAsAk(USER_ID, TENANT_ID,
                Map.of("AGENT_VIRTUAL_USER_ID", VIRTUAL_USER_ID, "imConvId", 7L));
        when(agentRpc.listPending(any())).thenReturn(new ImAgentPendingListDto(List.of(), 0));
        when(agentRpc.clearPending(any())).thenReturn(new ImAgentPendingClearDto(0, List.of()));

        controller.listPending(new NuwaxImSandboxRequests.PendingList(7L, "trace-1"));
        ImAgentPendingListRequest listReq = (ImAgentPendingListRequest) lastArg(agentRpc);
        assertThat(listReq.userId()).as("actor 必须是智能体虚拟身份").isEqualTo(VIRTUAL_USER_ID);
        assertThat(listReq.userId()).as("绝不能是登录用户本人").isNotEqualTo(USER_ID);
        assertThat(listReq.tenantId()).isEqualTo(TENANT_ID);
        assertThat(listReq.convId()).isEqualTo(7L);
        assertThat(listReq.traceId()).isEqualTo("trace-1");
        assertThat(listReq.bizMsgId()).as("读操作免幂等键").isNull();

        controller.clearPending(new NuwaxImSandboxRequests.PendingClear(7L, List.of(9001L), "biz-1", null));
        ImAgentPendingClearRequest clearReq = (ImAgentPendingClearRequest) lastArg(agentRpc);
        assertThat(clearReq.userId()).isEqualTo(VIRTUAL_USER_ID);
        assertThat(clearReq.convId()).isEqualTo(7L);
        assertThat(clearReq.msgIds()).as("★必须点名清除，不能整队清空").containsExactly(9001L);
        assertThat(clearReq.bizMsgId()).as("写操作必须带幂等键").isEqualTo("biz-1");
    }

    @Test
    @DisplayName("★★排队区两个端点：AK 未携带智能体虚拟身份 → 4030 且一个 RPC 都不发（不回退本人）")
    void pendingEndpointsFailClosedWithoutAgentIdentity() {
        // ★这是本文件里唯一【拒绝】以登录用户身份行使的一组端点：IM 侧只认已登记的智能体主体。
        //   若这里像 scenarioActorUserId 那样静默回退本人，会变成一个含糊的远端错误 + 一次白跑的 RPC。
        loginAsAk(USER_ID, TENANT_ID, Map.of());

        assertThatThrownBy(() -> controller.listPending(new NuwaxImSandboxRequests.PendingList(7L, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    BizException biz = (BizException) e;
                    assertThat(biz.getCode()).isEqualTo("4030");
                    assertThat(biz.getMsgName()).isEqualTo("validationFailedWithDetail");
                });
        assertThatThrownBy(() -> controller.clearPending(
                new NuwaxImSandboxRequests.PendingClear(7L, List.of(9001L), null, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo("4030"));

        // ★登录态直连（没有 AK ⇒ 必然读不到虚拟身份）同样拒 —— 它拿不出智能体主体身份。
        RequestContext.remove();
        loginAs(USER_ID, TENANT_ID);
        assertThatThrownBy(() -> controller.listPending(new NuwaxImSandboxRequests.PendingList(7L, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo("4030"));

        verifyNoInteractions(agentRpc);
    }

    @Test
    @DisplayName("★★clear 必须点名 msgIds：空 / 缺失一律本地 4000，绝不把「删 0 条」当成清干净")
    void pendingClearRequiresExplicitMsgIds() {
        // ★IM 侧对空 msgIds 是「删 0 条」的成功返回，而那与「确实清干净了」在响应上长得一模一样。
        //   一旦有人把参数名拼错，调用方就会以为自己消费完了 ⇒ 静默丢消息。
        //   ★带 imConvId 是为了让请求能走到 msgIds 那道校验 —— 否则会先被闸门③拦成 4030，
        //   这条测试就测不到它本来要测的东西（本地 4000）。
        loginAsAk(USER_ID, TENANT_ID,
                Map.of("AGENT_VIRTUAL_USER_ID", VIRTUAL_USER_ID, "imConvId", 7L));

        assertThatThrownBy(() -> controller.clearPending(
                new NuwaxImSandboxRequests.PendingClear(7L, null, null, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    BizException biz = (BizException) e;
                    assertThat(biz.getCode()).isEqualTo("4000");
                    assertThat(biz.getMsgName()).isEqualTo("fieldRequiredButEmpty");
                });
        assertThatThrownBy(() -> controller.clearPending(
                new NuwaxImSandboxRequests.PendingClear(7L, List.of(), null, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo("4000"));

        verifyNoInteractions(agentRpc);
    }

    @Test
    @DisplayName("★clear 是写操作 ⇒ 包 NuwaxImWriteResult 并回显 bizMsgId（调用方据此安全重试）")
    void pendingClearEchoesBizMsgIdForSafeRetry() {
        loginAsAk(USER_ID, TENANT_ID,
                Map.of("AGENT_VIRTUAL_USER_ID", VIRTUAL_USER_ID, "imConvId", 7L));
        when(agentRpc.clearPending(any())).thenReturn(new ImAgentPendingClearDto(1, List.of(9001L)));

        NuwaxImWriteResult<ImAgentPendingClearDto> result = controller.clearPending(
                new NuwaxImSandboxRequests.PendingClear(7L, List.of(9001L), null, null)).getData();

        assertThat(result.bizMsgId()).as("调用方没传时要生成并回显").isNotBlank();
        assertThat(result.data().removedMsgIdsOrEmpty()).containsExactly(9001L);
    }

    // ================================================================== 排队区 · 混乱代理闸门③

    @Test
    @DisplayName("★★闸门③：请求的 convId ≠ 本次触发会话 → 4030，且一个 RPC 都不发")
    void pendingConvMustBeTheTriggerConv() {
        // ★这条钉的是混乱代理的形态：智能体同时是群 A、群 B 的成员。一次【群 A 触发】的运行
        //   拿到的 AK 里 imConvId = 群 A，若允许它传 convId = 群 B：
        //     · list  → 读到群 B 的人发给智能体的消息；
        //     · clear → 声明「群 B 那批我已消费」⇒ 群 B 的消息不会再被处理，用户发了没回音。
        //   IM 侧对这两个端点只校验「userId 是已登记的智能体主体」，不校验成员资格，拦不住。
        loginAsAk(USER_ID, TENANT_ID,
                Map.of("AGENT_VIRTUAL_USER_ID", VIRTUAL_USER_ID, "imConvId", 7L));

        assertThatThrownBy(() -> controller.listPending(new NuwaxImSandboxRequests.PendingList(9L, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    BizException biz = (BizException) e;
                    assertThat(biz.getCode()).isEqualTo("4030");
                    assertThat(biz.getMsgName()).isEqualTo("validationFailedWithDetail");
                    // ★报错里要能看出「该往哪个会话发」——排查时最需要的就是这两个数
                    assertThat(biz.getMessage()).contains("7").contains("9");
                });
        assertThatThrownBy(() -> controller.clearPending(
                new NuwaxImSandboxRequests.PendingClear(9L, List.of(9001L), null, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo("4030"));

        verifyNoInteractions(agentRpc);
    }

    @Test
    @DisplayName("★★闸门③：带智能体身份却读不到 imConvId → 4030（fail-closed，不放行）")
    void pendingConvMissingTriggerConvFailsClosed() {
        // ★imConvId 由 IM 在群 / 私聊【两个分支的公共段】无条件 put ⇒「带着智能体虚拟身份却读不到它」
        //   不是一种合法的版本形态。若这里改成「读不到就当本次触发会话」，删掉一个键即可绕过整道闸门。
        loginAsAk(USER_ID, TENANT_ID, Map.of("AGENT_VIRTUAL_USER_ID", VIRTUAL_USER_ID));

        assertThatThrownBy(() -> controller.listPending(new NuwaxImSandboxRequests.PendingList(7L, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo("4030"));
        assertThatThrownBy(() -> controller.clearPending(
                new NuwaxImSandboxRequests.PendingClear(7L, List.of(9001L), null, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo("4030"));

        verifyNoInteractions(agentRpc);
    }

    @Test
    @DisplayName("★闸门③的取值按 JSON 反序列化形态读：imConvId 是字符串 / 与 convId 相等都能放行")
    void pendingConvAcceptsJsonStringShape() {
        // ★AK 的 ext 来自 JSON（IM 的 variableParams 原样透传）⇒ 数字可能是 Long、也可能是 String，
        //   两种都得认；认不出的（这里是 "not-a-number"）按读不到处理 → fail-closed。
        loginAsAk(USER_ID, TENANT_ID,
                Map.of("AGENT_VIRTUAL_USER_ID", VIRTUAL_USER_ID, "imConvId", "7"));
        when(agentRpc.listPending(any())).thenReturn(new ImAgentPendingListDto(List.of(), 0));

        assertThat(controller.listPending(new NuwaxImSandboxRequests.PendingList(7L, null)).getCode())
                .isEqualTo("0000");

        RequestContext.remove();
        loginAsAk(USER_ID, TENANT_ID,
                Map.of("AGENT_VIRTUAL_USER_ID", VIRTUAL_USER_ID, "imConvId", "not-a-number"));
        assertThatThrownBy(() -> controller.listPending(new NuwaxImSandboxRequests.PendingList(7L, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo("4030"));
    }

    @Test
    @DisplayName("★闸门③：私聊场景同样生效（imConvId 有值 ≠ 群场景）")
    void pendingConvAlsoAppliesToP2pScenario() {
        // ★这是闸门③与闸门①②的关键差别：那两道只对群场景生效，这一道两个场景都收窄 ——
        //   私聊里同样能读 / 清「智能体与另一个人的单聊」队列。
        //   这里 ext 没有群专属键（不是群场景），但请求的 convId 与 imConvId 不一致 → 照样拒。
        loginAsAk(USER_ID, TENANT_ID, Map.of(
                "AGENT_VIRTUAL_USER_ID", VIRTUAL_USER_ID,
                "IS_GROUP_MESSAGE", false,
                "imConvId", 11L));

        assertThatThrownBy(() -> controller.listPending(new NuwaxImSandboxRequests.PendingList(12L, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo("4030"));

        verifyNoInteractions(agentRpc);
    }

    // ================================================================== 读类 · 混乱代理闸门④

    /**
     * ★★闸门④：群场景下请求的 {@code convId} 必须等于本次触发会话 → 不等 4030，且一个 RPC 都不发。
     *
     * <p>钉的是这条链路：甲在群 A 里 @ 智能体（<b>甲不在群 B</b>）。群场景下 actor 是
     * <b>智能体虚拟身份</b>，而 IM 对按 convId 定位的读端点只判
     * {@code requireActiveMember(convId, 智能体)} —— 判的是「<b>智能体</b>在不在群 B」，
     * <b>不是「甲在不在群 B」</b> ⇒ 智能体先以自己的名义 {@code /conversation/list} 枚举出群 B，
     * 再把群 B 的消息读走、在群 A 里<b>转述</b>出来，群 A 全体成员（含不在群 B 的人）都看得到。
     * 泄漏形状是<b>权限放大</b>，受害方是<b>群 B 的成员</b>。
     */
    @Test
    @DisplayName("★★闸门④：群场景下读端点请求别的会话 → 4030，且一个 RPC 都不发")
    void groupReadConvMustBeTheTriggerConv() {
        loginAsAk(USER_ID, TENANT_ID, Map.of(
                "GROUP_VIRTUAL_USER_ID", 660001L,
                "AGENT_VIRTUAL_USER_ID", VIRTUAL_USER_ID,
                "imConvId", 7L));   // 本次运行发生在会话 7

        // ★五个读端点逐个过。漏挂任何一个，那个端点就是一条完整的跨群读取通道 ——
        //   而「文档里写着有、真调能读到别的群」正是最难被发现的那种缺口。
        List<org.assertj.core.api.ThrowableAssert.ThrowingCallable> reads = List.of(
                () -> controller.getMessage(new NuwaxImSandboxRequests.GetMessage(9L, 9001L, null)),
                () -> controller.readCounts(new NuwaxImSandboxRequests.ReadCounts(9L, List.of(9001L), null)),
                () -> controller.listMessages(
                        new NuwaxImSandboxRequests.ListMessages(9L, null, null, null, 20, null)),
                () -> controller.listMembers(new NuwaxImSandboxRequests.ListMembers(9L, 10, null)),
                () -> controller.getConversationDetail(
                        new NuwaxImSandboxRequests.GetConversationDetail(9L, null)));

        for (var read : reads) {
            assertThatThrownBy(read)
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> {
                        BizException biz = (BizException) e;
                        assertThat(biz.getCode()).isEqualTo("4030");
                        assertThat(biz.getMsgName()).isEqualTo("validationFailedWithDetail");
                        // ★两个 convId 都要出现在报错里：排查时最需要的就是「该读哪个」
                        assertThat(biz.getMessage()).contains("7").contains("9");
                    });
        }
        verifyNoInteractions(messageRpc, conversationRpc);
    }

    @Test
    @DisplayName("★★闸门④：群场景却读不到 imConvId → 4030（fail-closed，不放行）")
    void groupReadConvMissingTriggerConvFailsClosed() {
        // ★imConvId 由 IM 在群 / 私聊【两个分支的公共段】无条件 put ⇒「带着群虚拟键却读不到它」
        //   不是一种合法的版本形态。若这里改成「读不到就当本次触发会话」，删掉一个键即可绕过整道闸门。
        loginAsAk(USER_ID, TENANT_ID, Map.of(
                "GROUP_VIRTUAL_USER_ID", 660001L,
                "AGENT_VIRTUAL_USER_ID", VIRTUAL_USER_ID));

        assertThatThrownBy(() -> controller.listMessages(
                new NuwaxImSandboxRequests.ListMessages(7L, null, null, null, 20, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo("4030"));

        verifyNoInteractions(messageRpc);
    }

    @Test
    @DisplayName("★闸门④的取值按 JSON 反序列化形态读：imConvId 是字符串认，认不出的按读不到 fail-closed")
    void groupReadConvAcceptsJsonStringShape() {
        // ★AK 的 ext 来自 JSON（IM 的 variableParams 原样透传）⇒ 数字可能是 Long、也可能是 String，两种都得认。
        loginAsAk(USER_ID, TENANT_ID, Map.of(
                "GROUP_VIRTUAL_USER_ID", 660001L,
                "AGENT_VIRTUAL_USER_ID", VIRTUAL_USER_ID,
                "imConvId", "7"));
        when(messageRpc.listMessagesByTimeRange(any())).thenReturn(pageDto());

        assertThat(controller.listMessages(
                new NuwaxImSandboxRequests.ListMessages(7L, null, null, null, 20, null)).getCode())
                .isEqualTo("0000");

        // ★闸门④只管「读哪个会话」，不改 actor —— 放行时行使身份仍是智能体虚拟身份
        ArgumentCaptor<ImMessageListRequest> sent = ArgumentCaptor.forClass(ImMessageListRequest.class);
        org.mockito.Mockito.verify(messageRpc).listMessagesByTimeRange(sent.capture());
        assertThat(sent.getValue().userId()).isEqualTo(VIRTUAL_USER_ID);
        assertThat(sent.getValue().convId()).isEqualTo(7L);

        RequestContext.remove();
        loginAsAk(USER_ID, TENANT_ID, Map.of(
                "GROUP_VIRTUAL_USER_ID", 660001L,
                "AGENT_VIRTUAL_USER_ID", VIRTUAL_USER_ID,
                "imConvId", "not-a-number"));
        assertThatThrownBy(() -> controller.listMessages(
                new NuwaxImSandboxRequests.ListMessages(7L, null, null, null, 20, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo("4030"));
    }

    @Test
    @DisplayName("★★闸门④只在群场景生效：私聊场景 convId ≠ imConvId 照样放行（与闸门③的关键差别）")
    void readGateDoesNotApplyToP2pScenario() {
        // ★判据与 scenarioActorUserId 逐字对齐（只看 GROUP_VIRTUAL_USER_ID 单键）。
        //   私聊场景 actor 是【本人】⇒ IM 那道 requireActiveMember(convId, 本人) 判的正是
        //   「驱动者在不在」，本来就是对的边界；这里再钉只会砍掉
        //   「用户在私聊里让智能体读他自己有权限的群」这类正当用法。
        //   ★闸门③不同（它两个场景都收窄）—— 所以这里的「私聊不钉」不是漏了，
        //   而是本闸门立论（只有 actor 被换掉才需要钉）的直接推论。
        //   谁把判据改成 isGroupScenario 那个超集，改不到这条；谁把它改回"不分场景"，这条立刻变红。
        loginAsAk(USER_ID, TENANT_ID, Map.of(
                "AGENT_VIRTUAL_USER_ID", VIRTUAL_USER_ID,
                "IS_GROUP_MESSAGE", false,
                "imConvId", 7L));
        when(messageRpc.listMessagesByTimeRange(any())).thenReturn(pageDto());

        assertThat(controller.listMessages(
                new NuwaxImSandboxRequests.ListMessages(9L, null, null, null, 20, null)).getCode())
                .isEqualTo("0000");
    }

    @Test
    @DisplayName("★★写类端点的口径已翻转：members/add 在群场景下一律 4030（旧口径「跨会话破坏面不收窄」作废，2026-10-05 裁定）")
    void writeEndpointsAreNowCoveredByTheManagementGate() {
        // ★本用例原先断言的是「闸门④【不】覆盖写类端点 —— members/add 指向别的会话照样放行」。
        //   那条「跨会话破坏面未收窄」的旧口径已被用户 2026-10-05 的裁定推翻
        //   （原话：「/conversation/create-group、members/add、members/remove、p2p、digest
        //   以上接口不支持在群里操作」）⇒ 现在反过来钉住新口径。
        // ★拒它的【不是】闸门④（那是读类、判据还要连 convId 一起比），而是闸门⑥
        //   （群场景整条拒，与⑤同判据同出口）。两条腿分开留痕：将来有人把写类挂到④上，
        //   这里的行为差异仍可分辨（④会放过「convId == 触发会话」的那一次，⑥不会）。
        loginAsAk(USER_ID, TENANT_ID, Map.of(
                "GROUP_VIRTUAL_USER_ID", 660001L,
                "AGENT_VIRTUAL_USER_ID", VIRTUAL_USER_ID,
                "imConvId", 7L));
        when(conversationRpc.addMembers(any())).thenReturn(memberAddResultDto());

        assertThatThrownBy(() -> controller.addMembers(
                new NuwaxImSandboxRequests.AddMembers(9L, List.of(2L), null, null, null, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    assertThat(((BizException) e).getCode()).isEqualTo("4030");
                    assertThat(((BizException) e).getMessage()).contains("/conversation/members/add");
                });
        verifyNoInteractions(conversationRpc);
    }

    // ================================================================== 读类 · 混乱代理闸门⑤

    /**
     * ★★闸门⑤：群场景下，两个「列举<b>智能体自己的</b>资源」的端点
     * （{@code /conversation/list}、{@code /contact/list}）一律 {@code 4030}。
     *
     * <p>它们<b>没有 {@code convId} 参数</b>，返回什么完全由 actor 决定 —— 而群场景下
     * actor 是<b>智能体虚拟身份</b> ⇒ 返回的是<b>智能体自己</b>的会话列表 / 通讯录：
     * 它在哪些群里、跟谁有私聊、每个会话叫什么 / 多少人，以及 {@code lastMsgDigest}
     * （<b>末条消息摘要</b>，带发送者显示名与正文，非文本才退化成 {@code [图片]}）。
     * 这些在群里一说，<b>群全体成员</b>都看得到，包括跟这个智能体从无往来的人。
     *
     * <p>★泄漏形状与闸门④<b>不同</b>：④读的是「别人（群 B）的资源」，受害方是<b>群 B 的成员</b>；
     * ⑤读的是「智能体自己的私产」，受害方是<b>跟它有过往来的所有人</b>（联系人与会话对端）。
     * 闸门④的形态（钉死请求里的 {@code convId}）对⑤<b>结构上无从下手</b> —— 所以只能整条拒，
     * 而不是收窄。这也是⑤存在的唯一理由。
     */
    @Test
    @DisplayName("★★闸门⑤：群场景下 /conversation/list 与 /contact/list 一律 4030，且一个 RPC 都不发")
    void groupSelfResourceEndpointsAreRejectedInGroupScenario() {
        loginAsAk(USER_ID, TENANT_ID, Map.of(
                "GROUP_VIRTUAL_USER_ID", 660001L,
                "AGENT_VIRTUAL_USER_ID", VIRTUAL_USER_ID,
                "imConvId", 7L));

        // ★两个端点逐个过。漏挂任何一个，那个端点就是一条完整的「智能体私产外泄」通道 ——
        //   而「文档里写着群场景不可用、真调却能列出它所有群」正是最难被发现的那种缺口。
        List<org.assertj.core.api.ThrowableAssert.ThrowingCallable> calls = List.of(
                () -> controller.listConversations(new NuwaxImSandboxRequests.ListConversations(null, 20, null)),
                () -> controller.listContacts(new NuwaxImSandboxRequests.ListContacts(null)));

        for (var call : calls) {
            assertThatThrownBy(call)
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> {
                        BizException biz = (BizException) e;
                        assertThat(biz.getCode()).isEqualTo("4030");
                        assertThat(biz.getMsgName()).isEqualTo("validationFailedWithDetail");
                        // ★报错必须回显【是哪个端点】被拒 —— 这两个端点的正确用法不同，
                        //   只说「群场景不允许」调用方不知道该改哪一处
                        assertThat(biz.getMessage()).contains("群场景");
                    });
        }
        verifyNoInteractions(conversationRpc, contactRpc);
    }

    @Test
    @DisplayName("★★闸门⑤只在群场景生效：私聊场景与登录态直连照常返回，且 actor 是【驱动者本人】")
    void selfResourceGateDoesNotApplyOutsideGroupScenario() throws Exception {
        // ★判据与闸门④逐字相同（只看 GROUP_VIRTUAL_USER_ID 单键）。私聊场景 actor 是【本人】
        //   ⇒ 返回的恰好是驱动者自己在 IM 界面上看得到的那一块，不构成越权；
        //   在这里再拒只会砍掉正当用法（技能文档里「先 conversation-list 找到那个群」正是这条腿）。
        loginAsAk(USER_ID, TENANT_ID, Map.of(
                "AGENT_VIRTUAL_USER_ID", VIRTUAL_USER_ID,
                "IS_GROUP_MESSAGE", false,
                "imConvId", 7L));
        when(conversationRpc.listConversations(any())).thenReturn(new ImConversationPageDto(List.of(), false, null));
        when(contactRpc.listContacts(any())).thenReturn(new ImContactListDto(List.of(), false, false));

        assertThat(controller.listConversations(
                new NuwaxImSandboxRequests.ListConversations(null, 20, null)).getCode()).isEqualTo("0000");
        assertActorIsTheUser("私聊场景的 /conversation/list", lastArg(conversationRpc));
        assertThat(controller.listContacts(
                new NuwaxImSandboxRequests.ListContacts(null)).getCode()).isEqualTo("0000");
        assertActorIsTheUser("私聊场景的 /contact/list", lastArg(contactRpc));

        // 登录态直连（没有 AK）同样放行 —— actor 也是本人，IM 那道成员闸门判的正是他。
        // ★这条还钉住调用顺序：闸门⑤在登录态检查【之前】跑，但它对「没有 AK」直接放行，
        //   所以不会把 4010 吞成 4030（见 currentAccessKey 的 javadoc）。
        RequestContext.remove();
        loginAs(USER_ID, TENANT_ID);
        assertThat(controller.listConversations(
                new NuwaxImSandboxRequests.ListConversations(null, 20, null)).getCode()).isEqualTo("0000");
        assertActorIsTheUser("登录态下的 /conversation/list", lastArg(conversationRpc));
    }

    @Test
    @DisplayName("★闸门⑤判据是【群虚拟键单键】，不是 isGroupScenario 那个超集：只有 GROUP_CONV_ID 时放行")
    void selfResourceGateUsesSingleKeyNotTheSuperset() {
        // ★这条固定的是「闸门④⑤共用同一个判据」这个设计：需要钉死的充要条件是
        //   「actor 被换成了智能体」，而这件事只由 GROUP_VIRTUAL_USER_ID 一个键决定。
        //   谁把判据改成 isGroupScenario（群虚拟键【或】convId 键，见该方法 javadoc），这条立刻变红
        //   —— 那种形态下 actor 会回退成群代理，本来就过不了下游，钉与不钉结果一样，
        //   而“钉了但 actor 没换”正是判据漂移的定义。
        loginAsAk(USER_ID, TENANT_ID, Map.of(
                "GROUP_CONV_ID", 7L,
                "imConvId", 7L));
        when(conversationRpc.listConversations(any())).thenReturn(new ImConversationPageDto(List.of(), false, null));

        assertThat(controller.listConversations(
                new NuwaxImSandboxRequests.ListConversations(null, 20, null)).getCode())
                .as("★与闸门④同判据：只看 GROUP_VIRTUAL_USER_ID，不看 GROUP_CONV_ID")
                .isEqualTo("0000");
    }

    // ================================================================== 会话管理 · 混乱代理闸门⑥

    /**
     * ★★闸门⑥：群场景下 5 个「以智能体虚拟身份改动会话构成 / 归属」的端点一律 {@code 4030}
     * （{@code create-group}、{@code members/add}、{@code members/remove}、{@code p2p}、
     * {@code digest}）。
     *
     * <p>防的不是「读走什么」，而是「<b>把权限本身变大</b>」：前五道闸门都建立在
     * 「智能体的可达集合是既定的」这个前提上，而建群 / 拉人 / 开单聊会<b>持久地</b>往这个集合里加东西
     * —— 它们记在智能体名下，之后闸门①③④⑤面对的就已经是扩大后的集合了。
     * 其中「<b>拉人进群 = 让这个人从此看到该群的全部内容</b>」本身就是一条独立的泄漏路
     * （不是读、也不是破坏，是<b>开门</b>）。
     *
     * <p>口径（用户 2026-10-05 裁定）：<b>这些接口不支持在群里操作。</b>
     */
    @Test
    @DisplayName("★★闸门⑥：群场景下建群 / 加人 / 移人 / 开单聊 / 改摘要一律 4030，且一个 RPC 都不发")
    void conversationManagementEndpointsAreRejectedInGroupScenario() {
        loginAsAk(USER_ID, TENANT_ID, Map.of(
                "GROUP_VIRTUAL_USER_ID", 660001L,
                "AGENT_VIRTUAL_USER_ID", VIRTUAL_USER_ID,
                "imConvId", 7L));

        // ★五个端点逐个过。漏挂任何一个，那个端点就是一条完整的「借智能体之手改会话」通道。
        List<org.assertj.core.api.ThrowableAssert.ThrowingCallable> calls = List.of(
                () -> controller.createGroup(new NuwaxImSandboxRequests.CreateGroup(
                        "新群", null, null, null, null, null, null, null)),
                () -> controller.addMembers(new NuwaxImSandboxRequests.AddMembers(
                        9L, List.of(2L), null, null, null, null)),
                () -> controller.removeMembers(new NuwaxImSandboxRequests.RemoveMembers(
                        9L, List.of(2L), null, null, null)),
                () -> controller.getOrCreateP2p(new NuwaxImSandboxRequests.GetOrCreateP2p(
                        2L, null, null)),
                () -> controller.updateDigest(new NuwaxImSandboxRequests.UpdateDigest(
                        9L, "改了的摘要", null, null)));

        for (var call : calls) {
            assertThatThrownBy(call)
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> {
                        BizException biz = (BizException) e;
                        assertThat(biz.getCode()).isEqualTo("4030");
                        assertThat(biz.getMsgName()).isEqualTo("validationFailedWithDetail");
                        // ★报错必须回显【是哪个端点】被拒 —— 这五个端点的正确用法各不相同
                        assertThat(biz.getMessage()).contains("群场景").contains("/conversation/");
                    });
        }
        verifyNoInteractions(conversationRpc, messageRpc, contactRpc, eventRpc);
    }

    @Test
    @DisplayName("★★闸门⑥只在群场景生效：私聊场景下加人 / 改摘要照常放行，且 actor 是【驱动者本人】")
    void conversationManagementGateDoesNotApplyOutsideGroupScenario() throws Exception {
        // ★判据与闸门④⑤逐字相同（只看 GROUP_VIRTUAL_USER_ID 单键）。私聊场景 actor 是【本人】
        //   ⇒ 建群 / 拉人 / 踢人 / 开单聊全在他自己的权限之内；在这里再拒只会砍掉正当能力。
        loginAsAk(USER_ID, TENANT_ID, Map.of("AGENT_VIRTUAL_USER_ID", VIRTUAL_USER_ID));
        when(conversationRpc.addMembers(any())).thenReturn(memberAddResultDto());

        assertThat(controller.addMembers(
                new NuwaxImSandboxRequests.AddMembers(9L, List.of(2L), null, null, null, null)).getCode())
                .isEqualTo("0000");
        assertActorIsTheUser("私聊场景的 /conversation/members/add", lastArg(conversationRpc));

        assertThat(controller.updateDigest(
                new NuwaxImSandboxRequests.UpdateDigest(9L, "摘要", null, null)).getCode())
                .isEqualTo("0000");
        assertActorIsTheUser("私聊场景的 /conversation/digest", lastArg(conversationRpc));
    }

    @Test
    @DisplayName("★闸门⑥判据与④⑤同：只有 GROUP_CONV_ID 时放行（不是 isGroupScenario 那个超集）")
    void conversationManagementGateUsesSingleKeyNotTheSuperset() {
        // ★④⑤⑥三条腿服务的是同一个充要条件「actor 被换成了智能体」，判据必须永远同步
        //   （抽在 isGroupActorScenario）。谁把它改成 isGroupScenario 那个超集，这条立刻变红。
        loginAsAk(USER_ID, TENANT_ID, Map.of(
                "GROUP_CONV_ID", 7L,
                "imConvId", 7L));

        assertThat(controller.updateDigest(
                new NuwaxImSandboxRequests.UpdateDigest(9L, "摘要", null, null)).getCode())
                .as("★与闸门④⑤同判据：只看 GROUP_VIRTUAL_USER_ID，不看 GROUP_CONV_ID")
                .isEqualTo("0000");
    }

    // ================================================================== 系统通知（★DEV-291）

    /**
     * ★★actor 恒为「当前登录人」，<b>不</b>走场景分流 —— 这是本端点与其余所有端点唯一不同的身份口径。
     *
     * <p>其余端点的 actor 回答的是「以谁的身份<b>发言</b>」（本人 / 智能体虚拟身份 / 群代理，
     * 按场景分流）；本端点的发送身份由 IM 固定成合成账号「系统通知」，{@code userId}
     * 只作<b>审计归属</b>与租户上下文来源 ⇒ 它回答的是「<b>谁调的</b>」。
     *
     * <p>★这张 AK 刻意配成<b>最容易被误切的那一档</b>：同时带群虚拟键、群 convId 键与智能体虚拟键，
     * 即其余每个端点都会在此切走身份的那个形态（见 {@code scenarioActorUserId}）。
     * 三条"顺手统一"的改法都会被这一条钉住：
     * <ul>
     *   <li>换成 {@code scenarioActorUserId} ⇒ 群场景下 actor 变成<b>群代理</b>；</li>
     *   <li>换成 {@code agentActorUserId} ⇒ actor 变成<b>智能体虚拟身份</b>；</li>
     *   <li>换成 {@code agentSubjectUserId} ⇒ 直接本地 4030（这张 AK 带虚拟身份，仍会走通，
     *       但审计里记的就不再是调用者）。</li>
     * </ul>
     * 三种情况下审计日志记的都<b>不是</b>「谁点的这条通知」—— 而审计正是这个字段在这里的唯一用途。
     */
    @Test
    @DisplayName("★★系统通知：群场景下 actor 仍是登录人本人（不是群代理 / 虚拟身份），批次与 tenantId 原样透传")
    void systemNotifyActorsTheCallerNotTheVirtualIdentity() throws Exception {
        loginAsAdminAk(USER_ID, TENANT_ID, Map.of(
                "GROUP_VIRTUAL_USER_ID", 660001L,
                "GROUP_CONV_ID", 7L,
                "AGENT_VIRTUAL_USER_ID", VIRTUAL_USER_ID));
        when(messageRpc.sendSystemNotify(any())).thenReturn(systemNotifyResultDto());

        NuwaxImWriteResult<ImSystemNotifyResultDto> result = controller.sendSystemNotify(
                new NuwaxImSandboxRequests.SystemNotify(
                        List.of(2002L, 2003L), null, textNode(), "{\"notifyId\":\"77\"}", null, "trace-1")
        ).getData();

        ImSystemNotifyRequest req = (ImSystemNotifyRequest) lastArg(messageRpc);
        assertThat(req.userId()).as("actor 是调用者本人（审计归属），不是群代理 / 虚拟身份").isEqualTo(USER_ID);
        assertThat(req.tenantId()).isEqualTo(TENANT_ID);
        assertThat(req.toUserIds()).containsExactly(2002L, 2003L);
        assertThat(req.body().path("text").asText()).isEqualTo("hi");
        assertThat(req.ext()).isEqualTo("{\"notifyId\":\"77\"}");
        assertThat(req.traceId()).isEqualTo("trace-1");

        // ★msgType 留空 ⇒ 本层补 text（★DEV-298 起缺省从 system 改成 text 纯文本气泡；
        //   唯一的本地缺省值，见请求 record 的注释）。
        assertThat(req.msgType()).isEqualTo(ImSystemNotifyRequest.MSG_TYPE_TEXT);
        // ★写操作 ⇒ 回显 bizMsgId，调用方才能在响应丢失后用同一个键安全重试。
        assertThat(result.bizMsgId()).isNotBlank();
        assertThat(req.bizMsgId()).isEqualTo(result.bizMsgId());
    }

    /**
     * ★{@code msgType} 非空时<b>原样透传</b>，本层不做白名单。
     *
     * <p>理由是"一处口径"：★DEV-298 起合法取值是 {@code text} / {@code system} / {@code card} /
     * {@code rich_text} 四型，这条规则属于 IM 契约，
     * 本层抄一份就会漂移（抄少了拒掉合法值、抄多了放行非法值，两种都坏）。
     * 非法值交给 IM 回 {@code IM_90001}，与 {@code agentMode} 同规则。
     */
    @Test
    @DisplayName("★系统通知：msgType 给了就原样透传（非法值也照发，由 IM 回 IM_90001，不在本层白名单）")
    void systemNotifyPassesMsgTypeThroughUntouched() {
        loginAsAdmin(USER_ID, TENANT_ID);
        when(messageRpc.sendSystemNotify(any())).thenReturn(systemNotifyResultDto());

        controller.sendSystemNotify(new NuwaxImSandboxRequests.SystemNotify(
                List.of(2002L), "card", textNode(), null, null, null));

        assertThat(((ImSystemNotifyRequest) lastArg(messageRpc)).msgType()).isEqualTo("card");
    }

    /**
     * ★空收件人<b>本地拒</b>，且一个 RPC 都不发。
     *
     * <p>不能交给 IM：空列表在那边是「成功发出 0 条」，响应与"真的都发出去了"长得一模一样 ——
     * 字段名拼错（例如写成了 {@code toUsers}）就会静默什么都不发而调用方以为成功。
     */
    @Test
    @DisplayName("★系统通知：toUserIds 缺 / 空 ⇒ 本地 4000 且不发 RPC（IM 那边空列表是「成功发 0 条」）")
    void systemNotifyRejectsEmptyRecipientsLocally() {
        loginAsAdmin(USER_ID, TENANT_ID);

        assertThatThrownBy(() -> controller.sendSystemNotify(
                new NuwaxImSandboxRequests.SystemNotify(List.of(), null, textNode(), null, null, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    BizException biz = (BizException) e;
                    assertThat(biz.getCode()).isEqualTo("4000");
                    assertThat(biz.getMsgName()).isEqualTo("fieldRequiredButEmpty");
                });
        assertThatThrownBy(() -> controller.sendSystemNotify(
                new NuwaxImSandboxRequests.SystemNotify(null, null, textNode(), null, null, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo("4000"));
        // body 也必填：没有正文的系统通知就是"点开什么都没有"的未读红点。
        assertThatThrownBy(() -> controller.sendSystemNotify(
                new NuwaxImSandboxRequests.SystemNotify(List.of(2002L), null, null, null, null, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo("4000"));

        verifyNoInteractions(messageRpc);
    }

    /**
     * ★{@code data.failed} 必须<b>原样</b>传到调用方手里 —— 本层不得把它折叠成"成功/失败"。
     *
     * <p>个别收件人投不到时整体仍是 {@code 0000}：本层若在这里"顺手"把它当失败抛出，
     * 调用方就看不到「谁没收到」，也就没法按 failed 里的 toUserIds 精确补发。
     */
    @Test
    @DisplayName("★系统通知：个别收件人失败不改变 0000，明细（含 reason）原样回给调用方")
    void systemNotifySurfacesPerRecipientFailures() {
        loginAsAdmin(USER_ID, TENANT_ID);
        when(messageRpc.sendSystemNotify(any())).thenReturn(systemNotifyResultDto());

        ReqResult<NuwaxImWriteResult<ImSystemNotifyResultDto>> response = controller.sendSystemNotify(
                new NuwaxImSandboxRequests.SystemNotify(
                        List.of(2002L, 2003L, 2004L), null, textNode(), null, null, null));

        assertThat(response.getCode()).as("failed 非空 ≠ 调用失败").isEqualTo("0000");
        ImSystemNotifyResultDto data = response.getData().data();
        assertThat(data.targetCount()).isEqualTo(3);
        assertThat(data.sentCount()).isEqualTo(2);
        assertThat(data.allDelivered()).as("有人没投到 ⇒ 不是全送达").isFalse();
        assertThat(data.failedOrEmpty()).singleElement()
                .satisfies(f -> {
                    // ★对照桩返回的 failed[0]：本层不许改写/丢弃这些字段（用户 id 与 reason 都要原样）。
                    assertThat(f.toUserId()).isEqualTo(4L);
                    assertThat(f.reason()).contains("IM_20103");
                });
        assertThat(data.listOrEmpty()).hasSize(2);
    }

    // ================================================================== 管理员闸门

    /**
     * ★★把「send-system / push-event 只有管理员能用」从注释升级为<b>机器可查的断言</b>。
     *
     * <p>{@code send-system} / {@code event-push} 2026-09-23 曾被整个摘掉，2026-09-23 又按需求
     * 「拿回来，但要判调用者为管理员」恢复；{@code system-notify}（★DEV-291）则是新端点，
     * 按需求「sandbox 中仅管理员可以调用」<b>一开始就带闸门</b>。
     * 三次变更都只落在注释和 {@code requireAdmin()} 一行上 —— 而注释挡不住
     * 「下次为了联调方便把 {@code requireAdmin()} 那行先注释掉」：删掉闸门后，
     * 这三个端点各自都<b>工作正常</b>，其余测试全绿，没有任何红灯会亮。
     *
     * <p>★所以这里<b>同时</b>钉两层，缺一不可：
     * <ol>
     *   <li><b>映射级</b>：端点数必须等于 21，且三个危险路径必须在列 ——
     *       防止"哪天又被静默摘掉"（与本次需求相反的那个方向）；</li>
     *   <li><b>行为级</b>：非管理员的登录态调用这三个端点必须 4030，
     *       且<b>一个 RPC 都不发</b> —— 这才是真正锁住闸门的那条。
     *       只扫注解锁不住闸门：{@code requireAdmin()} 被删掉时映射完全没变。</li>
     * </ol>
     *
     * <p>★反向也钉了一条：管理员调用时闸门必须<b>放行</b>（能走到 RPC）。
     * 只断言"非管理员被拒"是半条测试 —— 把 {@code requireAdmin()} 写成
     * {@code throw} 常量也能让它绿，而那时这三个端点对谁都用不了了。
     */
    @Test
    @DisplayName("★★send-system / system-notify / push-event 必须存在，且非管理员 4030（映射 + 行为双层）")
    void dangerousEndpointsSitBehindTheAdminGate() {
        List<String> mappings = Arrays.stream(NuwaxImSandboxController.class.getDeclaredMethods())
                .map(m -> m.getAnnotation(PostMapping.class))
                .filter(java.util.Objects::nonNull)
                .flatMap(a -> Arrays.stream(a.value()))
                .toList();

        assertThat(mappings)
                .as("端点数变了就必须同步改本断言与技能文档的三处计数")
                .hasSize(21);
        assertThat(mappings)
                .as("★这三个端点按需求必须在列（它们是管理员端点，不是被摘掉的端点）")
                .contains("/message/send-system", "/message/system-notify", "/event/push");

        // ---- 行为级：非管理员一律 4030，且够不到 RPC ----
        loginAs(USER_ID, TENANT_ID); // 登录态里没有 UserDto ⇒ 取不到角色 ⇒ 必须拒（fail-closed）
        assertThatThrownBy(() -> controller.sendSystem(new NuwaxImSandboxRequests.SendSystem(
                7L, null, 3, "text", textNode(), null, null, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    BizException biz = (BizException) e;
                    assertThat(biz.getCode()).isEqualTo("4030");
                    assertThat(biz.getMsgName()).isEqualTo("permissionDenied");
                });
        assertThatThrownBy(() -> controller.sendSystemNotify(new NuwaxImSandboxRequests.SystemNotify(
                List.of(2L), null, textNode(), null, null, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    assertThat(((BizException) e).getCode()).isEqualTo("4030");
                    assertThat(((BizException) e).getMsgName()).isEqualTo("permissionDenied");
                });
        assertThatThrownBy(() -> controller.pushEvent(new NuwaxImSandboxRequests.PushEvent(
                List.of(2L), null, "evt", Map.of(), null, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo("4030"));

        // ★「取不到角色就当普通用户放行」是这里唯一不能犯的错：那会留出一条
        //   「让上下文读不到角色即可绕过闸门」的路。
        verifyNoInteractions(messageRpc, eventRpc);
    }

    @Test
    @DisplayName("★★闸门必须是方法第一行：非法参数也报 4030 而非 4000（否则错误码差异可被用来探参数结构）")
    void adminGateRunsBeforeParameterValidation() {
        loginAs(USER_ID, TENANT_ID);

        // 参数全是非法值（convId/toUserId 都缺、eventType 也缺、toUserIds 为空）——
        // 非管理员仍必须看到 4030。
        // 若闸门被挪到参数校验【之后】，这里会变成 4000，未授权调用方就能拿错误码当探针。
        assertThatThrownBy(() -> controller.sendSystem(new NuwaxImSandboxRequests.SendSystem(
                null, null, null, null, null, null, null, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo("4030"));
        // ★本端点的参数校验里有一条「toUserIds 空 / 缺」的本地拒 —— 它尤其不能挡在闸门前面：
        //   那个校验一旦先跑，未授权方就能靠"4000 还是 4030"直接读出「这个端点收一个收件人列表」。
        assertThatThrownBy(() -> controller.sendSystemNotify(new NuwaxImSandboxRequests.SystemNotify(
                List.of(), null, null, null, null, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo("4030"));
        assertThatThrownBy(() -> controller.pushEvent(new NuwaxImSandboxRequests.PushEvent(
                null, null, null, null, null, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo("4030"));
    }

    @Test
    @DisplayName("★闸门对管理员放行（不是「对谁都拒」）：管理员能走到 RPC，普通用户角色仍被拒")
    void adminGateLetsAdminsThroughAndOnlyAdmins() {
        loginAsAdmin(USER_ID, TENANT_ID);
        when(messageRpc.sendSystemMessage(any())).thenReturn(messageDto());
        when(messageRpc.sendSystemNotify(any())).thenReturn(systemNotifyResultDto());
        when(eventRpc.pushCustomEvent(any())).thenReturn(new ImCustomEventPushResultDto(1, 1, 1));

        assertThat(controller.sendSystem(new NuwaxImSandboxRequests.SendSystem(
                7L, null, 3, "text", textNode(), null, null, null)).getCode()).isEqualTo("0000");
        assertThat(controller.sendSystemNotify(new NuwaxImSandboxRequests.SystemNotify(
                List.of(2L, 3L), null, textNode(), null, null, null)).getCode()).isEqualTo("0000");
        assertThat(controller.pushEvent(new NuwaxImSandboxRequests.PushEvent(
                List.of(2L), null, "evt", Map.of(), null, null)).getCode()).isEqualTo("0000");

        // ★role=User 的 UserDto：有角色、但不是管理员 ⇒ 依然 4030。
        //   这条区分开「闸门在工作」与「闸门只会因为读不到角色才拒」。
        loginAsRole(USER_ID, TENANT_ID, User.Role.User);
        assertThatThrownBy(() -> controller.sendSystem(new NuwaxImSandboxRequests.SendSystem(
                7L, null, 3, "text", textNode(), null, null, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo("4030"));
        assertThatThrownBy(() -> controller.sendSystemNotify(new NuwaxImSandboxRequests.SystemNotify(
                List.of(2L), null, textNode(), null, null, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo("4030"));
    }

    // ================================================================== 工具

    private void loginAs(long userId, long tenantId) {
        RequestContext<Object> context = RequestContext.<Object>builder()
                .userId(userId)
                .tenantId(tenantId)
                .build();
        RequestContext.set(context);
    }

    /**
     * 带 AK 的登录态 —— 沙箱技能调用就是这条形态（{@code ApiKeyInterceptor} 会把 AK 塞进上下文）。
     *
     * @param ext AK 的 {@code config.ext}，即本轮 4.10 的 {@code variableParams}（原样放，允许非 map 形态）
     */
    private void loginAsAk(long userId, long tenantId, Object ext) {
        // ★不带 targetId：这正是"登录态直连 / 手工 AK"的形态，兜底解析必须在它上面安全地什么都不做
        loginAsAkWithTarget(userId, tenantId, null, ext);
    }

    /**
     * 同上，但 AK 带 {@code targetType=Sandbox} 与 {@code targetId} —— 沙箱技能调用的真实形态。
     *
     * @param targetId 两种铸造形态都可传：{@code "{agentId}-{conversationId}"}（ChatApplicationServiceImpl）
     *                 或裸 {@code "{agentId}"}（CustomPageChatFluxServiceImpl）；也可传 null / 垃圾值测 fail-closed
     */
    private void loginAsAkWithTarget(long userId, long tenantId, String targetId, Object ext) {
        RequestContext<Object> context = RequestContext.<Object>builder()
                .userId(userId)
                .tenantId(tenantId)
                .userAccessKey(UserAccessKeyDto.builder()
                        .id(1L)
                        .userId(userId)
                        .targetType(UserAccessKeyDto.AKTargetType.Sandbox)
                        .targetId(targetId)
                        .config(UserAccessKeyDto.UserAccessKeyConfig.builder().ext(ext).build())
                        .build())
                .build();
        RequestContext.set(context);
    }

    /**
     * 管理员登录态 —— 与 {@code ApiKeyInterceptor} 解析完 AK 后的上下文同形：
     * {@code userId} 有值、{@code user} 是 {@code UserDto} 且 {@code role == Admin}。
     *
     * <p>★只有 {@code /message/send-system} 与 {@code /event/push} 需要它，
     * 其余 15 个端点用 {@link #loginAs} 即可 —— 它们不判角色。
     */
    private void loginAsAdmin(long userId, long tenantId) {
        loginAsRole(userId, tenantId, User.Role.Admin);
    }

    /**
     * 管理员 + 带 AK 的登录态 —— 用来覆盖 {@code /event/push} 这类<b>既有角色闸门、又走 actor 分流</b>
     * 的端点：只有同时具备两者，才测得准「闸门放行之后用的是谁的身份」。
     */
    private void loginAsAdminAk(long userId, long tenantId, Object ext) {
        UserDto userDto = new UserDto();
        userDto.setId(userId);
        userDto.setTenantId(tenantId);
        userDto.setRole(User.Role.Admin);

        RequestContext<Object> context = RequestContext.<Object>builder()
                .userId(userId)
                .tenantId(tenantId)
                .user(userDto)
                .userAccessKey(UserAccessKeyDto.builder()
                        .id(1L)
                        .userId(userId)
                        .config(UserAccessKeyDto.UserAccessKeyConfig.builder().ext(ext).build())
                        .build())
                .build();
        RequestContext.set(context);
    }

    /** 取某个 RPC mock 上【最近一次】调用的第一个入参 —— 调一次端点、断言一次，顺序即对应关系。 */
    private static Object lastArg(Object rpcMock) {
        List<org.mockito.invocation.Invocation> invocations =
                List.copyOf(org.mockito.Mockito.mockingDetails(rpcMock).getInvocations());
        assertThat(invocations).as("该 RPC 一次都没被调用过").isNotEmpty();
        return invocations.get(invocations.size() - 1).getArguments()[0];
    }

    /**
     * 断言某个 RPC 请求把 actor 放在了<b>用户本人</b>上。
     *
     * <p>用反射取 {@code userId()}，是因为 13 个请求 DTO 类型各不相同而它们都带这个访问器；
     * 逐个写 captor 会变成 13 段同样形状的样板。
     */
    private static void assertActorIsTheUser(String endpoint, Object rpcRequest) throws Exception {
        assertThat(rpcRequest.getClass().getMethod("userId").invoke(rpcRequest))
                .as("%s 的 actor 必须是用户本人（私聊场景）", endpoint)
                .isEqualTo(USER_ID);
    }

    /** 指定平台侧角色登录：用来把「闸门拒的是角色」和「闸门只会因为读不到角色才拒」区分开。 */
    private void loginAsRole(long userId, long tenantId, User.Role role) {
        UserDto userDto = new UserDto();
        userDto.setId(userId);
        userDto.setTenantId(tenantId);
        userDto.setRole(role);

        RequestContext<Object> context = RequestContext.<Object>builder()
                .userId(userId)
                .tenantId(tenantId)
                .user(userDto)
                .build();
        RequestContext.set(context);
    }

    /** 取出最近一次 {@code sendSystemMessage} 的入参（断言"以谁的身份、发到哪"）。 */
    private ImSystemMessageRequest captureSystem() {
        ArgumentCaptor<ImSystemMessageRequest> captor = ArgumentCaptor.forClass(ImSystemMessageRequest.class);
        org.mockito.Mockito.verify(messageRpc, org.mockito.Mockito.atLeastOnce()).sendSystemMessage(captor.capture());
        return captor.getValue();
    }

    /** 取出最近一次 {@code sendP2pMessage} 的入参（断言"以谁的身份、发给谁"）。 */
    private ImP2pMessageRequest captureP2p() {
        ArgumentCaptor<ImP2pMessageRequest> captor = ArgumentCaptor.forClass(ImP2pMessageRequest.class);
        org.mockito.Mockito.verify(messageRpc, org.mockito.Mockito.atLeastOnce()).sendP2pMessage(captor.capture());
        return captor.getValue();
    }

    /** 取出最近一次 {@code sendGroupMessage} 的入参（断言"以谁的身份、发到哪个群"）。 */
    private ImGroupMessageRequest captureGroup() {
        ArgumentCaptor<ImGroupMessageRequest> captor = ArgumentCaptor.forClass(ImGroupMessageRequest.class);
        org.mockito.Mockito.verify(messageRpc, org.mockito.Mockito.atLeastOnce()).sendGroupMessage(captor.capture());
        return captor.getValue();
    }

    /** 取出最近一次 {@code revokeMessage} 的入参（断言"以谁的身份撤哪条"）。 */
    private ImMessageRevokeRequest captureRevoke() {
        ArgumentCaptor<ImMessageRevokeRequest> captor = ArgumentCaptor.forClass(ImMessageRevokeRequest.class);
        org.mockito.Mockito.verify(messageRpc, org.mockito.Mockito.atLeastOnce()).revokeMessage(captor.capture());
        return captor.getValue();
    }

    /** 取出最近一次 {@code sendCardMessage} 的入参（断言"以谁的身份、发到哪个会话"）。 */
    private ImCardMessageRequest captureCard() {
        ArgumentCaptor<ImCardMessageRequest> captor = ArgumentCaptor.forClass(ImCardMessageRequest.class);
        org.mockito.Mockito.verify(messageRpc, org.mockito.Mockito.atLeastOnce()).sendCardMessage(captor.capture());
        return captor.getValue();
    }

    // ================================================================== ★加成员结果的"五档结局"可读性

    /**
     * ★★{@code /conversation/members/add} 的返回必须把<b>五档结局</b>逐字段带给技能脚本。
     *
     * <h3>为什么这条断言值得单独写</h3>
     *
     * <p>加人这一次调用有<b>六种</b>结局，其中四种"一个成员行也没新增"。本端点曾长期只透出
     * {@code inserted / revived / alreadyIn / failedUserIds} ⇒ 这四种在技能脚本眼里<b>逐字相同</b>
     * （全是 {@code 0}），于是"转成入群申请、等管理员审"被读成了"被静默吞掉"。
     *
     * <p>★根因不是脚本读错，而是<b>信息在链路上就不存在</b>：
     * IM core-sdk → RPC → 平台 SDK → 本层 → 脚本，<b>四层都是白名单投影</b>，
     * 任何一层漏掉一个字段，下游就永远看不到、且<b>不会有任何报错</b>。
     * 本用例钉住链条<b>最后一段</b>（平台 web 的 wire 形态）：字段名逐字断言，
     * 改名/删除/被投影替换都会红。
     *
     * <p>★断言的是<b>序列化后的 JSON 键</b>而不是 DTO 上的 getter：
     * 技能脚本拿到的是 JSON，中间任何一层引入的 {@code @JsonIgnore} /
     * 非空过滤 / 自定义投影都只有在这里才看得见。
     */
    @Test
    @DisplayName("★★members/add 的五档结局字段必须出现在 wire 上（转申请 / 等本人确认 / 冷却跳过）")
    void addMembersExposesAllOutcomesOnTheWire() throws Exception {
        loginAsAdminAk(USER_ID, TENANT_ID, Map.of("AGENT_VIRTUAL_USER_ID", VIRTUAL_USER_ID));
        when(conversationRpc.addMembers(any())).thenReturn(new MemberAddResultDto(
                2, 0, 1, List.of(9005L),
                3, List.of(9101L, 9102L, 9103L),
                4, List.of(9201L, 9202L, 9203L, 9204L),
                List.of(9301L), 17, false));

        ReqResult<NuwaxImWriteResult<MemberAddResultDto>> r =
                controller.addMembers(new NuwaxImSandboxRequests.AddMembers(7L, List.of(2L), null, null, null, null));

        JsonNode data = JSON.readTree(JSON.writeValueAsString(r)).path("data").path("data");
        assertThat(data.path("inserted").asInt()).isEqualTo(2);
        assertThat(data.path("applied").asInt()).as("DEV-197-c：转申请的人数").isEqualTo(3);
        assertThat(data.path("appliedUserIds")).as("是谁转了申请（不是 failed）")
                .hasSize(3);
        assertThat(data.path("pendingConfirm").asInt()).as("DEV-256：等被邀请人本人同意的人数").isEqualTo(4);
        assertThat(data.path("pendingConfirmUserIds")).as("是谁在等确认")
                .hasSize(4);
        assertThat(data.path("skippedUserIds")).as("冷却期被跳过（『过一阵还能再试』，不是『加不了』）")
                .hasSize(1);
    }

    @Test
    @DisplayName("★★agentMode 从 JSON 一路到 IM RPC：绑定得上、且原样透传（DEV-271）")
    void agentModeTravelsFromJsonToTheRpcRequest() throws Exception {
        loginAsAdminAk(USER_ID, TENANT_ID, Map.of("AGENT_VIRTUAL_USER_ID", VIRTUAL_USER_ID));
        when(conversationRpc.addMembers(any())).thenReturn(memberAddResultDto());

        // ★这里刻意走一次真的 JSON 反序列化（技能脚本发过来的就是 JSON 文本）：
        //   record 的组件名能不能被识别、有没有被后加的 @JsonIgnore / @JsonIgnoreProperties
        //   之类挡掉，只有在这条路上才看得见 —— 直接 new record 的测试对它一无所知，
        //   而"技能传了 agentMode 却没绑上"的表现是【静默落回唤起】，不会报任何错。
        NuwaxImSandboxRequests.AddMembers fromWire = JSON.readValue(
                "{\"convId\":7,\"userIds\":[2],\"agentMode\":2}", NuwaxImSandboxRequests.AddMembers.class);

        ReqResult<NuwaxImWriteResult<MemberAddResultDto>> r = controller.addMembers(fromWire);

        assertThat(r.getCode()).isEqualTo("0000");
        assertThat(((ImMemberAddRequest) lastArg(conversationRpc)).agentMode())
                .as("2=监听：设了监听却落成唤起不会报错，只能靠这条断言挡")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("★不传 agentMode ⇒ 沙箱层不补默认值，原样 null 交给 IM（默认口径只有一处）")
    void agentModeIsNotDefaultedInTheSandboxLayer() throws Exception {
        loginAsAdminAk(USER_ID, TENANT_ID, Map.of("AGENT_VIRTUAL_USER_ID", VIRTUAL_USER_ID));
        when(conversationRpc.addMembers(any())).thenReturn(memberAddResultDto());

        controller.addMembers(new NuwaxImSandboxRequests.AddMembers(7L, List.of(2L), null, null, null, null));

        assertThat(((ImMemberAddRequest) lastArg(conversationRpc)).agentMode())
                .as("★沙箱层若替它填 1，IM 侧『null ⇒ 缺省唤起』那条口径就永远测不到了")
                .isNull();
    }

    /** 造一条最简卡片请求：只有会话 id 是断言用得上的，其余走 null。 */
    private static NuwaxImSandboxRequests.SendCard cardRequest(long convId) {
        return new NuwaxImSandboxRequests.SendCard(
                convId, new ImCardBody("t", "d", null, null, null, null, null),
                null, null, null);
    }

    /** 造一条最简群发请求：只有群 id 是断言用得上的，其余走 null。 */
    private static NuwaxImSandboxRequests.SendGroup groupRequest(long convId) {
        return new NuwaxImSandboxRequests.SendGroup(
                convId, "text", textNode(), null, null, null, null, null, null);
    }

    private static JsonNode textNode() {
        return JSON.createObjectNode().put("text", "hi");
    }

    private static ImMessageDto messageDto() {
        return new ImMessageDto(9001L, 7L, 3L, 1700000000000L, false);
    }

    private static ImMessageDetailDto detailDto() {
        return detailDtoFrom(USER_ID);
    }

    /** 同上，但指定发送者 —— 用来造「这条是智能体自己发的」那种消息（撤回要按 senderId 选身份）。 */
    private static ImMessageDetailDto detailDtoFrom(long senderId) {
        return new ImMessageDetailDto(9001L, 7L, 3L, senderId, 1, "text", textNode(), "hi", 1,
                null, null, null, 1700000000000L, null, null, 0, null);
    }

    private static ReadCountItemDto readCountItem() {
        return new ReadCountItemDto(9001L, 3L, 1, 2, false, "basic", false);
    }

    /**
     * 造一次「加 1 个人、全部成功」的加成员结果（群共 2 人）。
     *
     * <p>★这里必须走命名方法而<b>不是</b>在两个调用点各写一遍位置参数：
     * {@code MemberAddResultDto} 有 11 个位置参数，其中
     * {@code failedUserIds / appliedUserIds / pendingConfirmUserIds / skippedUserIds}
     * 是<b>四个相邻的 {@code List<Long>}</b> —— 按位置手写时把"等对方确认的人"
     * 填进"转申请的人"这类错位，编译、运行、断言<b>全都不会报</b>。
     */
    private static MemberAddResultDto memberAddResultDto() {
        //                   inserted, revived, alreadyIn, failedUserIds,
        //                   applied, appliedUserIds, pendingConfirm, pendingConfirmUserIds,
        //                   skippedUserIds, memberCount, idempotentHit
        return new MemberAddResultDto(1, 0, 0, List.of(),
                0, List.of(), 0, List.of(),
                List.of(), 2, false);
    }

    private static ImMessagePageDto pageDto() {
        return new ImMessagePageDto(List.of(), false, 0L);
    }

    /**
     * 造一个「我（{@link #USER_ID}）是群主」的会话详情 ——
     * ★含 {@code groupNo} 与 {@code ext}，因为这两个字段正是详情相对列表的<b>全部</b>增量。
     *
     * <p>★这里必须走命名方法而<b>不是</b>在调用点直接写位置参数，理由同
     * {@link #memberAddResultDto()}：{@code ImConversationDetailDto} 有 30 个位置参数，
     * 其中 {@code memberCount / maxMember / status / myRole / unreadCount} 是<b>相邻的
     * {@code Integer}</b> —— 按位置手写时把 {@code myRole} 填进 {@code unreadCount} 的位置，
     * 编译、运行、断言<b>全都不会报</b>；而 {@code myRole} 恰恰是判断"这个人在不在会话里"的
     * <b>唯一</b>判据（非成员时 {@code unreadCount} 是 0 而不是 null，见 DTO 类注释）。
     */
    private static ImConversationDetailDto conversationDetailDto() {
        //                 convId, convType, name, avatar, notice, ownerUserId, memberCount, maxMember,
        //                 status, spaceId, groupNo, lastMsgId, lastMsgSeq, lastMsgAt, lastMsgDigest,
        //                 lastSenderId, lastSenderName, peerUserId, peerName, peerAvatar, myRole,
        //                 myNickName, unreadCount, topFlag, topTime, dnd, visible, createTime,
        //                 joinTime, ext
        return new ImConversationDetailDto(7001L, 2, "研发群", "a.png", "公告", USER_ID, 3, 2000,
                1, 555L, "G-7001", 9001L, 9L, 1700000000000L, "收到",
                3L, "张三", null, null, null, 3,
                "我", 5, false, null, false, true, 1700000000000L,
                1700000000001L, "{\"k\":1}");
    }

    /** 造一条联系人：只填断言用得上的字段（其余走 null，贴近「平台资料没取到」的真实形态）。 */
    private static ImContactItemDto contact(long userId, int status, String displayName) {
        return new ImContactItemDto(userId, 1, "u" + userId, null, null, null, displayName, status, 1, null, null);
    }

    /**
     * 通讯录里的<b>智能体</b>条目：{@code subjectType=2}（IM 的注册表认它是智能体）、
     * {@code source=4}（IM 添加智能体走 direct，只写人类这一侧的单边行）。
     */
    private static ImContactItemDto agentContact(long userId, int status) {
        return new ImContactItemDto(userId, ImContactItemDto.SUBJECT_TYPE_AGENT, null, "智能体", null, null,
                "智能体", status, 4, null, null);
    }

    /** 平台 {@code user} 表里 {@code account_type='agent'} 的那一行（兜底查询的返回值）。 */
    private static VirtualIdentityDto agentVirtualIdentity(long id) {
        VirtualIdentityDto dto = new VirtualIdentityDto();
        dto.setId(id);
        dto.setTenantId(TENANT_ID);
        dto.setAccountType(User.ACCOUNT_TYPE_AGENT);
        dto.setPrincipalRefId("1596");
        dto.setNickName("智能体");
        dto.setStatus(User.Status.Enabled);
        dto.setNewlyCreated(false);
        return dto;
    }

    /**
     * 造一次「3 个收件人、2 条投出去、1 条业务性跳过」的系统通知结果（★DEV-291）。
     *
     * <p>★刻意让 {@code failed} <b>非空</b>：这是本端点最容易被读错的地方 ——
     * {@code failed} 非空<b>不等于</b>调用失败（整体仍是 {@code 0000}）。
     * 若这里造一个 {@code failed=[]} 的"全成功"样本，就没有任何用例会去区分
     * 「真的都送到了」与「读漏了 failed」。
     */
    private static ImSystemNotifyResultDto systemNotifyResultDto() {
        return new ImSystemNotifyResultDto(3, 2,
                List.of(new ImSystemNotifyItemDto(2L, 7001L, 9001L, 3L, 1700000000000L),
                        new ImSystemNotifyItemDto(3L, 7002L, 9002L, 1L, 1700000000001L)),
                List.of(new ImSystemNotifyFailedDto(4L, "IM_20103: 会话不存在")));
    }
}
