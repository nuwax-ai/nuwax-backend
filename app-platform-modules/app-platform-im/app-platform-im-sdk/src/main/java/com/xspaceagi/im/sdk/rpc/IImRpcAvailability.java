package com.xspaceagi.im.sdk.rpc;

/**
 * Nuwax IM RPC 适配的<b>启用状态</b>查询 —— 只读状态，不发起任何请求。
 *
 * <p>实现方是 infra 层的适配客户端，取值的单一来源是配置 {@code nuwax.im.adapter.enabled}
 * （{@code ImRpcProperties#enabled}，缺省 {@code false}）。
 *
 * <h2>为什么不把它挂在 {@link IImMessageRpcService} 上</h2>
 *
 * <p>「IM 适配开没开」是<b>装配期的部署事实</b>，「消息怎么发」是<b>运行期的业务动作</b>。
 * 把开关语义塞进消息服务，会让调用方误以为「发消息」这件事自带开关 ——
 * 真正的开关在配置里，谁都关得掉。
 *
 * <h2>★调用方为什么要先问一句</h2>
 *
 * <p>未启用时 {@link IImMessageRpcService} 等方法一律抛 {@link ImRpcException#disabled()}
 * （<b>拒绝</b>，不是静默跳过），这是对的设计：<b>用户显式发起</b>的路径（有人在界面上
 * 点了「发消息」）就该大声失败，否则用户会以为发出去了。
 *
 * <p>但<b>自动追加</b>的通道（典型是「通知消息顺带推一条 IM」）不适用这条：那里没有
 * 任何人点过什么，通知的权威副本也已经在平台侧落库，IM 只是锦上添花。在未部署 IM 的
 * 环境里逐条抛异常，换来的是成百上千行无意义日志 —— 而「为什么没推」的答案恰恰在
 * 部署配置里，不在业务代码里。
 *
 * <p>⇒ 自动通道先问一次 {@link #enabled()}，为 {@code false} 就<b>整批跳过</b>；
 * 显式调用的路径<b>不必问</b>，直接调用、让 {@code ImRpcException.disabled()} 冒出去。
 */
public interface IImRpcAvailability {

    /**
     * IM RPC 适配当前是否启用（对应配置 {@code nuwax.im.adapter.enabled}，缺省 {@code false}）。
     *
     * <p>★注意它<b>不</b>代表「配置完整」：{@code enabled=true} 但 {@code base-url} /
     * {@code secret} 没填时，本方法仍返回 {@code true}，缺项要到真正发请求时才会以
     * {@link ImRpcException#disabled()} 之外的 {@code notConfigured} 形式暴露 ——
     * 那是<b>配错了</b>，不是<b>没开</b>，需要被看见，不该被这里悄悄吞掉。
     */
    boolean enabled();
}
