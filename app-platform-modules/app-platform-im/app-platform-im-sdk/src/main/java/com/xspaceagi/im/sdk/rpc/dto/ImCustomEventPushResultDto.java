package com.xspaceagi.im.sdk.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * ★DEV-276 {@code events/push} 的响应（契约 {@code CustomEventPushResult}）——
 * <b>三个读数，没有一个是送达回执</b>。
 *
 * <h2>★★三个数怎么读</h2>
 *
 * <table border="1">
 *   <caption>读数</caption>
 *   <tr><th>字段</th><th>含义</th><th>怎么用</th></tr>
 *   <tr><td>{@link #targetUsers()}</td><td>解析出的目标<b>用户</b>数（去重后）</td>
 *       <td>核对"是不是发给了我想发的那些人"；
 *           ★{@code convId} 模式下这是<b>成员数</b>（含智能体成员 —— 它们没有长连接）</td></tr>
 *   <tr><td>{@link #targetDevices()}</td><td>其中<b>有路由（在线）的设备</b>数</td>
 *       <td>★★{@code 0} = <b>没人在线</b>，是调用方能看见的唯一"这条消息没人收到"信号
 *           ⇒ 据此决定要不要改走离线渠道（短信/邮件/站内信）</td></tr>
 *   <tr><td>{@link #publishedFrames()}</td><td>实际发出的 <b>PUBLISH 条数</b></td>
 *       <td>排障用（"帧到底有没有交给下行通道"）；
 *           ★<b>不是设备数</b>，<b>也不代表有人收到</b></td></tr>
 * </table>
 *
 * <h2>★★为什么 {@code publishedFrames} 不叫 {@code deliveredDevices}</h2>
 *
 * <p>因为它<b>不是设备数</b>：服务端把目标按网关聚合、再按每帧设备上限切分，
 * 这个读数是<b>帧</b>的条数（群发时与设备数差两个数量级 —— 3 帧 vs 600 设备）。
 * 名字里带 {@code Devices} 会让人把它当"送达设备数"用，而"帧已交出"与"端已收到"
 * 中间还隔着背压丢帧与设备离线两道。
 *
 * <h2>★入参非法是<b>异常</b>，不是"三个 0"</h2>
 *
 * <p>二选一错误 / {@code eventType} 空或超长 / {@code payload} &gt;16KB
 * ⇒ 抛 {@code ImRpcException}（{@code IM_90001}）——错误码是可行动的，调用方能改。
 * <p>而"投递失败"（帧没出去）<b>不抛</b>，表现为三个 0：帧没出去不该让调用方收到 500。
 * ⇒ ★调用方要区分这两件事：<b>catch 到的异常</b> vs <b>三个 0 的正常返回</b>。
 *
 * @param targetUsers     目标用户数（去重后；{@code convId} 模式下 = 成员数）
 * @param targetDevices   在线设备数（{@code 0} = 没人在线）
 * @param publishedFrames 发出的 PUBLISH 条数（★帧数，不是设备数）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImCustomEventPushResultDto(Integer targetUsers,
                                         Integer targetDevices,
                                         Integer publishedFrames) {

    /** ★是否"一个人都没在线"—— ★★这是本响应里唯一能驱动补偿动作的判断。 */
    public boolean nobodyReached() {
        return targetDevices == null || targetDevices == 0;
    }

    /** ★帧是否交给了下行通道（★不等于"有人收到"）。 */
    public boolean handedOff() {
        return publishedFrames != null && publishedFrames > 0;
    }
}
