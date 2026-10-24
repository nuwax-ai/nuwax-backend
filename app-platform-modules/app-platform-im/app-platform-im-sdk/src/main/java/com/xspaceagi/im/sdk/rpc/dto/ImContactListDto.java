package com.xspaceagi.im.sdk.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Collections;
import java.util.List;

/**
 * ★DEV-277 联系人列表响应（契约 {@code ContactListResult}）。
 *
 * <h2>★★两个「缩水」标志的语义<b>完全不同</b>，混了就会错报</h2>
 *
 * <table border="1">
 *   <caption>degraded 与 truncated 的区别</caption>
 *   <tr><th>标志</th><th>含义</th><th>结果集</th><th>该怎么办</th></tr>
 *   <tr><td>{@link #degraded()}</td><td>平台资料（昵称/头像）没取到，
 *       {@code displayName} 回落成 {@code 用户{id}}</td><td>★<b>完整</b></td>
 *       <td>照常用，最多弱提示一句「头像暂时加载不出来」；★<b>不要</b>整单报错</td></tr>
 *   <tr><td>{@link #truncated()}</td><td>撞到单用户上限 500 被截断</td><td>★<b>缩水了</b></td>
 *       <td>必须让用户知道（「仅显示前 500 位联系人」），否则会被当成丢数据</td></tr>
 * </table>
 *
 * <p>★注意 {@code degraded} 与用户态搜索接口 {@code /search/contacts} 的同名字段
 * <b>不是一回事</b>：那条的 {@code degraded=true} 表示「只搜了本地会话成员」，
 * 是<b>结果集缩水</b>；本接口的 {@code degraded} 只影响显示字段。
 *
 * <h2>★★返回的是<b>三态混合</b>列表，不是「只有好友」</h2>
 *
 * <p>{@code items} 里混着 {@code ACCEPTED}(1) + {@code PENDING_IN}(2) + {@code PENDING_OUT}(3)，
 * 每条自带 {@code status}。★「只要好友」由<b>调用方</b>按 {@code status} 自己筛
 * （见 {@link ImContactItemDto#accepted()}）——服务端不再筛第二遍，
 * 这样「平台查到的集合」始终是「界面上看到的集合」的超集，不会出现
 * 「界面里有、平台查不到」的诡异缺口。
 *
 * @param items     全部联系人（三个状态混在一起，按 {@code status} 升序、时间倒序）
 * @param degraded  ★平台资料未取到。★{@code items} <b>依然完整</b>，只是昵称/头像为 null
 * @param truncated ★撞到 500 上限被截断 ⇒ <b>结果集缩水</b>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImContactListDto(List<ImContactItemDto> items,
                               Boolean degraded,
                               Boolean truncated) {

    /** ★单用户联系人上限 —— 超过即 {@code truncated=true}（与服务端 {@code CONTACT_LIST_MAX} 同值）。 */
    public static final int MAX_CONTACTS = 500;

    /** ★空安全列表。 */
    public List<ImContactItemDto> itemsOrEmpty() {
        return items == null ? Collections.emptyList() : items;
    }

    /** ★空安全：未下发视为 false（资料完整）。 */
    public boolean degradedOrFalse() {
        return Boolean.TRUE.equals(degraded);
    }

    /** ★空安全：未下发视为 false（没被截断）。 */
    public boolean truncatedOrFalse() {
        return Boolean.TRUE.equals(truncated);
    }

    /** ★本页条数（不是总数）。 */
    public int size() {
        return itemsOrEmpty().size();
    }

    /**
     * ★只要「已是好友」的那些 —— 把三态混合列表收敛成正常联系人。
     *
     * <p>提供这个便捷方法，是为了让「忘了筛 status」这件事变得不容易发生：
     * 直接把待同意的申请当成好友渲染/发消息，是这条链路上最典型的误用。
     */
    public List<ImContactItemDto> acceptedOnly() {
        return itemsOrEmpty().stream().filter(ImContactItemDto::accepted).toList();
    }
}
