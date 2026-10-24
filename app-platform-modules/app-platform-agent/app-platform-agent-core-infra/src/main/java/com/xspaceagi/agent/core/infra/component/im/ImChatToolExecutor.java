package com.xspaceagi.agent.core.infra.component.im;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xspaceagi.agent.core.infra.component.agent.AgentContext;
import com.xspaceagi.agent.core.infra.component.model.dto.ComponentConfig;
import com.xspaceagi.im.sdk.rpc.IImAgentRpcService;
import com.xspaceagi.im.sdk.rpc.IImContactRpcService;
import com.xspaceagi.im.sdk.rpc.IImConversationRpcService;
import com.xspaceagi.im.sdk.rpc.IImEventRpcService;
import com.xspaceagi.im.sdk.rpc.IImMessageRpcService;
import com.xspaceagi.im.sdk.rpc.ImRpcException;
import com.xspaceagi.im.sdk.rpc.dto.*;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * chatbot 原生 IM 工具的<b>执行侧</b>（清单侧见 {@link ImChatToolProvider}）。
 *
 * <p>由 {@code ModelInvoker#componentExecute} 的 {@code case Im} 分派进来，直接调 IM RPC，
 * 与 {@code NuwaxImSandboxController} 打的是<b>同一组接口</b>、走的是<b>同一套身份口径</b>
 * （见 {@link ImChatIdentity}）。
 *
 * <h2>★返回值一律自己序列化成 String</h2>
 *
 * <p>{@code ModelInvoker} 的 {@code toolCallResultConverter} 对非 String 结果用的是 fastjson 的
 * {@code JSON.toJSONString}，而 IM 的 DTO 全是 <b>Java record + Jackson 注解</b>，
 * 里面还可能带 {@code JsonNode} 字段（消息正文）。交给 fastjson 序列化 record/JsonNode 的行为
 * 不是一个我愿意赌的东西 —— 赌输的表现是<b>模型收到 {@code {}}</b>，看起来像「接口没数据」，
 * 而不是一个报错。所以这里用 Jackson 自己写，返回 String（converter 对 String 原样透传）。
 *
 * <p>同理，{@code payloadJson} 这类自由结构的入参<b>不</b>声明成 {@code Object} 类型的 Arg：
 * {@code ArgExtractUtil.setArgDefaultValue} 对 {@code Object} 会在缺省时往入参里塞一个空 {@code HashMap}、
 * 对 {@code Array_Object} 更会塞进一个 {@code ArrayList} 元素，模型没填和模型填了空对象就分不出来了。
 * 让模型给一段 JSON 文本、这边自己 parse，反而语义清楚。
 *
 * <h2>★失败一律抛异常，不返回「失败文案」</h2>
 *
 * <p>抛出去才会走 {@code ModelInvoker} 的 FAILED 分支：轨迹记 FAILED、模型看到
 * {@code "tool execution failed:" + 真实原因}，于是它能改参数重试或如实告诉用户。
 * 若在这里 catch 住返回一段话，结果会被记成 <b>SUCCESS</b>，模型会以为发出去了。
 * 也因此本类构建的工具<b>不设</b> {@code exceptionOut} / {@code fallbackMsg}。
 */
@Slf4j
@Component
public class ImChatToolExecutor {

    /** 结果序列化：省略 null 字段（IM DTO 字段多、大半为 null，省下来的都是提示词预算）。 */
    private static final ObjectMapper RESULT_MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    /** {@code payloadJson} 解析用：只读，不参与结果序列化。 */
    private static final ObjectMapper INPUT_MAPPER = new ObjectMapper();

    /**
     * ★{@code im_send_p2p} / {@code im_send_group} 允许的 {@code msgType}（IM 侧
     * {@code ImMsgType.allWires()} 是 10 种，这里刻意只开 6 种内容型）。
     *
     * <p>另外 4 种不是"漏了"，每一条都有它自己的理由：
     * <ul>
     *   <li>{@code card} —— 平台已有 {@code im_send_card}，走的是<b>另一个端点</b>
     *       （{@code messages/card}）与<b>另一套 actor 规则</b>（智能体虚拟身份，见 ImChatIdentity）。
     *       从 p2p/group 发卡会让同一件事有两种身份口径，且绕开卡片的参数校验。</li>
     *   <li>{@code system} —— 同理属于 {@code im_send_system}（管理员专属、走 {@code messages/system}、
     *       senderType 固定为系统）。放开它等于让模型用一条普通消息冒充系统提示条。</li>
     *   <li>{@code meeting} —— IM 侧明确"本次不实现会议业务"，body 契约没有可依据的形状。</li>
     *   <li>{@code merge_forward} —— body 是<b>消息快照</b>（items[] 里是转发那一刻的子消息副本，
     *       IM 侧 {@code ImMergeForwardBodies} 另有准入校验）。平台侧只能自己编快照，
     *       那等于允许模型伪造一段聊天记录。</li>
     * </ul>
     */
    private static final Set<String> MESSAGE_TYPES =
            Set.of("text", "rich_text", "image", "file", "audio", "video");

    /** ★媒体类型：body 里给的是附件地址而不是正文（{@code ImMessageBodies.attachmentViewsOf} 的读取口径）。 */
    private static final Set<String> MEDIA_TYPES = Set.of("image", "file", "audio", "video");

    private IImMessageRpcService imMessageRpcService;
    private IImConversationRpcService imConversationRpcService;
    private IImContactRpcService imContactRpcService;
    private IImEventRpcService imEventRpcService;
    private IImAgentRpcService imAgentRpcService;

    // ★setter 注入（对齐 ModelInvoker 的风格）+ required=false：
    //   这 5 个接口在运行期由同一个 ImRpcClient bean 实现（ImRpcConfiguration 注册），
    //   但那个 bean 属于 app-platform-im-infra，不在本模块依赖里。装配不上时工具也不会被挂载
    //   （ImChatToolProvider#available() 同样问的是 IM 侧的 bean），这里再兜一层只是为了启动不炸。
    @Autowired(required = false)
    public void setImMessageRpcService(IImMessageRpcService v) {
        this.imMessageRpcService = v;
    }

    @Autowired(required = false)
    public void setImConversationRpcService(IImConversationRpcService v) {
        this.imConversationRpcService = v;
    }

    @Autowired(required = false)
    public void setImContactRpcService(IImContactRpcService v) {
        this.imContactRpcService = v;
    }

    @Autowired(required = false)
    public void setImEventRpcService(IImEventRpcService v) {
        this.imEventRpcService = v;
    }

    @Autowired(required = false)
    public void setImAgentRpcService(IImAgentRpcService v) {
        this.imAgentRpcService = v;
    }

    /**
     * 执行一次 IM 工具调用。
     *
     * @param ctx    智能体上下文（身份、租户、traceId 的唯一来源 —— ★不能取 {@code RequestContext}：
     *               工具执行跑在模型回调线程上，{@code ModelInvoker} 只在 {@code callAgent} 那一处
     *               set/remove 过线程租户，通用工具路径上没有）
     * @param config 工具定义（按 {@code functionName} 分派）
     * @param input  模型给的实参
     * @return JSON 文本（写操作统一带 {@code bizMsgId}，便于「用户说没收到、日志说发了」一句话收口）
     */
    public String execute(AgentContext ctx, ComponentConfig config, Map<String, Object> input) {
        ImChatIdentity identity = ImChatIdentity.of(ctx);
        Map<String, Object> in = input == null ? new HashMap<>() : input;
        String function = config == null ? null : config.getFunctionName();
        String bizMsgId = bizMsgId(text(in.get("bizMsgId")));
        String traceId = ctx == null ? null : ctx.getRequestId();
        try {
            Object data = dispatch(function, identity, in, bizMsgId, traceId);
            return write(bizMsgId, data);
        } catch (ImRpcException e) {
            throw translate(function, bizMsgId, e);
        }
    }

    // ============================== 分派 ==============================

    private Object dispatch(String function, ImChatIdentity id, Map<String, Object> in,
                            String bizMsgId, String traceId) {
        if (function == null) {
            throw missing("functionName");
        }
        // ★管理员端点的第二道闸门（第一道在清单侧：非管理员根本看不到这 3 个工具，
        //   见 ImChatToolProvider#ADMIN_ONLY_TOOLS 的 javadoc）。这里刻意【保留】：
        //   ① 判据是同一个常量，所以「清单侧过滤被改坏」不会直接变成越权 —— 少一层广告不等于少一层权限；
        //   ② 分派是按 functionName 的字符串 switch，任何一个绕过清单进来的调用（未来新增入口、
        //      测试直接调 execute）都会落到这里，集中判一次比散在 3 个方法里更难漏。
        //   下面 3 个方法体里各自的 requireAdmin() 也保留：那是「这个动作为什么要管理员」的就地文档。
        if (ImChatToolProvider.ADMIN_ONLY_TOOLS.contains(function)) {
            id.requireAdmin();
        }
        switch (function) {
            // ---------- 发送 ----------
            case "im_send_p2p":
                return sendP2p(id, in, bizMsgId, traceId);
            case "im_send_group":
                return sendGroup(id, in, bizMsgId, traceId);
            case "im_send_card":
                return sendCard(id, in, bizMsgId, traceId);
            case "im_send_system":
                return sendSystem(id, in, bizMsgId, traceId);
            case "im_system_notify":
                return systemNotify(id, in, bizMsgId, traceId);
            case "im_push_event":
                return pushEvent(id, in, bizMsgId, traceId);
            case "im_revoke":
                return revoke(id, in, bizMsgId, traceId);
            // ---------- 消息查询 ----------
            case "im_get_message":
                return rpc("messages/get", null, () -> requireService(imMessageRpcService).getMessage(
                        ImMessageGetRequest.builder()
                                .actor(id.scenarioActor(), id.getTenantId(), null)
                                .traceId(traceId)
                                .convId(convId(in, id, true))
                                .msgId(reqId(in, "msgId"))
                                .build()));
            case "im_read_counts":
                return rpc("messages/read-counts", null, () -> requireService(imMessageRpcService).getReadCounts(
                        ImReadCountsRequest.builder()
                                .actor(id.scenarioActor(), id.getTenantId(), null)
                                .traceId(traceId)
                                .convId(convId(in, id, true))
                                .msgIds(reqIds(in, "msgIds"))
                                .build()));
            case "im_list_messages":
                return rpc("messages/list", null, () -> requireService(imMessageRpcService).listMessagesByTimeRange(
                        ImMessageListRequest.builder()
                                .actor(id.scenarioActor(), id.getTenantId(), null)
                                .traceId(traceId)
                                .convId(convId(in, id, true))
                                .startTime(ImChatIdentity.toLong(in.get("startTime")))
                                .endTime(ImChatIdentity.toLong(in.get("endTime")))
                                .afterSeq(ImChatIdentity.toLong(in.get("afterSeq")))
                                .limit(integer(in.get("limit")))
                                .build()));
            // ---------- 会话 ----------
            case "im_list_conversations":
                return rpc("conversations/list", null, () -> requireService(imConversationRpcService).listConversations(
                        ImConversationListRequest.builder()
                                .actor(id.scenarioActor(), id.getTenantId(), null)
                                .traceId(traceId)
                                .cursor(text(in.get("cursor")))
                                .limit(integer(in.get("limit")))
                                .build()));
            case "im_conversation_detail":
                return rpc("conversations/detail", null,
                        () -> requireService(imConversationRpcService).getConversationDetail(
                                ImConversationDetailRequest.builder()
                                        .actor(id.scenarioActor(), id.getTenantId(), null)
                                        .traceId(traceId)
                                        .convId(convId(in, id, true))
                                        .build()));
            case "im_list_contacts":
                return rpc("contacts/list", null, () -> requireService(imContactRpcService).listContacts(
                        ImContactListRequest.builder()
                                .actor(id.scenarioActor(), id.getTenantId(), null)
                                .traceId(traceId)
                                .build()));
            case "im_list_members":
                return rpc("conversations/members/list", null, () -> requireService(imConversationRpcService).listMembers(
                        ImMemberListRequest.builder()
                                .actor(id.scenarioActor(), id.getTenantId(), null)
                                .traceId(traceId)
                                .convId(convId(in, id, true))
                                .limit(integer(in.get("limit")))
                                .build()));
            case "im_get_or_create_p2p":
                return rpc("conversations/p2p", bizMsgId, () -> requireService(imConversationRpcService).getOrCreateP2p(
                        ImP2pConversationRequest.builder()
                                .actor(id.scenarioActor(), id.getTenantId(), bizMsgId)
                                .traceId(traceId)
                                .toUserId(reqId(in, "toUserId"))
                                .build()));
            case "im_create_group":
                return rpc("conversations/create-group", bizMsgId, () -> requireService(imConversationRpcService).createGroup(
                        ImGroupCreateRequest.builder()
                                .actor(id.scenarioActor(), id.getTenantId(), bizMsgId)
                                .traceId(traceId)
                                .name(reqText(in, "name"))
                                .avatar(text(in.get("avatar")))
                                .notice(text(in.get("notice")))
                                .memberUserIds(reqIds(in, "memberUserIds"))
                                .maxMember(integer(in.get("maxMember")))
                                .clientGroupId(text(in.get("clientGroupId")))
                                .build()));
            case "im_update_digest":
                rpc("conversations/digest", bizMsgId, () -> {
                    requireService(imConversationRpcService).updateDigest(ImDigestUpdateRequest.builder()
                            .actor(id.scenarioActor(), id.getTenantId(), bizMsgId)
                            .traceId(traceId)
                            .convId(convId(in, id, true))
                            .digest(reqText(in, "digest"))
                            .build());
                    return null;
                });
                return null;
            // ---------- 群成员 ----------
            case "im_add_members":
                return rpc("conversations/members/add", bizMsgId, () -> requireService(imConversationRpcService).addMembers(
                        ImMemberAddRequest.builder()
                                .actor(id.scenarioActor(), id.getTenantId(), bizMsgId)
                                .traceId(traceId)
                                .convId(convId(in, id, true))
                                .userIds(reqIds(in, "userIds"))
                                .nickName(text(in.get("nickName")))
                                .agentMode(integer(in.get("agentMode")))
                                .build()));
            case "im_remove_members":
                rpc("conversations/members/remove", bizMsgId, () -> {
                    requireService(imConversationRpcService).removeMembers(ImMemberRemoveRequest.builder()
                            .actor(id.scenarioActor(), id.getTenantId(), bizMsgId)
                            .traceId(traceId)
                            .convId(convId(in, id, true))
                            .userIds(reqIds(in, "userIds"))
                            .reason(text(in.get("reason")))
                            .build());
                    return null;
                });
                return null;
            // ---------- 排队区 ----------
            case "im_pending_list":
                return rpc("agent/pending/list", null, () -> requireService(imAgentRpcService).listPending(
                        ImAgentPendingListRequest.builder()
                                .actor(id.agentSubject(), id.getTenantId(), null)
                                .traceId(traceId)
                                .convId(convId(in, id, true))
                                .build()));
            case "im_pending_clear":
                return rpc("agent/pending/clear", bizMsgId, () -> requireService(imAgentRpcService).clearPending(
                        ImAgentPendingClearRequest.builder()
                                .actor(id.agentSubject(), id.getTenantId(), bizMsgId)
                                .traceId(traceId)
                                .convId(convId(in, id, true))
                                .msgIds(reqIds(in, "msgIds"))
                                .build()));
            default:
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM,
                        BizExceptionCodeEnum.validationFailedWithDetail,
                        "未知的 IM 工具：" + function);
        }
    }

    // ============================== 发送类（身份敏感） ==============================

    private ImMessageDto sendP2p(ImChatIdentity id, Map<String, Object> in, String bizMsgId, String traceId) {
        // ★闸门②先于 actor 解析：群场景下一律拒（否则会以群代理身份落进任意人的单聊）
        id.requireP2pSendIsAllowed();
        Long toUserId = reqId(in, "toUserId");
        // ★msgType 省略时是 text ⇒ 只填 text 的旧调用一字不变；其余类型按类型取正文 / 附件地址
        String msgType = messageType(in);
        JsonNode body = messageBody(in, msgType);
        // ★收件人＝自己 ⇒ 智能体虚拟身份（这是「智能体回话」），否则本人（这是「用户自己在发」）
        Long actor = id.p2pActor(toUserId);
        return rpc("messages/p2p", bizMsgId, () -> requireService(imMessageRpcService).sendP2pMessage(
                ImP2pMessageRequest.builder()
                        .actor(actor, id.getTenantId(), bizMsgId)
                        .traceId(traceId)
                        .toUserId(toUserId)
                        .msgType(msgType)
                        .body(body)
                        .build()));
    }

    private ImMessageDto sendGroup(ImChatIdentity id, Map<String, Object> in, String bizMsgId, String traceId) {
        Long convId = ImChatIdentity.toLong(in.get("convId"));
        if (convId == null) {
            // 群场景：默认就是触发本次运行的那个群（也正是闸门①唯一会放行的那个）
            if (id.isGroupScenario() && id.getGroupConvId() != null) {
                convId = id.getGroupConvId();
            } else {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM,
                        BizExceptionCodeEnum.validationFailedWithDetail,
                        "私聊场景下发群消息必须显式给出目标群的 convId（可先用 im_list_conversations 查）");
            }
        }
        // ★闸门①：群场景下只能发回触发本次运行的那个群
        id.requireGroupSendTargetIsTriggerGroup(convId);
        // ★群场景读不到虚拟身份时返回 null → 交给 IM 大声拒（IM_90001），绝不回退成群代理发出去
        Long actor = id.groupActor();
        String msgType = messageType(in);
        JsonNode body = messageBody(in, msgType);
        Long finalConvId = convId;
        return rpc("messages/group", bizMsgId, () -> requireService(imMessageRpcService).sendGroupMessage(
                ImGroupMessageRequest.builder()
                        .actor(actor, id.getTenantId(), bizMsgId)
                        .traceId(traceId)
                        .convId(finalConvId)
                        .msgType(msgType)
                        .body(body)
                        .replyToMsgId(ImChatIdentity.toLong(in.get("replyToMsgId")))
                        .mentionUserIds(ids(in.get("mentionUserIds")))
                        .mentionAll(bool(in.get("mentionAll")))
                        .build()));
    }

    /**
     * ★互动卡片（{@code messages/card}）：title / desc / iconUrl / actionUrl / fields 可填，
     * <b>{@code actions} 恒为空</b>。
     *
     * <h2>★★{@code actions} 为空是刻意的，不是"还没接"</h2>
     *
     * <p>卡片上能点的东西有两种，语义完全不同：{@code actionUrl} 是"打开一个页面"，
     * {@code actions} 是"把一件事交给服务端执行"。后者在 IM 侧靠消息体里的
     * {@code actions[].actionType} 找执行者（{@code ImMessageActionRegistry}），
     * 而<b>动作类型只能由能执行它的那一方注册</b> —— 现在只注册了两个，都是 IM 自己的入群业务
     * （{@code join_request.review} / {@code join_invite.confirm}）。
     * ⇒ 从这里放开 {@code actions}，只有两种下场，都比"不给"更坏：
     * <ol>
     *   <li>编一个没人注册的类型 ⇒ 用户点下去拿到 {@code IM_10404}（"按钮点了没反应"），
     *       而模型以为自己做成了一张可交互卡片；</li>
     *   <li>填那两个已注册的类型 ⇒ <b>模型在替 IM 编"通过/拒绝"的裁决参数</b>（{@code payload}），
     *       而那正是 IM 侧刻意锁死的一条线（点击只回传 key，语义全在服务端写的 payload 里，
     *       见 {@code ImCardBody.Action} 的类注释）—— 一条被注入的消息就足以让智能体把
     *       "批准入群"的卡片递给群主。</li>
     * </ol>
     * <p>⇒ 平台侧要"一个能点的入口"时，正确做法就是 {@code actionUrl}（契约明文如此：
     * "若平台侧只想要一个打开某个页面的按钮，就该用 actionUrl"）。真要做按钮，
     * 得先在 IM 侧注册新的 {@code actionType} + {@code ImMessageActionHandler}，
     * 那不是本工具加一个参数就能成立的。★sandbox 面（{@code NuwaxImSandboxController}）
     * 是另一回事：它是给技能开发者用的透传接口，整份 {@code ImCardBody} 原样转发，本方法不跟它对齐。
     */
    private ImMessageDto sendCard(ImChatIdentity id, Map<String, Object> in, String bizMsgId, String traceId) {
        Long convId = convId(in, id, true);
        String title = reqText(in, "title");
        // ★subtitle 恒为 null：契约里已 deprecated（完整句子请用 desc），平台侧没有任何理由再写它。
        ImCardBody card = new ImCardBody(title, text(in.get("desc")), null,
                text(in.get("iconUrl")), text(in.get("actionUrl")), cardFields(in), List.of());
        // ★卡片的 actor 与群发是两条规则（判据不同、不可互换）：能发给谁取决于「这个身份在不在目标会话里」，
        //   而 AGENT_VIRTUAL_USER_ID 正是那个在会话里的身份 —— 换成本人后，往「智能体↔用户」的会话发卡片
        //   会变成【用户自己发的消息】，往群里发则因本人不是群成员而失败，两个方向都更坏。
        return rpc("messages/card", bizMsgId, () -> requireService(imMessageRpcService).sendCardMessage(
                ImCardMessageRequest.builder()
                        .actor(id.agentActor(), id.getTenantId(), bizMsgId)
                        .traceId(traceId)
                        .convId(convId)
                        .card(card)
                        .build()));
    }

    private ImMessageDto sendSystem(ImChatIdentity id, Map<String, Object> in, String bizMsgId, String traceId) {
        id.requireAdmin();
        Long convId = ImChatIdentity.toLong(in.get("convId"));
        Long toUserId = ImChatIdentity.toLong(in.get("toUserId"));
        if (convId == null && toUserId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM,
                    BizExceptionCodeEnum.fieldRequiredButEmpty, "convId 或 toUserId");
        }
        String text = reqText(in, "text");
        return rpc("messages/system", bizMsgId, () -> requireService(imMessageRpcService).sendSystemMessage(
                ImSystemMessageRequest.builder()
                        .actor(id.agentActor(), id.getTenantId(), bizMsgId)
                        .traceId(traceId)
                        .convId(convId)
                        .toUserId(toUserId)
                        .senderType(ImSystemMessageRequest.SENDER_TYPE_SYSTEM)
                        // ★msgType 固定 system（居中提示条）：它的 body 形状 {"text": ...} 是平台通知链路
                        //   已经跑通的那一个；其余 msgType 的 body 契约这里没有可验证依据，不让模型去猜。
                        .msgType(ImSystemNotifyRequest.MSG_TYPE_SYSTEM)
                        .body(textBody(text))
                        .build()));
    }

    private ImSystemNotifyResultDto systemNotify(ImChatIdentity id, Map<String, Object> in,
                                                 String bizMsgId, String traceId) {
        id.requireAdmin();
        List<Long> toUserIds = reqIds(in, "toUserIds");
        String text = reqText(in, "text");
        // ★actor 是【管理员本人】，刻意不走 agentActor / scenarioActor：
        //   批量通知是「谁批的」比「以谁的名义发」更要紧的动作，审计里出现群代理这类中间身份就等于没有审计。
        //   群场景下 selfUserId 就是群代理 —— 而群代理几乎不可能是 Admin，闸门会先把它拦在 4030。
        return rpc("messages/system-notify", bizMsgId, () -> requireService(imMessageRpcService).sendSystemNotify(
                ImSystemNotifyRequest.builder()
                        .actor(id.getSelfUserId(), id.getTenantId(), bizMsgId)
                        .traceId(traceId)
                        .toUserIds(toUserIds)
                        // ★DEV-298：白名单扩到 text/system/card/rich_text 四型后固定 text（纯文本气泡，
                        //   与平台通知链路 pushImNotify 同拍板）；body 形状 {"text": ...} 两型相同。
                        //   注意上方 sendSystem（messages/system 端点）不受 DEV-298 影响，仍固定 system。
                        .msgType(ImSystemNotifyRequest.MSG_TYPE_TEXT)
                        .body(textBody(text))
                        .build()));
    }

    @SuppressWarnings("unchecked")
    private ImCustomEventPushResultDto pushEvent(ImChatIdentity id, Map<String, Object> in,
                                                 String bizMsgId, String traceId) {
        id.requireAdmin();
        String eventType = reqText(in, "eventType");
        List<Long> userIds = ids(in.get("userIds"));
        Long convId = ImChatIdentity.toLong(in.get("convId"));
        if ((userIds == null || userIds.isEmpty()) && convId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM,
                    BizExceptionCodeEnum.fieldRequiredButEmpty, "userIds 或 convId");
        }
        Map<String, Object> payload = null;
        String payloadJson = text(in.get("payloadJson"));
        if (payloadJson != null) {
            try {
                JsonNode node = INPUT_MAPPER.readTree(payloadJson);
                if (node == null || node.isNull()) {
                    throw new IllegalArgumentException("payloadJson 是 null");
                }
                if (!node.isObject()) {
                    throw new IllegalArgumentException("payloadJson 必须是 JSON 对象，实际是 " + node.getNodeType());
                }
                payload = INPUT_MAPPER.convertValue(node, Map.class);
            } catch (IllegalArgumentException e) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM,
                        BizExceptionCodeEnum.validationFailedWithDetail, "payloadJson 不合法：" + e.getMessage());
            } catch (Exception e) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM,
                        BizExceptionCodeEnum.validationFailedWithDetail,
                        "payloadJson 不是合法的 JSON 对象文本：" + e.getMessage());
            }
        }
        Map<String, Object> finalPayload = payload;
        return rpc("events/push", bizMsgId, () -> requireService(imEventRpcService).pushCustomEvent(
                ImCustomEventPushRequest.builder()
                        .actor(id.scenarioActor(), id.getTenantId(), bizMsgId)
                        .traceId(traceId)
                        .userIds(userIds)
                        .convId(convId)
                        .eventType(eventType)
                        .payload(finalPayload)
                        .build()));
    }

    /**
     * 撤回：能撤的是【本次身份自己发的】消息，而「本次身份」要看两件事 ——
     * ① 触发场景（{@link ImChatIdentity#scenarioActor()}）；② 这条消息当初是谁发的。
     *
     * <p>★只有「场景身份 ≠ 智能体虚拟身份」时才多走一次 {@code messages/get} 读回：
     * 群场景下 {@code scenarioActor()} 返回的<b>就是</b> {@code AGENT_VIRTUAL_USER_ID}，没有歧义可言，
     * 那次读回纯属白花一趟 RTT。私聊场景才有歧义（智能体在这条私聊里的回话，sender 是虚拟身份，
     * 而用户自己发的消息 sender 是本人），此时按 {@code senderId} 选身份。
     */
    private Void revoke(ImChatIdentity id, Map<String, Object> in, String bizMsgId, String traceId) {
        Long convId = convId(in, id, true);
        Long msgId = reqId(in, "msgId");
        String reason = text(in.get("reason"));
        Long scenarioActor = id.scenarioActor();
        Long agentVirtualUserId = id.getAgentVirtualUserId();
        Long actor;
        if (agentVirtualUserId == null || agentVirtualUserId.equals(scenarioActor)) {
            actor = scenarioActor;
        } else {
            ImMessageDetailDto target = rpc("messages/get", null, () -> requireService(imMessageRpcService).getMessage(
                    ImMessageGetRequest.builder()
                            .actor(scenarioActor, id.getTenantId(), null)
                            .traceId(traceId)
                            .convId(convId)
                            .msgId(msgId)
                            .build()));
            if (target != null && agentVirtualUserId.equals(target.senderId())) {
                log.info("★[IM-TOOL] 私聊场景 / 目标消息是智能体自己发的（senderId={}）→ actor 切换为虚拟身份"
                        + " convId={} msgId={} userId={}", target.senderId(), convId, msgId, id.getSelfUserId());
                actor = agentVirtualUserId;
            } else {
                log.info("★[IM-TOOL] 私聊场景 / 目标消息不是智能体发的（senderId={}）→ actor 保持本人"
                        + " convId={} msgId={} userId={}",
                        target == null ? null : target.senderId(), convId, msgId, id.getSelfUserId());
                actor = scenarioActor;
            }
        }
        Long finalActor = actor;
        rpc("messages/revoke", bizMsgId, () -> {
            requireService(imMessageRpcService).revokeMessage(ImMessageRevokeRequest.builder()
                    .actor(finalActor, id.getTenantId(), bizMsgId)
                    .traceId(traceId)
                    .convId(convId)
                    .msgId(msgId)
                    .reason(reason)
                    .build());
            return null;
        });
        return null;
    }

    // ============================== 入参 / 出参 ==============================

    /**
     * 会话 ID：模型给了就用；没给则回落到本次触发的会话（群场景优先用 {@code GROUP_CONV_ID}）。
     *
     * <p>★这个默认值只是<b>省事</b>，不是<b>放宽</b>：真正的授权判定在 IM 侧
     * （{@code requireActiveMember(convId, actor)}）与本类的两道闸门上，回落拿到的恒是本次触发的会话。
     */
    private Long convId(Map<String, Object> in, ImChatIdentity id, boolean required) {
        Long convId = ImChatIdentity.toLong(in.get("convId"));
        if (convId != null) {
            return convId;
        }
        if (id.isGroupScenario() && id.getGroupConvId() != null) {
            return id.getGroupConvId();
        }
        if (id.getConvId() != null) {
            return id.getConvId();
        }
        if (required) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM,
                    BizExceptionCodeEnum.fieldRequiredButEmpty, "convId");
        }
        return null;
    }

    private Long reqId(Map<String, Object> in, String name) {
        Long value = ImChatIdentity.toLong(in.get(name));
        if (value == null) {
            throw missing(name);
        }
        return value;
    }

    private String reqText(Map<String, Object> in, String name) {
        String value = text(in.get(name));
        if (value == null) {
            throw missing(name);
        }
        return value;
    }

    /**
     * ★消息类型：省略 = {@code text}。
     *
     * <p>大小写不敏感（模型写 {@code Text} 也认），但只认 {@link #MESSAGE_TYPES} 里那 6 种内容型；
     * 其余值直接拒，并在错误信息里指出该用哪个工具 —— 放行一个平台没有验证过的类型，
     * 结果是"发出去了但前端渲染不出来"，那种失败用户只会以为是消息丢了。
     */
    private static String messageType(Map<String, Object> in) {
        String raw = text(in.get("msgType"));
        if (raw == null) {
            return "text";
        }
        String normalized = raw.toLowerCase(Locale.ROOT);
        if (!MESSAGE_TYPES.contains(normalized)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM,
                    BizExceptionCodeEnum.validationFailedWithDetail,
                    "不支持的 msgType=" + raw + "（本工具支持：" + MESSAGE_TYPES
                            + "；发卡片请用 im_send_card、发系统提示请用 im_send_system）");
        }
        return normalized;
    }

    /**
     * ★按 {@code msgType} 组装 body。
     *
     * <p>优先级：{@code bodyJson} &gt; 结构化参数。{@code bodyJson} 表达的是"调用方自己知道完整结构"
     * （例如 {@code rich_text} 的 {@code segments} 或多附件），给了它就不再拼装 ——
     * 否则两边各填一半，合出来的是一种谁都没设计过的形状。
     */
    private JsonNode messageBody(Map<String, Object> in, String msgType) {
        JsonNode explicit = parsedBodyJson(in);
        if (explicit != null) {
            requireRenderableBody(msgType, explicit);
            return explicit;
        }
        if (!MEDIA_TYPES.contains(msgType)) {
            return textBody(requiredText(in, "text", msgType));
        }
        // ★媒体类型：body 就是"一个附件"（键名与 IM 侧 ImMessageBodies 的读取口径一致：
        //   url / fileName / size；没有 width/height/duration —— IM 侧没有任何地方读它们，
        //   加了只会是模型照抄一遍的噪音）。
        String url = text(in.get("url"));
        if (url == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM,
                    BizExceptionCodeEnum.validationFailedWithDetail,
                    "msgType=" + msgType + " 需要给 url（媒体文件的可公开访问地址），当前没给");
        }
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("url", url);
        String fileName = text(in.get("fileName"));
        if (fileName != null) {
            body.put("fileName", fileName);
        }
        Integer size = integer(in.get("size"));
        if (size != null) {
            body.put("size", size);
        }
        return body;
    }

    /**
     * ★{@code bodyJson} 直通路径的最后一层护栏：这条消息必须「前端渲染得出来」。
     *
     * <p>结构化参数路径由 {@link #requiredText} 把关，但给了 {@code bodyJson} 就完全绕开了它。
     * 模型把字段名写成 {@code content} 而不是 {@code text} 时，请求会一路发到 IM 并<b>发送成功</b>，
     * 后果却全是静默的：摘要退化成 {@code [文本]}、气泡渲染不出、ES 也搜不到 ——
     * 而调用方收到的是成功，这种失败最难发现。拦在平台侧还能把正确字段名直接告诉模型。
     *
     * <p>判据与 IM 的 {@code system-notify} 同口径
     * （{@code ImMessageRpcServiceImpl#requireSystemNotifyBody}）：
     * <ul>
     *   <li>{@code text}：{@code body.text} 必须是非空<b>字符串</b>；</li>
     *   <li>{@code rich_text}：{@code text} / {@code segments} / {@code attachments} 至少其一
     *       —— 「一条只有多图没有文字的 rich_text」是合法形态，不能强制 text 必填；</li>
     *   <li>媒体型不走这里（结构化参数构造 body 时已要求 {@code url}）。</li>
     * </ul>
     *
     * <p>★只校验「能不能渲染」，不复制各类型的完整契约 —— 后者仍由 IM 一处说了算。
     */
    private static void requireRenderableBody(String msgType, JsonNode body) {
        if (!"text".equals(msgType) && !"rich_text".equals(msgType)) {
            return;
        }
        JsonNode text = body.get("text");
        boolean hasText = text != null && text.isTextual() && !text.asText().isBlank();
        if ("text".equals(msgType)) {
            if (hasText) {
                return;
            }
            // ★点名最可能的错因：模型习惯把正文写成 content（OpenAI messages 的字段名）。
            String hint = body.has("content")
                    ? "；检测到 body 里给的是 content，正确字段名是 text" : "";
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM,
                    BizExceptionCodeEnum.validationFailedWithDetail,
                    "msgType=text 的 bodyJson 缺少非空 text 字段" + hint
                            + "，应形如 {\"text\":\"...\"}（否则发出去只会显示成 [文本]）");
        }
        JsonNode segments = body.get("segments");
        JsonNode attachments = body.get("attachments");
        boolean hasSegments = segments != null && segments.isArray() && segments.size() > 0;
        boolean hasAttachments = attachments != null && attachments.isArray() && attachments.size() > 0;
        if (!hasText && !hasSegments && !hasAttachments) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM,
                    BizExceptionCodeEnum.validationFailedWithDetail,
                    "msgType=rich_text 的 bodyJson 至少要有一个非空的 text / segments / attachments"
                            + "（三者皆空的消息前端渲染出来是空气泡）");
        }
    }

    /** 必填文本：错误信息带上 {@code msgType}，模型才知道该补哪一个参数。 */
    private static String requiredText(Map<String, Object> in, String name, String msgType) {
        String value = text(in.get(name));
        if (value == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM,
                    BizExceptionCodeEnum.validationFailedWithDetail,
                    "msgType=" + msgType + " 需要 " + name + "（正文不能为空）");
        }
        return value;
    }

    /**
     * {@code bodyJson} → {@link JsonNode}（没给返回 {@code null}）。
     *
     * <p>★必须是 JSON <b>对象</b>：IM 的 body 就是对象，把数组/标量发过去等于"发了一条前端渲染不了的
     * 消息"，不如在平台侧就拦下来说清楚。★声明成 String 而不是 Object 的理由见类注释
     * （框架会给 Object 类入参塞空 Map 当默认值，"没填"与"填了空对象"分不出来）。
     */
    private static JsonNode parsedBodyJson(Map<String, Object> in) {
        String raw = text(in.get("bodyJson"));
        if (raw == null) {
            return null;
        }
        JsonNode node;
        try {
            node = INPUT_MAPPER.readTree(raw);
        } catch (Exception e) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM,
                    BizExceptionCodeEnum.validationFailedWithDetail,
                    "bodyJson 不是合法的 JSON：" + e.getMessage());
        }
        if (node == null || !node.isObject()) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM,
                    BizExceptionCodeEnum.validationFailedWithDetail,
                    "bodyJson 必须是一个 JSON 对象（形如 {\"text\":\"...\"}）");
        }
        return node;
    }

    /**
     * 卡片的 {@code fields}（键值对明细区）—— 由 {@code fieldsJson} 解析而来（没给返回空列表）。
     *
     * <p>★声明成<b>一段 JSON 文本</b>而不是 {@code Array_Object} 入参：{@code ArgExtractUtil} 对
     * {@code Array_Object} 会往缺省值里塞一个 {@code ArrayList} 元素，"没填"与"填了个空的"分不出来
     * （同 {@code bodyJson}，理由见类注释）。
     *
     * <p>★★{@code label}/{@code value} <b>保持字符串</b>：契约明文要求如此 —— 卡片上的"金额/单号"
     * 常常要保留前导零与精度（{@code 007}、{@code 1.10}），按数值传输就会在某一跳被规范化。
     * 模型写成数字时这里照样收（为一次格式问题报废整轮不值得），但按<b>字面量</b>转成字符串，
     * 免得卡片上出现"共 120.0 条"。
     */
    private static List<ImCardBody.Field> cardFields(Map<String, Object> in) {
        String raw = text(in.get("fieldsJson"));
        if (raw == null) {
            return List.of();
        }
        JsonNode node;
        try {
            node = INPUT_MAPPER.readTree(raw);
        } catch (Exception e) {
            throw invalid("fieldsJson 不是合法的 JSON：" + e.getMessage());
        }
        if (node == null || !node.isArray()) {
            throw invalid("fieldsJson 必须是一个 JSON 数组（形如 [{\"label\":\"工单号\",\"value\":\"007\"}]）");
        }
        List<ImCardBody.Field> fields = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (item == null || !item.isObject()) {
                throw invalid("fieldsJson 的每一项都要是对象（形如 {\"label\":\"工单号\",\"value\":\"007\"}）");
            }
            String label = scalarText(item.get("label"));
            String value = scalarText(item.get("value"));
            if (label == null || label.isBlank() || value == null || value.isBlank()) {
                // ★只给一半不收：卡片明细是一行"标签 + 值"，缺一半渲染出来就是一行残缺信息，
                //   而模型不会收到任何报错（它以为自己填全了）。
                throw invalid("fieldsJson 的每一项都要有非空的 label 与 value，当前：" + item);
            }
            fields.add(new ImCardBody.Field(label, value));
        }
        return fields;
    }

    /**
     * JSON 标量 → 字符串；{@code null} / 对象 / 数组一律返回 {@code null}（由调用方按"缺值"拒掉）。
     *
     * <p>★数字走 {@code stripTrailingZeros}：模型写 {@code 120} 时是 {@code IntNode}、本来就没事，
     * 但写成 {@code 120.0} 时 {@code DoubleNode#asText} 会交出 {@code "120.0"}，
     * 而卡片上"共 120.0 条"是肉眼可见的错。
     */
    private static String scalarText(JsonNode node) {
        if (node == null || node.isNull() || node.isContainerNode()) {
            return null;
        }
        return node.isNumber()
                ? node.decimalValue().stripTrailingZeros().toPlainString()
                : node.asText();
    }

    private List<Long> reqIds(Map<String, Object> in, String name) {
        List<Long> value = ids(in.get(name));
        if (value == null || value.isEmpty()) {
            throw missing(name);
        }
        return value;
    }

    /** 文本：空白串按「没给」处理（模型很爱填空串，让它落到必填校验上比发一条空消息好）。 */
    private static String text(Object raw) {
        if (raw == null) {
            return null;
        }
        String s = String.valueOf(raw).trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * id 列表：元素按 {@link ImChatIdentity#toLong} 逐个解析，<b>解析不出来的元素直接报错</b>。
     *
     * <p>★不静默丢弃：一份被悄悄去掉几个元素的收件人名单，比一次明确的失败危险得多。
     */
    private static List<Long> ids(Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Collection<?> collection)) {
            // 模型偶尔会给单个值而不是数组，认下来（比报「类型不对」更有用）
            Long single = ImChatIdentity.toLong(raw);
            if (single == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM,
                        BizExceptionCodeEnum.validationFailedWithDetail,
                        "参数不是一个 ID 列表，也无法解析成单个 ID：" + raw);
            }
            return List.of(single);
        }
        List<Long> result = new ArrayList<>(collection.size());
        for (Object item : collection) {
            Long value = ImChatIdentity.toLong(item);
            if (value == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM,
                        BizExceptionCodeEnum.validationFailedWithDetail,
                        "列表里有一个无法解析成正整数 ID 的元素：" + item
                                + "（浮点数一律不认：截断后的数字是另一个人的 id）");
            }
            result.add(value);
        }
        return result;
    }

    private static Integer integer(Object raw) {
        Long value = ImChatIdentity.toLong(raw);
        if (value == null) {
            return null;
        }
        if (value > Integer.MAX_VALUE) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM,
                    BizExceptionCodeEnum.validationFailedWithDetail,
                    "数值超出上限（" + Integer.MAX_VALUE + "）：" + value);
        }
        return value.intValue();
    }

    private static Boolean bool(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) {
            return null;
        }
        return Boolean.parseBoolean(s);
    }

    private static JsonNode textBody(String text) {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("text", text);
        return body;
    }

    /** 幂等键：调用方给了就用（重试必须由调用方给），没给则生成一个并回显。 */
    private static String bizMsgId(String given) {
        if (given != null && !given.isBlank()) {
            return given.trim();
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static BizException missing(String name) {
        return BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, name);
    }

    /** 参数格式不对的统一出口（与 {@link #missing} 同档：都走 INVALID_PARAM，模型看到 detail 才能自己改对）。 */
    private static BizException invalid(String detail) {
        return BizException.of(ErrorCodeEnum.INVALID_PARAM,
                BizExceptionCodeEnum.validationFailedWithDetail, detail);
    }

    private String write(String bizMsgId, Object data) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("ok", true);
        // ★bizMsgId 一定回显：出现「用户说没收到、日志说发了」时，拿它去 IM 侧查这次调用，
        //   命中幂等说明是重放、没命中说明确实发过 —— 一句话收口，不用猜。
        envelope.put("bizMsgId", bizMsgId);
        envelope.put("data", data);
        try {
            return RESULT_MAPPER.writeValueAsString(envelope);
        } catch (Exception e) {
            log.warn("[IM-TOOL] 结果序列化失败，降级为 toString bizMsgId={}: {}", bizMsgId, e.getMessage());
            return "{\"ok\":true,\"bizMsgId\":\"" + bizMsgId + "\",\"data\":"
                    + String.valueOf(data).replace("\"", "'") + "}";
        }
    }

    private static <T> T requireService(T service) {
        if (service == null) {
            throw BizException.of(BizExceptionCodeEnum.imRpcNotConfigured);
        }
        return service;
    }

    /**
     * IM RPC 异常 → 平台错误码。<b>与 {@code NuwaxImSandboxController#translate} 逐条同口径</b>：
     * 3612 未配置 / 3613 暂时不可用（可重试）/ 3614 被 IM 拒绝（不可重试）。
     *
     * <p>★顺序不能换：本地问题（secret 没配、适配没开）与远端抖动在 IM 侧都是 {@code 0001} + 无 HTTP 状态，
     * 先判 retryable 会把「没配」报成「抖了，稍后重试」—— 用户重试一百次也不会好，
     * 而真正该看日志的人不会收到任何信号。
     *
     * <p>★code 必须保留这三个枚举各自的值，<b>不要</b>用 {@code SYS_ERROR} 包一层：那样三者的 code 全变成 5000，
     * 调用方就只剩文案可读 —— 而它恰恰是要靠 code 决定「要不要带着同一个 bizMsgId 重试」的那一方。
     *
     * <p>日志只打码 / tid / 可重试性：异常 message 里可能有上游原文，但不会有 secret、签名或请求体。
     */
    private static BizException translate(String op, String bizMsgId, ImRpcException e) {
        log.warn("[IM-TOOL] Nuwax IM RPC 调用失败 op={} bizMsgId={} code={} tid={} retryable={} local={} http={} msg={}",
                op, bizMsgId, e.getCode(), e.getTid(), e.isRetryable(), e.isLocalProblem(), e.getHttpStatus(),
                e.getMessage());
        if (e.isLocalProblem()) {
            return BizException.of(BizExceptionCodeEnum.imRpcNotConfigured);
        }
        if (e.isRetryable()) {
            return BizException.of(BizExceptionCodeEnum.imRpcUnavailable);
        }
        return BizException.of(BizExceptionCodeEnum.imRpcRejected, e.getMessage());
    }

    private static <T> T rpc(String op, String bizMsgId, Supplier<T> call) {
        try {
            return call.get();
        } catch (ImRpcException e) {
            throw translate(op, bizMsgId, e);
        }
    }
}
