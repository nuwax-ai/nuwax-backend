package com.xspaceagi.ctl.oauth2;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.xspaceagi.im.sdk.rpc.IImContactRpcService;
import com.xspaceagi.im.sdk.rpc.IImConversationRpcService;
import com.xspaceagi.im.sdk.rpc.IImMessageRpcService;
import com.xspaceagi.im.sdk.rpc.ImRpcException;
import com.xspaceagi.im.sdk.rpc.dto.ImContactListDto;
import com.xspaceagi.im.sdk.rpc.dto.ImContactListRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImConversationListRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImConversationPageDto;
import com.xspaceagi.im.sdk.rpc.dto.ImConversationSummaryDto;
import com.xspaceagi.im.sdk.rpc.dto.ImGroupMessageRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImMessageDto;
import com.xspaceagi.im.sdk.rpc.dto.ImMessageListRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImMessagePageDto;
import com.xspaceagi.im.sdk.rpc.dto.ImP2pMessageRequest;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * OAuth2 门面 —— 消息通信组（IM）。
 * <p>
 * scope：读 {@code im:read}（会话列表、群组列表、联系人列表、会话消息列表），
 * 写 {@code im:push}（向指定目标推送消息）。
 * <p>
 * ★<b>两个 scope 都是「仅授权码模式」</b>（{@code authorizationCodeOnly}），M2M 拿不到。
 * 理由是这一组的能力全部以<b>授权用户本人的身份</b>生效：{@code actor} 就是授权用户的 userId，
 * 发出去的消息在 IM 里显示为该真人所发，读到的是他的私域关系链与会话内容。
 * client_credentials 的身份是「应用创建者」而非任何真实授权者，让它无声地以创建者身份发消息、
 * 读私聊，等于把创建者的社交身份交给持有 client_secret 的任何进程。必须由本人点一次授权。
 * <p>
 * ★<b>不走 {@code scenarioActorUserId} / AK 那套身份换算</b>：{@code NuwaxImSandboxController}
 * 要按 AK 形态在「本人 / 智能体虚拟身份 / 群代理」之间切换 actor，那是沙箱场景的需要。
 * 门面的语义恒定是「第三方代表这个真人」，所以 actor 直接取 {@code RequestContext} 的 userId，
 * 不做任何换算——换算反而会引入「以智能体名义发言」的意外身份。
 * <p>
 * ★<b>不开放</b> {@code send-system}、{@code event/push}、{@code revoke} 等端点：
 * 前两者在既有实现里带管理员闸门（IM 的 push 链上没有任何成员 / 关系校验，
 * 帧体也不带发送者），撤回则会在群里留下「XX 撤回了一条消息」的可见墓碑。
 * 这些都不属于「查询 + 推送」的需求范围，放大授权面没有收益。
 */
@Tag(name = "OAuth2 门面-消息通信", description = "需 OAuth2 access_token（oa- 前缀）；im:* 仅限授权码模式")
@RestController
@RequestMapping("/api/oauth2/im")
@Slf4j
public class OAuth2ImFacadeController {

    @Resource
    private IImConversationRpcService imConversationRpcService;

    @Resource
    private IImMessageRpcService imMessageRpcService;

    @Resource
    private IImContactRpcService imContactRpcService;

    // ================================================================== 读（im:read）

    /**
     * 授权用户的 IM 会话列表（单聊 + 群聊 + 系统会话，原样返回不过滤）。
     * <p>
     * ★翻页判据<b>只有</b> {@code data.hasMore}；把上一次响应的 {@code data.nextCursor} 原样回填到
     * {@code cursor}。游标不透明（自己拼会报 {@code IM_40002}）且<b>不含身份</b>——
     * 跨用户复用不报错，只会静默返回错位的一页，因此调用方必须按用户隔离存储游标。
     * 要「只要群」请用 {@link #groupList(String, Integer, String)}。
     */
    @Operation(summary = "查询授权用户的 IM 会话列表")
    @GetMapping("/conversation/list")
    public ReqResult<ImConversationPageDto> conversationList(
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "traceId", required = false) String traceId) {
        return ReqResult.success(rpc("conversations/list", null, () -> imConversationRpcService.listConversations(
                ImConversationListRequest.builder()
                        .actor(actorUserId(), actorTenantId(), null)
                        .traceId(traceId)
                        .cursor(cursor)
                        .limit(limit)
                        .build())));
    }

    /**
     * 授权用户的<b>群组</b>列表。
     * <p>
     * IM 侧没有独立的「群列表」RPC，本端点的实现是：取一页会话 → 服务端按
     * {@link ImConversationSummaryDto#isGroup()} 过滤（{@code convType} 2=IM 自建群、
     * 4=平台 space 镜像群，两者都算群）→ {@code hasMore} / {@code nextCursor} 原样透传。
     * <p>
     * ⚠️ 由此产生一个<b>必须让调用方知道</b>的口径：返回的 {@code list} 条数<b>可能少于</b> {@code limit}，
     * 甚至为空，而 {@code hasMore} 仍为 true——因为过滤发生在取页之后。
     * 这不是截断故障，判停<b>只能</b>看 {@code hasMore}，不能看「这页满不满」或「list 是否为空」。
     * 想要稳定每页 N 个群，只能持续翻页到 {@code hasMore=false}。
     * <p>
     * 刻意不在服务端循环翻页凑满一页：那会把「取一页」变成「扫全部会话」，
     * 与本接口用游标控制成本的初衷相反。
     */
    @Operation(summary = "查询授权用户的群组列表（按会话列表过滤，一页可能少于 limit，判停只看 hasMore）")
    @GetMapping("/group/list")
    public ReqResult<ImConversationPageDto> groupList(
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "traceId", required = false) String traceId) {
        ImConversationPageDto page = rpc("conversations/list", null, () -> imConversationRpcService.listConversations(
                ImConversationListRequest.builder()
                        .actor(actorUserId(), actorTenantId(), null)
                        .traceId(traceId)
                        .cursor(cursor)
                        .limit(limit)
                        .build()));
        if (page == null) {
            return ReqResult.success(null);
        }
        List<ImConversationSummaryDto> groups = page.listOrEmpty().stream()
                .filter(ImConversationSummaryDto::isGroup)
                .toList();
        return ReqResult.success(new ImConversationPageDto(groups, page.hasMore(), page.nextCursor()));
    }

    /**
     * 授权用户的联系人列表。
     * <p>
     * ★返回<b>三态混合</b>（{@code items[].status}：1=已互为联系人 2=对方申请我 3=我申请对方），
     * 要「只要好友」需自行按 {@code status==1} 筛。
     * ★{@code degraded=true} 只是昵称 / 头像没取到（列表完整），{@code truncated=true} 才是被 500 上限
     * 截断（列表缩水），两者含义不同别混。
     */
    @Operation(summary = "查询授权用户的联系人列表（三态混合，按 status 自行筛选）")
    @GetMapping("/contact/list")
    public ReqResult<ImContactListDto> contactList(@RequestParam(value = "traceId", required = false) String traceId) {
        return ReqResult.success(rpc("contacts/list", null, () -> imContactRpcService.listContacts(
                ImContactListRequest.builder()
                        .actor(actorUserId(), actorTenantId(), null)
                        .traceId(traceId)
                        .build())));
    }

    /**
     * 按时间范围查询某个会话的消息列表。
     * <p>
     * 翻页只认 {@code hasMore}；把上一次响应的 {@code data.nextSeq} 原样回填到 {@code afterSeq}。
     * 会话成员资格由 IM 侧校验（非成员读取会被上游拒绝），门面不重复实现成员判定。
     */
    @Operation(summary = "查询指定会话的消息列表")
    @GetMapping("/message/list")
    public ReqResult<ImMessagePageDto> messageList(
            @RequestParam("convId") Long convId,
            @RequestParam(value = "startTime", required = false) Long startTime,
            @RequestParam(value = "endTime", required = false) Long endTime,
            @RequestParam(value = "afterSeq", required = false) Long afterSeq,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "traceId", required = false) String traceId) {
        require(convId, "convId");
        return ReqResult.success(rpc("messages/list", null, () -> imMessageRpcService.listMessagesByTimeRange(
                ImMessageListRequest.builder()
                        .actor(actorUserId(), actorTenantId(), null)
                        .traceId(traceId)
                        .convId(convId)
                        .startTime(startTime)
                        .endTime(endTime)
                        .afterSeq(afterSeq)
                        .limit(limit)
                        .build())));
    }

    // ================================================================== 写（im:push）

    /**
     * 以授权用户身份向指定目标推送消息。
     * <p>
     * 目标二选一，<b>必须且只能</b>给一个：
     * <ul>
     * <li>{@code toUserId} → 私聊（IM 契约 {@code messages/p2p}，会话不存在会自动创建）；</li>
     * <li>{@code convId} → 群聊 / 既有会话（IM 契约 {@code messages/group}，支持引用回复与 @）。</li>
     * </ul>
     * 两个都给会判参数错误而不是「优先取其一」——静默选一个会让调用方以为发到了另一个目标。
     * <p>
     * ★{@code bizMsgId} 是<b>幂等键</b>：重试场景必须由调用方传同一个值，否则重发会产生两条消息。
     * 未传时服务端生成一个并在响应里回显（{@code data.bizMsgId}），
     * 便于「用户说没收到、日志说发了」这类争论一句话收口。
     * <p>
     * ★消息体 {@code body} 是随 {@code msgType} 而定的 JSON，原样透传给 IM，门面不解析其结构
     * （解析就等于把 IM 的契约复制一份到平台侧，两边迟早不一致）。
     * 但 {@code null} 与 JSON 的 {@code null} 都要挡在门外——后者经 Jackson 反序列化是
     * {@code NullNode} 而非 Java {@code null}，只判 {@code == null} 会放它过去，
     * 最后变成 IM 侧一条 {@code IM_90001}，错误发生在最远端、排查成本最高。
     */
    @Operation(summary = "向指定目标推送消息（toUserId=私聊 / convId=群聊，二选一）")
    @PostMapping("/message/push")
    public ReqResult<ImPushResult> messagePush(@RequestBody ImPushRequest request) {
        require(request, "request");
        require(request.getMsgType(), "msgType");
        requireJson(request.getBody(), "body");
        boolean toUser = request.getToUserId() != null;
        boolean toConv = request.getConvId() != null;
        if (toUser == toConv) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty,
                    "exactly one of toUserId or convId");
        }
        String bizMsgId = bizMsgId(request.getBizMsgId());
        ImMessageDto message = toUser
                ? rpc("messages/p2p", bizMsgId, () -> imMessageRpcService.sendP2pMessage(
                        ImP2pMessageRequest.builder()
                                .actor(actorUserId(), actorTenantId(), bizMsgId)
                                .traceId(request.getTraceId())
                                .toUserId(request.getToUserId())
                                .msgType(request.getMsgType())
                                .body(request.getBody())
                                .ext(request.getExt())
                                .build()))
                : rpc("messages/group", bizMsgId, () -> imMessageRpcService.sendGroupMessage(
                        ImGroupMessageRequest.builder()
                                .actor(actorUserId(), actorTenantId(), bizMsgId)
                                .traceId(request.getTraceId())
                                .convId(request.getConvId())
                                .msgType(request.getMsgType())
                                .body(request.getBody())
                                .replyToMsgId(request.getReplyToMsgId())
                                .mentionUserIds(request.getMentionUserIds())
                                .mentionAll(request.getMentionAll())
                                .ext(request.getExt())
                                .build()));
        return ReqResult.success(new ImPushResult(bizMsgId, message));
    }

    /**
     * 推送结果。
     *
     * @param bizMsgId 本次使用的幂等键（调用方传的原值，或服务端生成后回显）
     * @param message  IM 返回的消息体（含 msgId / seq 等）
     */
    public record ImPushResult(String bizMsgId, ImMessageDto message) {
    }

    /** 推送消息入参 */
    @Data
    @Schema(description = "推送消息入参")
    public static class ImPushRequest {

        @Schema(description = "接收方平台用户 ID（私聊）；与 convId 二选一")
        private Long toUserId;

        @Schema(description = "会话 ID（群聊 / 既有会话）；与 toUserId 二选一")
        private Long convId;

        @Schema(description = "消息类型（如 text / image），以 IM 契约的枚举为准")
        private String msgType;

        @Schema(description = "消息体，结构随 msgType 而定，原样透传给 IM")
        private JsonNode body;

        @Schema(description = "引用回复的消息 ID（仅群聊，可空）")
        private Long replyToMsgId;

        @Schema(description = "@ 的用户 ID 列表（仅群聊，可空）")
        private List<Long> mentionUserIds;

        @Schema(description = "是否 @ 全体（仅群聊，可空，缺省 false）")
        private Boolean mentionAll;

        @Schema(description = "扩展字段（JSON 字符串，可空）")
        private String ext;

        @Schema(description = "幂等键：重试必须复用同一个值；不传则由服务端生成并在响应回显")
        private String bizMsgId;

        @Schema(description = "链路 ID（可空）")
        private String traceId;
    }

    // ================================================================== 私有辅助

    /** actor = 授权用户本人（门面语义恒定，不做 AK 那套身份换算） */
    private static Long actorUserId() {
        return RequestContext.get().getUserId();
    }

    private static Long actorTenantId() {
        return RequestContext.get().getTenantId();
    }

    /**
     * 幂等键：调用方给了就用（重试场景必须由调用方给），没给则生成一个并回显。
     */
    private static String bizMsgId(String given) {
        if (StringUtils.isNotBlank(given)) {
            return given.trim();
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static void require(Object value, String name) {
        if (value == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, name);
        }
    }

    private static void require(String value, String name) {
        if (StringUtils.isBlank(value)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, name);
        }
    }

    /**
     * 消息体校验：JSON 里的 {@code "body": null} 反序列化后是 {@code NullNode} 而不是 Java {@code null}，
     * 必须两种都挡。
     */
    private static void requireJson(JsonNode value, String name) {
        if (value == null || value.isNull()) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, name);
        }
    }

    /**
     * 调 IM 并把 {@link ImRpcException} 翻译成平台业务异常。
     * <p>
     * 与 {@code NuwaxImSandboxController.rpc} 同规则（那里是 private static，无法复用；
     * 抽成共享工具要改 im-web 这个既有控制器，不划算，故在此同构一份）：
     * <pre>
     *   请求根本没发出去（isLocalProblem）→ 3612 IM 服务未配置或已停用   （重试无用，去查配置）
     *   上游暂时性故障（isRetryable）    → 3613 IM 服务暂时不可用        （可带同一 bizMsgId 重试）
     *   其余（上游业务拒绝）             → 3614 IM 拒绝了本次操作: 原文   （重试无用，改请求）
     * </pre>
     * ★判断顺序不能反：{@code isLocalProblem} 的几种在码值上与「网络不可达」几乎一样
     * （都是 {@code 0001} + 无 HTTP 状态），先判 retryable 会把「secret 没配」报成「IM 抖了，稍后重试」——
     * 用户重试一百次也不会好，而真正该看日志的运维收不到任何信号。
     * <p>
     * ★三个 code 必须各自保留，<b>不要</b>用 {@code ErrorCodeEnum.SYS_ERROR} 包一层：
     * 那样三者全变成 5000，调用方就只剩中文文案可读，而它恰恰要靠 code 决定
     * 「要不要带着同一个 bizMsgId 重试」。
     */
    private static <T> T rpc(String op, String bizMsgId, Supplier<T> call) {
        try {
            return call.get();
        } catch (ImRpcException e) {
            // 日志只打码 / tid / 可重试性：message 里可能有上游原文，但不会有 secret、签名或请求体
            log.warn("OAuth2 IM RPC failed, op={}, bizMsgId={}, code={}, tid={}, retryable={}, local={}, http={}, msg={}",
                    op, bizMsgId, e.getCode(), e.getTid(), e.isRetryable(), e.isLocalProblem(), e.getHttpStatus(),
                    e.getMessage());
            if (e.isLocalProblem()) {
                throw BizException.of(BizExceptionCodeEnum.imRpcNotConfigured);
            }
            if (e.isRetryable()) {
                throw BizException.of(BizExceptionCodeEnum.imRpcUnavailable);
            }
            throw BizException.of(BizExceptionCodeEnum.imRpcRejected, e.getMessage());
        }
    }
}
