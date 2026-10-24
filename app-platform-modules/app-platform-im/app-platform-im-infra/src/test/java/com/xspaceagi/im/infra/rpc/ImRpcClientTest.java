package com.xspaceagi.im.infra.rpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xspaceagi.im.sdk.rpc.ImRpcException;
import com.xspaceagi.im.sdk.rpc.dto.ImAgentPendingClearDto;
import com.xspaceagi.im.sdk.rpc.dto.ImAgentPendingClearRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImAgentPendingListDto;
import com.xspaceagi.im.sdk.rpc.dto.ImAgentPendingListRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImCardBody;
import com.xspaceagi.im.sdk.rpc.dto.ImCardMessageRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImContactListDto;
import com.xspaceagi.im.sdk.rpc.dto.ImContactListRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImConversationDetailDto;
import com.xspaceagi.im.sdk.rpc.dto.ImConversationDetailRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImConversationListRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImConversationPageDto;
import com.xspaceagi.im.sdk.rpc.dto.ImCustomEventPushRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImDigestUpdateRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImGroupCreateRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImGroupMessageRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImMemberAddRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImMemberListRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImMemberRemoveRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImMessageDto;
import com.xspaceagi.im.sdk.rpc.dto.ImMessageGetRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImMessageListRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImMessageRevokeRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImP2pConversationRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImP2pMessageRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImReadCountsRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImSystemMessageRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImSystemNotifyRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ImRpcClient} 的契约测试 —— 用一个<b>真实的</b> HTTP 服务端（JDK 自带的
 * {@code com.sun.net.httpserver}，零额外依赖）承接请求。
 *
 * <h2>★为什么不用 mock 掉 HttpClient</h2>
 *
 * <p>本类要证明的东西有一半在「字节层」：签名签的是<b>真正发出去的那串字节</b>、
 * 六个头的取值、Content-Type 的字符集声明、21 个端点各自落在哪条路径上。
 * mock 掉传输层就等于把这些全部跳过，测出来的只是「我调用了某个方法」。
 *
 * <h2>★桩服务端独立复算签名</h2>
 *
 * <p>桩服务端不复用 {@link ImHmacSigner}，而是直接拿 {@code MessageDigest} / {@code Mac}
 * 按契约重算一遍（见 {@link #expectedSignature}）。
 * 复用被测代码去验被测代码，只能得到「自己和自己一致」。
 */
class ImRpcClientTest {

    private static final String APP_ID = "app-platform-im";
    private static final String SECRET = "unit-test-secret-密钥";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 桩服务端返回的最小合法 data（{@code ImMessageDto}）。 */
    private static final String MESSAGE_DTO_JSON =
            "{\"msgId\":9001,\"convId\":7,\"seq\":3,\"sendTime\":1700000000000,\"idempotentHit\":false}";

    private HttpServer server;
    private ImRpcProperties properties;
    private final List<CapturedRequest> captured = new CopyOnWriteArrayList<>();

    /** 桩服务端的应答策略，默认返回 {@code code=0000} + 一个最小 data。 */
    private Responder responder;
    /** 桩服务端返回的 tid，用于验证「服务端给的追踪 ID 被原样带出」。 */
    private String stubTid = "tid-stub";

    @BeforeEach
    void startStubServer() throws IOException {
        captured.clear();
        stubTid = "tid-stub";
        responder = exchange -> ok("0000", null, "成功", defaultDataFor(exchange.getRequestURI().getPath()));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            // ★先读 body 再应答：handler 在写响应之前就把这次请求记下来了，
            //   于是 http.send() 返回时 captured 必定已经包含它（测试不会 flaky）。
            byte[] body = exchange.getRequestBody().readAllBytes();
            captured.add(new CapturedRequest(exchange, body));
            Response response = responder.respond(exchange);
            byte[] payload = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", response.contentType());
            exchange.sendResponseHeaders(response.status(), payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();

        properties = new ImRpcProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setAppId(APP_ID);
        properties.setSecret(SECRET);
        properties.setApiVersion("v1");
        properties.setTimeout(Duration.ofSeconds(3));
        properties.setConnectTimeout(Duration.ofSeconds(3));
    }

    @AfterEach
    void stopStubServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ================================================================== 头 / 签名 / 路径

    @Test
    @DisplayName("每个请求都带齐契约要求的 6 个头，且签名可被服务端【独立复算】通过")
    void everyRequestCarriesSixHeadersAndVerifiableSignature() {
        new ImRpcClient(properties).sendP2pMessage(ImP2pMessageRequest.builder()
                .actor(11L, 22L, "biz-1").traceId("trace-1")
                .toUserId(33L).msgType("text")
                .body(JSON.createObjectNode().put("text", "你好"))
                .build());

        assertThat(captured).hasSize(1);
        CapturedRequest req = captured.get(0);

        assertThat(req.method()).isEqualTo("POST");
        assertThat(req.header(ImRpcClient.HEADER_APP_ID)).isEqualTo(APP_ID);
        assertThat(req.header(ImRpcClient.HEADER_API_VERSION)).isEqualTo("v1");
        assertThat(req.header(ImRpcClient.HEADER_NONCE)).isNotBlank();
        assertThat(req.header(ImRpcClient.HEADER_REQUEST_ID)).isEqualTo("trace-1");
        // ★显式 UTF-8：body 字节是 UTF-8，声明里不写清楚就可能被按 ISO-8859-1 解读，中文直接乱码。
        assertThat(req.header("Content-Type")).isEqualTo("application/json; charset=UTF-8");
        assertThat(req.header(ImRpcClient.HEADER_TIMESTAMP)).matches("\\d{13}");

        // ★★本类最关键的一条：签名必须能被「只按契约实现」的一方复算出来。
        assertThat(req.header(ImRpcClient.HEADER_SIGNATURE))
                .isEqualTo(expectedSignature(APP_ID, req.header(ImRpcClient.HEADER_TIMESTAMP),
                        req.header(ImRpcClient.HEADER_NONCE), req.body()));

        // ★签的必须是【实际发出的字节】：把 body 原样再算一次能对上，
        //   说明没有「签名时序列化一遍、发送时再序列化一遍」（顺序一变就是偶发 IM_90004）。
        assertThat(new String(req.body(), StandardCharsets.UTF_8))
                .contains("\"userId\":11")
                .contains("\"tenantId\":22")
                .contains("\"bizMsgId\":\"biz-1\"")
                .contains("你好");
    }

    @Test
    @DisplayName("traceId 缺失或超 64 字符时，X-Request-Id 退化为 32 位随机串（不让观测字段否决业务请求）")
    void requestIdFallsBackWhenTraceIdUnusable() {
        ImRpcClient client = new ImRpcClient(properties);
        client.sendP2pMessage(p2p("trace-ok"));
        client.sendP2pMessage(p2p(null));
        client.sendP2pMessage(p2p("x".repeat(65)));

        assertThat(captured).hasSize(3);
        assertThat(captured.get(0).header(ImRpcClient.HEADER_REQUEST_ID)).isEqualTo("trace-ok");
        assertThat(captured.get(1).header(ImRpcClient.HEADER_REQUEST_ID)).matches("[0-9a-f]{32}");
        assertThat(captured.get(2).header(ImRpcClient.HEADER_REQUEST_ID)).matches("[0-9a-f]{32}");
    }

    @Test
    @DisplayName("每个请求的 nonce 都不同（服务端用它做重放去重，复用即 IM_90004）")
    void nonceIsUniquePerRequest() {
        ImRpcClient client = new ImRpcClient(properties);
        for (int i = 0; i < 5; i++) {
            client.sendP2pMessage(p2p("t" + i));
        }
        assertThat(captured).hasSize(5);
        assertThat(captured.stream().map(r -> r.header(ImRpcClient.HEADER_NONCE)).distinct()).hasSize(5);
    }

    @Test
    @DisplayName("21 个端点各自落在契约规定的路径上，且全部是 POST")
    void allTwentyEndpointsHitContractPaths() {
        ImRpcClient client = new ImRpcClient(properties);

        client.sendP2pMessage(p2p(null));
        client.sendGroupMessage(ImGroupMessageRequest.builder().actor(1L, 1L, "b").convId(1L).msgType("text")
                .body(JSON.createObjectNode()).build());
        client.sendSystemMessage(ImSystemMessageRequest.builder().actor(1L, 1L, "b").convId(1L).msgType("text")
                .body(JSON.createObjectNode()).build());
        // ★DEV-291：+1 系统通知（写端点 ⇒ bizMsgId 必填；批量收件人）
        client.sendSystemNotify(ImSystemNotifyRequest.builder().actor(1L, 1L, "b").toUserIds(List.of(2L, 3L))
                .msgType(ImSystemNotifyRequest.MSG_TYPE_SYSTEM)
                .body(JSON.createObjectNode().put("text", "通知内容")).build());
        client.sendCardMessage(ImCardMessageRequest.builder().actor(1L, 1L, "b").convId(1L)
                .card(new ImCardBody("审批", "张三申请加入", null, null, null, null, null)).build());
        client.revokeMessage(ImMessageRevokeRequest.builder().actor(1L, 1L, "b").convId(1L).msgId(2L).build());
        client.getMessage(ImMessageGetRequest.builder().actor(1L, 1L, null).convId(1L).msgId(2L).build());
        client.getReadCounts(ImReadCountsRequest.builder().actor(1L, 1L, null).convId(1L).msgIds(List.of(1L)).build());
        client.listMessagesByTimeRange(ImMessageListRequest.builder().actor(1L, 1L, null).convId(1L).limit(20).build());
        client.createGroup(ImGroupCreateRequest.builder().actor(1L, 1L, "b").name("群").build());
        client.addMembers(ImMemberAddRequest.builder().actor(1L, 1L, "b").convId(1L).userIds(List.of(2L)).build());
        client.removeMembers(ImMemberRemoveRequest.builder().actor(1L, 1L, "b").convId(1L).userIds(List.of(2L)).build());
        client.listMembers(ImMemberListRequest.builder().actor(1L, 1L, null).convId(1L).limit(10).build());
        client.getOrCreateP2p(ImP2pConversationRequest.builder().actor(1L, 1L, "b").toUserId(2L).build());
        client.updateDigest(ImDigestUpdateRequest.builder().actor(1L, 1L, "b").convId(1L).digest("d").build());
        client.listConversations(ImConversationListRequest.builder().actor(1L, 1L, null)
                .cursor("t1|1700000000000|1700000000001|7").limit(20).build());
        // ★DEV-288：+1 会话详情（读端点 ⇒ bizMsgId 传 null）
        client.getConversationDetail(ImConversationDetailRequest.builder().actor(1L, 1L, null).convId(1L).build());
        client.listContacts(ImContactListRequest.builder().actor(1L, 1L, null).build());
        client.pushCustomEvent(ImCustomEventPushRequest.builder().actor(1L, 1L, "b").userIds(List.of(2L))
                .eventType("demo").build());
        // ★DEV-285：+2 排队区（list 是读端点 ⇒ bizMsgId 传 null，与其它读端点同形）
        client.listPending(ImAgentPendingListRequest.builder().actor(1L, 1L, null).convId(1L).build());
        client.clearPending(ImAgentPendingClearRequest.builder().actor(1L, 1L, "b").convId(1L)
                .msgIds(List.of(2L)).build());

        assertThat(captured).hasSize(21);
        // ★路径写错的表现是 404/405，而不是「签名不对」—— 逐个钉死，改路径时这里必红。
        assertThat(captured.stream().map(CapturedRequest::path)).containsExactly(
                "/internal/im/rpc/messages/p2p",
                "/internal/im/rpc/messages/group",
                "/internal/im/rpc/messages/system",
                "/internal/im/rpc/messages/system-notify",
                "/internal/im/rpc/messages/card",
                "/internal/im/rpc/messages/revoke",
                "/internal/im/rpc/messages/get",
                "/internal/im/rpc/messages/read-counts",
                "/internal/im/rpc/messages/list",
                "/internal/im/rpc/conversations/group",
                "/internal/im/rpc/conversations/members/add",
                "/internal/im/rpc/conversations/members/remove",
                "/internal/im/rpc/conversations/members/list",
                "/internal/im/rpc/conversations/p2p",
                "/internal/im/rpc/conversations/digest",
                "/internal/im/rpc/conversations/list",
                "/internal/im/rpc/conversations/detail",
                "/internal/im/rpc/contacts/list",
                "/internal/im/rpc/events/push",
                "/internal/im/rpc/agents/pending/list",
                "/internal/im/rpc/agents/pending/clear");
        assertThat(captured).allSatisfy(r -> {
            assertThat(r.method()).isEqualTo("POST");
            assertThat(r.header(ImRpcClient.HEADER_SIGNATURE)).isNotBlank();
        });
    }

    @Test
    @DisplayName("只读端点不要求 bizMsgId：契约里它们不参与幂等闸门")
    void readOnlyEndpointsDoNotRequireBizMsgId() {
        ImRpcClient client = new ImRpcClient(properties);
        client.getMessage(ImMessageGetRequest.builder().actor(1L, 1L, null).convId(1L).msgId(2L).build());
        client.listMembers(ImMemberListRequest.builder().actor(1L, 1L, null).convId(1L).limit(10).build());

        assertThat(captured).hasSize(2);
        assertThat(new String(captured.get(0).body(), StandardCharsets.UTF_8)).contains("\"bizMsgId\":null");
    }

    // ================================================================== ★DEV-277 两个读端点

    @Test
    @DisplayName("★会话列表：cursor 原样上行、nextCursor 原样下行；被投影裁掉的字段（draft/readSeq/groupNo）下发也不炸")
    void conversationListRoundTripsCursorAndToleratesTrimmedFields() {
        ImConversationPageDto page = new ImRpcClient(properties).listConversations(
                ImConversationListRequest.builder().actor(11L, 22L, null)
                        .cursor("t1|1700000000000|1700000000001|7").limit(20).build());

        // ★游标必须逐字上行：任何「顺手 trim / 解析再拼回去」都会让它变成 IM_40002 或静默错页。
        assertThat(new String(captured.get(0).body(), StandardCharsets.UTF_8))
                .contains("\"cursor\":\"t1|1700000000000|1700000000001|7\"")
                .contains("\"limit\":20");
        assertThat(page.hasMoreOrFalse()).isTrue();
        assertThat(page.nextCursor()).isEqualTo("t1|1700000000002|1700000000001|7");
        assertThat(page.size()).isEqualTo(1);
        // 投影保留的字段照常可用；被裁字段不报错（本层不读它们，但不否认它们存在）
        assertThat(page.listOrEmpty().get(0).unreadOrZero()).isEqualTo(2);
        assertThat(page.listOrEmpty().get(0).isP2p()).isTrue();
    }

    @Test
    @DisplayName("★★会话详情：groupNo / ext 是本端点相对列表的【唯一增量】—— 列表恒为 null 的两个字段只有这里拿得到")
    void conversationDetailCarriesGroupNoAndExt() {
        ImConversationDetailDto detail = new ImRpcClient(properties).getConversationDetail(
                ImConversationDetailRequest.builder().actor(11L, 22L, null).convId(7001L).build());

        // ★请求体：身份三元组 + 唯一的业务字段 convId。★读端点 ⇒ bizMsgId 允许为 null（服务端不过幂等闸门）。
        String body = new String(captured.get(0).body(), StandardCharsets.UTF_8);
        assertThat(body).contains("\"convId\":7001")
                .contains("\"userId\":11")
                .contains("\"tenantId\":22")
                .contains("\"bizMsgId\":null");

        assertThat(detail.convId()).isEqualTo(7001L);
        assertThat(detail.isGroup()).isTrue();
        assertThat(detail.isTeam()).isFalse();
        // ★★这两条就是本端点存在的理由：列表路径一律拿不到它们（平台投影裁掉 groupNo/ext 的原文理由是
        //   「列表路径恒为 null」，而详情恰恰是它们唯一的出处）。
        assertThat(detail.groupNo()).isEqualTo("G-7001");
        assertThat(detail.ext()).isEqualTo("{\"k\":1}");
        // ★中立字段照常；成员视角下视角字段也可用
        assertThat(detail.name()).isEqualTo("研发群");
        assertThat(detail.memberCount()).isEqualTo(3);
        assertThat(detail.spaceId()).isEqualTo(555L);
        assertThat(detail.viewerIsMember()).isTrue();
        assertThat(detail.unreadCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("★★★会话详情·非成员：视角字段整块 null，而 unreadCount 是【0 而不是 null】⇒ 判成员只认 myRole")
    void conversationDetailOfNonMemberIsJudgedByMyRoleNotUnreadCount() {
        responder = exchange -> ok("0000", null, "成功",
                "{\"convId\":7001,\"convType\":2,\"name\":\"研发群\",\"memberCount\":3,\"status\":1,"
                        + "\"spaceId\":555,\"groupNo\":\"G-7001\",\"myRole\":null,\"myNickName\":null,"
                        + "\"unreadCount\":0,\"topFlag\":null,\"dnd\":null,\"visible\":null,"
                        + "\"joinTime\":null,\"ext\":\"{\\\"k\\\":1}\"}");

        ImConversationDetailDto detail = new ImRpcClient(properties).getConversationDetail(
                ImConversationDetailRequest.builder().actor(99L, 22L, null).convId(7001L).build());

        // ★★本用例钉的是一句会咬人的话：非成员时 unreadCount 是 0（不是 null）。
        //   ⇒ 判「他是不是成员」只认 myRole != null；用 unreadCount == 0 会把「看不见」读成「没有未读」。
        //   ★变异验证：把 viewerIsMember() 改成 unreadCount != null 或 unreadCount == 0 判断，本用例必红。
        assertThat(detail.myRole()).isNull();
        assertThat(detail.unreadCount()).isZero();
        assertThat(detail.viewerIsMember()).isFalse();
        // ★中立字段照常返回 —— 豁免成员闸门的价值就在这里：查得到群本身
        assertThat(detail.name()).isEqualTo("研发群");
        assertThat(detail.groupNo()).isEqualTo("G-7001");
        assertThat(detail.ext()).isEqualTo("{\"k\":1}");
        assertThat(detail.spaceId()).isEqualTo(555L);
        assertThat(detail.memberCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("★★联系人：degraded（列表完整、只是资料没取到）与 truncated（列表缩水）【互不影响】—— 混了就会错报")
    void contactListKeepsDegradedAndTruncatedIndependent() {
        ImContactListDto contacts = new ImRpcClient(properties).listContacts(
                ImContactListRequest.builder().actor(11L, 22L, null).build());

        // 桩返回 degraded=true + truncated=false：结果集完整，两个标志必须各归各位
        assertThat(contacts.degradedOrFalse()).as("资料缺失 ≠ 列表缩水").isTrue();
        assertThat(contacts.truncatedOrFalse()).as("没被 500 上限截断").isFalse();
        assertThat(contacts.size()).as("★degraded 不影响条数").isEqualTo(2);
        // 三态混合：不替调用方筛，但要提供筛的便捷方法
        assertThat(contacts.itemsOrEmpty()).extracting(i -> i.status()).containsExactly(1, 2);
        assertThat(contacts.acceptedOnly()).extracting(i -> i.userId()).containsExactly(33L);
        // ★类型判断只看 subjectType（人与智能体的 id 长得一样，不能按段位猜）
        assertThat(contacts.itemsOrEmpty().get(1).agent()).isTrue();
        // ★展示名用服务端算好的那份；服务端没给时才回落
        assertThat(contacts.itemsOrEmpty().get(0).displayNameOrFallback()).isEqualTo("老张");
    }

    // ================================================================== ★DEV-285 排队区两个端点

    @Test
    @DisplayName("★★排队区 list：正文优先取完整 text、缺了才回落 digest；条数与列表长度以列表为准")
    void pendingListPrefersFullTextOverDigest() {
        ImAgentPendingListDto pending = new ImRpcClient(properties).listPending(
                ImAgentPendingListRequest.builder().actor(11L, 22L, null).convId(7L).build());

        assertThat(pending.size()).isEqualTo(2);
        assertThat(pending.isEmpty()).as("有排队消息").isFalse();
        assertThat(pending.hitCap()).as("2 条远没到 500 上限，不是异常堆积").isFalse();
        // ★第一条两个字段都有：必须给【完整正文】，不能给截断摘要 —— 摘要会把撤回前的内容发出去。
        assertThat(pending.itemsOrEmpty().get(0).readableText()).isEqualTo("完整正文");
        // ★第二条只有 digest（非文本类型，服务端回退）：照样有可读内容，不是空串。
        assertThat(pending.itemsOrEmpty().get(1).readableText()).isEqualTo("[图片]");
        assertThat(pending.itemsOrEmpty().get(0).msgId()).as("clear 时按它点名").isEqualTo(9001L);
    }

    @Test
    @DisplayName("★排队区 clear：removedMsgIds 是判据（removedCount 只是冗余字段，不要拿请求条数比它）")
    void pendingClearIsJudgedByRemovedMsgIds() {
        ImAgentPendingClearDto cleared = new ImRpcClient(properties).clearPending(
                ImAgentPendingClearRequest.builder().actor(11L, 22L, "b").convId(7L)
                        .msgIds(List.of(9001L, 9002L)).build());

        // 桩只删掉了 9001（9002 已被 IM 侧取走是正常竞态）—— 这【不是】失败。
        assertThat(cleared.removedSize()).isEqualTo(1);
        assertThat(cleared.removedNothing()).isFalse();
        assertThat(cleared.removed(9001L)).isTrue();
        assertThat(cleared.removed(9002L)).as("★「删少了」是正常结局，不该报错也不该误判成没删").isFalse();
    }

    // ================================================================== 成功 / 幂等

    @Test
    @DisplayName("code=0000 时返回 data，不抛异常")
    void successEnvelopeReturnsData() {
        ImMessageDto dto = new ImRpcClient(properties).sendP2pMessage(p2p("t"));
        assertThat(dto.msgId()).isEqualTo(9001L);
        assertThat(dto.convId()).isEqualTo(7L);
        assertThat(dto.idempotentHit()).isFalse();
    }

    @Test
    @DisplayName("★code=IM_20005（幂等命中）按契约【视为成功】，同样返回 data，不抛异常")
    void idempotentHitIsTreatedAsSuccess() {
        responder = exchange -> ok("IM_20005", null, null,
                "{\"msgId\":9001,\"convId\":7,\"seq\":3,\"sendTime\":1700000000000,\"idempotentHit\":true}");

        // ★这条最容易被实现成抛异常：IM_20005 长得就像个错误码。
        //   真抛了的话，「带同一个 bizMsgId 重试」这条唯一的补偿路径会直接崩在调用方手里。
        ImMessageDto dto = new ImRpcClient(properties).sendP2pMessage(p2p("biz-retry"));
        assertThat(dto.idempotentHit()).isTrue();
        assertThat(dto.msgId()).isEqualTo(9001L);
    }

    @Test
    @DisplayName("void 端点：data 为 null 也能正常返回")
    void voidEndpointsAcceptNullData() {
        responder = exchange -> ok("0000", null, "成功", null);
        new ImRpcClient(properties).revokeMessage(
                ImMessageRevokeRequest.builder().actor(1L, 1L, "b").convId(1L).msgId(2L).build());
        assertThat(captured).hasSize(1);
    }

    @Test
    @DisplayName("服务端多返回未知字段，不应让老客户端硬故障")
    void unknownResponseFieldsAreTolerated() {
        responder = exchange -> ok("0000", null, "成功",
                "{\"msgId\":1,\"convId\":2,\"seq\":3,\"sendTime\":4,\"idempotentHit\":false,\"brandNewField\":\"x\"}");

        assertThat(new ImRpcClient(properties).sendP2pMessage(p2p("b")).msgId()).isEqualTo(1L);
    }

    // ================================================================== 失败分类

    @Test
    @DisplayName("业务错误码 → ImRpcException，码/ tid 原样带出，且按白名单判定可否重试")
    void businessErrorIsClassifiedByWhitelist() {
        stubTid = "tid-biz";
        responder = exchange -> ok("IM_20101", "GROUP_FULL", "群成员已满", null);

        assertThatThrownBy(() -> new ImRpcClient(properties).sendP2pMessage(p2p("b")))
                .isInstanceOf(ImRpcException.class)
                .satisfies(e -> {
                    ImRpcException rpc = (ImRpcException) e;
                    assertThat(rpc.getCode()).isEqualTo("IM_20101");
                    assertThat(rpc.getDisplayCode()).isEqualTo("GROUP_FULL");
                    assertThat(rpc.getMessage()).contains("群成员已满");
                    assertThat(rpc.getTid()).isEqualTo("tid-biz");
                    assertThat(rpc.isRetryable()).as("业务拒绝不可重试").isFalse();
                    assertThat(rpc.isLocalProblem()).as("请求已经发出去了").isFalse();
                });
    }

    @Test
    @DisplayName("IM_90002（服务繁忙）属于可重试白名单")
    void busyCodeIsRetryable() {
        responder = exchange -> ok("IM_90002", null, "服务繁忙", null);

        assertThatThrownBy(() -> new ImRpcClient(properties).sendP2pMessage(p2p("b")))
                .isInstanceOf(ImRpcException.class)
                .satisfies(e -> assertThat(((ImRpcException) e).isRetryable()).isTrue());
    }

    @Test
    @DisplayName("IM_90004（验签失败）【不可】重试 —— 重试只会得到同样的拒绝，还会掩盖『密钥配错了』这个唯一根因")
    void signatureFailureIsNotRetryable() {
        responder = exchange -> ok("IM_90004", null, "验签失败", null);

        assertThatThrownBy(() -> new ImRpcClient(properties).sendP2pMessage(p2p("b")))
                .isInstanceOf(ImRpcException.class)
                .satisfies(e -> assertThat(((ImRpcException) e).isRetryable()).isFalse());
    }

    @Test
    @DisplayName("HTTP 500 → 可重试；HTTP 405 → 不可重试且带状态码（405 通常是 base-url 填成了对外域名）")
    void httpStatusDrivesRetryability() {
        responder = exchange -> new Response(500, "application/json", "{\"code\":\"0001\",\"message\":\"boom\"}");

        assertThatThrownBy(() -> new ImRpcClient(properties).sendP2pMessage(p2p("b")))
                .isInstanceOf(ImRpcException.class)
                .satisfies(e -> {
                    assertThat(((ImRpcException) e).getHttpStatus()).isEqualTo(500);
                    assertThat(((ImRpcException) e).isRetryable()).isTrue();
                    assertThat(((ImRpcException) e).isLocalProblem()).as("请求确实发出去了").isFalse();
                });

        responder = exchange -> new Response(405, "text/html", "<html>405 Not Allowed</html>");

        assertThatThrownBy(() -> new ImRpcClient(properties).sendP2pMessage(p2p("b")))
                .isInstanceOf(ImRpcException.class)
                .satisfies(e -> {
                    assertThat(((ImRpcException) e).getHttpStatus()).isEqualTo(405);
                    assertThat(((ImRpcException) e).isRetryable()).isFalse();
                });
    }

    @Test
    @DisplayName("HTTP 非 200 但响应体是合法 ReqResult 时，服务端业务码要原样带出（IM_10401 比「HTTP 401」有用得多）")
    void envelopeCodeSurvivesNonOkStatus() {
        responder = exchange -> new Response(401, "application/json",
                "{\"code\":\"IM_10401\",\"message\":\"未授权\"}");

        assertThatThrownBy(() -> new ImRpcClient(properties).sendP2pMessage(p2p("b")))
                .isInstanceOf(ImRpcException.class)
                .satisfies(e -> {
                    assertThat(((ImRpcException) e).getCode()).isEqualTo("IM_10401");
                    assertThat(((ImRpcException) e).getHttpStatus()).isEqualTo(401);
                });
    }

    @Test
    @DisplayName("响应不是合法 ReqResult 信封 → malformed（可重试）")
    void malformedEnvelopeIsFlagged() {
        responder = exchange -> new Response(200, "application/json", "这不是 JSON");

        assertThatThrownBy(() -> new ImRpcClient(properties).sendP2pMessage(p2p("b")))
                .isInstanceOf(ImRpcException.class)
                .satisfies(e -> {
                    ImRpcException rpc = (ImRpcException) e;
                    assertThat(rpc.getMessage()).contains("ReqResult");
                    assertThat(rpc.isRetryable()).isTrue();
                    assertThat(rpc.isLocalProblem()).isFalse();
                });
    }

    @Test
    @DisplayName("★3xx 不会被跟随 —— 否则签名头（一次性调用凭据）会被送去一个我们从未打算信任的主机")
    void redirectsAreNotFollowed() {
        responder = exchange -> new Response(302, "text/plain", "");

        assertThatThrownBy(() -> new ImRpcClient(properties).sendP2pMessage(p2p("b")))
                .isInstanceOf(ImRpcException.class)
                .satisfies(e -> {
                    assertThat(((ImRpcException) e).getHttpStatus()).isEqualTo(302);
                    assertThat(((ImRpcException) e).isRetryable()).isFalse();
                });
        assertThat(captured).as("只应当发出这一次请求，绝不跟去 Location").hasSize(1);
    }

    // ================================================================== 本地配置问题（请求绝不发出）

    @Test
    @DisplayName("★★配置不全时【在发请求之前】就拒绝，且请求一条都不会发出去")
    void missingConfigurationFailsBeforeAnyRequest() {
        assertNoRequestSent(localProblemClient(p -> p.setBaseUrl("")), "base-url");
        assertNoRequestSent(localProblemClient(p -> p.setBaseUrl(null)), "base-url");
        assertNoRequestSent(localProblemClient(p -> p.setAppId("")), "app-id");
        assertNoRequestSent(localProblemClient(p -> p.setSecret("")), "secret");
        assertNoRequestSent(localProblemClient(p -> p.setSecret(null)), "secret");

        assertThat(captured).as("★配置不全时一条请求都不该发出去").isEmpty();
    }

    @Test
    @DisplayName("适配被显式关闭（im.adapter.enabled=false）→ disabled，而不是静默跳过")
    void disabledAdapterIsAnExplicitRejection() {
        ImRpcProperties disabled = localProblemClient(p -> p.setEnabled(false));

        assertThatThrownBy(() -> new ImRpcClient(disabled).sendP2pMessage(p2p("b")))
                .isInstanceOf(ImRpcException.class)
                .satisfies(e -> {
                    assertThat(((ImRpcException) e).isLocalProblem()).isTrue();
                    assertThat(((ImRpcException) e).isRetryable()).isFalse();
                });
        assertThat(captured).as("关闭适配时不应发出任何请求").isEmpty();
    }

    @Test
    @DisplayName("★enabled() 与真正决定发不发请求的开关【同源】：它说 false 就不可能发出去，说 true 就不会再被当没开")
    void enabledIsSourcedFromTheSameSwitchAsTheRequestGate() {
        assertThat(new ImRpcClient(properties).enabled())
                .as("自动追加通道（通知顺推 IM）靠它整批短路，必须反映真实的开关状态")
                .isTrue();

        ImRpcClient offClient = new ImRpcClient(localProblemClient(p -> p.setEnabled(false)));
        assertThat(offClient.enabled()).isFalse();
        // ★一致性：enabled()=false 时，任何调用都必须以 disabled 拒绝。两个判断一旦分叉，
        //   「先问 enabled 再决定发不发」的调用方就会漏掉本该跳过的整批收件人。
        assertThatThrownBy(() -> offClient.sendP2pMessage(p2p("b")))
                .isInstanceOf(ImRpcException.class)
                .satisfies(e -> {
                    ImRpcException rpc = (ImRpcException) e;
                    assertThat(rpc.isLocalProblem()).isTrue();
                    assertThat(rpc.isRetryable()).isFalse();
                });
        assertThat(captured).as("enabled()=false 时一条请求都不该发出去").isEmpty();
    }

    @Test
    @DisplayName("★网络不可达与本地配置问题的区分：两者码值都像 0001、httpStatus 都是 -1，但 isLocalProblem 必须不同")
    void networkFailureIsNotALocalProblem() {
        ImRpcProperties unreachable = new ImRpcProperties();
        unreachable.setEnabled(true);
        unreachable.setAppId(APP_ID);
        unreachable.setSecret(SECRET);
        unreachable.setApiVersion("v1");
        // 127.0.0.1:1 上不会有服务在听
        unreachable.setBaseUrl("http://127.0.0.1:1");
        unreachable.setTimeout(Duration.ofSeconds(2));
        unreachable.setConnectTimeout(Duration.ofSeconds(2));

        assertThatThrownBy(() -> new ImRpcClient(unreachable).sendP2pMessage(p2p("b")))
                .isInstanceOf(ImRpcException.class)
                .satisfies(e -> {
                    ImRpcException rpc = (ImRpcException) e;
                    assertThat(rpc.isLocalProblem())
                            .as("网络不可达时请求已经发出去了，不能报成『未配置』—— 否则排查方向整个跑偏")
                            .isFalse();
                    assertThat(rpc.isRetryable()).isTrue();
                    assertThat(rpc.getHttpStatus()).isEqualTo(-1);
                });
    }

    @Test
    @DisplayName("★异常信息里不出现 secret，也不出现请求体内容（异常最常见的去向就是日志）")
    void exceptionMessageNeverLeaksSecret() {
        responder = exchange -> ok("IM_90004", null, "验签失败", null);

        assertThatThrownBy(() -> new ImRpcClient(properties).sendP2pMessage(p2p("b")))
                .isInstanceOf(ImRpcException.class)
                .satisfies(e -> {
                    assertThat(e.getMessage()).doesNotContain(SECRET);
                    assertThat(e.getMessage()).doesNotContain("你好");
                    // 签名本身也是一次性的调用凭据，不该出现在异常里
                    assertThat(e.getMessage())
                            .doesNotContain(captured.get(0).header(ImRpcClient.HEADER_SIGNATURE));
                });
    }

    @Test
    @DisplayName("ImRpcProperties.toString() 把 secret 打成 ***")
    void propertiesToStringMasksSecret() {
        assertThat(properties.toString()).doesNotContain(SECRET).contains("***");
    }

    // ================================================================== 工具

    private ImP2pMessageRequest p2p(String traceId) {
        return ImP2pMessageRequest.builder()
                .actor(11L, 22L, "biz-" + traceId)
                .traceId(traceId)
                .toUserId(33L)
                .msgType("text")
                .body(JSON.createObjectNode().put("text", "你好"))
                .build();
    }

    private void assertNoRequestSent(ImRpcProperties props, String missingItem) {
        assertThatThrownBy(() -> new ImRpcClient(props).sendP2pMessage(p2p("b")))
                .as("缺 %s 时应本地拒绝", missingItem)
                .isInstanceOf(ImRpcException.class)
                .satisfies(e -> {
                    ImRpcException rpc = (ImRpcException) e;
                    assertThat(rpc.isLocalProblem()).isTrue();
                    assertThat(rpc.isRetryable()).isFalse();
                    // ★错误信息要说清缺哪一项：只回一个「未配置」等于让运维去猜 6 个字段。
                    assertThat(rpc.getMessage()).contains(missingItem);
                });
    }

    private ImRpcProperties localProblemClient(Consumer<ImRpcProperties> mutate) {
        ImRpcProperties copy = new ImRpcProperties();
        copy.setEnabled(true);
        copy.setBaseUrl(properties.getBaseUrl());
        copy.setAppId(APP_ID);
        copy.setSecret(SECRET);
        copy.setApiVersion("v1");
        copy.setTimeout(Duration.ofSeconds(3));
        copy.setConnectTimeout(Duration.ofSeconds(3));
        mutate.accept(copy);
        return copy;
    }

    /** 桩服务端<b>独立</b>复算签名：不复用 {@link ImHmacSigner}，避免「自己验自己」。 */
    private static String expectedSignature(String appId, String timestamp, String nonce, byte[] body) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            String bodyHash = java.util.HexFormat.of().formatHex(md.digest(body));
            String signingString = appId + "\n" + timestamp + "\n" + nonce + "\n" + bodyHash;
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(signingString.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 拼一个合法的 {@code ReqResult} 信封。
     *
     * <p>★{@code dataJson} 是<b>原样内联</b>的 JSON 文本而不是字符串字段：
     * 把 JSON 当字符串塞进去（{@code "data":"{}"}）会得到一个「格式对但语义错」的响应，
     * 于是测试测的是 Jackson 能不能把字符串解析成对象 —— 而不是被测代码。
     */
    /**
     * 按端点给出<b>形状正确</b>的 data。
     *
     * <p>★为什么不是所有端点都回同一个对象：{@code getReadCounts} 的 data 是<b>数组</b>、
     * {@code listMessagesByTimeRange} 是分页对象 —— 统一回 {@code ImMessageDto} 的话，
     * 「21 个端点都解析成功」这件事就测不出来（数组端点会直接解析失败）。
     * 反过来说，这里每个形状都过一遍，等于顺手锁住了「哪个端点回哪种 data」。
     *
     * <p>★会话列表那条<b>故意多塞了</b>平台投影裁掉的字段（{@code draft}/{@code readSeq}/{@code groupNo}）：
     * 服务端仍照 42 字段下发时，平台这层不能硬故障 —— 裁字段是为了不去读它们，不是为了否认它们存在。
     */
    private static String defaultDataFor(String path) {
        return switch (path) {
            case "/internal/im/rpc/messages/get" -> "{\"msgId\":9001,\"convId\":7,\"seq\":3,\"senderId\":11,"
                    + "\"senderType\":1,\"msgType\":\"text\",\"body\":{\"text\":\"hi\"},\"digest\":\"hi\","
                    + "\"msgStatus\":1,\"sendTime\":1700000000000,\"readCount\":0}";
            case "/internal/im/rpc/messages/read-counts" -> "[{\"msgId\":9001,\"seq\":3,\"readCount\":1,"
                    + "\"eligibleCount\":2,\"readAll\":false,\"level\":\"basic\",\"readerListAvailable\":false}]";
            case "/internal/im/rpc/messages/list" -> "{\"list\":[],\"hasMore\":false,\"nextSeq\":0}";
            case "/internal/im/rpc/conversations/group" -> "{\"convId\":7,\"convType\":2,\"name\":\"群\","
                    + "\"ownerUserId\":11,\"memberCount\":1,\"created\":true,\"createTime\":1700000000000}";
            case "/internal/im/rpc/conversations/p2p" -> "{\"convId\":7,\"convType\":1,\"memberCount\":2}";
            case "/internal/im/rpc/conversations/members/add" -> "{\"inserted\":1,\"revived\":0,\"alreadyIn\":0,"
                    + "\"failedUserIds\":[],\"memberCount\":2,\"idempotentHit\":false}";
            case "/internal/im/rpc/conversations/members/list" -> "{\"totalCount\":1,\"degraded\":false,"
                    + "\"members\":[{\"userId\":11,\"role\":1}]}";
            // ★含 42 字段契约里被平台投影裁掉的 draft / readSeq / groupNo，见方法注释。
            case "/internal/im/rpc/conversations/list" -> "{\"list\":[{\"convId\":7,\"convType\":1,\"name\":\"张三\","
                    + "\"peerUserId\":33,\"unreadCount\":2,\"topFlag\":true,\"topTime\":1700000000002,"
                    + "\"draft\":\"没发出去的草稿\",\"readSeq\":9,\"groupNo\":\"G-1\"}],"
                    + "\"hasMore\":true,\"nextCursor\":\"t1|1700000000002|1700000000001|7\"}";
            // ★DEV-288 会话详情：默认给【成员视角】的完整形态（含 groupNo / ext —— 本端点相对列表的全部增量）。
            //   ★非成员形态（myRole=null 且 unreadCount=0）单独一条用例，不混在这里。
            case "/internal/im/rpc/conversations/detail" -> "{\"convId\":7001,\"convType\":2,\"name\":\"研发群\","
                    + "\"avatar\":\"a.png\",\"notice\":\"公告\",\"ownerUserId\":11,\"memberCount\":3,"
                    + "\"maxMember\":2000,\"status\":1,\"spaceId\":555,\"groupNo\":\"G-7001\","
                    + "\"lastMsgId\":9001,\"lastMsgSeq\":9,\"lastMsgAt\":1700000000000,\"lastMsgDigest\":\"收到\","
                    + "\"lastSenderId\":33,\"lastSenderName\":\"张三\",\"myRole\":3,\"myNickName\":\"我\","
                    + "\"unreadCount\":5,\"topFlag\":false,\"topTime\":null,\"dnd\":false,\"visible\":true,"
                    + "\"createTime\":1700000000000,\"joinTime\":1700000000001,\"ext\":\"{\\\"k\\\":1}\"}";
            case "/internal/im/rpc/contacts/list" -> "{\"items\":[{\"userId\":33,\"subjectType\":1,"
                    + "\"userName\":\"zhangsan\",\"remark\":\"老张\",\"displayName\":\"老张\",\"status\":1,\"time\":1},"
                    + "{\"userId\":44,\"subjectType\":2,\"nickName\":\"小助手\",\"displayName\":\"小助手\",\"status\":2}],"
                    + "\"degraded\":true,\"truncated\":false}";
            case "/internal/im/rpc/events/push" -> "{\"targetUsers\":1,\"targetDevices\":1,\"publishedFrames\":1}";
            // ★DEV-291 系统通知：data 是【三堆】——计数 + 成功明细 + 业务性跳过。
            //   刻意让 failed 非空：failed 非空 ≠ 调用失败，这个形状本身就是要锁住的知识点。
            case "/internal/im/rpc/messages/system-notify" -> "{\"targetCount\":3,\"sentCount\":2,"
                    + "\"list\":[{\"toUserId\":2,\"convId\":7001,\"msgId\":9001,\"seq\":3,\"sendTime\":1700000000000},"
                    + "{\"toUserId\":3,\"convId\":7002,\"msgId\":9002,\"seq\":1,\"sendTime\":1700000000001}],"
                    + "\"failed\":[{\"toUserId\":4,\"reason\":\"IM_20103: 会话不存在\"}]}";
            // ★DEV-285：排队区两条。list 的 items 里带上 text 与 digest 两种正文形态，
            // 顺带锁住「readableText 优先取完整正文、退回摘要」这条口径。
            case "/internal/im/rpc/agents/pending/list" -> "{\"items\":["
                    + "{\"msgId\":9001,\"senderId\":33,\"senderName\":\"张三\",\"seq\":3,"
                    + "\"text\":\"完整正文\",\"digest\":\"截断摘要\",\"enqueueTime\":1700000000000},"
                    + "{\"msgId\":9002,\"senderId\":44,\"seq\":4,\"digest\":\"[图片]\"}],"
                    + "\"count\":2}";
            case "/internal/im/rpc/agents/pending/clear" -> "{\"removedCount\":1,\"removedMsgIds\":[9001]}";
            // revoke / members-remove / digest 是 void 端点（data 为 null）；
            // 其余（p2p / group / system / card）都是 ImMessageDto。
            case "/internal/im/rpc/messages/revoke",
                 "/internal/im/rpc/conversations/members/remove",
                 "/internal/im/rpc/conversations/digest" -> null;
            default -> MESSAGE_DTO_JSON;
        };
    }

    private Response ok(String code, String displayCode, String message, String dataJson) {
        return new Response(200, "application/json; charset=UTF-8", envelope(code, displayCode, message, dataJson));
    }

    private String envelope(String code, String displayCode, String message, String dataJson) {
        return "{\"code\":" + quote(code)
                + ",\"displayCode\":" + quote(displayCode)
                + ",\"message\":" + quote(message)
                + ",\"data\":" + (dataJson == null ? "null" : dataJson)
                + ",\"tid\":" + quote(stubTid)
                + "}";
    }

    private static String quote(String value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record Response(int status, String contentType, String body) {
    }

    @FunctionalInterface
    private interface Responder {
        Response respond(HttpExchange exchange);
    }

    private record CapturedRequest(String method, String path, Map<String, String> headers, byte[] body) {

        CapturedRequest(HttpExchange exchange, byte[] body) {
            this(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), flatten(exchange), body);
        }

        String header(String name) {
            return headers.get(name.toLowerCase());
        }

        private static Map<String, String> flatten(HttpExchange exchange) {
            Map<String, String> flat = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((k, v) ->
                    flat.put(k.toLowerCase(), v == null || v.isEmpty() ? null : v.get(0)));
            return flat;
        }
    }
}
