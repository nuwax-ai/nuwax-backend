package com.xspaceagi.system.application.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.xspaceagi.im.sdk.rpc.IImEventRpcService;
import com.xspaceagi.im.sdk.rpc.IImMessageRpcService;
import com.xspaceagi.im.sdk.rpc.IImRpcAvailability;
import com.xspaceagi.im.sdk.rpc.dto.ImCustomEventPushRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImCustomEventPushResultDto;
import com.xspaceagi.im.sdk.rpc.dto.ImSystemNotifyFailedDto;
import com.xspaceagi.im.sdk.rpc.dto.ImSystemNotifyRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImSystemNotifyResultDto;
import com.xspaceagi.system.application.dto.EventDto;
import com.xspaceagi.system.application.dto.SendNotifyMessageDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.AuthService;
import com.xspaceagi.system.application.service.TenantConfigApplicationService;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.domain.service.NotifyMessageDomainService;
import com.xspaceagi.system.infra.dao.entity.NotifyMessage;
import com.xspaceagi.system.infra.dao.service.NotifyMessageService;
import com.xspaceagi.system.spec.jackson.JsonSerializeUtil;
import com.xspaceagi.system.spec.utils.RedisUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link NotifyMessageApplicationServiceImpl} 的 IM 推送分支测试 —— IM 侧 RPC 全部 Mockito 桩掉。
 *
 * <h2>本类要锁住的是八件事</h2>
 *
 * <ol>
 *   <li><b>启用闸门</b>：{@code nuwax.im.adapter.enabled} 关着时<b>一条 RPC 都不发</b>
 *       （不是「照发、接住异常」）；取不到启用状态同样按未启用处理（fail-closed）。
 *       见 {@link #imDisabledMeansNoRpcEvenForBroadcast()} 等五个用例。</li>
 *   <li><b>★通道已统一（DEV-291 的行为变化）</b>：{@code Broadcast} / {@code System} /
 *       {@code Private} <b>全都</b>走 {@code messages/system-notify} —— 替换前 {@code Private}
 *       走 {@code events/push}，理由是「只有事件通道能批量」，而 system-notify 同时给了批量
 *       与落库（未读红点 / 进历史）⇒ 理由消失。见
 *       {@link #privateScopeAlsoGoesOutAsSystemNotify()}。</li>
 *   <li><b>actor 是「本批首个收件人」，且 senderId 一个字段都不许漏出去</b>：IM 侧
 *       {@code requireContext} 要求 {@code userId > 0}（senderId 可能是 -1），而 actor
 *       在这条路上只作 runAs / 租户 / 审计归属，不决定展示出来的发送者
 *       （发送者是合成账号「系统通知」）。见 {@link #actorIsTheBatchHeadAndNeverTheSenderId()}。</li>
 *   <li><b>tenantId 兜底</b>：IM 要求 {@code tenantId > 0}，而多数调用方（Broadcast 群发、mcp、
 *       无 id 重载）根本没传 —— 必须按收件人反查；查不到就<b>跳过</b>，不猜。</li>
 *   <li><b>分批与分租户</b>：IM 一次调用只能一个租户、且收件人 >100 时<b>整批拒绝</b>
 *       （不截断）⇒ 这两件事不做的话，一条都发不出去。</li>
 *   <li><b>幂等键</b>：IM 的幂等键是<b>整批一个</b>，所以键必须唯一标识「这一批是谁」——
 *       用<b>去重后分块的首元素</b>（块互不相交 ⇒ 首元素唯一），同一输入重放逐字相同。
 *       见 {@link #batchesWithinOneTenantGetDistinctBizMsgIds()}。</li>
 *   <li><b>失败不阻断</b>：IM 抛异常、以及「部分收件人被逐条跳过」都绝不能冒泡到调用方
 *       （提现审批 / MCP 部署 / 文档评论都走这里），且后者<b>不</b>自动重试。</li>
 *   <li><b>★事件桥（{@code publishEvent} → {@code events/push}）的边界</b>：会话类事件桥过去，
 *       但 {@code new_notify_message} <b>不</b>桥（它已由 system-notify 承载，桥了就是同一条通知
 *       双发，还会把 Broadcast 放大成每收件人一次 RPC）；payload 用<b>不带类型信息</b>的序列化
 *       （{@code @class} 一个字节都不许漏给 IM 前端）；租户只按 userId 反查、反查不到就跳过；
 *       超上限整条跳过而不截断。见「事件桥」一节。</li>
 * </ol>
 */
class NotifyMessageApplicationServiceImplTest {

    private static final long NOTIFY_ID = 9001L;

    private final NotifyMessageDomainService notifyMessageDomainService = mock(NotifyMessageDomainService.class);
    private final NotifyMessageService notifyMessageService = mock(NotifyMessageService.class);
    private final UserApplicationService userApplicationService = mock(UserApplicationService.class);
    private final TenantConfigApplicationService tenantConfigApplicationService = mock(TenantConfigApplicationService.class);
    private final AuthService authService = mock(AuthService.class);
    private final RedisUtil redisUtil = mock(RedisUtil.class);
    private final IImMessageRpcService imMessageRpcService = mock(IImMessageRpcService.class);
    private final IImEventRpcService imEventRpcService = mock(IImEventRpcService.class);
    private final IImRpcAvailability imRpcAvailability = mock(IImRpcAvailability.class);

    private NotifyMessageApplicationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new NotifyMessageApplicationServiceImpl();
        ReflectionTestUtils.setField(service, "notifyMessageDomainService", notifyMessageDomainService);
        ReflectionTestUtils.setField(service, "notifyMessageService", notifyMessageService);
        ReflectionTestUtils.setField(service, "userApplicationService", userApplicationService);
        ReflectionTestUtils.setField(service, "tenantConfigApplicationService", tenantConfigApplicationService);
        ReflectionTestUtils.setField(service, "authService", authService);
        ReflectionTestUtils.setField(service, "redisUtil", redisUtil);
        ReflectionTestUtils.setField(service, "imMessageRpcService", imMessageRpcService);
        ReflectionTestUtils.setField(service, "imEventRpcService", imEventRpcService);
        ReflectionTestUtils.setField(service, "imRpcAvailability", imRpcAvailability);

        // ★IM 适配默认按【已启用】桩住：本类绝大多数用例验的是「启用后怎么推」，
        //   未启用时整批跳过那条路径由 imDisabled* 五个用例单独钉。
        when(imRpcAvailability.enabled()).thenReturn(true);
        // save 要回填自增 id，否则幂等键退化成随机值、本类多条断言失去意义
        when(notifyMessageService.save(any(NotifyMessage.class))).thenAnswer(invocation -> {
            invocation.<NotifyMessage>getArgument(0).setId(NOTIFY_ID);
            return true;
        });
        // publishEvent 会遍历 clientIds，桩成空列表避免 NPE（与"用户没有在线客户端"等价）
        when(authService.getUserClientIds(anyLong())).thenReturn(List.of());
        when(userApplicationService.queryUserListByIds(anyList())).thenReturn(List.of());
    }

    // ================================================================ 启用闸门（第一件事）

    @Test
    @DisplayName("★IM 适配未启用 → 一条 RPC 都不发、连收件人租户都不反查，但通知照常落库")
    void imDisabledMeansNoRpcEvenForBroadcast() {
        when(imRpcAvailability.enabled()).thenReturn(false);
        when(userApplicationService.queryUserIdList(anyLong(), anyInt()))
                .thenReturn(List.of(1L, 2L), List.of());

        service.sendNotifyMessage(dto(NotifyMessage.MessageScope.Broadcast, null, null));

        verify(imMessageRpcService, never()).sendSystemNotify(any());
        // ★闸门只挡 IM：广播主流程（落库 + 建收件人行）一步不少
        verify(notifyMessageService).save(any(NotifyMessage.class));
        verify(notifyMessageDomainService).addNotifyMessage(any(NotifyMessage.class), anyList());
        // ★「整批跳过」的字面证据：连那条批量反查租户的调用都没发生，不是「查了但没发」
        verify(userApplicationService, never()).queryUserListByIds(anyList());
    }

    @Test
    @DisplayName("★未启用时与 scope 无关（通道已统一，闸门在通道之前）")
    void imDisabledStopsPushWhateverTheScope() {
        when(imRpcAvailability.enabled()).thenReturn(false);

        service.sendNotifyMessage(dto(NotifyMessage.MessageScope.Private, 1L, List.of(7L, 8L)));

        verify(imMessageRpcService, never()).sendSystemNotify(any());
        verify(notifyMessageService).save(any(NotifyMessage.class));
    }

    @Test
    @DisplayName("★无 id 重载（提现/MCP/评论走的第三条调用点）同样受闸门管")
    void imDisabledAlsoAppliesToTheNoIdOverload() {
        when(imRpcAvailability.enabled()).thenReturn(false);
        // ★这条桩是必须的：没有它，收件人租户反查为空 ⇒ 就算闸门被删掉也「恰好」发不出去，
        //   用例会变成一条两个世界都绿的假用例（变异验证时实测到了这一点）。给了租户，
        //   闸门就是这条用例唯一能拦住推送的东西。
        when(userApplicationService.queryUserListByIds(anyList())).thenReturn(List.of(user(7L, 1L)));

        service.sendNotifyMessage(7L, "桌搭设备下单成功");

        verify(imMessageRpcService, never()).sendSystemNotify(any());
        verify(notifyMessageDomainService).addNotifyMessage(any(NotifyMessage.class), anyList());
    }

    @Test
    @DisplayName("★取不到启用状态（bean 缺席）按未启用处理 —— fail-closed，不朝不确定的 IM 发消息")
    void missingAvailabilityBeanIsTreatedAsDisabled() {
        ReflectionTestUtils.setField(service, "imRpcAvailability", null);
        when(userApplicationService.queryUserListByIds(anyList())).thenReturn(List.of(user(7L, 1L)));

        service.sendNotifyMessage(dto(NotifyMessage.MessageScope.System, 1L, List.of(7L)));

        verify(imMessageRpcService, never()).sendSystemNotify(any());
        verify(notifyMessageService).save(any(NotifyMessage.class));
    }

    @Test
    @DisplayName("读启用状态本身抛异常也按未启用处理，且绝不冒泡到调用方")
    void availabilityLookupFailureIsTreatedAsDisabled() {
        when(imRpcAvailability.enabled()).thenThrow(new IllegalStateException("config boom"));
        when(userApplicationService.queryUserListByIds(anyList())).thenReturn(List.of(user(7L, 1L)));

        assertThatCode(() -> service.sendNotifyMessage(dto(NotifyMessage.MessageScope.System, 1L, List.of(7L))))
                .doesNotThrowAnyException();

        verify(imMessageRpcService, never()).sendSystemNotify(any());
        verify(notifyMessageService).save(any(NotifyMessage.class));
    }

    // ================================================================ 通道已统一（DEV-291 行为变化）

    @Test
    @DisplayName("System → system-notify，收件人原样带上")
    void systemScopeGoesOutAsSystemNotify() {
        service.sendNotifyMessage(dto(NotifyMessage.MessageScope.System, 1L, List.of(7L)));

        List<ImSystemNotifyRequest> requests = capturedNotifyRequests();
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).toUserIds()).containsExactly(7L);
    }

    @Test
    @DisplayName("★★Private 也走 system-notify —— 替换 `events/push` 就是要换掉这个分支")
    void privateScopeAlsoGoesOutAsSystemNotify() {
        service.sendNotifyMessage(dto(NotifyMessage.MessageScope.Private, 1L, List.of(7L, 8L)));

        List<ImSystemNotifyRequest> requests = capturedNotifyRequests();
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).toUserIds()).containsExactly(7L, 8L);
        // ★替换前这条消息在 IM 侧不落库、离线即丢；现在它进「系统通知」会话（未读红点 + 历史）
        // ★DEV-298 起固定 text（纯文本气泡），不再是 system（居中提示条）
        assertThat(requests.get(0).msgType()).isEqualTo(ImSystemNotifyRequest.MSG_TYPE_TEXT);
    }

    @Test
    @DisplayName("★Broadcast 现在是【整批一次】而不是每收件人一次 —— 量级从 N 次 RPC 降到 ⌈N/100⌉")
    void broadcastGoesOutAsOneBatchNotPerRecipient() {
        when(userApplicationService.queryUserIdList(anyLong(), anyInt()))
                .thenReturn(List.of(1L, 2L), List.of());
        when(userApplicationService.queryUserListByIds(anyList()))
                .thenReturn(List.of(user(1L, 1L), user(2L, 1L)));

        service.sendNotifyMessage(dto(NotifyMessage.MessageScope.Broadcast, null, null));

        List<ImSystemNotifyRequest> requests = capturedNotifyRequests();
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).toUserIds()).containsExactly(1L, 2L);
    }

    // ================================================================ actor / 消息体 / ext

    @Test
    @DisplayName("★actor 是本批首个收件人，且 senderId 一个字段都不许漏出去")
    void actorIsTheBatchHeadAndNeverTheSenderId() {
        // senderId 故意给一个"看起来像身份"的值：它绝不能出现在任何字段上
        service.sendNotifyMessage(SendNotifyMessageDto.builder()
                .tenantId(1L)
                .senderId(555L)
                .scope(NotifyMessage.MessageScope.System)
                .content("提现已通过")
                .userIds(List.of(7L, 8L))
                .build());

        ImSystemNotifyRequest req = capturedNotifyRequests().get(0);
        assertThat(req.userId()).as("actor = 本批第一个收件人").isEqualTo(7L);
        assertThat(req.tenantId()).isEqualTo(1L);
        assertThat(req.toUserIds()).containsExactly(7L, 8L);
        assertThat(req.bizMsgId()).contains("7");
        assertThat(req.userId()).isNotEqualTo(555L);
    }

    @Test
    @DisplayName("★msgType 固定 text（DEV-298 气泡）、正文在 body.text；ext 带出关联字段（notifyId / scope）")
    void bodyIsTheSystemTextAndExtCarriesTheCorrelationFields() {
        service.sendNotifyMessage(dto(NotifyMessage.MessageScope.System, 1L, List.of(7L), "提现已通过"));

        ImSystemNotifyRequest req = capturedNotifyRequests().get(0);
        assertThat(req.msgType()).isEqualTo(ImSystemNotifyRequest.MSG_TYPE_TEXT);
        JsonNode body = req.body();
        assertThat(body.get("text").asText()).isEqualTo("提现已通过");
        // ★ext 是替换事件通道后唯一的「这条 IM 通知对应平台哪条通知」的线索
        assertThat(req.ext()).contains("\"notifyId\":\"" + NOTIFY_ID + "\"");
        assertThat(req.ext()).contains("\"scope\":\"System\"");
    }

    @Test
    @DisplayName("★senderId = -1 的调用路径也能推出去（actor 不看它）")
    void theNoIdOverloadAlsoPushesEvenThoughItsSenderIdIsMinusOne() {
        when(userApplicationService.queryUserListByIds(anyList())).thenReturn(List.of(user(7L, 1L)));

        service.sendNotifyMessage(7L, "桌搭设备下单成功");

        ImSystemNotifyRequest req = capturedNotifyRequests().get(0);
        assertThat(req.userId()).isEqualTo(7L);
        assertThat(req.toUserIds()).containsExactly(7L);
        assertThat(req.body().get("text").asText()).isEqualTo("桌搭设备下单成功");
        assertThat(req.ext()).contains("\"senderId\":-1");
    }

    @Test
    @DisplayName("★内容空白时本地就不发 —— 否则收件人只会看到一个「点开什么都没有」的未读红点")
    void blankContentIsSkippedLocally() {
        service.sendNotifyMessage(dto(NotifyMessage.MessageScope.System, 1L, List.of(7L), "   "));

        verify(imMessageRpcService, never()).sendSystemNotify(any());
        // 主流程照常（通知本身仍然落库，只是不追加 IM 触达）
        verify(notifyMessageService).save(any(NotifyMessage.class));
    }

    // ================================================================ tenantId 兜底

    @Test
    @DisplayName("通知没带 tenantId 时按收件人反查（IM 要求 tenantId > 0）")
    void tenantIdFallsBackToTheRecipientLookup() {
        when(userApplicationService.queryUserListByIds(anyList())).thenReturn(List.of(user(7L, 42L)));

        service.sendNotifyMessage(dto(NotifyMessage.MessageScope.System, null, List.of(7L)));

        assertThat(capturedNotifyRequests().get(0).tenantId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("反查不到租户就跳过，绝不猜一个租户顶上")
    void notifyWithoutResolvableTenantIsSkipped() {
        when(userApplicationService.queryUserListByIds(anyList())).thenReturn(List.of());

        service.sendNotifyMessage(dto(NotifyMessage.MessageScope.System, null, List.of(7L)));

        verify(imMessageRpcService, never()).sendSystemNotify(any());
    }

    // ================================================================ 分批与分租户

    @Test
    @DisplayName("★IM 一次调用只能一个租户 —— 跨租户收件人必须分组")
    void recipientsAreSplitByTenant() {
        when(userApplicationService.queryUserListByIds(anyList()))
                .thenReturn(List.of(user(7L, 1L), user(8L, 2L)));

        service.sendNotifyMessage(dto(NotifyMessage.MessageScope.Private, null, List.of(7L, 8L)));

        List<ImSystemNotifyRequest> requests = capturedNotifyRequests();
        assertThat(requests).hasSize(2);
        assertThat(requests).extracting(ImSystemNotifyRequest::tenantId).containsExactlyInAnyOrder(1L, 2L);
        assertThat(requests).extracting(ImSystemNotifyRequest::toUserIds)
                .containsExactlyInAnyOrder(List.of(7L), List.of(8L));
    }

    @Test
    @DisplayName("★收件人超过 100 就自己分批 —— IM 是【整批拒绝】不截断，不分批连一条都发不出去")
    void recipientsAreChunkedByTheImLimit() {
        List<Long> userIds = IntStream.rangeClosed(1, 250).mapToObj(Long::valueOf).toList();

        service.sendNotifyMessage(dto(NotifyMessage.MessageScope.Private, 1L, userIds));

        List<ImSystemNotifyRequest> requests = capturedNotifyRequests();
        assertThat(requests).hasSize(3);
        assertThat(requests.get(0).toUserIds()).hasSize(100);
        assertThat(requests.get(1).toUserIds()).hasSize(100);
        assertThat(requests.get(2).toUserIds()).hasSize(50);
        // ★同一批内不允许重复寻址同一个收件人
        assertThat(requests.stream().flatMap(req -> req.toUserIds().stream()).distinct()).hasSize(250);
    }

    @Test
    @DisplayName("收件人为空时一个 RPC 都不发")
    void noRecipientsMeansNoRpc() {
        service.sendNotifyMessage(dto(NotifyMessage.MessageScope.Private, 1L, List.of()));

        verify(imMessageRpcService, never()).sendSystemNotify(any());
        verify(userApplicationService, never()).queryUserListByIds(anyList());
    }

    // ================================================================ 幂等键

    @Test
    @DisplayName("★同一份输入重放 → 幂等键逐字相同（IM 靠它把重发挡在门外）")
    void bizMsgIdIsStablePerNotifyAndBatchHead() {
        service.sendNotifyMessage(dto(NotifyMessage.MessageScope.System, 1L, List.of(7L, 8L)));
        service.sendNotifyMessage(dto(NotifyMessage.MessageScope.System, 1L, List.of(7L, 8L)));

        List<ImSystemNotifyRequest> requests = capturedNotifyRequests();
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).bizMsgId()).isEqualTo("notify-" + NOTIFY_ID + "-7");
        assertThat(requests.get(1).bizMsgId()).isEqualTo(requests.get(0).bizMsgId());
    }

    @Test
    @DisplayName("★★同一租户内多批的幂等键必须互不相同 —— 块首唯一这条不变量一破，两批会互相吞掉")
    void batchesWithinOneTenantGetDistinctBizMsgIds() {
        List<Long> userIds = IntStream.rangeClosed(1, 250).mapToObj(Long::valueOf).toList();

        service.sendNotifyMessage(dto(NotifyMessage.MessageScope.Private, 1L, userIds));

        List<String> bizMsgIds = capturedNotifyRequests().stream()
                .map(ImSystemNotifyRequest::bizMsgId)
                .toList();
        assertThat(bizMsgIds).hasSize(3).doesNotHaveDuplicates();
        // ★每批的键由【该批首元素】拼成：第 2 批的首元素是 101（第 1 批是 1..100）
        assertThat(bizMsgIds).containsExactly(
                "notify-" + NOTIFY_ID + "-1",
                "notify-" + NOTIFY_ID + "-101",
                "notify-" + NOTIFY_ID + "-201");
    }

    @Test
    @DisplayName("★没有 notifyId 时用随机键而非内容哈希 —— 宁可不去重，也不能让同内容第二次被静默吞掉")
    void bizMsgIdIsFreshWhenThereIsNoNotifyId() {
        when(userApplicationService.queryUserListByIds(anyList())).thenReturn(List.of(user(7L, 1L)));

        service.sendNotifyMessage(7L, "同样的内容");
        service.sendNotifyMessage(7L, "同样的内容");

        List<ImSystemNotifyRequest> requests = capturedNotifyRequests();
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).bizMsgId()).isNotBlank();
        assertThat(requests.get(0).bizMsgId()).isNotEqualTo(requests.get(1).bizMsgId());
    }

    // ================================================================ 失败不阻断

    @Test
    @DisplayName("★IM 抛异常绝不冒泡 —— 调用方是提现审批 / MCP 部署 / 文档评论，不能因 IM 没配好而失败")
    void imFailureNeverPropagatesToTheCaller() {
        when(imMessageRpcService.sendSystemNotify(any()))
                .thenThrow(new IllegalStateException("im not configured"));

        assertThatCode(() -> service.sendNotifyMessage(dto(NotifyMessage.MessageScope.System, 1L, List.of(7L))))
                .doesNotThrowAnyException();

        // 通知本身必须已经落库 + 建好收件人行 —— IM 只是追加通道
        verify(notifyMessageService).save(any(NotifyMessage.class));
        verify(notifyMessageDomainService).addNotifyMessage(any(NotifyMessage.class), anyList());
    }

    @Test
    @DisplayName("★「部分收件人被跳过」既不冒泡、也【不】自动重试（故障不该升级成重试风暴）")
    void perRecipientFailureDoesNotPropagateAndIsNotRetried() {
        when(imMessageRpcService.sendSystemNotify(any()))
                .thenReturn(new ImSystemNotifyResultDto(2, 1, List.of(), List.of(
                        new ImSystemNotifyFailedDto(8L, "IM_20103: 会话不存在"))));

        assertThatCode(() -> service.sendNotifyMessage(dto(NotifyMessage.MessageScope.System, 1L, List.of(7L, 8L))))
                .doesNotThrowAnyException();

        // ★恰好一次：有 failed 就自己换 key 重发是错的（同一 bizMsgId 会被 IM 回放，换 key 则会给
        //   已成功的人再发一条）。补发是运维按文档走的纪律，不是这里的自动行为。
        verify(imMessageRpcService, times(1)).sendSystemNotify(any());
    }

    @Test
    @DisplayName("★替换 IM 通道不影响平台自己的长连接事件队列（event_queue 与 IM 无关）")
    void platformEventQueueIsIndependentOfTheImChannel() {
        when(authService.getUserClientIds(7L)).thenReturn(List.of("client-1"));

        service.sendNotifyMessage(dto(NotifyMessage.MessageScope.System, 1L, List.of(7L)));

        verify(redisUtil).leftPush(eq("event_queue:client-1"), anyString());
    }

    // ================================================================ 事件桥（events/push）

    @Test
    @DisplayName("★★通知事件【不】桥到 events/push —— 它已由 system-notify 承载，桥了就是同一条通知双发")
    void notifyMessageEventIsNotBridgedToIm() {
        // ★租户故意给全：反查得到租户也照样不桥 ⇒ 拦住它的是【类型排除】，
        //   而不是"恰好查不到租户"（否则删掉排除集合这条用例也还是绿的，白钉）。
        when(userApplicationService.queryUserListByIds(anyList())).thenReturn(List.of(user(7L, 1L)));
        when(authService.getUserClientIds(7L)).thenReturn(List.of("client-1"));

        service.publishEvent(7L, EventDto.builder()
                .type(EventDto.EVENT_TYPE_NEW_NOTIFY_MESSAGE)
                .event(Map.of("id", 9001L))
                .build());

        verify(imEventRpcService, never()).pushCustomEvent(any());
        // ★平台那条腿一个字都没变
        verify(redisUtil).leftPush(eq("event_queue:client-1"), anyString());
    }

    @Test
    @DisplayName("★Broadcast 不会把一条全员通知放大成 N 次 events/push（它是逐个收件人调 publishEvent 的）")
    void broadcastDoesNotAmplifyIntoPerRecipientEventPushes() {
        when(userApplicationService.queryUserIdList(anyLong(), anyInt()))
                .thenReturn(List.of(1L, 2L, 3L), List.of());
        when(userApplicationService.queryUserListByIds(anyList()))
                .thenReturn(List.of(user(1L, 1L), user(2L, 1L), user(3L, 1L)));

        service.sendNotifyMessage(dto(NotifyMessage.MessageScope.Broadcast, null, null));

        // system-notify 那条腿：整批一次
        assertThat(capturedNotifyRequests()).hasSize(1);
        // ★事件桥：一次都没有（3 个收件人 ⇒ 3 次 publishEvent，全被类型排除挡在最前面）
        verify(imEventRpcService, never()).pushCustomEvent(any());
    }

    @Test
    @DisplayName("★会话类事件桥到 events/push：eventType 原样透传、收件人是本人、payload 不带类型信息")
    void chatEventIsBridgedWithPlainPayload() {
        when(userApplicationService.queryUserListByIds(anyList())).thenReturn(List.of(user(7L, 42L)));
        when(authService.getUserClientIds(7L)).thenReturn(List.of("client-1"));

        service.publishEvent(7L, chatFinishedEvent());

        List<ImCustomEventPushRequest> requests = capturedEventRequests();
        assertThat(requests).hasSize(1);
        ImCustomEventPushRequest req = requests.get(0);
        // ★eventType 就是平台这套常量本身，不另造一套命名（两边前端要对得上）
        assertThat(req.eventType()).isEqualTo(EventDto.EVENT_TYPE_CHAT_FINISHED);
        assertThat(req.userIds()).containsExactly(7L);
        assertThat(req.convId()).as("平台事件发给【人】，不是发给某个会话的全体成员").isNull();
        assertThat(req.userId()).as("actor = 收件人本人").isEqualTo(7L);
        assertThat(req.tenantId()).as("租户来自反查").isEqualTo(42L);
        assertThat(req.payload()).containsEntry("conversationId", "88");

        // ★同一个 EventDto 在两条腿上是【两种序列化】：平台 event_queue 那份带类型信息
        //   （@class，回放时要用它还原 EventDto），IM 那份不带。
        //   ★"不带"这条断言在【本用例的输入下】是钉不住的 —— Map.of 是 final 类，
        //   NON_FINAL 的默认 typing 根本不给它加 @class，两种口径输出逐字相同。
        //   真正能泄 @class 的是嵌进去的 POJO，见 nestedPojoNeverLeaksItsJavaTypeToIm。
        ArgumentCaptor<String> redisJson = ArgumentCaptor.forClass(String.class);
        verify(redisUtil).leftPush(eq("event_queue:client-1"), redisJson.capture());
        assertThat(redisJson.getValue()).contains("@class").contains("EventDto");
    }

    @Test
    @DisplayName("★★event 里嵌 POJO 时 @class 绝不许漏给 IM —— 真实的 refresh_chat_message 就嵌着 ChatMessageDto")
    void nestedPojoNeverLeaksItsJavaTypeToIm() {
        when(userApplicationService.queryUserListByIds(anyList())).thenReturn(List.of(user(7L, 1L)));
        when(authService.getUserClientIds(7L)).thenReturn(List.of("client-1"));
        // ★形状照抄 AsyncExecuteResponseHandler：外层 Map、里面嵌一个非 final 的 POJO
        Map<String, Object> event = Map.of("conversationId", "88", "message", user(7L, 1L));

        // ★对照断言：同一份 event 用平台自己回放 event_queue 的口径序列化，@class 就出来了
        //   （外层 Map.of 是 final 不加，嵌进去的 UserDto 不是 ⇒ 带上 Java 全限定类名）。
        //   没有这行，下面那条 doesNotContain 可能只是因为"输入里压根没有 POJO"而假绿。
        assertThat(JsonSerializeUtil.toJSONStringGeneric(event)).contains("@class");

        service.publishEvent(7L, EventDto.builder()
                .type(EventDto.EVENT_TYPE_REFRESH_CHAT_MESSAGE)
                .event(event)
                .build());

        ImCustomEventPushRequest req = capturedEventRequests().get(0);
        // 嵌套字段照常透传（不是被整个丢掉、也不是只剩一层壳）
        assertThat(req.payload()).containsKey("message");
        assertThat(JsonSerializeUtil.toJSONString(req.payload().get("message"))).contains("tenantId");
        // ★★真正要钉的：@class 与内部包名一个字节都不许出现在发给 IM 的 payload 里
        String imJson = JsonSerializeUtil.toJSONString(req.payload());
        assertThat(imJson).doesNotContain("@class").doesNotContain("com.xspaceagi");
    }

    @Test
    @DisplayName("★tenantId 只按 userId 反查；反查不到就跳过，绝不拿 RequestContext 里的租户顶")
    void eventWithoutResolvableTenantIsSkipped() {
        when(userApplicationService.queryUserListByIds(anyList())).thenReturn(List.of());
        when(authService.getUserClientIds(7L)).thenReturn(List.of("client-1"));

        service.publishEvent(7L, chatFinishedEvent());

        verify(imEventRpcService, never()).pushCustomEvent(any());
        // ★平台那条腿照发：租户只是 IM 的硬约束，不是事件的
        verify(redisUtil).leftPush(eq("event_queue:client-1"), anyString());
    }

    @Test
    @DisplayName("★IM 未启用 → 事件桥一条都不发、连租户都不反查（闸门在反查之前），平台那条腿照常")
    void imDisabledMeansNoEventBridge() {
        when(imRpcAvailability.enabled()).thenReturn(false);
        when(userApplicationService.queryUserListByIds(anyList())).thenReturn(List.of(user(7L, 1L)));
        when(authService.getUserClientIds(7L)).thenReturn(List.of("client-1"));

        service.publishEvent(7L, chatFinishedEvent());

        verify(imEventRpcService, never()).pushCustomEvent(any());
        // ★"整条跳过"的字面证据：那次批量反查根本没发生
        verify(userApplicationService, never()).queryUserListByIds(anyList());
        verify(redisUtil).leftPush(eq("event_queue:client-1"), anyString());
    }

    @Test
    @DisplayName("★events/push 抛异常绝不冒泡（调用方是聊天主流程），且平台那条腿已经完成")
    void eventBridgeFailureNeverPropagates() {
        when(userApplicationService.queryUserListByIds(anyList())).thenReturn(List.of(user(7L, 1L)));
        when(authService.getUserClientIds(7L)).thenReturn(List.of("client-1"));
        when(imEventRpcService.pushCustomEvent(any())).thenThrow(new IllegalStateException("im not configured"));

        assertThatCode(() -> service.publishEvent(7L, chatFinishedEvent())).doesNotThrowAnyException();

        verify(redisUtil).leftPush(eq("event_queue:client-1"), anyString());
    }

    @Test
    @DisplayName("★eventRpc bean 缺席时不 NPE —— fail-closed，与 availability 同理")
    void missingEventRpcBeanIsTreatedAsDisabled() {
        ReflectionTestUtils.setField(service, "imEventRpcService", null);
        when(userApplicationService.queryUserListByIds(anyList())).thenReturn(List.of(user(7L, 1L)));
        when(authService.getUserClientIds(7L)).thenReturn(List.of("client-1"));

        assertThatCode(() -> service.publishEvent(7L, chatFinishedEvent())).doesNotThrowAnyException();

        verify(redisUtil).leftPush(eq("event_queue:client-1"), anyString());
    }

    @Test
    @DisplayName("★payload 超过上限就整条跳过 —— 截断会给出损坏的 JSON，那比不发更糟")
    void oversizedEventPayloadIsSkipped() {
        when(userApplicationService.queryUserListByIds(anyList())).thenReturn(List.of(user(7L, 1L)));
        when(authService.getUserClientIds(7L)).thenReturn(List.of("client-1"));

        service.publishEvent(7L, EventDto.builder()
                .type(EventDto.EVENT_TYPE_REFRESH_CHAT_MESSAGE)
                .event(Map.of("text", "x".repeat(20 * 1024)))
                .build());

        verify(imEventRpcService, never()).pushCustomEvent(any());
        // ★平台 event_queue 没有这个上限，那份仍然完整（收件端还能从那儿拉到）
        verify(redisUtil).leftPush(eq("event_queue:client-1"), anyString());
    }

    @Test
    @DisplayName("★event 不是 JSON 对象（标量/数组）时包一层 value，而不是把事件整个丢掉")
    void nonObjectEventIsWrapped() {
        when(userApplicationService.queryUserListByIds(anyList())).thenReturn(List.of(user(7L, 1L)));
        when(authService.getUserClientIds(7L)).thenReturn(List.of("client-1"));

        service.publishEvent(7L, EventDto.builder().type("custom_type").event("一段纯文本").build());

        assertThat(capturedEventRequests().get(0).payload()).containsEntry("value", "一段纯文本");
    }

    @Test
    @DisplayName("★幂等键每次都是新的 —— EventDto 没有稳定 id，宁可不去重也不能拿内容哈希当键")
    void eventBizMsgIdIsFreshEveryTime() {
        when(userApplicationService.queryUserListByIds(anyList())).thenReturn(List.of(user(7L, 1L)));
        when(authService.getUserClientIds(7L)).thenReturn(List.of("client-1"));

        service.publishEvent(7L, chatFinishedEvent());
        service.publishEvent(7L, chatFinishedEvent());

        List<String> bizMsgIds = capturedEventRequests().stream()
                .map(ImCustomEventPushRequest::bizMsgId)
                .toList();
        assertThat(bizMsgIds).hasSize(2).doesNotHaveDuplicates();
        assertThat(bizMsgIds.get(0)).startsWith("plat-evt-7-");
    }

    @Test
    @DisplayName("★userId 非正数时跳过（IM 硬要求 userId > 0），但平台那条腿照发")
    void nonPositiveUserIdIsNotBridged() {
        when(authService.getUserClientIds(0L)).thenReturn(List.of("client-1"));

        service.publishEvent(0L, chatFinishedEvent());

        verify(imEventRpcService, never()).pushCustomEvent(any());
        verify(userApplicationService, never()).queryUserListByIds(anyList());
    }

    @Test
    @DisplayName("★三个读数为 0 是【正常返回】不是失败（没人在线而已），不重试也不冒泡")
    void nobodyOnlineIsANormalResultNotAFailure() {
        when(userApplicationService.queryUserListByIds(anyList())).thenReturn(List.of(user(7L, 1L)));
        when(authService.getUserClientIds(7L)).thenReturn(List.of("client-1"));
        when(imEventRpcService.pushCustomEvent(any()))
                .thenReturn(new ImCustomEventPushResultDto(1, 0, 0));

        assertThatCode(() -> service.publishEvent(7L, chatFinishedEvent())).doesNotThrowAnyException();

        // ★恰好一次：events/push 本来就不承诺送达，重试只是再丢一次
        verify(imEventRpcService, times(1)).pushCustomEvent(any());
    }

    // ================================================================ helpers

    private EventDto<Object> chatFinishedEvent() {
        return EventDto.builder()
                .type(EventDto.EVENT_TYPE_CHAT_FINISHED)
                .event(Map.of("conversationId", "88", "requestId", "req-1"))
                .build();
    }

    private SendNotifyMessageDto dto(NotifyMessage.MessageScope scope, Long tenantId, List<Long> userIds) {
        return dto(scope, tenantId, userIds, "通知内容");
    }

    private SendNotifyMessageDto dto(NotifyMessage.MessageScope scope, Long tenantId, List<Long> userIds, String content) {
        return SendNotifyMessageDto.builder()
                .tenantId(tenantId)
                .senderId(-1L)
                .scope(scope)
                .content(content)
                .userIds(userIds)
                .build();
    }

    private UserDto user(Long id, Long tenantId) {
        UserDto userDto = new UserDto();
        userDto.setId(id);
        userDto.setTenantId(tenantId);
        return userDto;
    }

    private List<ImSystemNotifyRequest> capturedNotifyRequests() {
        ArgumentCaptor<ImSystemNotifyRequest> captor = ArgumentCaptor.forClass(ImSystemNotifyRequest.class);
        verify(imMessageRpcService, atLeast(0)).sendSystemNotify(captor.capture());
        return new ArrayList<>(captor.getAllValues());
    }

    private List<ImCustomEventPushRequest> capturedEventRequests() {
        ArgumentCaptor<ImCustomEventPushRequest> captor = ArgumentCaptor.forClass(ImCustomEventPushRequest.class);
        verify(imEventRpcService, atLeast(0)).pushCustomEvent(captor.capture());
        return new ArrayList<>(captor.getAllValues());
    }
}
