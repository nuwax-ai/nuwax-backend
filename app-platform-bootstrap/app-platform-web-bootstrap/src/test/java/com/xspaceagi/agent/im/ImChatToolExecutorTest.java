package com.xspaceagi.agent.im;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xspaceagi.agent.core.infra.component.agent.AgentContext;
import com.xspaceagi.agent.core.infra.component.im.ImChatIdentity;
import com.xspaceagi.agent.core.infra.component.im.ImChatToolExecutor;
import com.xspaceagi.agent.core.infra.component.im.ImChatToolProvider;
import com.xspaceagi.agent.core.infra.component.model.dto.ComponentConfig;
import com.xspaceagi.agent.core.spec.enums.ComponentTypeEnum;
import com.xspaceagi.im.sdk.rpc.IImAgentRpcService;
import com.xspaceagi.im.sdk.rpc.IImContactRpcService;
import com.xspaceagi.im.sdk.rpc.IImConversationRpcService;
import com.xspaceagi.im.sdk.rpc.IImEventRpcService;
import com.xspaceagi.im.sdk.rpc.IImMessageRpcService;
import com.xspaceagi.im.sdk.rpc.ImRpcException;
import com.xspaceagi.im.sdk.rpc.dto.ImAgentPendingClearRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImAgentPendingListRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImCardBody;
import com.xspaceagi.im.sdk.rpc.dto.ImCardMessageRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImConversationDetailRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImCustomEventPushRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImGroupMessageRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImMessageDetailDto;
import com.xspaceagi.im.sdk.rpc.dto.ImMessageDto;
import com.xspaceagi.im.sdk.rpc.dto.ImMessageRevokeRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImP2pMessageRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImSystemMessageRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImSystemNotifyRequest;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;

/**
 * {@link ImChatToolExecutor} 的单元测试——5 个 IM RPC 接口全用 mock，不起容器、不连库、不发网络。
 *
 * <p>这一层钉的是<b>「以谁的身份、把什么、发到哪」</b>三件事。判错的后果不是报错，而是：
 * <ul>
 *   <li>消息以<b>错的身份</b>发出（群里显示「张三说：…」其实是智能体说的）；</li>
 *   <li>驱动本次运行的人<b>借智能体之手</b>往自己进不去的群投言；</li>
 *   <li>失败被<b>吞成成功</b>（模型据此告诉用户「已发送」）。</li>
 * </ul>
 * 三种都是静默的，所以每条都用 {@code ArgumentCaptor} 把真正发出去的请求对象抓出来逐字段核对，
 * 而不是只看有没有抛异常。
 *
 * <p>断言口径与 {@code NuwaxImSandboxController} 那套 {@code *ActorUserId(...)} 一致——两条腿打的是
 * 同一组 RPC，身份规则必须是同一套。
 */
class ImChatToolExecutorTest {

    private static final Long SELF = 1001L;
    private static final Long TENANT = 7L;
    private static final Long AGENT_VIRTUAL = 1_000_000_000_123L;
    private static final Long GROUP_PROXY = 555L;
    private static final Long TRIGGER_CONV = 90001L;
    private static final Long OTHER_CONV = 90002L;
    private static final Long P2P_CONV = 80001L;
    private static final Long OTHER_USER = 2002L;

    private static final String CODE_INVALID_PARAM = ErrorCodeEnum.INVALID_PARAM.getCode();       // 4000
    private static final String CODE_PERMISSION_DENIED = ErrorCodeEnum.PERMISSION_DENIED.getCode(); // 4030
    private static final String CODE_IM_NOT_CONFIGURED = "3612";
    private static final String CODE_IM_UNAVAILABLE = "3613";
    private static final String CODE_IM_REJECTED = "3614";

    private static final ObjectMapper JSON = new ObjectMapper();

    private IImMessageRpcService msgRpc;
    private IImConversationRpcService convRpc;
    private IImContactRpcService contactRpc;
    private IImEventRpcService eventRpc;
    private IImAgentRpcService agentRpc;
    private ImChatToolExecutor executor;

    @BeforeEach
    void setUp() {
        msgRpc = mock(IImMessageRpcService.class);
        convRpc = mock(IImConversationRpcService.class);
        contactRpc = mock(IImContactRpcService.class);
        eventRpc = mock(IImEventRpcService.class);
        agentRpc = mock(IImAgentRpcService.class);
        executor = newExecutor();
    }

    private ImChatToolExecutor newExecutor() {
        ImChatToolExecutor e = new ImChatToolExecutor();
        e.setImMessageRpcService(msgRpc);
        e.setImConversationRpcService(convRpc);
        e.setImContactRpcService(contactRpc);
        e.setImEventRpcService(eventRpc);
        e.setImAgentRpcService(agentRpc);
        return e;
    }

    // ============================== 构造辅助 ==============================

    private static AgentContext ctx(Map<String, Object> imVars, Long userId, User.Role role) {
        AgentContext ctx = new AgentContext();
        ctx.setFrom("nuwax_im");
        ctx.setRequestId("req-trace-1");
        ctx.setImVars(imVars);
        ctx.setUserId(userId);
        UserDto user = new UserDto();
        user.setId(userId);
        user.setTenantId(TENANT);
        user.setRole(role);
        ctx.setUser(user);
        return ctx;
    }

    private static Map<String, Object> groupVars() {
        Map<String, Object> vars = new HashMap<>();
        vars.put(ImChatIdentity.KEY_IS_GROUP_MESSAGE, true);
        vars.put(ImChatIdentity.KEY_AGENT_VIRTUAL_USER_ID, AGENT_VIRTUAL);
        vars.put(ImChatIdentity.KEY_GROUP_VIRTUAL_USER_ID, GROUP_PROXY);
        vars.put(ImChatIdentity.KEY_GROUP_CONV_ID, TRIGGER_CONV);
        vars.put(ImChatIdentity.KEY_GROUP_SENDER_ID, OTHER_USER);
        vars.put(ImChatIdentity.KEY_IM_MODE, ImChatIdentity.MODE_MENTION);
        vars.put(ImChatIdentity.KEY_IM_CONV_ID, TRIGGER_CONV);
        return vars;
    }

    private static Map<String, Object> p2pVars() {
        Map<String, Object> vars = new HashMap<>();
        vars.put(ImChatIdentity.KEY_AGENT_VIRTUAL_USER_ID, AGENT_VIRTUAL);
        vars.put(ImChatIdentity.KEY_IM_MODE, ImChatIdentity.MODE_P2P);
        vars.put(ImChatIdentity.KEY_IM_CONV_ID, P2P_CONV);
        vars.put(ImChatIdentity.KEY_IM_MSG_ID, 70001L);
        return vars;
    }

    /** 群场景：调用主体是群代理（4.10 的 userId），几乎不可能是管理员。 */
    private static AgentContext groupCtx() {
        return ctx(groupVars(), GROUP_PROXY, User.Role.User);
    }

    private static AgentContext p2pCtx() {
        return ctx(p2pVars(), SELF, User.Role.User);
    }

    private static AgentContext adminP2pCtx() {
        return ctx(p2pVars(), SELF, User.Role.Admin);
    }

    /**
     * ★管理员口径的群场景上下文 —— 只有「拿全量清单」这类需要看到 21 个工具的测试用它。
     *
     * <p>群聊的调用主体是群代理（{@link #groupCtx()} 的 userId），天然不是管理员，
     * 所以清单侧收窄后群代理只能看到 18 个；要逐个验「清单里的工具执行侧都认得」，
     * 就必须换一个既是管理员、又带群触发变量的口径，否则那 3 个管理端点根本进不了清单、也就验不到。
     */
    private static AgentContext adminGroupCtx() {
        return ctx(groupVars(), SELF, User.Role.Admin);
    }

    private static ComponentConfig config(String functionName) {
        ComponentConfig c = new ComponentConfig();
        c.setType(ComponentTypeEnum.Im);
        c.setFunctionName(functionName);
        c.setName("工具");
        return c;
    }

    /** 调一次工具，返回解析后的信封（{@code {"ok":..,"bizMsgId":..,"data":..}}）。 */
    private static JsonNode call(ImChatToolExecutor e, AgentContext ctx, String function, Object... kv)
            throws Exception {
        Map<String, Object> in = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            in.put((String) kv[i], kv[i + 1]);
        }
        String raw = e.execute(ctx, config(function), in);
        assertNotNull(raw, "执行成功时必须返回一段 JSON 文本（模型只能读文本）");
        return JSON.readTree(raw);
    }

    private JsonNode call(AgentContext ctx, String function, Object... kv) throws Exception {
        return call(executor, ctx, function, kv);
    }

    private static ImMessageDto msgDto(Long msgId) {
        return new ImMessageDto(msgId, P2P_CONV, 3L, 1_700_000_000_000L, false);
    }

    private static ImMessageDetailDto detail(Long msgId, Long convId, Long senderId) {
        return new ImMessageDetailDto(msgId, convId, 1L, senderId, 1, "text", null, null, 1,
                null, null, null, 1_700_000_000_000L, null, null, 0, null);
    }

    // ============================== im_send_p2p ==============================

    @Test
    @DisplayName("私聊：发给别人 ⇒ actor 是本人（用户自己在发），并回显幂等键与真实数据")
    void p2pSendActorIsSelfForOtherRecipient() throws Exception {
        when(msgRpc.sendP2pMessage(any())).thenReturn(msgDto(7001L));

        JsonNode env = call(p2pCtx(), "im_send_p2p", "toUserId", OTHER_USER, "text", "你好");

        ArgumentCaptor<ImP2pMessageRequest> cap = ArgumentCaptor.forClass(ImP2pMessageRequest.class);
        verify(msgRpc).sendP2pMessage(cap.capture());
        ImP2pMessageRequest req = cap.getValue();
        assertEquals(SELF, req.userId(), "发给别人时必须以用户本人身份发出");
        assertEquals(TENANT, req.tenantId());
        assertEquals(OTHER_USER, req.toUserId());
        assertEquals("text", req.msgType());
        assertEquals("你好", req.body().get("text").asText());
        assertEquals("req-trace-1", req.traceId(), "traceId 必须来自 AgentContext#requestId，不能取 RequestContext");
        assertFalse(req.isBlankBizMsgId(), "没给幂等键时必须自动生成一个");

        assertTrue(env.get("ok").asBoolean());
        assertEquals(req.bizMsgId(), env.get("bizMsgId").asText(), "★幂等键必须回显：用户说没收到时靠它对账");
        // ★这条同时钉住「结果由 Jackson 自己序列化」那个决定：IM 的 DTO 是 record，
        //   若哪天被换成 fastjson 或对 record 不友好的序列化器，模型收到的会是 {} 而不是数据。
        assertEquals(7001L, env.get("data").get("msgId").asLong(), "返回必须带得出真实数据，不能是空对象");
    }

    @Test
    @DisplayName("私聊：收件人就是自己 ⇒ actor 是智能体虚拟身份（这是智能体在回话）")
    void p2pSendActorIsVirtualWhenRecipientIsSelf() throws Exception {
        when(msgRpc.sendP2pMessage(any())).thenReturn(msgDto(7002L));

        call(p2pCtx(), "im_send_p2p", "toUserId", SELF, "text", "我回来了");

        ArgumentCaptor<ImP2pMessageRequest> cap = ArgumentCaptor.forClass(ImP2pMessageRequest.class);
        verify(msgRpc).sendP2pMessage(cap.capture());
        assertEquals(AGENT_VIRTUAL, cap.getValue().userId());
        assertFalse(SELF.equals(cap.getValue().userId()), "自己给自己发不能用本人身份（那是用户自己在发）");
    }

    @Test
    @DisplayName("★群场景下发私聊一律拒，且一个 RPC 都不许发出去")
    void p2pSendRejectedInGroupScenario() {
        BizException e = assertThrows(BizException.class,
                () -> call(groupCtx(), "im_send_p2p", "toUserId", OTHER_USER, "text", "hi"));
        assertEquals(CODE_PERMISSION_DENIED, e.getCode());
        assertTrue(e.getMessage().contains("私聊"), "报错要说清是「不能发私聊」：" + e.getMessage());
        verifyNoInteractions(msgRpc, convRpc, contactRpc, eventRpc, agentRpc);
    }

    @Test
    @DisplayName("调用方给的 bizMsgId 必须原样送到 RPC（重试幂等全靠它）")
    void callerSuppliedBizMsgIdIsReused() throws Exception {
        when(msgRpc.sendP2pMessage(any())).thenReturn(msgDto(7003L));

        JsonNode env = call(p2pCtx(), "im_send_p2p",
                "toUserId", OTHER_USER, "text", "hi", "bizMsgId", " biz-fixed-001 ");

        ArgumentCaptor<ImP2pMessageRequest> cap = ArgumentCaptor.forClass(ImP2pMessageRequest.class);
        verify(msgRpc).sendP2pMessage(cap.capture());
        assertEquals("biz-fixed-001", cap.getValue().bizMsgId());
        assertEquals("biz-fixed-001", env.get("bizMsgId").asText());
    }

    // ============================== im_send_group ==============================

    @Test
    @DisplayName("群：convId 省略 ⇒ 默认发回触发本次运行的那个群，actor 是智能体虚拟身份")
    void groupSendDefaultsToTriggerConvAndVirtualActor() throws Exception {
        when(msgRpc.sendGroupMessage(any())).thenReturn(msgDto(8001L));

        call(groupCtx(), "im_send_group", "text", "收到");

        ArgumentCaptor<ImGroupMessageRequest> cap = ArgumentCaptor.forClass(ImGroupMessageRequest.class);
        verify(msgRpc).sendGroupMessage(cap.capture());
        ImGroupMessageRequest req = cap.getValue();
        assertEquals(TRIGGER_CONV, req.convId());
        assertEquals(AGENT_VIRTUAL, req.userId(), "群里发言必须是智能体的虚拟身份");
        assertFalse(GROUP_PROXY.equals(req.userId()), "绝不能以群代理身份发言");
        assertEquals("收到", req.body().get("text").asText());
        assertNull(req.mentionAll(), "没给 mentionAll 就不该塞个 false 进去（那是「不 @ 任何人」的明确指令）");
    }

    @Test
    @DisplayName("★闸门①：群场景下发往别的群一律拒，且不发 RPC")
    void groupSendToAnotherConvIsRejected() {
        BizException e = assertThrows(BizException.class,
                () -> call(groupCtx(), "im_send_group", "convId", OTHER_CONV, "text", "hi"));
        assertEquals(CODE_PERMISSION_DENIED, e.getCode());
        assertTrue(e.getMessage().contains(String.valueOf(TRIGGER_CONV)), "报错应带上触发群：" + e.getMessage());
        verifyNoInteractions(msgRpc, convRpc, contactRpc, eventRpc, agentRpc);
    }

    @Test
    @DisplayName("★群场景读不到虚拟身份 ⇒ actor 传 null 让 IM 大声拒，不回退群代理")
    void groupSendActorIsNullWhenVirtualMissing() throws Exception {
        Map<String, Object> vars = groupVars();
        vars.remove(ImChatIdentity.KEY_AGENT_VIRTUAL_USER_ID);
        when(msgRpc.sendGroupMessage(any())).thenReturn(msgDto(8002L));

        call(ctx(vars, GROUP_PROXY, User.Role.User), "im_send_group", "text", "hi");

        ArgumentCaptor<ImGroupMessageRequest> cap = ArgumentCaptor.forClass(ImGroupMessageRequest.class);
        verify(msgRpc).sendGroupMessage(cap.capture());
        assertNull(cap.getValue().userId(), "宁可让 IM 判 IM_90001，也不能以群代理身份把消息发出去");
    }

    @Test
    @DisplayName("私聊场景发群消息必须显式给 convId（缺了就 4000，不猜）")
    void groupSendInP2pWithoutConvIdIsRejected() {
        BizException e = assertThrows(BizException.class, () -> call(p2pCtx(), "im_send_group", "text", "hi"));
        assertEquals(CODE_INVALID_PARAM, e.getCode());
        assertTrue(e.getMessage().contains("convId"), "报错要指出缺什么：" + e.getMessage());
        verifyNoInteractions(msgRpc);
    }

    @Test
    @DisplayName("私聊场景显式发群 ⇒ actor 是本人（用户自己要求往群里发），且不触发闸门①")
    void groupSendInP2pUsesSelfActor() throws Exception {
        when(msgRpc.sendGroupMessage(any())).thenReturn(msgDto(8003L));

        call(p2pCtx(), "im_send_group", "convId", OTHER_CONV, "text", "hi");

        ArgumentCaptor<ImGroupMessageRequest> cap = ArgumentCaptor.forClass(ImGroupMessageRequest.class);
        verify(msgRpc).sendGroupMessage(cap.capture());
        assertEquals(SELF, cap.getValue().userId());
        assertEquals(OTHER_CONV, cap.getValue().convId());
    }

    @Test
    @DisplayName("@ 与引用回复的参数必须逐个送到（字符串数字也认）")
    void groupSendPassesMentionsAndReplyTo() throws Exception {
        when(msgRpc.sendGroupMessage(any())).thenReturn(msgDto(8004L));

        call(groupCtx(), "im_send_group", "text", "hi",
                "replyToMsgId", "70001", "mentionUserIds", Arrays.asList("2002", 2003L), "mentionAll", "true");

        ArgumentCaptor<ImGroupMessageRequest> cap = ArgumentCaptor.forClass(ImGroupMessageRequest.class);
        verify(msgRpc).sendGroupMessage(cap.capture());
        ImGroupMessageRequest req = cap.getValue();
        assertEquals(70001L, req.replyToMsgId());
        assertEquals(List.of(2002L, 2003L), req.mentionUserIds());
        assertEquals(Boolean.TRUE, req.mentionAll());
    }

    @Test
    @DisplayName("★名单里有解析不出的元素 ⇒ 报错，绝不静默丢弃（少发一个人比失败更危险）")
    void mentionListRejectsUnparseableElement() {
        BizException e = assertThrows(BizException.class,
                () -> call(groupCtx(), "im_send_group", "text", "hi", "mentionUserIds", Arrays.asList(2002L, 2003.7d)));
        assertEquals(CODE_INVALID_PARAM, e.getCode());
        assertTrue(e.getMessage().contains("2003.7"), "报错要点名那个元素：" + e.getMessage());
        verifyNoInteractions(msgRpc);
    }

    // ============ 消息类型（msgType / url / fileName / bodyJson） ============

    @Test
    @DisplayName("默认仍是 text：不传 msgType 时行为与加类型之前一字不变")
    void defaultMsgTypeIsText() throws Exception {
        when(msgRpc.sendP2pMessage(any())).thenReturn(msgDto(7101L));

        call(p2pCtx(), "im_send_p2p", "toUserId", OTHER_USER, "text", "你好");

        ArgumentCaptor<ImP2pMessageRequest> cap = ArgumentCaptor.forClass(ImP2pMessageRequest.class);
        verify(msgRpc).sendP2pMessage(cap.capture());
        assertEquals("text", cap.getValue().msgType());
        assertEquals("你好", cap.getValue().body().get("text").asText());
    }

    @Test
    @DisplayName("私聊发图片：msgType=image，body 是 url/fileName/size（不是 {text}）")
    void p2pImageMessageCarriesUrl() throws Exception {
        when(msgRpc.sendP2pMessage(any())).thenReturn(msgDto(7102L));

        call(p2pCtx(), "im_send_p2p", "toUserId", OTHER_USER, "msgType", "image",
                "url", "https://cdn.example.com/chart.png", "fileName", "chart.png", "size", 20480);

        ArgumentCaptor<ImP2pMessageRequest> cap = ArgumentCaptor.forClass(ImP2pMessageRequest.class);
        verify(msgRpc).sendP2pMessage(cap.capture());
        ImP2pMessageRequest req = cap.getValue();
        assertEquals("image", req.msgType());
        assertEquals("https://cdn.example.com/chart.png", req.body().get("url").asText());
        assertEquals("chart.png", req.body().get("fileName").asText());
        assertEquals(20480, req.body().get("size").asInt());
        assertNull(req.body().get("text"), "★媒体类型不许带 text：那会拼出一个谁都没设计过的 body");
    }

    @Test
    @DisplayName("群发文件：换成媒体类型也不该把 @ 与引用回复丢掉")
    void groupFileMessageKeepsMentions() throws Exception {
        when(msgRpc.sendGroupMessage(any())).thenReturn(msgDto(8101L));

        call(groupCtx(), "im_send_group", "msgType", "file", "url", "https://cdn.example.com/r.xlsx",
                "fileName", "9月报表.xlsx", "mentionUserIds", List.of(OTHER_USER), "replyToMsgId", 70001L);

        ArgumentCaptor<ImGroupMessageRequest> cap = ArgumentCaptor.forClass(ImGroupMessageRequest.class);
        verify(msgRpc).sendGroupMessage(cap.capture());
        ImGroupMessageRequest req = cap.getValue();
        assertEquals("file", req.msgType());
        assertEquals("https://cdn.example.com/r.xlsx", req.body().get("url").asText());
        assertEquals("9月报表.xlsx", req.body().get("fileName").asText());
        assertEquals(List.of(OTHER_USER), req.mentionUserIds());
        assertEquals(70001L, req.replyToMsgId());
    }

    @Test
    @DisplayName("富文本：msgType=rich_text 仍走 text 参数")
    void richTextUsesTextArg() throws Exception {
        when(msgRpc.sendP2pMessage(any())).thenReturn(msgDto(7103L));

        call(p2pCtx(), "im_send_p2p", "toUserId", OTHER_USER, "msgType", "rich_text", "text", "结论如下");

        ArgumentCaptor<ImP2pMessageRequest> cap = ArgumentCaptor.forClass(ImP2pMessageRequest.class);
        verify(msgRpc).sendP2pMessage(cap.capture());
        assertEquals("rich_text", cap.getValue().msgType());
        assertEquals("结论如下", cap.getValue().body().get("text").asText());
    }

    @Test
    @DisplayName("★媒体类型没给 url ⇒ 报错，并在信息里点名 msgType 与 url（不发一条空图片出去）")
    void mediaMessageWithoutUrlIsRejected() {
        BizException e = assertThrows(BizException.class,
                () -> call(p2pCtx(), "im_send_p2p", "toUserId", OTHER_USER, "msgType", "image"));
        assertEquals(CODE_INVALID_PARAM, e.getCode());
        assertTrue(e.getMessage().contains("image") && e.getMessage().contains("url"), e.getMessage());
        verifyNoInteractions(msgRpc);
    }

    @Test
    @DisplayName("★文本类没给 text ⇒ 报错（参数在清单里改成可选后，必填约束由执行侧兜住）")
    void textMessageWithoutTextIsRejected() {
        BizException e = assertThrows(BizException.class,
                () -> call(p2pCtx(), "im_send_p2p", "toUserId", OTHER_USER));
        assertEquals(CODE_INVALID_PARAM, e.getCode());
        assertTrue(e.getMessage().contains("text"), e.getMessage());
        verifyNoInteractions(msgRpc);
    }

    @Test
    @DisplayName("★白名单外的类型（card/system/meeting/merge_forward）一律拒，并指路该用哪个工具")
    void typesOutsideTheWhitelistAreRejected() {
        for (String bad : List.of("card", "system", "meeting", "merge_forward", "hologram")) {
            BizException e = assertThrows(BizException.class,
                    () -> call(p2pCtx(), "im_send_p2p", "toUserId", OTHER_USER, "msgType", bad, "text", "hi"));
            assertEquals(CODE_INVALID_PARAM, e.getCode(), "不该放行 msgType=" + bad);
            assertTrue(e.getMessage().contains(bad), "报错要点名那个值：" + e.getMessage());
        }
        BizException card = assertThrows(BizException.class,
                () -> call(groupCtx(), "im_send_group", "msgType", "card", "text", "hi"));
        assertTrue(card.getMessage().contains("im_send_card"), "卡片要指路到 im_send_card：" + card.getMessage());
        verifyNoInteractions(msgRpc);
    }

    @Test
    @DisplayName("msgType 大小写不敏感，但必须归一成小写（否则 ES 的 term 聚合会分成两个桶）")
    void msgTypeIsCaseInsensitive() throws Exception {
        when(msgRpc.sendP2pMessage(any())).thenReturn(msgDto(7104L));

        call(p2pCtx(), "im_send_p2p", "toUserId", OTHER_USER, "msgType", "IMAGE", "url", "https://x/y.png");

        ArgumentCaptor<ImP2pMessageRequest> cap = ArgumentCaptor.forClass(ImP2pMessageRequest.class);
        verify(msgRpc).sendP2pMessage(cap.capture());
        assertEquals("image", cap.getValue().msgType());
    }

    @Test
    @DisplayName("★bodyJson 优先：给了它就不再拼结构化参数（两边各填一半会合出怪 body）")
    void bodyJsonWinsOverStructuredArgs() throws Exception {
        when(msgRpc.sendP2pMessage(any())).thenReturn(msgDto(7105L));

        call(p2pCtx(), "im_send_p2p", "toUserId", OTHER_USER, "msgType", "rich_text", "text", "会被忽略",
                "bodyJson", "{\"text\":\"来自 bodyJson\",\"segments\":[{\"type\":\"text\",\"text\":\"hi\"}]}");

        ArgumentCaptor<ImP2pMessageRequest> cap = ArgumentCaptor.forClass(ImP2pMessageRequest.class);
        verify(msgRpc).sendP2pMessage(cap.capture());
        JsonNode body = cap.getValue().body();
        assertEquals("来自 bodyJson", body.get("text").asText());
        assertEquals(1, body.get("segments").size());
    }

    @Test
    @DisplayName("bodyJson 不是 JSON 对象 ⇒ 报错（数组/标量发过去是前端渲染不了的消息）")
    void bodyJsonMustBeAnObject() {
        for (String bad : List.of("[1,2]", "not json")) {
            BizException e = assertThrows(BizException.class,
                    () -> call(p2pCtx(), "im_send_p2p", "toUserId", OTHER_USER, "bodyJson", bad));
            assertEquals(CODE_INVALID_PARAM, e.getCode(), "bodyJson=" + bad);
        }
        BizException arr = assertThrows(BizException.class,
                () -> call(p2pCtx(), "im_send_p2p", "toUserId", OTHER_USER, "bodyJson", "[1,2]"));
        assertTrue(arr.getMessage().contains("JSON 对象"), arr.getMessage());
        verifyNoInteractions(msgRpc);
    }

    @Test
    @DisplayName("★bodyJson 把正文写成 content（字段名错）⇒ 本地就拒，并点名正确字段 text")
    void textBodyJsonWithWrongFieldNameIsRejected() {
        BizException e = assertThrows(BizException.class,
                () -> call(p2pCtx(), "im_send_p2p", "toUserId", OTHER_USER,
                        "bodyJson", "{\"content\":\"实际正文\"}"));

        assertEquals(CODE_INVALID_PARAM, e.getCode());
        assertTrue(e.getMessage().contains("content") && e.getMessage().contains("text"), e.getMessage());
        verifyNoInteractions(msgRpc);
    }

    @Test
    @DisplayName("★bodyJson 的 text 为空串/空白/null ⇒ 拒（发出去只会显示成 [文本]）")
    void blankTextBodyJsonIsRejected() {
        for (String bad : List.of("{\"text\":\"\"}", "{\"text\":\"   \"}", "{\"text\":null}")) {
            BizException e = assertThrows(BizException.class,
                    () -> call(p2pCtx(), "im_send_p2p", "toUserId", OTHER_USER, "bodyJson", bad));
            assertEquals(CODE_INVALID_PARAM, e.getCode(), "bodyJson=" + bad);
        }
        verifyNoInteractions(msgRpc);
    }

    @Test
    @DisplayName("rich_text：bodyJson 只有附件没有文字是合法存量形态，不能误伤")
    void richTextBodyJsonAllowsAttachmentsOnly() throws Exception {
        when(msgRpc.sendP2pMessage(any())).thenReturn(msgDto(7106L));

        call(p2pCtx(), "im_send_p2p", "toUserId", OTHER_USER, "msgType", "rich_text",
                "bodyJson", "{\"attachments\":[{\"url\":\"https://cdn.example.com/a.png\"}]}");

        ArgumentCaptor<ImP2pMessageRequest> cap = ArgumentCaptor.forClass(ImP2pMessageRequest.class);
        verify(msgRpc).sendP2pMessage(cap.capture());
        assertEquals(1, cap.getValue().body().get("attachments").size());
    }

    @Test
    @DisplayName("★rich_text：bodyJson 的 text/segments/attachments 三者皆空 ⇒ 拒（前端只能渲染空气泡）")
    void emptyRichTextBodyJsonIsRejected() {
        BizException e = assertThrows(BizException.class,
                () -> call(p2pCtx(), "im_send_p2p", "toUserId", OTHER_USER, "msgType", "rich_text",
                        "bodyJson", "{\"text\":\"\",\"segments\":[],\"attachments\":[]}"));

        assertEquals(CODE_INVALID_PARAM, e.getCode());
        assertTrue(e.getMessage().contains("rich_text"), e.getMessage());
        verifyNoInteractions(msgRpc);
    }

    // ============================== im_send_card ==============================

    @Test
    @DisplayName("卡片：私聊场景也用智能体虚拟身份（与群发是两条规则，判据不同不可互换）")
    void cardActorIsVirtualEvenInP2p() throws Exception {
        when(msgRpc.sendCardMessage(any())).thenReturn(msgDto(9001L));

        call(p2pCtx(), "im_send_card", "title", "构建完成", "desc", "点进去看", "actionUrl", "https://x/y");

        ArgumentCaptor<ImCardMessageRequest> cap = ArgumentCaptor.forClass(ImCardMessageRequest.class);
        verify(msgRpc).sendCardMessage(cap.capture());
        ImCardMessageRequest req = cap.getValue();
        assertEquals(AGENT_VIRTUAL, req.userId(), "换成本人会变成「用户自己发的消息」，往群里发则因非成员失败");
        assertEquals(P2P_CONV, req.convId(), "convId 缺省回落到本次触发的会话");
        ImCardBody card = req.card();
        assertEquals("构建完成", card.title());
        assertEquals("点进去看", card.desc());
        assertEquals("https://x/y", card.actionUrl());
        assertNull(card.subtitle());
        assertTrue(card.fields().isEmpty());
        assertTrue(card.actions().isEmpty());
    }

    @Test
    @DisplayName("卡片：iconUrl 与 fieldsJson 原样落到 card 上（图标 + 明细区不再是写死的 null/空表）")
    void cardCarriesIconAndFields() throws Exception {
        when(msgRpc.sendCardMessage(any())).thenReturn(msgDto(9002L));

        call(p2pCtx(), "im_send_card", "title", "数据核对完成", "desc", "共 120 条，异常 0 条",
                "iconUrl", "https://cdn.example.com/ok.png",
                "fieldsJson", "[{\"label\":\"工单号\",\"value\":\"WO-20260929-007\"},"
                        + "{\"label\":\"状态\",\"value\":\"已完成\"}]");

        ImCardBody card = capturedCard();
        assertEquals("https://cdn.example.com/ok.png", card.iconUrl());
        assertEquals(2, card.fields().size(), "两条明细都要落上：" + card.fields());
        assertEquals("工单号", card.fields().get(0).label());
        assertEquals("WO-20260929-007", card.fields().get(0).value(), "前导零不能被规范化掉");
        assertEquals("已完成", card.fields().get(1).value());
    }

    /**
     * ★契约明文要求 {@code label}/{@code value} 保持字符串（"金额/单号往往要保留前导零与精度，
     * 一旦按数值传输就会在某一跳被规范化"）。模型不一定会听话，所以这里连"写成数字"的形态一起钉住：
     * 收得下，且转出来的字面量是 {@code 120} 而不是 {@code 120.0}。
     */
    @Test
    @DisplayName("卡片明细：模型把值写成数字也收，但按字面量转字符串（120.0 不许渲染成 120.0）")
    void cardFieldValuesAreNormalizedToPlainStrings() throws Exception {
        when(msgRpc.sendCardMessage(any())).thenReturn(msgDto(9003L));

        call(p2pCtx(), "im_send_card", "title", "t",
                "fieldsJson", "[{\"label\":\"条数\",\"value\":120},{\"label\":\"占比\",\"value\":120.0}]");

        List<ImCardBody.Field> fields = capturedCard().fields();
        assertEquals("120", fields.get(0).value());
        assertEquals("120", fields.get(1).value(), "DoubleNode.asText() 会交出 120.0，那是肉眼可见的错");
    }

    @Test
    @DisplayName("卡片明细：fieldsJson 不是数组 / 项不是对象 / 缺 label 或 value / 不是 JSON，四种都给可读的报错")
    void malformedCardFieldsAreRejected() {
        record Case(String json, String expect) {
        }
        List<Case> cases = List.of(
                new Case("{\"label\":\"工单号\",\"value\":\"007\"}", "JSON 数组"),
                new Case("[\"工单号\"]", "对象"),
                new Case("[{\"label\":\"工单号\"}]", "label"),
                new Case("[{\"value\":\"007\"}]", "label"),
                new Case("[{\"label\":\"  \",\"value\":\"007\"}]", "label"),
                new Case("工单号=007", "不是合法的 JSON"));

        for (Case c : cases) {
            BizException e = assertThrows(BizException.class,
                    () -> call(p2pCtx(), "im_send_card", "title", "t", "fieldsJson", c.json()),
                    "应拒绝：" + c.json());
            assertTrue(e.getMessage().contains(c.expect()),
                    "报错要点出问题在哪（期望含「" + c.expect() + "」）：" + e.getMessage());
        }
        verifyNoInteractions(msgRpc);
    }

    /**
     * ★★{@code actions} 恒为空是<b>刻意的安全取舍</b>，不是"还没接" —— 理由见
     * {@code ImChatToolExecutor#sendCard} 的 javadoc。这条用例把它钉成"决定"而不是"巧合"：
     * 模型即使瞎传 actions/actionType/payload 这类键，也不会有一条动作落到卡片上
     * （IM 侧 {@code actionType} 是"服务端执行什么"的判据，放开等于让模型替 IM 编裁决参数）。
     */
    @Test
    @DisplayName("★卡片动作恒不发出：模型瞎传 actions/actionType/payload 也一律不生效")
    void cardActionsAreNeverEmitted() throws Exception {
        when(msgRpc.sendCardMessage(any())).thenReturn(msgDto(9004L));

        call(p2pCtx(), "im_send_card", "title", "入群申请", "desc", "张三申请加入本群",
                "actions", "[{\"key\":\"approve\",\"label\":\"通过\"}]",
                "actionsJson", "[{\"key\":\"reject\",\"label\":\"拒绝\"}]",
                "actionType", "join_request.review",
                "payload", "{\"decision\":\"approve\"}");

        ImCardBody card = capturedCard();
        assertTrue(card.actions().isEmpty(),
                "动作区必须始终为空：非注册类型是死按钮（点击 IM_10404），已注册类型则是替 IM 编裁决参数");
    }

    /** 取这次调用真正发出去的那张卡。 */
    private ImCardBody capturedCard() {
        ArgumentCaptor<ImCardMessageRequest> cap = ArgumentCaptor.forClass(ImCardMessageRequest.class);
        verify(msgRpc).sendCardMessage(cap.capture());
        return cap.getValue().card();
    }

    // ============================== 三个管理员工具 ==============================

    /**
     * ★管理员端点的<b>第二道</b>闸门（第一道在清单侧：非管理员根本看不到这几个工具）。
     *
     * <p>为什么清单侧已经藏了还要留这道：{@code dispatch} 是按 functionName 的字符串 switch，
     * 任何绕过清单进来的调用（未来新增入口、测试直接调 {@code execute}）都会落到这里。
     * 少一层广告不等于少一层权限 —— 万一清单侧的过滤被改坏，这里仍然拦得住。
     *
     * <p>★名单刻意取 {@link ImChatToolProvider#ADMIN_ONLY_TOOLS} 而不是在这里再写一遍字面量：
     * 两道闸门<b>共用一份名单</b>，所以不存在「清单说它不是管理员端点、执行侧却当它是」的漂移。
     * 加第四个管理员端点时只改那个常量，两边同时生效，这条测试也自动覆盖到它。
     */
    @Test
    @DisplayName("★管理员工具：非管理员一律 4030，且一个 RPC 都不许发出去（名单取自 ADMIN_ONLY_TOOLS）")
    void adminGatedToolsRejectNonAdmin() {
        List<String> adminOnly = ImChatToolProvider.ADMIN_ONLY_TOOLS;
        assertEquals(3, adminOnly.size(), "管理员端点数量变了要同步改技能文档（21 − 3 = 18）与 ProviderTest");
        for (String function : adminOnly) {
            BizException e = assertThrows(BizException.class, () -> call(p2pCtx(), function,
                    "toUserId", OTHER_USER, "toUserIds", List.of(OTHER_USER),
                    "eventType", "agent.task.completed", "text", "hi"),
                    function + " 必须做管理员判定");
            assertEquals(CODE_PERMISSION_DENIED, e.getCode(), function + " 应 4030");
        }
        // 群场景更严：调用主体是群代理，天然不是管理员
        for (String function : adminOnly) {
            assertThrows(BizException.class, () -> call(groupCtx(), function, "text", "hi"), function);
        }
        verifyNoInteractions(msgRpc, convRpc, contactRpc, eventRpc, agentRpc);
    }

    @Test
    @DisplayName("发系统消息：senderType=3 + msgType=system（只暴露 body 形状已验证的那一档）")
    void sendSystemUsesTheVerifiedBodyShape() throws Exception {
        when(msgRpc.sendSystemMessage(any())).thenReturn(msgDto(9101L));

        call(adminP2pCtx(), "im_send_system", "toUserId", OTHER_USER, "text", "任务已完成");

        ArgumentCaptor<ImSystemMessageRequest> cap = ArgumentCaptor.forClass(ImSystemMessageRequest.class);
        verify(msgRpc).sendSystemMessage(cap.capture());
        ImSystemMessageRequest req = cap.getValue();
        assertEquals(Integer.valueOf(ImSystemMessageRequest.SENDER_TYPE_SYSTEM), req.senderType());
        assertEquals("system", req.msgType());
        assertEquals("任务已完成", req.body().get("text").asText());
        assertEquals(AGENT_VIRTUAL, req.userId(), "系统消息以智能体身份发出");
        assertNull(req.convId());
        assertEquals(OTHER_USER, req.toUserId());
    }

    @Test
    @DisplayName("发系统消息：convId 与 toUserId 二选一，都不给就 4000（不能广播到全租户）")
    void sendSystemRequiresConvIdOrToUserId() {
        BizException e = assertThrows(BizException.class,
                () -> call(adminP2pCtx(), "im_send_system", "text", "hi"));
        assertEquals(CODE_INVALID_PARAM, e.getCode());
        assertTrue(e.getMessage().contains("convId"), "报错要说清二选一：" + e.getMessage());
        verifyNoInteractions(msgRpc);
    }

    @Test
    @DisplayName("★批量通知的 actor 是【管理员本人】，不是智能体虚拟身份（审计要看得出谁批的）")
    void systemNotifyActorIsTheAdminHimself() throws Exception {
        call(adminP2pCtx(), "im_system_notify", "toUserIds", Arrays.asList(2002L, "2003"), "text", "维护通知");

        ArgumentCaptor<ImSystemNotifyRequest> cap = ArgumentCaptor.forClass(ImSystemNotifyRequest.class);
        verify(msgRpc).sendSystemNotify(cap.capture());
        ImSystemNotifyRequest req = cap.getValue();
        assertEquals(SELF, req.userId());
        assertFalse(AGENT_VIRTUAL.equals(req.userId()), "批量通知不得以中间身份发出");
        assertEquals(List.of(2002L, 2003L), req.toUserIds());
        // ★DEV-298：system-notify 固定 text（纯文本气泡）；messages/system 那条（上方）仍是 system
        assertEquals("text", req.msgType());
    }

    @Test
    @DisplayName("自定义事件：payloadJson 解析成对象；非对象/坏 JSON 一律 4000，绝不发出去")
    void pushEventParsesPayloadJson() throws Exception {
        call(adminP2pCtx(), "im_push_event", "eventType", "agent.task.completed",
                "userIds", List.of(OTHER_USER), "payloadJson", "{\"taskId\":123,\"ok\":true}");

        ArgumentCaptor<ImCustomEventPushRequest> cap = ArgumentCaptor.forClass(ImCustomEventPushRequest.class);
        verify(eventRpc).pushCustomEvent(cap.capture());
        ImCustomEventPushRequest req = cap.getValue();
        assertEquals("agent.task.completed", req.eventType());
        assertEquals(List.of(OTHER_USER), req.userIds());
        assertEquals(123, req.payload().get("taskId"));
        assertEquals(Boolean.TRUE, req.payload().get("ok"));

        for (String bad : List.of("[1,2]", "\"str\"", "null", "{oops")) {
            BizException e = assertThrows(BizException.class,
                    () -> call(adminP2pCtx(), "im_push_event", "eventType", "t",
                            "userIds", List.of(OTHER_USER), "payloadJson", bad),
                    "payloadJson=" + bad + " 必须被拒");
            assertEquals(CODE_INVALID_PARAM, e.getCode());
            assertTrue(e.getMessage().contains("payloadJson"), "报错要指出是哪个参数：" + e.getMessage());
        }
        verify(eventRpc, times(1)).pushCustomEvent(any());
    }

    @Test
    @DisplayName("自定义事件：userIds 与 convId 二选一")
    void pushEventRequiresTargets() {
        BizException e = assertThrows(BizException.class,
                () -> call(adminP2pCtx(), "im_push_event", "eventType", "agent.task.completed"));
        assertEquals(CODE_INVALID_PARAM, e.getCode());
        assertTrue(e.getMessage().contains("userIds"), e.getMessage());
        verifyNoInteractions(eventRpc);
    }

    // ============================== im_revoke ==============================

    @Test
    @DisplayName("★私聊撤回：多读一次消息，senderId 是虚拟身份才切身份（按 senderId 选身份）")
    void revokeInP2pSwitchesToVirtualWhenAgentSentIt() throws Exception {
        when(msgRpc.getMessage(any())).thenReturn(detail(70001L, P2P_CONV, AGENT_VIRTUAL));

        call(p2pCtx(), "im_revoke", "msgId", 70001L, "reason", "发错了");

        ArgumentCaptor<ImMessageRevokeRequest> cap = ArgumentCaptor.forClass(ImMessageRevokeRequest.class);
        verify(msgRpc).revokeMessage(cap.capture());
        assertEquals(AGENT_VIRTUAL, cap.getValue().userId());
        assertEquals(70001L, cap.getValue().msgId());
        assertEquals("发错了", cap.getValue().reason());
        verify(msgRpc, times(1)).getMessage(any());
    }

    @Test
    @DisplayName("★私聊撤回：消息是别人发的 ⇒ actor 保持本人")
    void revokeInP2pKeepsSelfWhenSomeoneElseSentIt() throws Exception {
        when(msgRpc.getMessage(any())).thenReturn(detail(70002L, P2P_CONV, OTHER_USER));

        call(p2pCtx(), "im_revoke", "msgId", 70002L);

        ArgumentCaptor<ImMessageRevokeRequest> cap = ArgumentCaptor.forClass(ImMessageRevokeRequest.class);
        verify(msgRpc).revokeMessage(cap.capture());
        assertEquals(SELF, cap.getValue().userId());
    }

    @Test
    @DisplayName("私聊撤回：读不到那条消息 ⇒ 保持本人（不猜身份，让 IM 去判）")
    void revokeInP2pKeepsSelfWhenTargetMissing() throws Exception {
        when(msgRpc.getMessage(any())).thenReturn(null);

        call(p2pCtx(), "im_revoke", "msgId", 70003L);

        ArgumentCaptor<ImMessageRevokeRequest> cap = ArgumentCaptor.forClass(ImMessageRevokeRequest.class);
        verify(msgRpc).revokeMessage(cap.capture());
        assertEquals(SELF, cap.getValue().userId());
    }

    @Test
    @DisplayName("群场景撤回：scenarioActor 本身就是虚拟身份 ⇒ 不花那次多余的 messages/get")
    void revokeInGroupSkipsTheExtraRead() throws Exception {
        call(groupCtx(), "im_revoke", "msgId", 70004L);

        ArgumentCaptor<ImMessageRevokeRequest> cap = ArgumentCaptor.forClass(ImMessageRevokeRequest.class);
        verify(msgRpc).revokeMessage(cap.capture());
        assertEquals(AGENT_VIRTUAL, cap.getValue().userId());
        assertEquals(TRIGGER_CONV, cap.getValue().convId(), "convId 缺省回落到触发群");
        verify(msgRpc, never()).getMessage(any());
    }

    @Test
    @DisplayName("撤回：convId 与 msgId 都缺时报错，且不发 RPC")
    void revokeRequiresMsgId() {
        BizException e = assertThrows(BizException.class, () -> call(p2pCtx(), "im_revoke"));
        assertEquals(CODE_INVALID_PARAM, e.getCode());
        verifyNoInteractions(msgRpc);
    }

    // ============================== 排队区 ==============================

    @Test
    @DisplayName("★排队区：私聊场景也用虚拟身份，绝不回退本人（那个契约不接受真人主体）")
    void pendingUsesVirtualIdentityEvenInP2p() throws Exception {
        call(p2pCtx(), "im_pending_list");

        ArgumentCaptor<ImAgentPendingListRequest> cap =
                ArgumentCaptor.forClass(ImAgentPendingListRequest.class);
        verify(agentRpc).listPending(cap.capture());
        assertEquals(AGENT_VIRTUAL, cap.getValue().userId());
        assertFalse(SELF.equals(cap.getValue().userId()));
        assertEquals(P2P_CONV, cap.getValue().convId());
    }

    @Test
    @DisplayName("★排队区：没有虚拟身份就本地 fail-closed，不发 RPC")
    void pendingFailsClosedWithoutVirtual() {
        Map<String, Object> vars = p2pVars();
        vars.remove(ImChatIdentity.KEY_AGENT_VIRTUAL_USER_ID);

        BizException e = assertThrows(BizException.class, () -> call(ctx(vars, SELF, User.Role.User), "im_pending_list"));
        assertEquals(CODE_PERMISSION_DENIED, e.getCode());
        assertTrue(e.getMessage().contains(ImChatIdentity.KEY_AGENT_VIRTUAL_USER_ID), e.getMessage());
        verifyNoInteractions(agentRpc);
    }

    @Test
    @DisplayName("排队区清除：msgIds 必须送到")
    void pendingClearPassesMsgIds() throws Exception {
        call(p2pCtx(), "im_pending_clear", "msgIds", Arrays.asList(1L, "2"));

        ArgumentCaptor<ImAgentPendingClearRequest> cap =
                ArgumentCaptor.forClass(ImAgentPendingClearRequest.class);
        verify(agentRpc).clearPending(cap.capture());
        assertEquals(List.of(1L, 2L), cap.getValue().msgIds());
        assertEquals(AGENT_VIRTUAL, cap.getValue().userId());
    }

    // ============================== 查询类的 actor 与 convId 回落 ==============================

    @Test
    @DisplayName("群场景查询 ⇒ 虚拟身份；convId 缺省回落到触发群")
    void queryInGroupUsesVirtualIdentity() throws Exception {
        call(groupCtx(), "im_conversation_detail");

        ArgumentCaptor<ImConversationDetailRequest> cap =
                ArgumentCaptor.forClass(ImConversationDetailRequest.class);
        verify(convRpc).getConversationDetail(cap.capture());
        assertEquals(AGENT_VIRTUAL, cap.getValue().userId());
        assertEquals(TRIGGER_CONV, cap.getValue().convId());
    }

    @Test
    @DisplayName("私聊场景查询 ⇒ 本人身份；convId 缺省回落到 imConvId")
    void queryInP2pUsesSelfIdentity() throws Exception {
        call(p2pCtx(), "im_conversation_detail");

        ArgumentCaptor<ImConversationDetailRequest> cap =
                ArgumentCaptor.forClass(ImConversationDetailRequest.class);
        verify(convRpc).getConversationDetail(cap.capture());
        assertEquals(SELF, cap.getValue().userId());
        assertEquals(P2P_CONV, cap.getValue().convId());
    }

    @Test
    @DisplayName("★闸门①只管「发」，不管「读」：查询类显式给别的会话是放行的（授权判定在 IM 的成员校验上）")
    void gateAppliesToSendNotToRead() throws Exception {
        call(groupCtx(), "im_conversation_detail", "convId", OTHER_CONV);

        ArgumentCaptor<ImConversationDetailRequest> cap =
                ArgumentCaptor.forClass(ImConversationDetailRequest.class);
        verify(convRpc).getConversationDetail(cap.capture());
        assertEquals(OTHER_CONV, cap.getValue().convId());
    }

    @Test
    @DisplayName("convId 无处可回落时报 4000（不会拿 null 去调 IM）")
    void convIdRequiredWhenNoFallback() {
        AgentContext bare = ctx(new HashMap<>(), SELF, User.Role.User);
        BizException e = assertThrows(BizException.class, () -> call(bare, "im_conversation_detail"));
        assertEquals(CODE_INVALID_PARAM, e.getCode());
        assertTrue(e.getMessage().contains("convId"), e.getMessage());
        verifyNoInteractions(convRpc);
    }

    // ============================== 入参严格性 ==============================

    @Test
    @DisplayName("必填缺失 / 空白串都算没填（空白串发出去就是一条空消息）")
    void requiredAndBlankAreBothRejected() {
        BizException missing = assertThrows(BizException.class,
                () -> call(p2pCtx(), "im_send_p2p", "toUserId", OTHER_USER));
        assertEquals(CODE_INVALID_PARAM, missing.getCode());
        assertTrue(missing.getMessage().contains("text"), missing.getMessage());

        BizException blank = assertThrows(BizException.class,
                () -> call(p2pCtx(), "im_send_p2p", "toUserId", OTHER_USER, "text", "   "));
        assertEquals(CODE_INVALID_PARAM, blank.getCode());
        verifyNoInteractions(msgRpc);
    }

    @Test
    @DisplayName("★id 参数拒绝浮点（截断后是另一个人的 id），但认数字串")
    void idArgsRejectFloatsButAcceptNumericStrings() throws Exception {
        BizException e = assertThrows(BizException.class,
                () -> call(p2pCtx(), "im_send_p2p", "toUserId", 2002.7d, "text", "hi"));
        assertEquals(CODE_INVALID_PARAM, e.getCode());
        assertTrue(e.getMessage().contains("toUserId"), "要说清是哪个参数：" + e.getMessage());
        verifyNoInteractions(msgRpc);

        when(msgRpc.sendP2pMessage(any())).thenReturn(msgDto(9201L));
        call(p2pCtx(), "im_send_p2p", "toUserId", String.valueOf(OTHER_USER), "text", "hi");
        ArgumentCaptor<ImP2pMessageRequest> cap = ArgumentCaptor.forClass(ImP2pMessageRequest.class);
        verify(msgRpc).sendP2pMessage(cap.capture());
        assertEquals(OTHER_USER, cap.getValue().toUserId());
    }

    @Test
    @DisplayName("超出 int 上限的分页/数量参数报 4000（不能悄悄截断成负数）")
    void integerArgsAreBoundsChecked() {
        BizException e = assertThrows(BizException.class,
                () -> call(p2pCtx(), "im_list_messages", "limit", 1L << 40));
        assertEquals(CODE_INVALID_PARAM, e.getCode());
        assertTrue(e.getMessage().contains("上限"), e.getMessage());
        verifyNoInteractions(msgRpc);
    }

    @Test
    @DisplayName("单个标量当成一个元素的列表认下来（模型经常不给数组）")
    void singleScalarIsAcceptedAsOneElementList() throws Exception {
        call(p2pCtx(), "im_pending_clear", "msgIds", 70001L);

        ArgumentCaptor<ImAgentPendingClearRequest> cap =
                ArgumentCaptor.forClass(ImAgentPendingClearRequest.class);
        verify(agentRpc).clearPending(cap.capture());
        assertEquals(List.of(70001L), cap.getValue().msgIds());
    }

    // ============================== 失败必须是失败 ==============================

    @Test
    @DisplayName("★错误码翻译：本地问题→3612、可重试→3613、被拒→3614 三档互不混淆（不能被包成 5000）")
    void translateKeepsTheThreeCodesDistinct() {
        Map<String, ImRpcException> cases = new LinkedHashMap<>();
        cases.put("notConfigured", ImRpcException.notConfigured("im.adapter.secret 未配置"));
        cases.put("disabled", ImRpcException.disabled());
        cases.put("network", ImRpcException.network("connect refused", new RuntimeException()));
        cases.put("biz500", ImRpcException.httpStatus(500, null, "tid-1"));
        cases.put("bizRejected", ImRpcException.biz("IM_20106", "20106", "不允许给自己发消息", "tid-2", 200));
        cases.put("http404", ImRpcException.httpStatus(404, null, "tid-3"));

        Map<String, String> expect = Map.of(
                "notConfigured", CODE_IM_NOT_CONFIGURED,
                "disabled", CODE_IM_NOT_CONFIGURED,
                "network", CODE_IM_UNAVAILABLE,
                "biz500", CODE_IM_UNAVAILABLE,
                "bizRejected", CODE_IM_REJECTED,
                "http404", CODE_IM_REJECTED);

        for (Map.Entry<String, ImRpcException> entry : cases.entrySet()) {
            ImRpcException raw = entry.getValue();
            // ★SDK 现状：localProblem 与 retryable 互斥（notConfigured / disabled / serialization / closed
            //   都是 retryable=false），所以 translate 里两个 if 的先后【今天不可观测】。
            //   这条钉的是那个前提本身：哪天有人加了两者都为 true 的工厂，顺序立刻开始决定错误码，这条会先红。
            assertFalse(raw.isLocalProblem() && raw.isRetryable(),
                    entry.getKey() + " 既是本地问题又可重试 ⇒ translate 的分支顺序开始有意义，请一并复核");
            // ★只能用 doThrow：when(mock.m(..)) 会【真的调一次】m 去记录桩，
            //   而 m 上一轮已经被打成抛异常了 ⇒ 第 2 次循环就在打桩语句本身炸出来（不是断言失败）。
            doThrow(raw).when(msgRpc).sendP2pMessage(any());
            BizException e = assertThrows(BizException.class,
                    () -> call(p2pCtx(), "im_send_p2p", "toUserId", OTHER_USER, "text", "hi"),
                    entry.getKey());
            assertEquals(expect.get(entry.getKey()), e.getCode(),
                    entry.getKey() + " 的错误码错了（调用方靠 code 决定要不要带同一个 bizMsgId 重试）");
        }
        // ★被拒时 IM 的原文必须留在 message 里，否则模型和用户都只能看到一句「IM 拒绝了本次操作」
        doThrow(cases.get("bizRejected")).when(msgRpc).sendP2pMessage(any());
        BizException rejected = assertThrows(BizException.class,
                () -> call(p2pCtx(), "im_send_p2p", "toUserId", OTHER_USER, "text", "hi"));
        assertTrue(rejected.getMessage().contains("不允许给自己发消息"), rejected.getMessage());
    }

    @Test
    @DisplayName("RPC bean 没装配 ⇒ 3612「未配置」，不是 NPE 也不是「成功」")
    void missingRpcBeanIsReportedAsNotConfigured() {
        ImChatToolExecutor bare = new ImChatToolExecutor();
        BizException e = assertThrows(BizException.class,
                () -> call(bare, p2pCtx(), "im_send_p2p", "toUserId", OTHER_USER, "text", "hi"));
        assertEquals(CODE_IM_NOT_CONFIGURED, e.getCode());
    }

    @Test
    @DisplayName("未知工具名 / config 为 null ⇒ 4000，且不发 RPC")
    void unknownFunctionIsRejected() {
        BizException e = assertThrows(BizException.class, () -> call(p2pCtx(), "im_drop_database"));
        assertEquals(CODE_INVALID_PARAM, e.getCode());
        assertTrue(e.getMessage().contains("未知的 IM 工具"), e.getMessage());

        BizException noConfig = assertThrows(BizException.class,
                () -> executor.execute(p2pCtx(), null, new HashMap<>()));
        assertEquals(CODE_INVALID_PARAM, noConfig.getCode());
        verifyNoInteractions(msgRpc, convRpc, contactRpc, eventRpc, agentRpc);
    }

    // ============================== 清单侧与执行侧不许劈叉 ==============================

    /**
     * ★清单侧挂出来的每一个工具，执行侧都必须认得。
     *
     * <p>用<b>管理员口径</b>取清单（全量 21 个）：非管理员只有 18 个，用它验就等于
     * 那 3 个管理端点的「名字 ↔ switch 分支」对应关系永久不被检查 —— 而这类劈叉
     * 只在模型真调它的那一刻暴露，表现是「调了个不存在的函数」。
     *
     * <p>执行也用管理员上下文：否则那 3 个会先被 {@code requireAdmin()} 拦下（4030），
     * 而管理员判定跑在 switch <b>之前</b>，函数名写错也一样是 4030 —— 那就验不到分派本身了。
     */
    @Test
    @DisplayName("★清单侧挂出来的每一个工具，执行侧都必须认得（否则表现是「模型调了个不存在的函数」）")
    void everyMountedToolIsDispatchable() {
        ImChatToolProvider provider = new ImChatToolProvider();
        AgentContext adminGroup = adminGroupCtx();
        List<ComponentConfig> tools = provider.buildTools(ImChatIdentity.of(adminGroup));
        assertEquals(21, tools.size(), "IM 工具数量变了要同步改这条与技能文档");

        List<String> unknown = new ArrayList<>();
        for (ComponentConfig tool : tools) {
            try {
                executor.execute(adminGroup, tool, new HashMap<>());
            } catch (BizException e) {
                // 缺参数、非管理员、闸门拒绝都是预期的；「未知的 IM 工具」才是清单与分派劈叉
                if (e.getMessage() != null && e.getMessage().contains("未知的 IM 工具")) {
                    unknown.add(tool.getFunctionName());
                }
            } catch (RuntimeException e) {
                unknown.add(tool.getFunctionName() + "（抛了非 BizException：" + e + "）");
            }
        }
        assertTrue(unknown.isEmpty(), "这些工具执行侧不认识：" + unknown);
    }
}
