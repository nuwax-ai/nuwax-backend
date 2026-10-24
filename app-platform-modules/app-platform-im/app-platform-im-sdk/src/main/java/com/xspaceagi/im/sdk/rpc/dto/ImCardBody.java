package com.xspaceagi.im.sdk.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * ★互动卡片体（契约 {@code CardBody}）。
 *
 * <p>卡片消息的会话列表摘要固定为 {@code [卡片] title}（契约与 02 §4.3.2 一致）——
 * 所以 {@code title} 是<b>必填</b>（服务端校验），且它是唯一会出现在未读列表里的字段：
 * 不填或填成"通知"这类泛化词，用户在没有点开之前无法区分两条卡片。
 *
 * <p>{@code actionUrl} 由<b>调用方</b>决定跳哪里，IM 不做域名白名单校验 ——
 * 这是刻意的边界：卡片是平台侧的业务表达，IM 只负责投递。
 * ⇒ 平台侧必须自行保证 {@code actionUrl} 不指向钓鱼/外站（客户端渲染时也应按域校验）。
 *
 * <h2>★★{@code actions}：从"通知"到"待办"（DEV-197）</h2>
 *
 * <p>没有 {@code actions} 时，卡片只能<b>告诉</b>用户一件事（"张三申请加入本群"），
 * 用户看完还得自己去找管理入口。有了 {@code actions}，卡片自己就是那个入口 ——
 * 按钮点了就地把申请批掉。
 *
 * <p>★★<b>接入方只需关心 {@link Action#key()} 与 {@link Action#label()}，
 * 不要读 {@link Action#payload()}</b>：payload 是<b>服务端自己写给自己的参数</b>
 * （"这个按钮代表批准还是拒绝"就藏在里面），点击时<b>不回传</b>。
 * 服务端拿 {@code msgId} 从库里读出 payload 再执行 ——
 * 这样"批准/拒绝"的决定无法被客户端篡改。
 *
 * <p>⇒ 接入方构造卡片时，payload 由<b>谁提供动作语义谁负责</b>：
 * 若平台侧只想要一个"打开某个页面"的按钮，就该用 {@code actionUrl}，
 * 而不是编一个自己也无法执行的 payload。
 *
 * @param title     标题（必填，≤128）
 * @param desc      正文说明（≤512）。★多行完整句子用这个；subtitle 已在契约里 deprecated
 * @param subtitle  副标题（≤256）。★deprecated，新代码请用 {@code desc}
 * @param iconUrl   图标（≤512）
 * @param actionUrl 点击跳转地址（≤512）
 * @param fields    键值对明细（{@code label/value}），用于展示"订单号/金额"这类结构化信息
 * @param actions   可点击动作（≤3 个）。★空数组与不传等价
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImCardBody(String title,
                         String desc,
                         String subtitle,
                         String iconUrl,
                         String actionUrl,
                         List<Field> fields,
                         List<Action> actions) {

    /** ★空安全列表。 */
    public List<Field> fieldsOrEmpty() {
        return fields == null ? Collections.emptyList() : fields;
    }

    /**
     * ★空安全动作列表。
     *
     * <p>★<b>返回空列表而不是 null</b>：调用方（渲染循环）要写
     * {@code for (a : body.actionsOrEmpty())} 这种形状，
     * 让它每次都自带一次判空会漏 —— 而漏掉的那次是 NPE。
     */
    public List<Action> actionsOrEmpty() {
        return actions == null ? Collections.emptyList() : actions;
    }

    /**
     * ★卡片的一行键值。
     *
     * <p>★{@code label}/{@code value} 都保持为<b>字符串</b>（不使用数值类型）：
     * 卡片要展示的"金额""单号"往往需要保留前导零与精度（{@code 007}、{@code 1.10}），
     * 一旦按数值传输就会在某一跳被规范化。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Field(String label, String value) {
    }

    /**
     * ★★卡片上的一个可点击动作（契约 {@code CardAction}）。
     *
     * <h2>★★接入方只填 {@link #key()} / {@link #label()} / {@link #style()}；
     * {@link #actionType()} 与 {@link #payload()} 由<b>能执行这个动作的那一方</b>填</h2>
     *
     * <p>把动作类型写成一个客户端可选的值，等于让客户端决定服务端执行什么 ——
     * 一个能构造请求的人就能把"拒绝"改成"通过"。
     *
     * @param key        本条消息内唯一的键（≤32）。★点击回执按它定位，重复则整张卡拒发
     * @param label      按钮文案（≤16 字）
     * @param style      配色：{@code default}/{@code primary}/{@code danger}。★只影响配色，不影响语义
     * @param actionType 服务端动作类型（≤64，如 {@code join_request.review}）。★服务端分发用，客户端不解析
     * @param payload    服务端动作参数。★客户端<b>只读不写、绝不回传</b>
     * @param state      动作当前状态。★<b>发卡时为 null</b>（客户端按"缺失即待处理"渲染）；
     *                   读历史消息时由服务端按业务表回填。接入方构造卡片时<b>不要填</b>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Action(String key,
                         String label,
                         String style,
                         String actionType,
                         Map<String, Object> payload,
                         ActionState state) {
    }

    /**
     * ★动作的当前状态（契约 {@code CardActionState}）。
     *
     * <p>★{@code done} / {@code stale} / {@code forbidden} 三态由服务端回填，
     * {@link #text()} 是<b>服务端给的现成文案</b>（"李四 已通过"）——
     * 接入方<b>直接展示</b>，不要按 {@link #state()} 自己拼：
     * 那样服务端改口径要靠发客户端版本，而未升级的客户端会长期显示旧话术。
     *
     * <p>★{@link #at()} 是 epoch 毫秒（可为 null），<b>不是</b>日期字符串 ——
     * 与 {@code sendTime} 同一口径，避免各语言各写一份日期解析。
     *
     * @param state {@code pending}/{@code done}/{@code stale}/{@code forbidden}。
     *              ★未知取值请按<b>不可点</b>处理（fail-closed），不要按 pending
     * @param text  状态文案（≤64）
     * @param at    状态时间（epoch 毫秒，可 null）
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ActionState(String state, String text, Long at) {
    }
}
