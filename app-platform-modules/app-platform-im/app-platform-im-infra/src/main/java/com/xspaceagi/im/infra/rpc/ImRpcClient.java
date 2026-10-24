package com.xspaceagi.im.infra.rpc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xspaceagi.im.sdk.rpc.IImAgentRpcService;
import com.xspaceagi.im.sdk.rpc.IImContactRpcService;
import com.xspaceagi.im.sdk.rpc.IImConversationRpcService;
import com.xspaceagi.im.sdk.rpc.IImEventRpcService;
import com.xspaceagi.im.sdk.rpc.IImMessageRpcService;
import com.xspaceagi.im.sdk.rpc.IImRpcAvailability;
import com.xspaceagi.im.sdk.rpc.ImRpcException;
import com.xspaceagi.im.sdk.rpc.dto.ImAgentPendingClearDto;
import com.xspaceagi.im.sdk.rpc.dto.ImAgentPendingClearRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImAgentPendingListDto;
import com.xspaceagi.im.sdk.rpc.dto.ImAgentPendingListRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImCardMessageRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImContactListDto;
import com.xspaceagi.im.sdk.rpc.dto.ImContactListRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImConversationDetailDto;
import com.xspaceagi.im.sdk.rpc.dto.ImConversationDetailRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImConversationDto;
import com.xspaceagi.im.sdk.rpc.dto.ImConversationListRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImConversationPageDto;
import com.xspaceagi.im.sdk.rpc.dto.ImCustomEventPushRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImCustomEventPushResultDto;
import com.xspaceagi.im.sdk.rpc.dto.ImDigestUpdateRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImGroupCreateRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImGroupMessageRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImMemberAddRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImMemberListRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImMemberRemoveRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImMessageDetailDto;
import com.xspaceagi.im.sdk.rpc.dto.ImMessageDto;
import com.xspaceagi.im.sdk.rpc.dto.ImMessageGetRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImMessageListRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImMessagePageDto;
import com.xspaceagi.im.sdk.rpc.dto.ImMessageRevokeRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImP2pConversationRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImP2pMessageRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImReadCountsRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImRpcRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImSystemMessageRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImSystemNotifyRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImSystemNotifyResultDto;
import com.xspaceagi.im.sdk.rpc.dto.MemberAddResultDto;
import com.xspaceagi.im.sdk.rpc.dto.MemberPageDto;
import com.xspaceagi.im.sdk.rpc.dto.ReadCountItemDto;
import com.xspaceagi.im.sdk.rpc.dto.ReqResultDto;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * ★Nuwax IM 内部 RPC 客户端 —— 覆盖 {@code /internal/im/rpc/**} 全部 <b>21</b> 个端点
 * （★DEV-285 起：新增 tag=agents 2 个 —— 智能体排队区自查通道；
 * ★DEV-288 起：新增 {@code conversations/detail} 1 个 —— 按 convId 查会话详情，
 * 至此与服务端 {@code ImInternalRpcController} 的端点集合<b>一一对齐</b>；
 * ★DEV-291 起：新增 {@code messages/system-notify} 1 个 —— 批量发「系统通知」，
 * 至此与服务端<b>再次</b>一一对齐）。
 *
 * <h2>★★本类就是 {@code app-platform-im-sdk} 五个接口的实现</h2>
 *
 * <p>方法名与契约 {@code operationId} <b>逐字符一致</b>（{@code sendP2pMessage} /
 * {@code getOrCreateP2p} / …）。这不是审美问题：契约改了而实现没跟上时，
 * 「按 operationId 反查方法」是自动发现漂移的唯一手段。
 *
 * <h2>★为什么只有一层（不再套一层 Service）</h2>
 *
 * <p>本类既承担「HTTP + 签名」的传输职责，也直接实现五个 SDK 接口 —— 21 个方法里
 * 没有一个是「参数转换」或「业务编排」，多套一层只会多一份需要同步的空壳。
 * 有编排需求时，在调用方（application / web）里组合这五个接口即可。
 *
 * <h2>★★不自动重试（刻意）</h2>
 *
 * <p>发消息是<b>用户可感知</b>的操作：慢失败（3 秒后报错、用户重按一次）比
 * 「客户端偷偷重试 3 次、每次 3 秒」更好 —— 后者在 IM 抖动时会<b>放大</b>流量，
 * 而且把「到底发出去没有」变成调用方无法回答的问题。
 * ⇒ 本类只负责「发一次、如实报告」，失败抛 {@link ImRpcException}，
 * 由 {@link ImRpcException#isRetryable()} 给出「能不能带同一个 bizMsgId 再试」的建议。
 *
 * <h2>★★X-Im-Signature 签的是<b>实际发出的字节</b></h2>
 *
 * <p>{@code payload} 序列化<b>一次</b>，签名用它的字节、发送也用同一份字节。
 * 绝不「签名时序列化一遍、发送时再序列化一遍」：Jackson 对 {@code Map}/{@code Set}
 * 这类无序容器不保证顺序，一旦顺序变了签名就对不上，表现为<b>偶发</b> IM_90004（最难查的一类）。
 *
 * <h2>★线程安全</h2>
 *
 * <p>无状态（{@link HttpClient} / {@link ObjectMapper} 本身线程安全），
 * 由 {@link ImRpcConfiguration} 做成单例供全平台共享。
 */
@Slf4j
public class ImRpcClient implements IImMessageRpcService, IImConversationRpcService,
        IImContactRpcService, IImEventRpcService, IImAgentRpcService, IImRpcAvailability {

    // ================================================================== 头名（与契约逐字符一致）

    public static final String HEADER_APP_ID = "X-Im-App-Id";
    public static final String HEADER_TIMESTAMP = "X-Im-Timestamp";
    public static final String HEADER_NONCE = "X-Im-Nonce";
    public static final String HEADER_SIGNATURE = "X-Im-Signature";
    public static final String HEADER_API_VERSION = "X-Im-Api-Version";
    public static final String HEADER_REQUEST_ID = "X-Request-Id";

    // ================================================================== 路径（与契约逐字符一致）

    private static final String P_MESSAGES_P2P = "/internal/im/rpc/messages/p2p";
    private static final String P_MESSAGES_GROUP = "/internal/im/rpc/messages/group";
    private static final String P_MESSAGES_SYSTEM = "/internal/im/rpc/messages/system";
    private static final String P_MESSAGES_SYSTEM_NOTIFY = "/internal/im/rpc/messages/system-notify";
    private static final String P_MESSAGES_CARD = "/internal/im/rpc/messages/card";
    private static final String P_MESSAGES_REVOKE = "/internal/im/rpc/messages/revoke";
    private static final String P_MESSAGES_GET = "/internal/im/rpc/messages/get";
    private static final String P_MESSAGES_READ_COUNTS = "/internal/im/rpc/messages/read-counts";
    private static final String P_MESSAGES_LIST = "/internal/im/rpc/messages/list";
    private static final String P_CONV_GROUP = "/internal/im/rpc/conversations/group";
    private static final String P_CONV_MEMBERS_ADD = "/internal/im/rpc/conversations/members/add";
    private static final String P_CONV_MEMBERS_REMOVE = "/internal/im/rpc/conversations/members/remove";
    private static final String P_CONV_MEMBERS_LIST = "/internal/im/rpc/conversations/members/list";
    private static final String P_CONV_P2P = "/internal/im/rpc/conversations/p2p";
    private static final String P_CONV_DIGEST = "/internal/im/rpc/conversations/digest";
    private static final String P_CONV_LIST = "/internal/im/rpc/conversations/list";
    private static final String P_CONV_DETAIL = "/internal/im/rpc/conversations/detail";
    private static final String P_CONTACTS_LIST = "/internal/im/rpc/contacts/list";
    private static final String P_EVENTS_PUSH = "/internal/im/rpc/events/push";
    private static final String P_AGENTS_PENDING_LIST = "/internal/im/rpc/agents/pending/list";
    private static final String P_AGENTS_PENDING_CLEAR = "/internal/im/rpc/agents/pending/clear";

    // ================================================================== 响应类型（静态常量，避免每次调用新建 TypeReference）

    private static final TypeReference<ReqResultDto<ImMessageDto>> T_MESSAGE =
            new TypeReference<>() { };
    private static final TypeReference<ReqResultDto<ImMessageDetailDto>> T_MESSAGE_DETAIL =
            new TypeReference<>() { };
    private static final TypeReference<ReqResultDto<List<ReadCountItemDto>>> T_READ_COUNTS =
            new TypeReference<>() { };
    private static final TypeReference<ReqResultDto<ImSystemNotifyResultDto>> T_SYSTEM_NOTIFY =
            new TypeReference<>() { };
    private static final TypeReference<ReqResultDto<ImConversationDto>> T_CONVERSATION =
            new TypeReference<>() { };
    private static final TypeReference<ReqResultDto<MemberAddResultDto>> T_MEMBER_ADD =
            new TypeReference<>() { };
    private static final TypeReference<ReqResultDto<MemberPageDto>> T_MEMBER_PAGE =
            new TypeReference<>() { };
    private static final TypeReference<ReqResultDto<ImMessagePageDto>> T_MESSAGE_PAGE =
            new TypeReference<>() { };
    private static final TypeReference<ReqResultDto<ImCustomEventPushResultDto>> T_EVENT_PUSH =
            new TypeReference<>() { };
    private static final TypeReference<ReqResultDto<ImConversationPageDto>> T_CONVERSATION_PAGE =
            new TypeReference<>() { };
    private static final TypeReference<ReqResultDto<ImConversationDetailDto>> T_CONVERSATION_DETAIL =
            new TypeReference<>() { };
    private static final TypeReference<ReqResultDto<ImContactListDto>> T_CONTACT_LIST =
            new TypeReference<>() { };
    private static final TypeReference<ReqResultDto<ImAgentPendingListDto>> T_AGENT_PENDING_LIST =
            new TypeReference<>() { };
    private static final TypeReference<ReqResultDto<ImAgentPendingClearDto>> T_AGENT_PENDING_CLEAR =
            new TypeReference<>() { };
    private static final TypeReference<ReqResultDto<Void>> T_VOID =
            new TypeReference<>() { };

    /** 契约 {@code X-Request-Id} maxLength=64。超长时退回自动生成，不让观测字段否决业务请求。 */
    private static final int MAX_REQUEST_ID_LEN = 64;

    private final ImRpcProperties properties;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public ImRpcClient(ImRpcProperties properties) {
        this(properties, newDefaultMapper());
    }

    /** 可注入 mapper（测试桩 / 共享序列化策略）。★注入进来的 mapper 不会被本类改写任何配置。 */
    public ImRpcClient(ImRpcProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        Duration read = properties.getTimeout() == null ? Duration.ofSeconds(3) : properties.getTimeout();
        Duration connect = properties.getConnectTimeout() == null ? read : properties.getConnectTimeout();
        this.http = HttpClient.newBuilder()
                .connectTimeout(connect)
                // ★followRedirects(NEVER) 是安全要求而非性能选择：请求头里带着 X-Im-Signature
                //   （相当于一次性的调用凭据）。若 IM 侧（或被劫持的 DNS / 中间层）返回 302 到
                //   另一个地址，JDK 客户端会【带着签名头】去请求那个新地址 —— 把凭据交给了一个
                //   我们从未打算信任的主机。宁可让 302 表现为一次失败（配置错了能一眼看出）。
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    // ================================================================== messages（9）

    @Override
    public ImMessageDto sendP2pMessage(ImP2pMessageRequest req) {
        return post(P_MESSAGES_P2P, req, T_MESSAGE);
    }

    @Override
    public ImMessageDto sendGroupMessage(ImGroupMessageRequest req) {
        return post(P_MESSAGES_GROUP, req, T_MESSAGE);
    }

    @Override
    public ImMessageDto sendSystemMessage(ImSystemMessageRequest req) {
        return post(P_MESSAGES_SYSTEM, req, T_MESSAGE);
    }

    @Override
    public ImSystemNotifyResultDto sendSystemNotify(ImSystemNotifyRequest req) {
        return post(P_MESSAGES_SYSTEM_NOTIFY, req, T_SYSTEM_NOTIFY);
    }

    @Override
    public ImMessageDto sendCardMessage(ImCardMessageRequest req) {
        return post(P_MESSAGES_CARD, req, T_MESSAGE);
    }

    @Override
    public void revokeMessage(ImMessageRevokeRequest req) {
        post(P_MESSAGES_REVOKE, req, T_VOID);
    }

    @Override
    public ImMessageDetailDto getMessage(ImMessageGetRequest req) {
        return post(P_MESSAGES_GET, req, T_MESSAGE_DETAIL);
    }

    @Override
    public List<ReadCountItemDto> getReadCounts(ImReadCountsRequest req) {
        return post(P_MESSAGES_READ_COUNTS, req, T_READ_COUNTS);
    }

    @Override
    public ImMessagePageDto listMessagesByTimeRange(ImMessageListRequest req) {
        return post(P_MESSAGES_LIST, req, T_MESSAGE_PAGE);
    }

    // ================================================================== conversations（7）

    @Override
    public ImConversationDto createGroup(ImGroupCreateRequest req) {
        return post(P_CONV_GROUP, req, T_CONVERSATION);
    }

    @Override
    public MemberAddResultDto addMembers(ImMemberAddRequest req) {
        return post(P_CONV_MEMBERS_ADD, req, T_MEMBER_ADD);
    }

    @Override
    public void removeMembers(ImMemberRemoveRequest req) {
        post(P_CONV_MEMBERS_REMOVE, req, T_VOID);
    }

    @Override
    public MemberPageDto listMembers(ImMemberListRequest req) {
        return post(P_CONV_MEMBERS_LIST, req, T_MEMBER_PAGE);
    }

    @Override
    public ImConversationDto getOrCreateP2p(ImP2pConversationRequest req) {
        return post(P_CONV_P2P, req, T_CONVERSATION);
    }

    @Override
    public void updateDigest(ImDigestUpdateRequest req) {
        post(P_CONV_DIGEST, req, T_VOID);
    }

    @Override
    public ImConversationPageDto listConversations(ImConversationListRequest req) {
        return post(P_CONV_LIST, req, T_CONVERSATION_PAGE);
    }

    @Override
    public ImConversationDetailDto getConversationDetail(ImConversationDetailRequest req) {
        return post(P_CONV_DETAIL, req, T_CONVERSATION_DETAIL);
    }

    // ================================================================== contacts（1）

    @Override
    public ImContactListDto listContacts(ImContactListRequest req) {
        return post(P_CONTACTS_LIST, req, T_CONTACT_LIST);
    }

    // ================================================================== events（1）

    @Override
    public ImCustomEventPushResultDto pushCustomEvent(ImCustomEventPushRequest req) {
        return post(P_EVENTS_PUSH, req, T_EVENT_PUSH);
    }

    // ================================================================== agents（2，★DEV-285）

    @Override
    public ImAgentPendingListDto listPending(ImAgentPendingListRequest req) {
        return post(P_AGENTS_PENDING_LIST, req, T_AGENT_PENDING_LIST);
    }

    @Override
    public ImAgentPendingClearDto clearPending(ImAgentPendingClearRequest req) {
        return post(P_AGENTS_PENDING_CLEAR, req, T_AGENT_PENDING_CLEAR);
    }

    // ================================================================== 核心：一次 POST

    /**
     * 发一次请求并解析 {@code ReqResult}。21 个端点<b>全部</b>走这里 ——
     * 签名、头、超时、错误分类只有一份实现。
     *
     * @param path     契约路径（本类内的常量）
     * @param body     请求 DTO（{@code record}；实现 {@link ImRpcRequest} 时透传 traceId）
     * @param respType 响应信封类型（静态常量，含 data 的泛型）
     * @return data（成功或幂等命中）
     * @throws ImRpcException 见 {@link ImRpcException} 的三分类
     */
    private <T> T post(String path, Object body, TypeReference<ReqResultDto<T>> respType) {
        ensureUsable(path);

        // ---------- 序列化一次，后面签名与发送都用这同一份字节（见类注释） ----------
        final byte[] payload;
        try {
            payload = mapper.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            throw ImRpcException.serialization(e);
        }

        final long timestamp = System.currentTimeMillis();
        final String nonce = UUID.randomUUID().toString();
        final String signature = ImHmacSigner.sign(
                properties.getSecret(), properties.getAppId(), timestamp, nonce, payload);

        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(properties.getBaseUrl() + path))
                .timeout(properties.getTimeout())
                // ★显式 UTF-8：body 字节是 UTF-8，声明里也必须写清楚，
                //   否则某些服务端 / 网关会按 ISO-8859-1 解读，中文 body 直接乱码。
                .header("Content-Type", "application/json; charset=UTF-8")
                .header(HEADER_APP_ID, properties.getAppId())
                .header(HEADER_TIMESTAMP, Long.toString(timestamp))
                .header(HEADER_NONCE, nonce)
                .header(HEADER_SIGNATURE, signature)
                .header(HEADER_API_VERSION, properties.getApiVersion())
                .header(HEADER_REQUEST_ID, requestIdOf(body))
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();

        final HttpResponse<byte[]> response;
        try {
            response = http.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            // ★连接拒绝 / 读超时 / 连接重置。describe 里只有异常类型与主机级描述，不含任何凭据。
            log.warn("IM RPC 网络失败 path={} appId={} err={}", path, properties.getAppId(), describe(e));
            throw ImRpcException.network(describe(e), e);
        } catch (InterruptedException e) {
            // ★必须恢复中断标志：吞掉它会让上层「可取消」的逻辑全部失效
            //   （线程池无法停止、优雅停机卡住）。这是 Java 并发里最容易漏的一行。
            Thread.currentThread().interrupt();
            throw ImRpcException.interrupted(e);
        }

        final int status = response.statusCode();
        final byte[] raw = response.body() == null ? new byte[0] : response.body();

        // ★先尝试解析信封，再判状态码：这样 401/413 这类「非 200 但也带 ReqResult」的响应
        //   能把服务端的业务码原样带给调用方（IM_10401 比「HTTP 401」有用得多）。
        final ReqResultDto<T> envelope;
        try {
            envelope = mapper.readValue(raw, respType);
        } catch (IOException e) {
            if (status != 200) {
                // ★405 高发：多半是 baseUrl 填成了对外域名（nginx 的 /internal 没有出口）。
                log.warn("IM RPC HTTP {} path={}（若为 405/404 请检查 im.adapter.base-url 是否为内网直连地址）",
                        status, path);
                throw ImRpcException.httpStatus(status, null, null);
            }
            throw ImRpcException.malformed(describe(e), e);
        }

        if (status != 200) {
            log.warn("IM RPC HTTP {} path={} code={} tid={}", status, path, envelope.code(), envelope.tid());
            throw ImRpcException.httpStatus(status, envelope.code(), envelope.tid());
        }

        // ★IM_20005（幂等命中）与 0000 同为「成功」：契约明确「视为成功」，两者都会返回 data。
        //   调用方若需区分，看 data 里的 idempotentHit（对象型 data）。
        if (envelope.isSuccess() || envelope.isIdempotentHit()) {
            return envelope.data();
        }
        log.warn("IM RPC 业务失败 path={} code={} tid={} msg={}",
                path, envelope.code(), envelope.tid(), envelope.message());
        throw ImRpcException.biz(envelope.code(), envelope.displayCode(),
                envelope.message(), envelope.tid(), status);
    }

    // ================================================================== 私有工具

    /**
     * {@inheritDoc}
     *
     * <p>★与 {@link #ensureUsable(String)} 读的是同一个 {@code properties.enabled} ——
     * 这里返回 {@code false} 时，那个方法必定抛 {@link ImRpcException#disabled()}，
     * 两者不可能给出矛盾答案。调用方问它是为了<b>在遍历收件人之前</b>就整批短路，
     * 而不是逐条去接那个必然到来的异常。
     */
    @Override
    public boolean enabled() {
        return properties.isEnabled();
    }

    /**
     * ★配置不全时<b>在发请求之前</b>就拒绝，并且错误信息里说清缺哪一项。
     *
     * <p>为什么要拦：配置缺失时直接发出去，服务端只会回一个不区分原因的 {@code IM_90004}
     * （验签失败按契约不透露细节），排查只能靠猜。
     */
    private void ensureUsable(String path) {
        if (!properties.isEnabled()) {
            throw ImRpcException.disabled();
        }
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
            throw ImRpcException.notConfigured("im.adapter.base-url 未配置（应为 IM 内网直连地址，如 http://<host>:18092）");
        }
        if (properties.getAppId() == null || properties.getAppId().isBlank()) {
            throw ImRpcException.notConfigured("im.adapter.app-id 未配置");
        }
        if (properties.getSecret() == null || properties.getSecret().isBlank()) {
            throw ImRpcException.notConfigured("im.adapter.secret 未配置（对应 IM 侧 im.rpc.secrets." + properties.getAppId() + "）");
        }
        if (!log.isDebugEnabled()) {
            return;
        }
        log.debug("IM RPC → {} {}", properties.getBaseUrl(), path);
    }

    /**
     * {@code X-Request-Id} 的取值：优先用请求体里的 {@code traceId}
     * （让平台的一条链路与 IM 侧的一次执行用同一个 ID 串起来），否则生成 32 位十六进制随机串。
     */
    private static String requestIdOf(Object body) {
        if (body instanceof ImRpcRequest req) {
            String traceId = req.traceId();
            if (traceId != null && !traceId.isBlank() && traceId.length() <= MAX_REQUEST_ID_LEN) {
                return traceId.trim();
            }
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** ★只取异常类型与 message（截断），不打印堆栈也不打印 header/body。 */
    private static String describe(Exception e) {
        String msg = e.getMessage();
        if (msg == null) {
            msg = "";
        }
        if (msg.length() > 200) {
            msg = msg.substring(0, 200) + "…";
        }
        return e.getClass().getSimpleName() + (msg.isEmpty() ? "" : ": " + msg);
    }

    /** 默认 mapper：只为「反序列化未知字段不报错」服务 —— 服务端加字段不该让老客户端硬故障。 */
    private static ObjectMapper newDefaultMapper() {
        return new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
}
