package com.xspaceagi.im.sdk.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * ★★DEV-291 批量发「系统通知」请求（契约 {@code SystemNotifySendRequest} →
 * {@code POST /internal/im/rpc/messages/system-notify}）。
 *
 * <h2>它补的是哪个洞（与 {@code messages/system} 的分工）</h2>
 *
 * <p>「系统通知」是 IM 的一等公民：会话列表里一个由<b>合成账号</b>（展示名"系统通知"）
 * 与收件人构成的单聊，里面有卡片 / 居中提示条，<b>带未读红点、进历史、可删除</b>。
 * 在 DEV-291 之前，平台侧只能借 {@code messages/system} 的 {@code actor} 去凑 ——
 * 而那条路的 {@code userId} 是<b>执行身份</b>，要过 {@code requireSenderSegment}
 * （"智能体主体只能声明 {@code senderType=2}"），合成账号恰好落在智能体号段
 * ⇒ 调用方<b>结构上发不出系统通知</b>。
 *
 * <p>★本端点的做法是<b>调用方根本给不了发送身份</b>：请求体里<b>没有</b>
 * {@code senderType} 字段，发送身份由 IM 内部固定为合成账号，
 * {@link #userId()} 只作<b>审计归属</b>与租户上下文来源。旧闸门一字未改，能力却补齐了。
 *
 * <h2>★★批量语义：一次调用给多个收件人各发一条</h2>
 *
 * <p>每人落在<b>自己</b>与「系统通知」的单聊里，收件人之间互相看不见。
 * 一次调用会写入<b>多份</b>库，而 RPC 幂等键是<b>整批一个</b>（{@link #bizMsgId()}）。
 * 由此推出一条调用方纪律：<b>每次调用都必须给一个能代表"这一批是谁"的幂等键</b> ——
 * 同一个 {@code bizMsgId} 再调一次，IM 会把上次的结果<b>原样回放</b>
 * （{@code IM_20005}），既不会重发、也不会补发。
 *
 * <h2>★{@link #msgType()} 白名单（★DEV-298 起四种）</h2>
 *
 * <ul>
 *   <li>{@link #MSG_TYPE_SYSTEM} —— 居中浅灰提示条，{@code body} 形如
 *       {@code {"text": "..."}}，<b>{@code text} 必须是非空字符串</b>；
 *       发一条 {@code text} 空的 system 消息，收件人看到的是会话列表里一个
 *       <b>点开什么都没有</b>的未读红点。</li>
 *   <li>{@link #MSG_TYPE_CARD} —— 通知卡（{@code title} 必填、可带按钮）。</li>
 *   <li>★DEV-298 {@link #MSG_TYPE_TEXT} —— 纯文本<b>气泡</b>，{@code body} 判据与
 *       {@code system} 完全相同（{@code {"text": "..."}}，非空字符串），只是渲染形态不同。</li>
 *   <li>★DEV-298 {@link #MSG_TYPE_RICH_TEXT} —— 富文本<b>气泡</b>（markdown 渲染），
 *       {@code body} 三种合法形态<b>至少其一</b>：裸 markdown {@code {"text","attachments"}} /
 *       {@code segments} 段序 / 仅 {@code attachments}（附件 url 原样透传）。三者皆空拒绝。</li>
 * </ul>
 * <p>★DEV-291 曾<b>刻意</b>不给 {@code text}/{@code image}；DEV-298 用户拍板「系统通知支持
 * 气泡消息」⇒ 只覆盖<b>气泡文本</b>这一层，核心防线一字未动：{@code image}/{@code file} 等
 * <b>单体媒体型仍然禁止</b>（富内容由 {@code rich_text} 的 {@code attachments} 承载）；
 * 发送者恒为「系统通知」合成账号、只落收件人与它的专属单聊 —— 调用方依然给不了
 * {@code senderType}，冒充不了真人，塞不进任意两人之间的私聊。
 *
 * <h2>★★失败是<b>逐条</b>的，且"有失败"不等于"调用失败"</h2>
 *
 * <p>个别收件人不存在 / 查不到资料时，整体信封仍是 {@code code=0000}
 * （那是<b>业务结论</b>，不是调用出错），失败明细在
 * {@link ImSystemNotifyResultDto#failed()} 里 —— 调用方<b>必须</b>读它。
 * 补发纪律见 {@link ImSystemNotifyResultDto}。
 *
 * @param toUserIds 收件人（必填、非空；去重后 ≤100，超出<b>整批拒绝</b>而非静默截断）
 * @param msgType   {@link #MSG_TYPE_SYSTEM} / {@link #MSG_TYPE_CARD} /
 *                  ★DEV-298 {@link #MSG_TYPE_TEXT} / {@link #MSG_TYPE_RICH_TEXT}（白名单，见类注释）
 * @param body      消息体（必填；形状随 {@code msgType} 而变，见类注释）
 * @param ext       业务扩展字段（JSON 原文 ≤2048）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImSystemNotifyRequest(Long userId,
                                    Long tenantId,
                                    String traceId,
                                    String bizMsgId,
                                    List<Long> toUserIds,
                                    String msgType,
                                    JsonNode body,
                                    String ext)
        implements ImRpcRequest {

    /** ★居中提示条（{@code body} = {@code {"text": "..."}}，{@code text} 必须非空）。 */
    public static final String MSG_TYPE_SYSTEM = "system";

    /** ★通知卡（{@code body.title} 必填）。 */
    public static final String MSG_TYPE_CARD = "card";

    /**
     * ★DEV-298 纯文本<b>气泡</b>（本类默认用法：通知消息就是一段文字，要渲染成普通气泡）。
     * {@code body} 判据与 {@link #MSG_TYPE_SYSTEM} 完全相同：{@code {"text": "..."}}，非空字符串。
     */
    public static final String MSG_TYPE_TEXT = "text";

    /**
     * ★DEV-298 富文本<b>气泡</b>（markdown 渲染）。{@code body} 三种合法形态<b>至少其一</b>：
     * {@code {"text":"<markdown>","attachments":[…]}} / {@code {"segments":[…]}} /
     * {@code {"attachments":[…]}}（附件 url 原样透传，不校验可达性）。
     */
    public static final String MSG_TYPE_RICH_TEXT = "rich_text";

    public static Builder builder() {
        return new Builder();
    }

    /** ★链式构造。 */
    public static final class Builder {
        private Long userId;
        private Long tenantId;
        private String traceId;
        private String bizMsgId;
        private List<Long> toUserIds;
        private String msgType;
        private JsonNode body;
        private String ext;

        /**
         * ★{@code userId} 只作<b>审计归属</b>（发送身份由 IM 内部固定为合成账号），
         * 但它<b>仍必须为正数</b>：{@code requireContext} 拿它 {@code runAs}，
         * 且 {@link #tenantId} 为 0 / 缺失时 IM 直接拒（{@code IM_90001}）。
         */
        public Builder actor(Long userId, Long tenantId, String bizMsgId) {
            this.userId = userId;
            this.tenantId = tenantId;
            this.bizMsgId = bizMsgId;
            return this;
        }

        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public Builder toUserIds(List<Long> toUserIds) {
            this.toUserIds = toUserIds;
            return this;
        }

        public Builder msgType(String msgType) {
            this.msgType = msgType;
            return this;
        }

        public Builder body(JsonNode body) {
            this.body = body;
            return this;
        }

        public Builder ext(String ext) {
            this.ext = ext;
            return this;
        }

        public ImSystemNotifyRequest build() {
            return new ImSystemNotifyRequest(userId, tenantId, traceId, bizMsgId,
                    toUserIds, msgType, body, ext);
        }
    }
}
