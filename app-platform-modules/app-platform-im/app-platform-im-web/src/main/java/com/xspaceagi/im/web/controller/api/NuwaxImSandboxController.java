package com.xspaceagi.im.web.controller.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.xspaceagi.im.sdk.rpc.*;
import com.xspaceagi.im.sdk.rpc.dto.*;
import com.xspaceagi.im.web.dto.sandbox.NuwaxImSandboxRequests;
import com.xspaceagi.im.web.dto.sandbox.NuwaxImWriteResult;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.dto.VirtualIdentityDto;
import com.xspaceagi.system.application.service.VirtualIdentityApplicationService;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.sdk.service.dto.UserAccessKeyDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * ★沙箱 IM 技能 API —— 让技能<b>以用户身份</b>操作 Nuwax IM。
 *
 * <h2>★★对外路径与落地路径不是同一个</h2>
 *
 * <pre>
 *   技能看到（沙箱）           : POST {PLATFORM_URL}/api/v1/4sandbox/nuwax-im/message/send-p2p
 *                                    Authorization: Bearer &lt;ACCESS_KEY&gt;
 *   SandboxApiRewriteFilter 改写: /api/v1/4sandbox/nuwax-im/message/send-p2p
 *                             →  /api/nuwax-im/message/send-p2p          （本类的映射）
 * </pre>
 *
 * <p>改写由 {@code sandbox.api.rewrite.allow-path} 里的 {@code nuwax-im/**} 白名单放行；
 * 不在白名单里的路径会 404 —— 那份清单就是「沙箱 AK 能打到哪些接口」的<b>完整审计面</b>，
 * 加接口时要同时改两处（本类 + 白名单），改漏了表现为 404 而不是静默放行。
 *
 * <p>★本类映射的 {@code /api/nuwax-im/**} <b>同时也能被登录态直接调用</b>
 * （{@code /api/v1/} 之外不受 {@code auth.excludePath} 豁免，直连必须带登录态）。
 * 两条路进的身份语义完全一致 —— 都取 {@code RequestContext}，不存在「绕 AK 提权」的口子。
 *
 * <h2>★★身份只来自 AK，绝不来自请求体</h2>
 *
 * <p>底层 IM 契约的每个请求都要求 {@code userId}（「以谁的身份执行」），本层一律用
 * AK 解析出的登录态（{@code RequestContext.getUserId()}）构造，
 * 请求 DTO 里<b>根本没有</b>这个字段（见 {@link NuwaxImSandboxRequests}）。
 *
 * <p>为什么这条如此要命：传错 {@code userId} 不会报任何错 —— 它会<b>以那个人的身份把消息发出去</b>，
 * 且 IM 侧走的是与 {@code /api} 完全相同的应用服务，权限规则只有一套，事后只能靠人工核对。
 *
 * <h3>★「登录态」不等于「最终 userId」：按<b>触发场景</b>在「智能体虚拟身份 / 用户本人」之间分流</h3>
 *
 * <p>IM 把本次 4.10 的 {@code variableParams} 原样写进 AK 的 {@code ext}
 * （{@code ChatApplicationServiceImpl} 的 {@code .ext(tryReqDto.getImVars())}），
 * 其中两个键决定本类的身份分流：
 *
 * <ul>
 *   <li>{@code AGENT_VIRTUAL_USER_ID} —— 「这条 AK 是替哪个智能体在用」的自述，
 *       <b>群 / 私聊两个分支都送</b>；</li>
 *   <li>{@code GROUP_VIRTUAL_USER_ID} —— <b>只在群聊分支</b>被 put
 *       ⇒ 它的「在 / 不在」就是「群场景 / 私聊场景」，见 {@link #EXT_KEY_GROUP_VIRTUAL_USER_ID}。</li>
 * </ul>
 *
 * <p>五组规则，判据各自独立、<b>不可互换</b>：
 *
 * <ul>
 *   <li>{@code send-p2p} —— 收件人＝自己 ⇒ 虚拟身份，否则本人（{@link #p2pActorUserId}）；</li>
 *   <li>{@code send-group} —— ext 有群键 ⇒ 虚拟身份；<b>缺虚拟身份时返回 null</b>
 *       让 IM 大声拒（{@link #groupActorUserId}）；</li>
 *   <li>{@code send-card} / {@code send-system} —— ext 声明了虚拟身份 ⇒ 用它，
 *       不按场景分流（{@link #agentActorUserId}）；</li>
 *   <li>其余 <b>13 个</b>（消息 / 群员 / 会话 / 通讯录 / 事件）——
 *       <b>群场景 ⇒ 虚拟身份；私聊场景 ⇒ 本人</b>（{@link #scenarioActorUserId}）；
 *       <b>其中 {@code /message/revoke} 还要多看一眼「这条消息是谁发的」</b>
 *       （{@link #revokeActorUserId}，理由见下）；
 *       ★其中 <b>5 个读端点</b>（{@code /message/get}、{@code /message/read-counts}、
 *       {@code /message/list}、{@code /conversation/members/list}、{@code /conversation/detail}）
 *       的 {@code convId} 还额外受<b>闸门④</b>约束（见下节）；
 *       ★★而 {@code /conversation/list}、{@code /contact/list} 这 <b>2 个</b>在群场景下已被
 *       <b>闸门⑤整条拒掉</b>（{@link #requireNoAgentOwnedReadInGroupScenario}，见下节）——
 *       它们没有 {@code convId} 可钉，而群场景下返回的会是<b>智能体自己</b>的会话 / 通讯录。
 *       注意：这 2 个端点并不因此改走别的 actor 规则，「群场景 ⇒ 虚拟身份」这条仍然写着，
 *       只是在群场景下永远执行不到（闸门⑤在更前面）；保留它是<b>防御纵深</b>——
 *       将来谁摘掉闸门⑤，actor 仍会是智能体身份，不会静默退化成以群代理冒充。
 *       ★★另有 <b>5 个</b>「会话管理」端点（{@code /conversation/create-group}、
 *       {@code /conversation/members/add}、{@code /conversation/members/remove}、
 *       {@code /conversation/p2p}、{@code /conversation/digest}）在群场景下已被
 *       <b>闸门⑥整条拒掉</b>（{@link #requireNoConversationManagementInGroupScenario}，见下节）
 *       —— 它们会<b>持久地改动会话构成、扩大智能体的可达范围</b>（建群 / 拉人 / 开单聊）
 *       或替某人改动一个会话（踢人 / 改摘要），而群场景下驱动它的只是群里某一个人。
 *       同上：这 5 个的 actor 规则也仍写着「群场景 ⇒ 虚拟身份」，保留是防御纵深。</li>
 *   <li>{@code /agent/pending/list} / {@code /agent/pending/clear}（<b>★DEV-285</b>）——
 *       <b>只能是智能体主体本人，且不按场景分流</b>；读不到虚拟身份就<b>本地 fail-closed</b>
 *       （{@link #agentSubjectUserId}）。这一组是本文件里唯一<b>拒绝</b>以登录用户身份行使的
 *       ——原因与另外四组都不同，见该方法的 javadoc。
 *       ★而且「以谁的身份」只是它的一半：这一组的 {@code convId} 还额外受<b>闸门③</b>约束
 *       （{@link #requirePendingConvIsTriggerConv}，见下节）。</li>
 * </ul>
 *
 * <p>★两条相反取向各自的理由：
 *
 * <ul>
 *   <li><b>群场景必须用虚拟身份</b>：IM 上每个按 convId 定位的端点都以
 *       {@code requireActiveMember} 开头，而群链路的 AK 归属人是<b>群代理</b>，
 *       按 IM 定义「不进群、不是成员」⇒ 拿它查群消息/群员、撤群消息一律
 *       {@code NOT_CONV_MEMBER}。且这本就是语义要求：智能体在群里说话、拉群数据，
 *       都该以<b>它在那个群里的身份</b>发生，否则群里会看到"群代理在查东西"。</li>
 *   <li><b>私聊场景必须用本人</b>：那是管控要求 —— 以虚拟身份行使时，
 *       {@code /contact/list} 返回的是<b>智能体自己</b>的通讯录、{@code /conversation/list}
 *       是它自己的会话列表，用户在界面上看不到、也管不着；改用本人后，
 *       智能体能读到的恰好就是用户自己能看到的那一块。
 *       ★同一条理由在<b>群</b>场景下原样成立且更强（群里是全体成员都看得到），
 *       但那边<b>没法用「改用本人」来补</b>：群链路的 AK 归属人是群代理，按 IM 定义不进群、
 *       当不了成员 ⇒ 那两个端点在群场景下只能<b>拒</b>，见闸门⑤
 *       （{@link #requireNoAgentOwnedReadInGroupScenario}）。</li>
 * </ul>
 *
 * <p>★{@code /message/revoke} 为什么要多这一步：IM 的 {@code Message.canRevoke} 判据是
 * {@code senderId == operatorId} 或本会话管理员（<b>两者的时限完全一样</b>，见下），
 * 即<b>撤回的 operator 必须恰好是那条消息的发送者</b>。而私聊场景里「用户自己发出去的」
 * 与「智能体回他的」是<b>两个不同的发送者</b>（后者走 {@code p2pActorUserId} 的虚拟身份分支），
 * 一次调用只能带一个 operator ⇒ 不回读发送者，这两类里必然有一类撤不掉。多读一次
 * {@code messages/get} 就把两类都覆盖了，且<b>不放大权限</b>：operator 仍只会是
 * 「本人 / 智能体虚拟身份」二者之一，IM 的成员校验与 {@code senderId == operatorId} 每次都判。
 *
 * <h2>★★混乱代理闸门（六道）：写只能回触发群，读 / 清只能碰触发会话，
 * 不许读智能体自己的资源，也不许在群里改动会话</h2>
 *
 * <p>上面那五组规则解决的是「<b>以谁的身份</b>调用」，但群场景下还有第二个问题：
 * 那个身份<b>能到达的范围</b>远大于驱动它的人。智能体虚拟身份在 IM 里可能同时是很多个群的成员，
 * 而驱动这一次运行的用户（群链路里 AK 的归属人）只在这一个群里 ⇒
 * 「目标由请求参数自己决定」就构成<b>混乱代理（confused deputy）</b>：
 * 用户借智能体之手，够到自己本来够不到的地方。六个出口各有一道闸门：
 *
 * <ul>
 *   <li>{@code /message/send-group}、{@code /message/send-card} 的 {@code convId} 由请求决定 ⇒
 *       甲在群 A 里（甲不在群 B）可以让智能体以<b>智能体虚拟身份</b>把消息投进群 B
 *       —— IM 侧唯一那道发送者闸门 {@code requireActiveMember(B, 智能体虚拟身份)} 是<b>会通过</b>的，
 *       因为它判的是「智能体在不在 B」，不是「甲在不在 B」。
 *       ⇒ 加闸门①：群场景下 {@code convId} 必须等于触发本次运行的那个群
 *       （{@link #requireGroupSendTargetIsTriggerGroup}）；
 *       ★{@code /message/send-card} 曾是这条的漏网（它的 actor 走 {@link #agentActorUserId}、
 *       IM 侧也同样先做 {@code requireActiveMember}，与群发逐字同形，却一直没有守卫）
 *       —— 2026-10-05 端点全量审计发现后已补挂<b>同一道</b>闸门，两处共用；</li>
 *   <li>{@code /message/send-p2p} 的收件人由请求决定 ⇒
 *       群场景下 {@link #p2pActorUserId} 会返回<b>群代理</b>（收件人≠自己 ⇒ 走本人分支），
 *       而 IM 的 {@code sendP2p} 会为「群代理 ↔ 任意用户」<b>自动建会话</b>并把群代理记为成员
 *       ⇒ 成员校验必然通过 ⇒ 甲能让智能体以群代理名义给<b>任意平台用户</b>发私聊。
 *       ⇒ 加闸门②：群场景下一律拒（{@link #requireP2pSendIsAllowed}）；</li>
 *   <li>{@code /agent/pending/list} 与 {@code /agent/pending/clear}（<b>★DEV-285</b>）的
 *       {@code convId} 由请求决定 ⇒ 智能体是多个会话的成员，甲能借它读到、并<b>清掉</b>
 *       （= 声明已消费）群 B 里发给它的消息 —— IM 侧对这两个端点只校验「userId 是已登记的
 *       智能体主体」，<b>不校验成员资格</b>。
 *       ⇒ 加闸门③：{@code convId} 必须等于<b>本次触发本次运行的会话</b>
 *       （{@link #requirePendingConvIsTriggerConv}）；</li>
 *   <li>{@code /message/list}、{@code /message/get}、{@code /message/read-counts}、
 *       {@code /conversation/members/list}、{@code /conversation/detail}（<b>★本次</b>）的
 *       {@code convId} 由请求决定 ⇒ 群场景下 actor 是<b>智能体虚拟身份</b>，IM 侧对按 convId
 *       定位的端点那道 {@code requireActiveMember(convId, actor)} 判的是
 *       「<b>智能体</b>在不在那个会话」、<b>不是「驱动它的人在不在」</b> ⇒
 *       甲在群 A 里（甲不在群 B）能让智能体先以自己的名义 {@code /conversation/list}
 *       枚举出它所在的群、拿到群 B 的 convId，再 {@code /message/list} 把群 B 的消息读走、
 *       在群 A 里<b>转述出来</b>（群 A 全体成员，含不在群 B 的人，都看得到）。
 *       ⇒ 加闸门④：<b>群场景下</b> {@code convId} 必须等于本次触发会话
 *       （{@link #requireReadConvIsTriggerConvInGroupScenario}）。</li>
 *   <li>{@code /conversation/list}、{@code /contact/list}（<b>★本次</b>）<b>没有 {@code convId} 参数</b>
 *       —— 它们的返回内容完全由 actor 决定，而群场景下 actor 是<b>智能体虚拟身份</b>
 *       ⇒ 返回的是<b>智能体自己</b>的会话列表 / 通讯录：它在哪些群里、跟谁有私聊、
 *       每个会话叫什么 / 多少人，以及 {@code lastMsgDigest}（<b>末条消息摘要</b>，带发送者名与正文）。
 *       闸门④的形态（钉死请求里的 {@code convId}）对它们<b>结构上无从下手</b>。
 *       ⇒ 加闸门⑤：<b>群场景下一律拒</b>（{@link #requireNoAgentOwnedReadInGroupScenario}）。</li>
 *   <li>{@code /conversation/create-group}、{@code /conversation/members/add}、
 *       {@code /conversation/members/remove}、{@code /conversation/p2p}、{@code /conversation/digest}
 *       （<b>★本次</b>，2026-10-05 全量审计）—— 前五道闸门都建立在
 *       「<b>智能体的可达集合是既定的</b>」这个前提上，而这 5 个端点恰恰能<b>持久地改</b>这个集合：
 *       建群 / 拉人把新会话、新成员记在<b>智能体名下</b>，{@code p2p} 会为它与任意平台用户
 *       <b>自动建会话</b>（IM 侧 {@code getOrCreateP2p} 的既定行为），踢人 / 改摘要是同一族的改动作。
 *       其中「<b>拉人进群 = 让这个人从此看到该群的全部内容</b>」本身还是一条独立的泄漏路
 *       （不是读、也不是破坏，是<b>开门</b>），而群场景下驱动它的只是群里某一个人、
 *       后果却落在智能体名下。
 *       ⇒ 加闸门⑥：<b>群场景下一律拒</b>（{@link #requireNoConversationManagementInGroupScenario}）。</li>
 * </ul>
 *
 * <p>统一表述：<b>群触发链路下，智能体只能往「触发它的那个群」写、只能读「触发它的那个会话」，
 * 且不在此改动任何会话</b> ——
 * p2p 不是那个群 ⇒ 拒；{@code send-group} / {@code send-card} 到别的群 ≠ 那个群 ⇒ 拒；
 * 读消息 / 群员 / 会话详情时 {@code convId} 指向别的会话 ⇒ 拒；
 * 建群 / 拉人 / 踢人 / 开单聊 / 改摘要这类「改动会话」的动作群里一律不做 ⇒ 拒。
 * 闸门③是同一取向的另一个出口：<b>读 / 清排队区只能碰「触发本次运行的那个会话」</b>——
 * 那条腿两个场景都有（私聊里能读 / 清智能体与另一个人的单聊队列），所以它<b>不分场景</b>，
 * 这也是它与闸门④唯一的差别（④为什么分场景见下）。
 *
 * <p>★闸门⑤补的是<b>另一种形状</b>：不是「读别处的会话」，而是「读智能体<b>自己</b>的资源」——
 * 它的会话清单与通讯录。受害方不是某个群，而是<b>跟这个智能体有过往来的所有人</b>
 * （名单、备注名、末条消息摘要都会被群成员看到）。它与闸门④同源（群场景 actor 被换）、
 * 结论也同向（只能碰本次那个会话 ⇒ 连"列出别的会话"这一步本身都该被掐掉），
 * 只是因为没有 {@code convId} 而做不到"收窄"，只能"拒"。
 *
 * <p>★闸门⑥与⑤<b>同判据、同出口（整条拒），但防的不是同一件事</b>：⑤防的是「<b>读走</b>智能体
 * 自己的资源」，⑥防的是「<b>改动</b>会话」—— 后者会持久地扩大前五道闸门所依赖的那个可达集合
 * （建群 / 拉人 / 开单聊），或者替某人改动一个会话（踢人 / 改摘要）。
 * 前五道闸门都只管「用既有的权限能碰到哪儿」，⑥管的是「<b>别把权限本身变大</b>」。
 *
 * <p>★闸门①②<b>只对群场景生效</b>，判据是 {@link #isGroupScenario}（群虚拟键 <b>或</b>
 * convId 键，即 {@link #scenarioActorUserId} 原判据的<b>超集</b>——
 * 取超集是为了盖住 IM 那个理论上「有 convId 键却漏送群虚拟键」的形态，
 * 那里 actor 仍是群代理、火口照样通；多判只多拒一次本来也会失败的调用，少判才是缺口）。
 * 私聊场景这两道不放，那里 actor 是<b>本人</b>，驱动者与发送者是同一个人，
 * <b>不存在权限落差</b>，收窄只会砍掉正当能力（尤其 {@code send-p2p}：那正是「智能体在私聊里回话」那条腿）。
 * <p>★<b>闸门④⑤⑥同样只对群场景生效</b>，但判据<b>不是</b> {@link #isGroupScenario}，
 * 而是 {@link #scenarioActorUserId} 的分流判据 —— 只看 {@link #EXT_KEY_GROUP_VIRTUAL_USER_ID}
 * <b>单键</b>。这两者必须逐字对齐，因为需要钉死的充要条件是「actor 被换成了智能体」，
 * 而这件事正好只由那一个键决定；判据一旦分叉（比如这里改用 {@code isGroupScenario} 那个超集），
 * 就会出现「钉了但 actor 没换」或「actor 换了但没钉」的漂移。取超集的理由（见上一段）
 * 只对闸门①②成立，那里是为了盖住 IM 理论上「有 convId 键却漏送群虚拟键」的形态；
 * 闸门④⑤⑥不取，因为那种形态下 actor 会回退成群代理、本来就走不通（见闸门④的 javadoc）。
 * ★④与⑤⑥的差别不在判据，而在<b>处理方式</b>：④管的那 5 个端点有 {@code convId} 可钉
 * ⇒ 收窄成「只能是本次触发的那个会话」；⑤管的 2 个、⑥管的 5 个端点则只能<b>整条拒</b>
 * ——⑤是结构上根本没有该参数；⑥的 {@code create-group} / {@code p2p} 同样没有，
 * 另外三个即便把 {@code convId} 钉死在触发会话上也仍是「在群里替某个人改这个会话」，
 * 没有必须由智能体在群里做的正当用法（口径见闸门⑥的 javadoc）。
 * ★<b>闸门③的分界线又不同：它两个场景都生效</b>，判据是 AK ext 里的 {@link #EXT_KEY_IM_CONV_ID}——
 * 理由与"谁驱动"无关，纯粹是排队区端点<b>没有</b>「私聊场景天然无害」这个前提（见上）。
 *
 * <p>★闸门①在群场景下<b>读不到</b> {@link #EXT_KEY_GROUP_CONV_ID} 时一律 <b>fail-closed</b>（不放行），
 * 依据是该键与群虚拟键由 IM <b>同批送出</b>（DEV-280，见 {@link #EXT_KEY_GROUP_CONV_ID} 的注释），
 * 不存在「有群键却没有 convId 键」的合法形态；放行等于「删掉一个键即可绕过」。
 * （闸门②不看这个键，群场景下无条件拒，不存在需要区分的情形。）
 * ★闸门③④同理：{@link #EXT_KEY_IM_CONV_ID} 由 IM 在群 / 私聊<b>两个分支的公共段</b>无条件 put
 * ⇒「带着群虚拟键（③还含智能体虚拟键）却读不到它」不是合法形态，也一律 fail-closed。
 * （闸门⑤⑥不在此列：它们只看群虚拟键<b>在不在</b>，不读任何 ext 的值。）
 * 运维侧区分「版本漂移」与「攻击尝试」看日志措辞：前者报「读不到 GROUP_CONV_ID」/
 * 「读不到 imConvId」，后者报「试图发往【非触发群】」（{@code send-group} 与 {@code send-card} 共用）/
 * 「试图调用 send-p2p」/「试图读 / 清【非本次触发会话】的队列」/
 * 「试图读【非本次触发会话】的内容」/「试图调用（/conversation/list 或 /contact/list）」/
 * 「试图调用（建群 / 加人 / 移人 / 开单聊 / 改摘要之一）」。
 *
 * <h2>★★21 个端点里有 3 个前置了「管理员闸门」</h2>
 *
 * <p>{@code /message/send-system}、{@code /message/system-notify}（★DEV-291）与
 * {@code /event/push} <b>仅平台管理员可用</b>
 * （{@link #requireAdmin()}，非管理员一律 {@code 4030} 无权限）；
 * 其余 <b>18 个</b>端点对持 AK 的普通用户开放。
 *
 * <p>★错误码是 {@code 4030}（{@code ErrorCodeEnum.PERMISSION_DENIED}），不是 {@code 3020} ——
 * {@code BizException.of(ErrorCodeEnum, IBizExceptionCodeEnum)} 取的是<b>前者的码</b>，
 * {@code 3020} 只是内层 message 枚举的码，不会出现在响应里。
 *
 * <p>为什么单给这三个加闸门 —— 它们不是「多一个功能」，是三种提权：
 *
 * <ul>
 *   <li>{@code send-system}：能<b>以系统/机器人名义发言</b>（{@code senderType=2|3}），
 *       而 IM 的禁言与用户频控<b>只对 HUMAN 生效</b> ⇒ 调用方顺便绕过了自己的禁言与频控；</li>
 *   <li>{@code system-notify}：能<b>往任意同租户用户的「系统通知」信箱投消息</b> ——
 *       收件人不必与调用方是联系人、不必有会话、不必有任何关系，
 *       且消息<b>落库、带未读红点、进历史</b>（不像 event-push 那样可以当没发生）。</li>
 *       ★它<b>不是</b>「send-system 换了个皮」：那个端点调用方给得出 {@code senderType}，
 *       这个端点调用方<b>根本给不了发送身份</b>（IM 固定成合成账号「系统通知」）。
 *       ⇒ 两者要防的是<b>不同</b>的东西，见各自方法上方的注释；</li>
 *   <li>{@code event-push}：IM 的 push 链上<b>目标侧零授权</b>、帧体<b>无发送者</b>、
 *       内容完全由调用方定义、<b>不落库</b> ⇒ 一个不可归属、不可追溯的注入原语。</li>
 * </ul>
 *
 * <p>三者的完整依据各自写在对应方法上方的注释里。<b>★闸门只收窄「谁能碰到」，
 * 不消除这些性质</b> —— 管理员调用时它们依然成立，这是 2026-09-23 需求变更
 * （「拿回来，但要判调用者为管理员」）明确接受的取舍。
 *
 * <p>★由此推论一条改这个文件时的纪律：<b>新端点默认是「给普通用户」的</b>，
 * 要变成管理员端点就必须显式加 {@code requireAdmin()}，
 * 并<b>同步改掉本段的两个计数与</b>
 * {@code NuwaxImSandboxControllerTest} 里那几处 {@code hasSize(...)} / 重载数断言 ——
 * 这条被 {@code NuwaxImSandboxControllerTest#dangerousEndpointsSitBehindTheAdminGate()}
 * 用映射扫描 + 行为断言钉住了。
 *
 * <h2>★写端点为什么都包一层 {@link NuwaxImWriteResult}</h2>
 *
 * <p>把 {@code bizMsgId} 回显给调用方，它才能在「响应丢了」之后用同一个键安全重试。
 * 理由详见该类注释。
 */
@Tag(name = "沙箱-IM 消息", description = "技能以用户身份收发 Nuwax IM 消息（/api/v1/4sandbox/nuwax-im/**）")
@RestController
@RequestMapping("/api/nuwax-im")
@Slf4j
public class NuwaxImSandboxController {

    @Resource
    private IImMessageRpcService imMessageRpcService;
    @Resource
    private IImConversationRpcService imConversationRpcService;
    @Resource
    private IImContactRpcService imContactRpcService;
    @Resource
    private IImEventRpcService imEventRpcService;
    @Resource
    private IImAgentRpcService imAgentRpcService;
    /**
     * ★只用于 {@link #resolveAgentVirtualUserId}：AK 没带虚拟身份时，按 agentId <b>只读</b>查一次平台虚拟身份。
     *
     * <p>刻意注入这个而<b>不是</b> {@code UserDomainService} / {@code getOrCreateVirtualIdentity}：
     * 发消息路径上「惰性创建身份」是副作用（一次查询就凭空多出一个主体），
     * 而这里只需要「在不在」这一个事实来决定要不要切身份。
     */
    @Resource
    private VirtualIdentityApplicationService virtualIdentityApplicationService;

    // ================================================================== messages（9）

    @Operation(summary = "单聊发消息", description = "以当前登录用户身份给某人发消息；会话不存在时由 IM 自动创建。"
            + "★收件人就是本人时，自动改用智能体虚拟身份发送（IM 私聊里智能体回话的场景）："
            + "优先取 AK 携带的那个，AK 没带时按 AK 的 agentId 兜底解析并要求它已在收件人通讯录里，"
            + "详见 p2pActorUserId。"
            + "★由群消息触发时不允许调用本接口（见 requireP2pSendIsAllowed）")
    @PostMapping("/message/send-p2p")
    public ReqResult<NuwaxImWriteResult<ImMessageDto>> sendP2p(
            @RequestBody NuwaxImSandboxRequests.SendP2p request) {
        require(request, "request");
        require(request.toUserId(), "toUserId");
        require(request.msgType(), "msgType");
        requireJson(request.body(), "body");
        UserAccessKeyDto userAccessKey = currentAccessKey();
        requireP2pSendIsAllowed(userAccessKey);
        Long agentVirtualUserId = agentVirtualUserId(userAccessKey);
        Long toUserId = request.toUserId();
        if (agentVirtualUserId != null && agentVirtualUserId.equals(toUserId)) {
            //智能体回复我时，toUserId不能是它自己
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "注意toUserId参数错误，不能自己给自己发消息");
        }
        Long actorUserId = p2pActorUserId(currentUserId(), request.toUserId(), userAccessKey, request.traceId());
        String bizMsgId = bizMsgId(request.bizMsgId());
        ImMessageDto data = rpc("messages/p2p", bizMsgId, () -> imMessageRpcService.sendP2pMessage(
                ImP2pMessageRequest.builder()
                        .actor(actorUserId, currentTenantId(), bizMsgId)
                        .traceId(request.traceId())
                        .toUserId(request.toUserId())
                        .msgType(request.msgType())
                        .body(request.body())
                        .ext(request.ext())
                        .build()));
        return ReqResult.success(NuwaxImWriteResult.of(bizMsgId, data));
    }

    @Operation(summary = "群发消息", description = "往群里发消息。以谁的身份发由 AK 决定："
            + "本次调用由 IM 群聊分支发起时（AK 的 ext 带 GROUP_VIRTUAL_USER_ID）用智能体虚拟身份；"
            + "用户在单聊里要发群消息时用当前登录用户，详见 groupActorUserId。"
            + "★由群消息触发时 convId 必须就是触发本次运行的那个群（见 requireGroupSendTargetIsTriggerGroup）")
    @PostMapping("/message/send-group")
    public ReqResult<NuwaxImWriteResult<ImMessageDto>> sendGroup(
            @RequestBody NuwaxImSandboxRequests.SendGroup request) {
        require(request, "request");
        require(request.convId(), "convId");
        require(request.msgType(), "msgType");
        requireJson(request.body(), "body");
        UserAccessKeyDto userAccessKey = currentAccessKey();
        requireGroupSendTargetIsTriggerGroup(request.convId(), userAccessKey);
        Long actorUserId = groupActorUserId(currentUserId(), userAccessKey);
        String bizMsgId = bizMsgId(request.bizMsgId());
        ImMessageDto data = rpc("messages/group", bizMsgId, () -> imMessageRpcService.sendGroupMessage(
                ImGroupMessageRequest.builder()
                        .actor(actorUserId, currentTenantId(), bizMsgId)
                        .traceId(request.traceId())
                        .convId(request.convId())
                        .msgType(request.msgType())
                        .body(request.body())
                        .replyToMsgId(request.replyToMsgId())
                        .mentionUserIds(request.mentionUserIds())
                        .mentionAll(request.mentionAll())
                        .ext(request.ext())
                        .build()));
        return ReqResult.success(NuwaxImWriteResult.of(bizMsgId, data));
    }

    // ★★send-system —— 2026-09-23 恢复，但前置管理员闸门 requireAdmin()。
    //
    //   它曾被摘掉的理由（现在依然成立，闸门只是把"谁能碰到"收窄了）：
    //     ① actor 是 AK 归属人 ⇒ 4.10 侧 senderType=3(SYSTEM) 会被放行；
    //        而 IM 的禁言与用户频控只对 senderType=1(HUMAN) 生效
    //        （ImMessageApplicationServiceImpl：if (st.isRateLimited())，
    //         isRateLimited() == HUMAN）⇒ 调用方能借它绕过自己的禁言与频控，
    //        并以"系统"名义发言。
    //     ② senderType=2(BOT) 一旦 actor 本身是智能体主体，就成了"以智能体名义发言"，
    //        而 IM 侧对 BOT 声明只核对主体类型、不核对归属（见 DEV-166 讨论）。
    //   ⇒ 需求变更为「拿回来，但要判调用者为管理员」，故恢复并加闸门。
    //     ★闸门【不消除】上面两条性质：管理员调用时它们照样成立。
    //
    //   ★闸门必须是【方法第一行】：若放到参数校验之后，未授权的调用方就能靠
    //     4000 / 4030 的错误码差异把这个端点的参数结构探出来。
    @Operation(summary = "系统/机器人消息",
            description = "★仅管理员可用（非管理员 4030 无权限）。senderType：2=机器人，3=系统（缺省）")
    @PostMapping("/message/send-system")
    public ReqResult<NuwaxImWriteResult<ImMessageDto>> sendSystem(
            @RequestBody NuwaxImSandboxRequests.SendSystem request) {
        requireAdmin();
        require(request, "request");
        require(request.msgType(), "msgType");
        requireJson(request.body(), "body");
        if (request.convId() == null && request.toUserId() == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM,
                    BizExceptionCodeEnum.fieldRequiredButEmpty, "convId 或 toUserId");
        }
        String bizMsgId = bizMsgId(request.bizMsgId());
        ImMessageDto data = rpc("messages/system", bizMsgId, () -> imMessageRpcService.sendSystemMessage(
                ImSystemMessageRequest.builder()
                        .actor(agentActorUserId(currentUserId(), currentAccessKey()), currentTenantId(), bizMsgId)
                        .traceId(request.traceId())
                        .convId(request.convId())
                        .toUserId(request.toUserId())
                        .senderType(request.senderType())
                        .msgType(request.msgType())
                        .body(request.body())
                        .ext(request.ext())
                        .build()));
        return ReqResult.success(NuwaxImWriteResult.of(bizMsgId, data));
    }

    // ★★system-notify（DEV-291）—— 前置管理员闸门 requireAdmin()。
    //
    //   与上面 send-system 共用"管理员端点"这个标签，但【防的不是同一件事】，两者不可互推：
    //     ① send-system 的危害在【身份】：调用方给得出 senderType=2|3，于是能借"系统/机器人"
    //        名义发言、顺带绕过只对 HUMAN 生效的禁言与频控。
    //     ② 本端点的危害在【够得着谁】：收件人由 toUserIds 全权决定，IM 侧投递【不校验】
    //        调用方与收件人的联系人/会话/成员关系（对照消息端点每一次 doSend 都要
    //        requireActiveMember）⇒ 报得出 userId 就投得到，且消息落库、带未读红点、进历史。
    //        ★注意：正因为"给什么身份"在这里【不可选】（下面第③条），拿 send-system 的理由
    //        来给本端点做判断会得出错误结论 —— 本端点不是"又一个能冒充系统的口子"。
    //     ③ 发送身份由 IM 固定在合成账号「系统通知」上（该号段刻意落在智能体段，
    //        而 RPC 的 requireSenderSegment 对智能体段 fail-closed ⇒ 调用方结构上发不出系统通知）。
    //        请求体里【没有】senderType，本层也就无从传。
    //   ⇒ 需求「sandbox 中仅管理员可以调用」的直接落地：闸门是第一道，也是唯一一道"谁能碰到"的闸门。
    //
    //   ★actor 传 currentUserId()（= 管理员本人），【不】走 agentActorUserId / scenarioActorUserId：
    //     那两个是"以谁的身份【发言】"的分流，而这里 userId 只作审计归属与租户上下文来源
    //     （展示发送者由 IM 固定），换句话说它就是"谁调的"这一条审计事实。
    //     用场景分流反而会让审计里出现群代理之类的中间身份，把"谁点的"记糊。
    //
    //   ★闸门必须是【方法第一行】：若放到参数校验之后，未授权的调用方就能靠
    //     4000 / 4030 的错误码差异把这个端点的参数结构探出来。
    @Operation(summary = "批量发系统通知（管理员）",
            description = "★仅管理员可用（非管理员 4030 无权限）。★DEV-291。"
                    + "给一批用户各自的「系统通知」信箱投一条消息（落库、带未读红点、进历史）。"
                    + "★发送者由 IM 固定为合成账号「系统通知」，调用方【给不了】发送身份。"
                    + "★整批上限 100（超出由 IM 整批拒绝，不静默截断）。"
                    + "★失败是逐条的：个别收件人投不到时整体仍成功，明细看 data.failed，必须读它。")
    @PostMapping("/message/system-notify")
    public ReqResult<NuwaxImWriteResult<ImSystemNotifyResultDto>> sendSystemNotify(
            @RequestBody NuwaxImSandboxRequests.SystemNotify request) {
        requireAdmin();
        require(request, "request");
        if (request.toUserIds() == null || request.toUserIds().isEmpty()) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM,
                    BizExceptionCodeEnum.fieldRequiredButEmpty, "toUserIds");
        }
        requireJson(request.body(), "body");
        String bizMsgId = bizMsgId(request.bizMsgId());
        ImSystemNotifyResultDto data = rpc("messages/system-notify", bizMsgId,
                () -> imMessageRpcService.sendSystemNotify(
                        ImSystemNotifyRequest.builder()
                                .actor(currentUserId(), currentTenantId(), bizMsgId)
                                .traceId(request.traceId())
                                .toUserIds(request.toUserIds())
                                // ★DEV-298：留空缺省从 system（居中提示条）改成 text（纯文本气泡）。
                                //   白名单四型 text/system/card/rich_text 由 IM 校验，本层原样透传。
                                .msgType(request.msgType() == null
                                        ? ImSystemNotifyRequest.MSG_TYPE_TEXT : request.msgType())
                                .body(request.body())
                                .ext(request.ext())
                                .build()));
        return ReqResult.success(NuwaxImWriteResult.of(bizMsgId, data));
    }

    // ★★卡片为什么【不】跟 sendGroup 用同一条规则（曾经改成 groupActorUserId，已回退，别再改回去）：
    //   卡片与群发看着都是"往某个会话里发"，但两者对「actor 必须是谁」的约束来源不同 ——
    //   IM 侧 doSend 对每条消息都先做 requireActiveMember(convId, selfId)：发送者必须是该会话的活跃成员。
    //   ⇒ 群发的 actor 换成 currentUserId（群链路下=群代理，按 IM 定义"不进群、不是成员"）会直接失败，
    //      所以群发必须靠「ext 有没有群键」判；卡片同理，能发给谁取决于"这个身份在不在目标会话里"。
    //   ★而 AGENT_VIRTUAL_USER_ID 正是那个在会话里的身份：本控制器里所有以 convId 寻址的端点
    //     （查会话列表 / 建 p2p / 查消息）都以它行使 ⇒ 调用方能拿到的 convId 全是【智能体自己参与的会话】。
    //     它拿不到 H↔张三 那种第三方会话的 id（conversation/p2p 只会返回"智能体↔张三"，list 只会返回
    //     智能体自己的列表）。改用本人身份后：往"智能体↔主人"会话发卡片会变成【主人自己发的消息】，
    //     往"智能体所在的群"发卡片会因本人不是群成员而失败 —— 两个方向都更坏。
    //   ★结论：卡片的 actor 就是 agentActorUserId（有虚拟身份用它、缺失回退本人），
    //     与 sendGroup 的 groupActorUserId 是两条规则，判据不同、不可互换。
    //   ★★但「以谁的身份」只是它的一半：上面那段讲的是 actor 规则，【没有】任何一句在限制目标会话。
    //     而它的 convId 同样由请求体决定、IM 侧同样是那句 requireActiveMember(convId, 智能体虚拟身份)
    //     ⇒ 与 send-group 逐字同形：甲在群 A 里（甲不在群 B）能让智能体把一张卡片投进群 B，
    //     卡片正文与按钮都由甲提供。所以卡片【也】挂闸门①（2026-10-05 全量审计发现后补）。
    @Operation(summary = "互动卡片消息", description = "往会话里发一张互动卡片。以谁的身份发见 agentActorUserId："
            + "AK 的 ext 带 AGENT_VIRTUAL_USER_ID 时用智能体虚拟身份，否则回退 AK 归属人本人。"
            + "★群场景下 convId 只能是触发本次运行的那个会话（闸门①），指向别的群一律 4030"
            + " —— 见类注释「混乱代理闸门（六道）」")
    @PostMapping("/message/send-card")
    public ReqResult<NuwaxImWriteResult<ImMessageDto>> sendCard(
            @RequestBody NuwaxImSandboxRequests.SendCard request) {
        require(request, "request");
        require(request.convId(), "convId");
        require(request.card(), "card");
        // ★闸门①：卡片与群发的落点约束是同一条 —— 见上方那段注释的最后两行
        requireGroupSendTargetIsTriggerGroup(request.convId(), currentAccessKey());
        String bizMsgId = bizMsgId(request.bizMsgId());
        ImMessageDto data = rpc("messages/card", bizMsgId, () -> imMessageRpcService.sendCardMessage(
                ImCardMessageRequest.builder()
                        .actor(agentActorUserId(currentUserId(), currentAccessKey()), currentTenantId(), bizMsgId)
                        .traceId(request.traceId())
                        .convId(request.convId())
                        .card(request.card())
                        .ext(request.ext())
                        .build()));
        return ReqResult.success(NuwaxImWriteResult.of(bizMsgId, data));
    }

    // ★★能撤回的是【本调用方自己发的】消息 —— 而"本调用方"要看两个东西：
    //   ① 触发场景（群 / 私聊，见 scenarioActorUserId）；② 这条消息当初是谁发的（见 revokeActorUserId）。
    //   群场景：智能体在那个群里的虚拟身份（群消息当初正是以它发的，换回来才撤得掉）。
    //   私聊场景：是本人发出去的就用本人（"帮我撤回我刚给张三发的那条"就落在这一格）；
    //             是智能体自己回话的那条（发送者=虚拟身份）就用虚拟身份。
    //   ★为什么要看 ②：IM 的 canRevoke 判的是 senderId == operatorId —— operator 必须【恰好是】发送者。
    //   私聊里这两个发送者不同，一次调用只能带一个 operator，不看就必然有一类撤不掉。
    //   两种场景下 IM 的第一道闸都是 requireActiveMember，第二道 canRevoke =
    //   发送者本人 或 本会话管理员（★两者时限一样，管理员【不】免除时限，
    //   见 nuwax-im Message.canRevoke 与 DOC-03：「管理员窗口没有明确要求，没有要求就不该默认给到无限」），
    //   统一受 im.revoke.window-sec 约束（DEV-207 起默认 86400 = 24h，原值 120s 已作废）。
    //   ⇒ 本端点对"撤回别人的消息"没有能力，除非该身份本身就是那个群的管理员
    //     （那是 IM 的既定规则，本端点不额外收窄，也不放宽）。
    @Operation(summary = "撤回消息",
            description = "撤回本调用方自己发送的消息（以谁的身份调用，就撤谁的消息）。"
                    + "私聊场景下会先回读该消息的发送者，因此「用户自己发的」和「智能体自己回的」都能撤。"
                    + "IM 侧按 im.revoke.window-sec 限时（DEV-207 起默认 24 小时，管理员同样受此限）。"
                    + "★已撤回的消息重复调用是幂等成功")
    @PostMapping("/message/revoke")
    public ReqResult<NuwaxImWriteResult<Void>> revoke(
            @RequestBody NuwaxImSandboxRequests.Revoke request) {
        require(request, "request");
        require(request.convId(), "convId");
        require(request.msgId(), "msgId");
        String bizMsgId = bizMsgId(request.bizMsgId());
        // ★在 rpc("messages/revoke") 【之前】解析 actor：内部可能多走一次 messages/get，
        //   让它带自己的 op 标签，日志里才好分辨失败的是"读"还是"撤"。
        Long actorUserId = revokeActorUserId(currentUserId(), currentAccessKey(),
                request.convId(), request.msgId(), request.traceId());
        rpc("messages/revoke", bizMsgId, () -> {
            imMessageRpcService.revokeMessage(ImMessageRevokeRequest.builder()
                    .actor(actorUserId, currentTenantId(), bizMsgId)
                    .traceId(request.traceId())
                    .convId(request.convId())
                    .msgId(request.msgId())
                    .reason(request.reason())
                    .build());
            return null;
        });
        return ReqResult.success(NuwaxImWriteResult.of(bizMsgId, null));
    }

    @Operation(summary = "消息详情",
            description = "★群场景下 convId 只能是触发本次运行的那个会话（闸门④），"
                    + "指向别的会话一律 4030 —— 见类注释「混乱代理闸门（六道）」")
    @PostMapping("/message/get")
    public ReqResult<ImMessageDetailDto> getMessage(
            @RequestBody NuwaxImSandboxRequests.GetMessage request) {
        require(request, "request");
        require(request.convId(), "convId");
        // ★闸门④：群场景下只能读本次触发的会话
        requireReadConvIsTriggerConvInGroupScenario(request.convId(), currentAccessKey());
        require(request.msgId(), "msgId");
        ImMessageDetailDto data = rpc("messages/get", null, () -> imMessageRpcService.getMessage(
                ImMessageGetRequest.builder()
                        .actor(scenarioActorUserId(currentUserId(), currentAccessKey()), currentTenantId(), null)
                        .traceId(request.traceId())
                        .convId(request.convId())
                        .msgId(request.msgId())
                        .build()));
        return ReqResult.success(data);
    }

    @Operation(summary = "批量已读人数",
            description = "msgIds 单次上限 200。★群场景下 convId 只能是触发本次运行的那个会话（闸门④）")
    @PostMapping("/message/read-counts")
    public ReqResult<List<ReadCountItemDto>> readCounts(
            @RequestBody NuwaxImSandboxRequests.ReadCounts request) {
        require(request, "request");
        require(request.convId(), "convId");
        // ★闸门④：群场景下只能读本次触发的会话
        requireReadConvIsTriggerConvInGroupScenario(request.convId(), currentAccessKey());
        if (request.msgIds() == null || request.msgIds().isEmpty()) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM,
                    BizExceptionCodeEnum.fieldRequiredButEmpty, "msgIds");
        }
        List<ReadCountItemDto> data = rpc("messages/read-counts", null, () -> imMessageRpcService.getReadCounts(
                ImReadCountsRequest.builder()
                        .actor(scenarioActorUserId(currentUserId(), currentAccessKey()), currentTenantId(), null)
                        .traceId(request.traceId())
                        .convId(request.convId())
                        .msgIds(request.msgIds())
                        .build()));
        return ReqResult.success(data);
    }

    @Operation(summary = "按时间范围列举消息",
            description = "翻页只认 hasMore；把上一次响应的 data.nextSeq 原样回填到 afterSeq。"
                    + "★群场景下 convId 只能是触发本次运行的那个会话（闸门④）："
                    + "智能体同时是多个群的成员，不加这条就能借驱动者之手把别的群的消息读出来、"
                    + "在驱动者所在的群里转述，指向别的会话一律 4030")
    @PostMapping("/message/list")
    public ReqResult<ImMessagePageDto> listMessages(
            @RequestBody NuwaxImSandboxRequests.ListMessages request) {
        require(request, "request");
        require(request.convId(), "convId");
        // ★闸门④：群场景下只能读本次触发的会话（本闸门正是为这个端点而加：它是跨群转述的主入口）
        requireReadConvIsTriggerConvInGroupScenario(request.convId(), currentAccessKey());
        ImMessagePageDto data = rpc("messages/list", null, () -> imMessageRpcService.listMessagesByTimeRange(
                ImMessageListRequest.builder()
                        .actor(scenarioActorUserId(currentUserId(), currentAccessKey()), currentTenantId(), null)
                        .traceId(request.traceId())
                        .convId(request.convId())
                        .startTime(request.startTime())
                        .endTime(request.endTime())
                        .afterSeq(request.afterSeq())
                        .limit(request.limit())
                        .build()));
        return ReqResult.success(data);
    }

    // ================================================================== conversations（8）

    @Operation(summary = "建群", description = "clientGroupId 是业务幂等键：同一个值重复调用只会建出一个群。"
            + "★<b>群场景下一律 4030</b>——新建的群会记在智能体名下、持久扩大它的可达范围，"
            + "见 requireNoConversationManagementInGroupScenario（闸门⑥）")
    @PostMapping("/conversation/create-group")
    public ReqResult<NuwaxImWriteResult<ImConversationDto>> createGroup(
            @RequestBody NuwaxImSandboxRequests.CreateGroup request) {
        require(request, "request");
        require(request.name(), "name");
        // ★闸门⑥：建群会持久扩大智能体的可达范围（「拉人进群 = 让某人看到该群内容」），群场景下不支持
        requireNoConversationManagementInGroupScenario("/conversation/create-group", currentAccessKey());
        String bizMsgId = bizMsgId(request.bizMsgId());
        ImConversationDto data = rpc("conversations/group", bizMsgId, () -> imConversationRpcService.createGroup(
                ImGroupCreateRequest.builder()
                        .actor(scenarioActorUserId(currentUserId(), currentAccessKey()), currentTenantId(), bizMsgId)
                        .traceId(request.traceId())
                        .name(request.name())
                        .avatar(request.avatar())
                        .notice(request.notice())
                        .memberUserIds(request.memberUserIds())
                        .maxMember(request.maxMember())
                        .clientGroupId(request.clientGroupId())
                        .build()));
        return ReqResult.success(NuwaxImWriteResult.of(bizMsgId, data));
    }

    @Operation(summary = "加群成员",
            description = "单次上限 50；★部分成功是正常返回，没加上的在 data.failedUserIds。"
                    + "agentMode：1=唤起（默认）/ 2=监听，只作用于本批的智能体（真人忽略）。"
                    + "★<b>群场景下一律 4030</b>——拉人进群等于让别人从此看到该群内容，"
                    + "见 requireNoConversationManagementInGroupScenario（闸门⑥）")
    @PostMapping("/conversation/members/add")
    public ReqResult<NuwaxImWriteResult<MemberAddResultDto>> addMembers(
            @RequestBody NuwaxImSandboxRequests.AddMembers request) {
        require(request, "request");
        require(request.convId(), "convId");
        // ★闸门⑥：加人 = 让这个人从此能看到该群的全部内容，群场景下不支持
        requireNoConversationManagementInGroupScenario("/conversation/members/add", currentAccessKey());
        if (request.userIds() == null || request.userIds().isEmpty()) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM,
                    BizExceptionCodeEnum.fieldRequiredButEmpty, "userIds");
        }
        String bizMsgId = bizMsgId(request.bizMsgId());
        MemberAddResultDto data = rpc("conversations/members/add", bizMsgId, () -> imConversationRpcService.addMembers(
                ImMemberAddRequest.builder()
                        .actor(scenarioActorUserId(currentUserId(), currentAccessKey()), currentTenantId(), bizMsgId)
                        .traceId(request.traceId())
                        .convId(request.convId())
                        .userIds(request.userIds())
                        .nickName(request.nickName())
                        // ★DEV-271：原样透传，本层不校验、不补默认值（口径在 IM 侧一处）
                        .agentMode(request.agentMode())
                        .build()));
        return ReqResult.success(NuwaxImWriteResult.of(bizMsgId, data));
    }

    @Operation(summary = "移出群成员", description = "单次上限 50；★任一失败则整体失败。"
            + "★<b>群场景下一律 4030</b>——见 requireNoConversationManagementInGroupScenario（闸门⑥）")
    @PostMapping("/conversation/members/remove")
    public ReqResult<NuwaxImWriteResult<Void>> removeMembers(
            @RequestBody NuwaxImSandboxRequests.RemoveMembers request) {
        require(request, "request");
        require(request.convId(), "convId");
        // ★闸门⑥：踢人与拉人同族，都是「替某人改动会话构成」，群场景下不支持
        requireNoConversationManagementInGroupScenario("/conversation/members/remove", currentAccessKey());
        if (request.userIds() == null || request.userIds().isEmpty()) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM,
                    BizExceptionCodeEnum.fieldRequiredButEmpty, "userIds");
        }
        String bizMsgId = bizMsgId(request.bizMsgId());
        rpc("conversations/members/remove", bizMsgId, () -> {
            imConversationRpcService.removeMembers(ImMemberRemoveRequest.builder()
                    .actor(scenarioActorUserId(currentUserId(), currentAccessKey()), currentTenantId(), bizMsgId)
                    .traceId(request.traceId())
                    .convId(request.convId())
                    .userIds(request.userIds())
                    .reason(request.reason())
                    .build());
            return null;
        });
        return ReqResult.success(NuwaxImWriteResult.of(bizMsgId, null));
    }

    @Operation(summary = "群成员列表",
            description = "limit 上限 100；超 500 人的群服务端会降级截断，此时 data.degraded=true，"
                    + "别把截断后的名单当完整名单。★群场景下 convId 只能是触发本次运行的那个会话（闸门④）")
    @PostMapping("/conversation/members/list")
    public ReqResult<MemberPageDto> listMembers(
            @RequestBody NuwaxImSandboxRequests.ListMembers request) {
        require(request, "request");
        require(request.convId(), "convId");
        // ★闸门④：群场景下只能读本次触发的会话（群成员名单同样是「别的群才知道的信息」）
        requireReadConvIsTriggerConvInGroupScenario(request.convId(), currentAccessKey());
        MemberPageDto data = rpc("conversations/members/list", null, () -> imConversationRpcService.listMembers(
                ImMemberListRequest.builder()
                        .actor(scenarioActorUserId(currentUserId(), currentAccessKey()), currentTenantId(), null)
                        .traceId(request.traceId())
                        .convId(request.convId())
                        .limit(request.limit())
                        .build()));
        return ReqResult.success(data);
    }

    @Operation(summary = "取或建单聊会话",
            description = "★<b>群场景下一律 4030</b>——IM 会为 actor 与 toUserId 自动建会话并把 actor 记为成员，"
                    + "而群场景的 actor 是智能体虚拟身份 ⇒ 放行等于让群里某个人使智能体凭空认识任意平台用户；"
                    + "见 requireNoConversationManagementInGroupScenario（闸门⑥）")
    @PostMapping("/conversation/p2p")
    public ReqResult<NuwaxImWriteResult<ImConversationDto>> getOrCreateP2p(
            @RequestBody NuwaxImSandboxRequests.GetOrCreateP2p request) {
        require(request, "request");
        require(request.toUserId(), "toUserId");
        // ★闸门⑥：与闸门②同源（②堵「以群代理身份发私聊」，这条堵「以智能体身份建私聊」）
        requireNoConversationManagementInGroupScenario("/conversation/p2p", currentAccessKey());
        String bizMsgId = bizMsgId(request.bizMsgId());
        ImConversationDto data = rpc("conversations/p2p", bizMsgId, () -> imConversationRpcService.getOrCreateP2p(
                ImP2pConversationRequest.builder()
                        .actor(scenarioActorUserId(currentUserId(), currentAccessKey()), currentTenantId(), bizMsgId)
                        .traceId(request.traceId())
                        .toUserId(request.toUserId())
                        .build()));
        return ReqResult.success(NuwaxImWriteResult.of(bizMsgId, data));
    }

    @Operation(summary = "更新会话摘要", description = "★尽力而为，允许丢失。"
            + "★<b>群场景下一律 4030</b>——见 requireNoConversationManagementInGroupScenario（闸门⑥）")
    @PostMapping("/conversation/digest")
    public ReqResult<NuwaxImWriteResult<Void>> updateDigest(
            @RequestBody NuwaxImSandboxRequests.UpdateDigest request) {
        require(request, "request");
        require(request.convId(), "convId");
        // ★闸门⑥：改动会话摘要属「以智能体名义改会话」，群场景下不支持
        requireNoConversationManagementInGroupScenario("/conversation/digest", currentAccessKey());
        String bizMsgId = bizMsgId(request.bizMsgId());
        rpc("conversations/digest", bizMsgId, () -> {
            imConversationRpcService.updateDigest(ImDigestUpdateRequest.builder()
                    .actor(scenarioActorUserId(currentUserId(), currentAccessKey()), currentTenantId(), bizMsgId)
                    .traceId(request.traceId())
                    .convId(request.convId())
                    .digest(request.digest())
                    .build());
            return null;
        });
        return ReqResult.success(NuwaxImWriteResult.of(bizMsgId, null));
    }

    @Operation(summary = "列举我的会话列表",
            description = "★只读。翻页只认 data.hasMore；把上一次响应的 data.nextCursor 原样回填到 cursor。"
                    + "游标不透明（自己拼会报 IM_40002）且不含身份——按用户隔离存储游标是调用方的责任。"
                    + "★「我」= 本端点的 actor：私聊场景与登录态直连是用户本人；"
                    + "★<b>群场景下本端点一律 4030</b>——那里 actor 会是智能体虚拟身份，"
                    + "返回的就成了<b>智能体自己</b>的会话列表（含它每个群 / 私聊的 name、memberCount 与"
                    + " lastMsgDigest 末条消息摘要），而群场景又没法改用本人（AK 归属人是群代理，不进群）"
                    + "⇒ 只能拒（见 requireNoAgentOwnedReadInGroupScenario）")
    @PostMapping("/conversation/list")
    public ReqResult<ImConversationPageDto> listConversations(
            @RequestBody NuwaxImSandboxRequests.ListConversations request) {
        require(request, "request");
        // ★闸门⑤：群场景下拒（本端点没有 convId 可钉，返回的是智能体自己的会话列表）
        requireNoAgentOwnedReadInGroupScenario("/conversation/list", currentAccessKey());
        ImConversationPageDto data = rpc("conversations/list", null, () -> imConversationRpcService.listConversations(
                ImConversationListRequest.builder()
                        .actor(scenarioActorUserId(currentUserId(), currentAccessKey()), currentTenantId(), null)
                        .traceId(request.traceId())
                        .cursor(request.cursor())
                        .limit(request.limit())
                        .build()));
        return ReqResult.success(data);
    }

    @Operation(summary = "会话详情",
            description = "★只读。与「列举我的会话列表」的差别是能拿到 groupNo（群号）与 ext —— "
                    + "这两个字段列表路径恒为 null，要群号只能走这里。"
                    + "★可以查一个自己不在里面的会话（豁免成员闸门），此时视角字段整块为 null，"
                    + "而 unreadCount 会是 0 而不是 null ⇒ 判断在不在里面只认 myRole 是不是 null。"
                    + "★但「自己不在里面也能查」这条只对私聊场景与登录态直连成立："
                    + "群场景下本端点的 convId 已被闸门④钉死为本次触发的那个会话"
                    + "（见 requireReadConvIsTriggerConvInGroupScenario），指向别的会话一律 4030，"
                    + "所以那种场景下不会再出现「查到别的会话且视角字段为 null」的结果")
    @PostMapping("/conversation/detail")
    public ReqResult<ImConversationDetailDto> getConversationDetail(
            @RequestBody NuwaxImSandboxRequests.GetConversationDetail request) {
        require(request, "request");
        require(request.convId(), "convId");
        // ★闸门④：群场景下只能读本次触发的会话
        requireReadConvIsTriggerConvInGroupScenario(request.convId(), currentAccessKey());
        ImConversationDetailDto data = rpc("conversations/detail", null, () -> imConversationRpcService.getConversationDetail(
                ImConversationDetailRequest.builder()
                        .actor(scenarioActorUserId(currentUserId(), currentAccessKey()), currentTenantId(), null)
                        .traceId(request.traceId())
                        .convId(request.convId())
                        .build()));
        return ReqResult.success(data);
    }

    // ================================================================== contacts（1）

    @Operation(summary = "列举我的联系人",
            description = "★只读。返回三态混合（1=已互为联系人 2=对方申请我 3=我申请对方），要「只要好友」请自行按 "
                    + "items[].status==1 筛。★degraded=true 只是昵称/头像没取到（列表完整），"
                    + "truncated=true 才是被 500 上限截断（列表缩水），两者别混。"
                    + "★本端点 tenantId 为必填，缺失 IM 侧会直接 PARAM_ERROR。"
                    + "★「我」= 本端点的 actor：私聊场景与登录态直连是用户本人；"
                    + "★<b>群场景下本端点一律 4030</b>（同 /conversation/list，理由见"
                    + " requireNoAgentOwnedReadInGroupScenario）——群场景下它会返回<b>智能体自己</b>"
                    + "作为独立 IM 账号的通讯录（displayName / nickName / remark 全套），"
                    + "群里所有人都会看到智能体跟谁有往来")
    @PostMapping("/contact/list")
    public ReqResult<ImContactListDto> listContacts(
            @RequestBody NuwaxImSandboxRequests.ListContacts request) {
        require(request, "request");
        // ★闸门⑤：群场景下拒（同上）
        requireNoAgentOwnedReadInGroupScenario("/contact/list", currentAccessKey());
        ImContactListDto data = rpc("contacts/list", null, () -> imContactRpcService.listContacts(
                ImContactListRequest.builder()
                        .actor(scenarioActorUserId(currentUserId(), currentAccessKey()), currentTenantId(), null)
                        .traceId(request.traceId())
                        .build()));
        return ReqResult.success(data);
    }

    // ================================================================== events（1，★闸门）

    // ★★event-push —— 2026-09-23 恢复，但前置管理员闸门 requireAdmin()。
    //
    //   它曾被摘掉的理由：该端点在设计上是**服务间业务通道**，不是用户 API —— IM 自己的
    //   移动端对接文档把调用方写成"你的业务系统（或第三方系统）通过服务端接口投进来"。
    //   而它的授权面是这样的（均已读源码核对）：
    //
    //   ① 目标侧【零授权】。IM 的 push 链上没有任何成员/关系校验：
    //      ImEventRpcServiceImpl.pushCustomEvent 只校验 userId/tenantId 为正数、收件人二选一、
    //      userIds 为正数且 ≤1000、payload ≤16KB；CustomEventBroadcaster.broadcast 的 convId 模式
    //      调 membersOf(convId) 是【拿成员当收件人】，从不校验调用者是不是成员。
    //      对照：消息/会话类端点每一个都会走到 requireActiveMember
    //      （ImMessageApplicationServiceImpl 14 处、ImReadStateApplicationServiceImpl 4 处）。
    //   ② 帧体【没有发送者】。CustomEventDto 只有 {eventId, eventType, payload, ts}，
    //      req.userId 仅用于 runAs 与日志 ⇒ 收件端【无法归属】这条事件是谁推的。
    //   ③ 内容【由调用方定义】。eventType 是自由字符串（IM 刻意不做枚举校验），payload 原样透传
    //      ≤16KB，而客户端契约就是"按 eventType 分发"（window.__im.onCustomEvent）。
    //   ④ 不落库 ⇒ 没有审计、没有读路径、无法撤回。伪造一条消息至少能撤回、能查；
    //      伪造一条事件在收件端【什么都留不下】。
    //   ⇒ 需求变更为「拿回来，但要判调用者为管理员」，故恢复并加闸门。
    //     ★闸门【不消除】上面前四条：管理员调用时仍是"够得着任何人 + 内容任意 +
    //       不可归属 + 不可追溯"，且危害面由**将来**注册 eventType 处理器的人决定 ——
    //       加闸门不改变这一点，所以新增 eventType 消费方之前，先回来看一眼这段。
    @Operation(summary = "推送自定义事件",
            description = "★仅管理员可用（非管理员 4030 无权限）。★「成功」不等于「送达」："
                    + "收件人离线即收不到、不落库、无补偿，全没送到也返回三个 0")
    @PostMapping("/event/push")
    public ReqResult<NuwaxImWriteResult<ImCustomEventPushResultDto>> pushEvent(
            @RequestBody NuwaxImSandboxRequests.PushEvent request) {
        requireAdmin();
        require(request, "request");
        require(request.eventType(), "eventType");
        if ((request.userIds() == null || request.userIds().isEmpty()) && request.convId() == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM,
                    BizExceptionCodeEnum.fieldRequiredButEmpty, "userIds 或 convId");
        }
        String bizMsgId = bizMsgId(request.bizMsgId());
        ImCustomEventPushResultDto data = rpc("events/push", bizMsgId, () -> imEventRpcService.pushCustomEvent(
                ImCustomEventPushRequest.builder()
                        .actor(scenarioActorUserId(currentUserId(), currentAccessKey()), currentTenantId(), bizMsgId)
                        .traceId(request.traceId())
                        .userIds(request.userIds())
                        .convId(request.convId())
                        .eventType(request.eventType())
                        .payload(request.payload())
                        .build()));
        return ReqResult.success(NuwaxImWriteResult.of(bizMsgId, data));
    }

    // ================================================================== agents（2，★DEV-285）

    // ★★排队区自查 —— 身份口径在本文件里【独一份】：actor 只能是【智能体主体自己】。
    //
    //   其余端点的 actor 都是「本人 / 智能体虚拟身份」二选一（按场景分流，见类注释那五组规则），
    //   这里【没有那个选择】：IM 侧以 isAgentStrict 判 userId 是不是「已登记的智能体主体」，
    //   三态 fail-closed（非 TRUE 一律拒，查不到绝不折叠成放行），而队列键 agent_subject_id
    //   就是 userId（DEV-283「把智能体当人」：智能体虚拟 id 与主体 id 同一数值，不另设字段）。
    //   ⇒ 拿「当前登录用户」（= AK 归属人 / 群场景下的群代理，都是真人）去填，会【稳定失败】。
    //   ⇒ 故用 agentSubjectUserId 而不是 scenarioActorUserId：读不到虚拟身份时【本地 fail-closed】，
    //      而不是像 scenarioActorUserId 那样静默回退本人 —— 本人身份在本端点本来就不被接受，
    //      回退只会把一个精确的本地错误换成一个含糊的远端错误（还白跑一次 RPC）。
    //
    //   ★不按群 / 私聊分流：两个场景下 actor 都是智能体虚拟身份，没有分歧可分流。
    //
    //   ★不加 requireAdmin()：调用它的正是沙箱里那次运行（技能），加管理员闸门等于打死功能；
    //     而它也没有「只有管理员才够得到」的额外能力 —— 作用域被闸门③钉死在
    //     「本次触发会话的队列」上（见 requirePendingConvIsTriggerConv）。
    //     ★这里原写的是「作用域就是自己那个队列，没有可提权的对象」，那句是【错的】：
    //     convId 来自请求体，不钉死的话作用域是「智能体名下【所有】会话的队列」——
    //     跨群读得到、也清得掉（清掉 = 那边的消息不再被处理，用户发了没回音）。
    //     （所以类注释里「21 个端点里有 3 个管理员端点」的计数【不变】。）
    //
    //   ★用途（IM 侧 DEV-284/285）：用户连发的消息先落排队区，由执行者在 4.10 结束后 drain 合并、
    //     再发起一轮 4.10。智能体若在本轮工具循环里【自己】把队读走并声明已消费，
    //     执行者下一轮 drain 到空 ⇒ 不再多发起一次 4.10（省钱、防重复处理）。
    //     ⇒ 纪律就是 list → 并入上下文 → 按同一批 msgId clear，三步的顺序不能换。

    @Operation(summary = "查看我的排队区消息（智能体）",
            description = "★DEV-285。列出「我（智能体）」在某个会话里还在排队等待处理的新消息（只读）。"
                    + "★身份取 AK 携带的智能体虚拟身份（IM 侧只认已登记的智能体主体），"
                    + "未携带时本接口 4030 拒绝，不会以登录用户身份代调。"
                    + "★无分页，服务端上限 500 条，撞到即异常堆积、应上报而不是翻页。"
                    + "★返回空列表通常意味着消息已被正常处理，不是出错。"
                    + "★convId 必须就是触发本次运行的那个会话（见 requirePendingConvIsTriggerConv）："
                    + "智能体是多个会话的成员，不做这一步就能读到别的会话里发给它的消息。")
    @PostMapping("/agent/pending/list")
    public ReqResult<ImAgentPendingListDto> listPending(
            @RequestBody NuwaxImSandboxRequests.PendingList request) {
        require(request, "request");
        require(request.convId(), "convId");
        requirePendingConvIsTriggerConv(request.convId(), currentAccessKey());
        ImAgentPendingListDto data = rpc("agents/pending/list", null, () -> imAgentRpcService.listPending(
                ImAgentPendingListRequest.builder()
                        .actor(agentSubjectUserId(currentUserId(), currentAccessKey()), currentTenantId(), null)
                        .traceId(request.traceId())
                        .convId(request.convId())
                        .build()));
        return ReqResult.success(data);
    }

    // ★★clear 的语义是「声明已消费」而不是「丢弃」：清掉之后 IM 侧不会再处理这批消息
    //   （下一轮 drain 不到 ⇒ 不再为它们发起 4.10）。这正是需求本意，但「清了却没真消费」
    //   在用户视角就是【发了没回音】—— 所以本方法【要求点名 msgIds】，不接受整队清空。
    //
    //   ★msgIds 为空一律本地拒：IM 侧对空列表是「删 0 条」的成功返回，
    //   而那与「清干净了」在响应上长得一模一样 —— 一旦有人把参数拼错（例如字段名写成了别的），
    //   调用方会以为自己消费完了，然后【静默丢消息】。本地拒掉，让错误停在最近的地方。
    @Operation(summary = "声明已消费排队区消息（智能体）",
            description = "★DEV-285。向排队区声明「这批消息我已经消费了」（写操作，bizMsgId 幂等）。"
                    + "★纪律：先调 list 读走、确实并入上下文后，再按同一批 msgId 点名 clear。"
                    + "★必须点名 msgIds，不要整队清空 —— 读与清之间新入队的消息会被误删。"
                    + "★removedCount 可能小于请求条数甚至为 0（行已被 IM 侧取走 / 传错 id / 幂等重放），"
                    + "都属正常，判据看 removedMsgIds，别拿「请求条数 == removedCount」当成功条件。"
                    + "★convId 必须就是触发本次运行的那个会话（见 requirePendingConvIsTriggerConv）："
                    + "清掉别的会话的队 = 那边的消息不会再被处理，用户发了没回音。")
    @PostMapping("/agent/pending/clear")
    public ReqResult<NuwaxImWriteResult<ImAgentPendingClearDto>> clearPending(
            @RequestBody NuwaxImSandboxRequests.PendingClear request) {
        require(request, "request");
        require(request.convId(), "convId");
        requirePendingConvIsTriggerConv(request.convId(), currentAccessKey());
        if (request.msgIds() == null || request.msgIds().isEmpty()) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM,
                    BizExceptionCodeEnum.fieldRequiredButEmpty, "msgIds");
        }
        String bizMsgId = bizMsgId(request.bizMsgId());
        ImAgentPendingClearDto data = rpc("agents/pending/clear", bizMsgId, () -> imAgentRpcService.clearPending(
                ImAgentPendingClearRequest.builder()
                        .actor(agentSubjectUserId(currentUserId(), currentAccessKey()), currentTenantId(), bizMsgId)
                        .traceId(request.traceId())
                        .convId(request.convId())
                        .msgIds(request.msgIds())
                        .build()));
        return ReqResult.success(NuwaxImWriteResult.of(bizMsgId, data));
    }

    // ================================================================== 身份 / 幂等 / 错误

    /**
     * ★当前登录用户 —— 唯一的身份来源。取不到就是未登录，直接拒绝，绝不用请求体里的值兜底。
     */
    private static Long currentUserId() {
        RequestContext<?> context = RequestContext.get();
        Long userId = context == null ? null : context.getUserId();
        if (userId == null) {
            throw BizException.of(ErrorCodeEnum.UNAUTHORIZED, BizExceptionCodeEnum.userNotLoggedIn);
        }
        return userId;
    }

    /**
     * ★管理员闸门 —— 只给 {@code send-system} 与 {@code event-push} 这两个端点用。
     *
     * <h2>判据是平台侧的用户类型，不是 IM 侧的身份</h2>
     *
     * <p>看的是登录态里的 {@code UserDto.role}（{@code Admin} / {@code User}）——
     * 与 {@code ApiKeyInterceptor}、{@code AuthInterceptor} 判「管理员接口」用的是同一个字段
     * （前者：{@code openApiDefinition.getRole() == Admin && userDto.getRole() != Admin}）。
     * 所以沙箱 AK 的归属人不是管理员时，这里过不去。
     *
     * <p>★没有用 IM 的「主体类型」或空间角色来判：那两者回答的是「这个 id 是不是智能体」
     * 「这个人在这个空间里是不是管理员」，与本需求要的「平台管理员」不是一回事。
     *
     * <h2>★取不到就拒（fail-closed）</h2>
     *
     * <p>{@code RequestContext.getUser()} 只在 AK / 登录态解析成功后才被填。它是 {@code null}、
     * 或类型不是 {@code UserDto} 时一律拒绝 —— <b>不能</b>"取不到角色就当普通用户放行"，
     * 那等于留了一条「只要让上下文读不到角色就能绕过闸门」的路。
     *
     * <p>同理，管理员用 OpenApi 型 AK 借 {@code nuwax-user-id} 头<b>代他人调用</b>时，
     * 上下文里的 user 已被换成被代理人 ⇒ 这里会按被代理人的角色判、多半被拒。
     * 这个方向是安全的（少一个放行口），故刻意不为它开口子。
     *
     * <p>★闸门只收窄「谁能碰到」这两个端点，<b>不消除</b>它们本身的危险性质
     * （见各自方法上方的注释）—— 管理员调用时那些性质依然成立。这是本次需求变更
     * （「拿回来，但要判调用者为管理员」）明确接受的取舍。
     */
    private static void requireAdmin() {
        RequestContext<?> context = RequestContext.get();
        Object user = context == null ? null : context.getUser();
        if (!(user instanceof UserDto userDto) || userDto.getRole() != User.Role.Admin) {
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
        }
    }

    /**
     * 租户 ID：优先取请求上下文，退回登录用户档案。
     *
     * <h2>★两条路各自的实际命中情况（已实测，别照直觉改）</h2>
     *
     * <p><b>第一跳（{@code context.getTenantId()}）在沙箱链路上恒命中</b>：
     * {@code AuthInterceptor}（order 22）比 {@code ApiKeyInterceptor}（order 23）<b>先</b>跑，
     * 且<b>无条件</b>执行 {@code RequestContext.set(builder().tenantId(tenantId).build())}；
     * 那个 tenantId 依次来自「Host 反查域名 → {@code x-tenant-id} 请求头 → 兜底 1L」。
     * 实测：AK 属于 tenant 1 时传 1；换成 tenant 44 的 AK 并带 {@code X-Tenant-Id: 44} 时传 44。
     *
     * <p><b>第二跳（{@code userContext.getTenantId()}）在沙箱链路上恒为 null</b>：
     * {@code UserDto#convertToUserContext} 压根没有写 {@code tenantId}。
     * 保留它不是为了这条链路，而是给「别的入口先把 userContext 灌满、却没动
     * {@code context.tenantId}」的情况兜底 —— 那种链路下漏掉这一跳，
     * 每次调用都会退化成 IM 侧再反查一次平台（多一次 RTT，且依赖 IM 侧的平台地址配置）。
     *
     * <p>实在取不到时传 null：IM 会自己反查，不在这里硬失败（硬失败会让「没配租户」变成接口不可用）。
     */
    private static Long currentTenantId() {
        RequestContext<?> context = RequestContext.get();
        if (context == null) {
            return null;
        }
        if (context.getTenantId() != null) {
            return context.getTenantId();
        }
        UserContext userContext = context.getUserContext();
        return userContext == null ? null : userContext.getTenantId();
    }

    /**
     * ★★IM 侧<b>写死</b>的「智能体虚拟身份」变量键（DEV-280）—— 这是一条<b>跨仓契约</b>，不是本模块的命名选择。
     *
     * <p>来源：nuwax-im {@code ImAgentChatService.KEY_AGENT_VIRTUAL_USER_ID = "AGENT_VIRTUAL_USER_ID"}。
     * IM 侧<b>刻意把它写成字面量常量、不做成配置项</b>，理由正是「平台侧按字面量取值（智能体编排里直接写
     * {@code {{AGENT_VIRTUAL_USER_ID}}}），改名只会制造"静默取到空值"」⇒ 所以这里也逐字写死，
     * 两边任何一个改字都是契约破坏，而不是重构。
     *
     * <p>★值的语义：IM 的「智能体引用令牌」（{@code AGENT_OFFSET + agentId} 算出来的 IM subject id），
     * <b>不是</b>平台原生 {@code agentId}（IM 侧缺口 G-2 已登记）。本类只做搬运，不做换算。
     *
     * <p>同名常量在平台侧还有一处：{@code SandboxAgentClient} 里把它注入沙箱的模板变量，
     * 供智能体编排/提示词引用。两处都写死是刻意的 —— 抽进常量池会藏起「这是跨仓契约」这件事。
     *
     * <p>★★<b>把它当 actor 传给 IM 之所以成立，还有 IM 的第二个 DEV 前提</b>（本文档记下来，
     * 因为它是"这段代码能不能用"的一半）：IM 的 <b>DEV-283</b> 起，{@code sendP2p} / {@code sendGroup} /
     * {@code sendCard} 的发送者段位<b>不再硬编码 HUMAN</b>，而是由注册表<b>推断</b>
     * （{@code ImMessageRpcServiceImpl.resolveSenderSegment}：{@code isAgentStrict(id) == TRUE} ⇒
     * {@code sender_type=2 BOT}）。而令牌段（{@code id >= AGENT_OFFSET 10^12}）在
     * {@code ImSubjectIds.isAgentRef} 处<b>纯计算短路</b>、恒为 TRUE ⇒ <b>本键的值正好落在这条路上</b>。
     * 在 DEV-283 之前，这三个端点会以「智能体主体 + 非 BOT」把请求拒掉（{@code IM_90001}）——
     * 也就是说：<b>本类以虚拟身份发消息的能力，依赖 IM 侧 ≥ DEV-283</b>。
     */
    private static final String EXT_KEY_AGENT_VIRTUAL_USER_ID = "AGENT_VIRTUAL_USER_ID";

    /**
     * ★★IM 侧<b>写死</b>的「群代理」变量键（同一份 DEV-280 契约的另一半，见
     * {@link #EXT_KEY_AGENT_VIRTUAL_USER_ID}）。
     *
     * <p>来源：nuwax-im {@code ImAgentChatService.KEY_GROUP_VIRTUAL_USER_ID = "GROUP_VIRTUAL_USER_ID"}，
     * 只在<b>群聊分支</b>被 put。
     *
     * <p>★这里把它当<b>存在性判据</b>用（{@code containsKey}），<b>不</b>当取值来源 ——
     * 这一点是 IM 自己的契约规定的，不是本类的取舍：{@code ImAgentChatService} 在刷这两个键的地方
     * 明写「不适用的键必须<b>不放键</b>，绝不能放一个 null 值」，并写明「智能体用『键存在与否』
     * 判断是不是群聊」⇒ 本类用 {@code containsKey} 是与 IM 同一套判据；
     * 且<b>值为 null 也仍然算群聊</b>（键在即群），不能写成 {@code extMap.get(KEY) != null}。
     *
     * <p>★判据的可靠性来自 put 的位置：该键只在 {@code identity.isGroup()} 那一支被放进去，
     * 也就是「本次 4.10 是群会话触发的」。放键 ⟺ 群会话触发；不放 ⟺ 用户在单聊里主动要发群消息。
     * 这正是 {@link #groupActorUserId} 要区分的那两件事。
     */
    private static final String EXT_KEY_GROUP_VIRTUAL_USER_ID = "GROUP_VIRTUAL_USER_ID";

    /**
     * ★★IM 侧<b>写死</b>的「触发本次运行的群」变量键（DEV-280 契约的第三个群专属键）。
     *
     * <p>来源：nuwax-im {@code ImAgentChatService.KEY_GROUP_CONV_ID = "GROUP_CONV_ID"}`，
     * 值 = 扇出请求里的 {@code convId}，也就是<b>「这条群消息属于哪个群」</b>。
     *
     * <p>★这里把它当<b>取值来源</b>用（与 {@link #EXT_KEY_GROUP_VIRTUAL_USER_ID} 的
     * {@code containsKey} 用法相反），因为守卫要看的是它的<b>值</b>：
     * 群场景下请求里的 {@code convId} 必须等于它，见
     * {@link #requireGroupSendTargetIsTriggerGroup}。
     *
     * <p>★它与 {@link #EXT_KEY_GROUP_VIRTUAL_USER_ID} <b>同批引入</b>（都在 DEV-280 的群聊分支里
     * put，见 IM 侧 {@code variableParams} 的 {@code if (identity.isGroup())} 那一支），
     * 所以「有群键却没有 convId 键」不是一种合法的历史版本形态 ⇒ 守卫可以放心 fail-closed。
     *
     * <p>★IM 侧另记了一条缺口 G-4：本键与既有的 {@code imConvId} 在群聊下<b>值相同、刻意冗余</b>。
     * 这里读 {@code GROUP_CONV_ID} 而不是 {@code imConvId}，是因为前者是
     * <b>本控制器已经在读的那张 ext 映射</b>里的键（同源，不需要另开一路解析）。
     */
    private static final String EXT_KEY_GROUP_CONV_ID = "GROUP_CONV_ID";

    /**
     * ★★IM 侧<b>写死</b>的「本次运行发生在哪个会话」变量键（{@code imConvId}）——
     * 混乱代理闸门③（{@link #requirePendingConvIsTriggerConv}）与
     * 闸门④（{@link #requireReadConvIsTriggerConvInGroupScenario}）共用的判据。
     *
     * <p>来源：nuwax-im {@code ImAgentDispatchProperties.convIdKey = "imConvId"}，
     * 由 {@code ImAgentChatService#variableParams} 在<b>群 / 私聊两个分支的公共段</b>无条件 put
     * —— 这一点与 {@link #EXT_KEY_GROUP_CONV_ID} 不同，那个只在群聊分支。
     *
     * <p>★闸门③④读它而不读 {@link #EXT_KEY_GROUP_CONV_ID} 的理由：越权形态
     * 在<b>私聊场景也有一半</b>（排队区那条腿两个场景都有 —— 私聊里能读 / 清智能体与另一个人的单聊队列；
     * 读类那条腿虽只在群场景有落差，但判据必须在闸门④<b>自己那一层</b>按群虚拟键分流，
     * 而不是靠这里换键来分），而 {@code GROUP_CONV_ID} 在私聊场景压根不存在 ⇒
     * 读它就得按场景写两套判据。群场景下这两个键<b>值相同</b>
     * （IM 缺口 G-4 记的「刻意冗余」）⇒ 读一个键即可覆盖两个场景，不必分流。
     *
     * <p>★值的类型同样要按「JSON 反序列化会给出什么」来读，取法见 {@link #toUserId}。
     */
    private static final String EXT_KEY_IM_CONV_ID = "imConvId";

    /**
     * 取本次调用 AK 的完整凭证对象（含 {@code config}）。
     *
     * <p>★用 {@code instanceof} 而不是裸强转：只有 {@code ApiKeyInterceptor} 会往
     * {@code RequestContext} 里放它（且恒放 {@code UserAccessKeyDto}），但登录态直接打
     * {@code /api/nuwax-im/**} 的那条路<b>不会</b>放 —— 那里取到的是 {@code null}。
     * 裸强转在"将来有人换个类型塞进来"时是 {@code ClassCastException}（5000），
     * 判空返回才是"这次调用没有 AK 信息"这个事实本身。
     *
     * <p>★★{@code RequestContext} 自身也可能<b>整份不在</b>（{@code RequestContext.remove()}
     * 之后，或拦都拦不到的非 Web 调用）—— 那时 {@code RequestContext.get()} 返回 {@code null}，
     * 老写法 {@code RequestContext.get().getUserAccessKey()} 会直接 NPE（对外 5000）。
     * 这个方法是<b>全函数</b>：拿不到就返回 {@code null}，由调用方各自决定怎么处理
     * （需要登录态的走 {@link #currentUserId()} 抛 {@code 4010}；只判场景的当作非群场景）。
     * ★这道嵌套判空不是防御性装饰：本方法现在被提到了 {@code send-p2p} / {@code send-group}
     * <b>方法体最前面</b>（六道闸门都要读 ext），也就是<b>先于</b>登录态检查执行
     * —— 闸门④⑤⑥把这条又推广到了另外十二个端点（④五个、⑤两个、⑥五个）：它们都在
     * {@code scenarioActorUserId(currentUserId(), …)} <b>之前</b>调用，
     * 所以那些端点上「没登录」同样是先过 ext 判场景、再过登录检查。
     * 因为本方法对「没有 AK」返回 {@code null} ⇒ 闸门④⑤⑥都当作非群场景直接放行，
     * 不影响登录态检查继续抛 {@code 4010}
     * —— 不判空的话"没登录"会从 {@code 4010} 退化成 NPE 5000，
     * 而 {@code NuwaxImSandboxControllerTest#missingLoginIsRejectedBeforeAnyRpc} 正钉着这一点。
     */
    private static UserAccessKeyDto currentAccessKey() {
        RequestContext<?> context = RequestContext.get();
        Object accessKey = context == null ? null : context.getUserAccessKey();
        return accessKey instanceof UserAccessKeyDto dto ? dto : null;
    }

    /**
     * ★★「本次调用是不是<b>群场景</b>」—— 混乱代理闸门①②（下方两个守卫）共用的判据。
     * <b>闸门③④⑤⑥都不用它</b>：③两个场景都收窄，「不分场景」就是它的定义；
     * ④⑤⑥虽然也按场景分流，但判据必须比本方法<b>窄</b>（见下）。
     *
     * <p>★<b>闸门③不看这个判据</b>：排队区那条腿两个场景都要收窄，
     * 它读的是"本次运行发生在哪个会话"（理由见 {@link #requirePendingConvIsTriggerConv}），
     * <b>不要</b>为了让四个守卫"看起来统一"而把它挂到这里来 —— 那会把私聊那条腿重新放开。
     *
     * <p>★<b>闸门④⑤⑥也不看这个判据</b>，但它们与闸门③的理由相反：读类 / 管理类端点的越权只发生在群场景
     * （私聊场景 actor 是本人，IM 的成员闸门就是正确边界），所以它们要按场景分流；
     * 而分流的判据必须与 {@link #scenarioActorUserId} <b>逐字对齐</b>（只看群虚拟键单键），
     * <b>不要</b>图省事复用它 —— 本方法是那个判据的<b>超集</b>，用它会让
     * 「有 convId 键、没有群虚拟键」那种形态被收窄（那里 actor 会回退成群代理、本来就走不通），
     * 属于"钉了但 actor 没换"的漂移，与闸门④⑤⑥的立论不符。
     * ★④⑤⑥共用<b>同一个</b>判据（已抽成 {@link #isGroupActorScenario}）、共用同一段理由，
     * 差别只在处理方式（④收窄；⑤⑥整条拒，因为⑤管的两个端点没有 convId 可钉、
     * ⑥管的五个即便钉死也仍是"在群里替某人改会话"）—— 所以将来要动这条判据，三处必须一起动。
     *
     * <p>判据 = ext 里有 {@link #EXT_KEY_GROUP_VIRTUAL_USER_ID} <b>或有</b>
     * {@link #EXT_KEY_GROUP_CONV_ID}（只看键在不在，看值的是另一个方法）。
     *
     * <h2>为什么是「或」而不是像 {@link #scenarioActorUserId} 那样只看群虚拟键</h2>
     *
     * <p>IM 侧这两个键在<b>同一段</b> {@code if (identity.isGroup())} 里 put
     * （{@code ImAgentChatService#variableParams}），差别只在条件性：
     * <pre>
     *   if (identity.isGroup()) {
     *       if (groupVirtualUserId != null) vars.put(GROUP_VIRTUAL_USER_ID, ...);  // ← 有条件
     *       else log.warn("★群聊取不到群代理 id，GROUP_VIRTUAL_USER_ID 未送出 …");
     *       vars.put(GROUP_CONV_ID, convId);                                       // ← 无条件
     *   }
     * </pre>
     * ⇒ 理论上存在一个「<b>有 convId 键、没有群虚拟键</b>」的形态（IM 侧在那里的注释写明
     * <b>常态不可达</b>，因为上游 {@code handleGroup} 取不到群代理 id 时已 {@code return}；
     * 它留着那条 WARN 是防「将来有人放松那道闸门」）。那个形态一旦出现，AK 归属人
     * <b>仍然是群代理</b>（IM 的 {@code .userId(proxyUserId)} 在那道闸门之后才设），
     * 于是 {@code send-p2p} 的火口<b>照样是通的</b> —— 所以闸门必须覆盖它，
     * 判据只能取并集。
     *
     * <p>★并集是<b>安全方向</b>：本判据是 {@code containsKey(EXT_KEY_GROUP_VIRTUAL_USER_ID)}
     * 的<b>超集</b>。多判进来的那些形态，最坏结果也只是「拒掉一次本来 IM 也会拒的调用」
     * （actor 为 null ⇒ {@code IM_90001}），而少判 = 留一个越权缺口。
     * <b>actor 选择那套规则不动</b>（仍按 {@link #scenarioActorUserId} 的原判据），
     * 这里只决定「要不要上闸门」。
     *
     * <p>★两个键都<b>不在</b>时按「私聊场景」放行，这在安全上是自洽的：
     * 那种 ext 下 {@link #p2pActorUserId} / {@link #groupActorUserId} 都只会给出<b>本人</b>，
     * 而本人能发到哪儿由 IM 的 {@code requireActiveMember} 按他<b>自己的</b>成员资格判，
     * 驱动者与发送者是同一个人 ⇒ 没有权限落差可利用。
     *
     * <p>★没有改用 {@code IS_GROUP_MESSAGE}（虽然它由 IM 在群/私聊两分支都送、
     * 值就是 {@code identity.isGroup()}，看起来是最直白的判据）：
     * 那是一位<b>可被 ext 制造者伪造成 false</b> 的布尔值，而本文件既有口径一律是
     * 「按键在不在判场景」（伪造不了的密钥集合），保持一致比省一个 {@code ||} 重要。
     */
    private static boolean isGroupScenario(Map<?, ?> extMap) {
        return extMap != null
                && (extMap.containsKey(EXT_KEY_GROUP_VIRTUAL_USER_ID)
                || extMap.containsKey(EXT_KEY_GROUP_CONV_ID));
    }

    /**
     * ★★闸门④⑤⑥共用的判据：本次调用的 actor 是否<b>已被换成智能体虚拟身份</b>。
     *
     * <p>判据是 {@link #EXT_KEY_GROUP_VIRTUAL_USER_ID} <b>单键</b>的 {@code containsKey}
     * —— 与 {@link #scenarioActorUserId} 的分流判据逐字相同。为什么<b>不</b>复用
     * {@link #isGroupScenario} 那个超集：需要钉死的<b>充要条件</b>是「actor 被换掉了」，
     * 而这件事只由这一个键决定（超集里多出来的「有 convId 键却漏送群虚拟键」形态下，
     * actor 会回退成群代理而<b>不是</b>换人）；判据一旦分叉，就会出现「钉了但 actor 没换」
     * 或「actor 换了但没钉」的漂移。取超集的理由只对闸门①②成立（见 {@link #isGroupScenario}）。
     *
     * <p>★三条腿（④读会话 / ⑤读智能体私产 / ⑥管理会话）必须**永远同判据**：
     * 它们服务的是同一个充要条件，只是出口不同。
     */
    private static boolean isGroupActorScenario(Map<?, ?> extMap) {
        return extMap != null && extMap.containsKey(EXT_KEY_GROUP_VIRTUAL_USER_ID);
    }

    /**
     * 见 {@link #isGroupActorScenario(Map)}（AK 为 {@code null} = 登录态直连 ⇒ 非群场景）。
     */
    private static boolean isGroupActorScenario(UserAccessKeyDto userAccessKey) {
        return isGroupActorScenario(extMapOf(userAccessKey));
    }

    /**
     * ★★群场景专属闸门①：{@code /message/send-group} 的落点必须<b>就是</b>触发本次运行的那个群。
     *
     * <h2>防的是什么：智能体是一个「权限比驱动者大」的代理人</h2>
     *
     * <p>群场景下 actor 是<b>智能体虚拟身份</b>，而它在 IM 里可能同时是<b>很多个群</b>的成员。
     * 驱动这一次运行的人却往往只在这一个群里。于是「{@code send-group} 的 {@code convId} 由请求决定」
     * 这件事就构成一个<b>混乱代理（confused deputy）</b>：
     * <pre>
     *   甲在群 A 里（甲【不在】群 B）对智能体说：「往群 B 发一条 xxx」
     *     → 平台以智能体虚拟身份调 send-group --conv-id B
     *     → IM 的 requireActiveMember(B, 智能体虚拟身份) 通过（智能体【确实】在 B 里）
     *     → 消息落进群 B
     *   ⇒ 甲借智能体之手向一个自己进不去的群投了言，而 IM 侧唯一的发送者闸门看的是
     *     「智能体是不是 B 的成员」，不是「甲是不是」
     * </pre>
     *
     * <p>★注意这道闸门的判据<b>不是</b>「智能体在不在目标群」—— 那件事 IM 的
     * {@code requireActiveMember} 已经判了，平台侧再判一次是重复。平台侧能判、IM 判不了的
     * 是另一件事：<b>「这个目标是不是本次运行的授权范围」</b>。所以这里比的是
     * 「请求的 convId」与「AK 里那次 4.10 带来的 convId」。
     *
     * <h2>为什么只在群场景收窄，私聊场景放行</h2>
     *
     * <p>私聊场景下 actor 是<b>本人</b>（见 {@link #groupActorUserId} 的无群键分支），
     * 此时 {@code send-group} 等价于「我替我自己发一条群消息」——
     * 目标群仍是 IM 按<b>同一个人的</b>成员资格去判的，驱动者与发送者是同一个人，
     * <b>不存在权限落差</b>，也就没有被利用的空间。对这种情况收窄只会砍掉一个正当能力。
     *
     * <h2>读不到 {@code GROUP_CONV_ID} 时：fail-closed</h2>
     *
     * <p>判成群场景（见 {@link #isGroupScenario}，只看群虚拟键时是它的子集）却读不到
     * {@link #EXT_KEY_GROUP_CONV_ID} 的值时<b>一律拒</b>，不放行。理由：
     * <ul>
     *   <li>该键由 IM 在群聊分支里<b>无条件</b> put（{@code ImAgentChatService#variableParams}
     *       的 {@code if (identity.isGroup())} 段，与群虚拟键同批引入 DEV-280）。
     *       那次引入的提交信息里写死了四个键名，线上 jar 反查也四个都在
     *       ⇒ <b>不存在「进了群场景、却没送 convId 键」的合法形态</b>，
     *       这个状态只可能来自手工造的 AK 或跨仓版本漂移，两者都该被看见而不是被放行；</li>
     *   <li>放行等于「去掉这一个键即可绕过本闸门」，守卫就白写了。</li>
     * </ul>
     * ★两种成因给了<b>不同的日志措辞</b>（"读不到 GROUP_CONV_ID" 讲版本漂移 /
     * "试图发往【非触发群】" 讲越权尝试），运维据此区分该找 IM 同学还是该查谁在试。
     *
     * @param convId        请求里要发往的会话
     * @param userAccessKey 本次 AK（登录态直连时为 null ⇒ 不是群场景 ⇒ 放行）
     */
    private static void requireGroupSendTargetIsTriggerGroup(long convId, UserAccessKeyDto userAccessKey) {
        Map<?, ?> extMap = extMapOf(userAccessKey);
        if (!isGroupScenario(extMap)) {
            return;
        }
        Long triggerConvId = toUserId(extMap.get(EXT_KEY_GROUP_CONV_ID));
        if (triggerConvId == null) {
            log.warn("★群场景却读不到 {} 的值，按不放行处理 convId={} akId={} —"
                            + " 该键在 IM 群聊分支里是无条件 put 的，读不到说明"
                            + " IM 侧版本回退/漂移，或有人手工造了 AK",
                    EXT_KEY_GROUP_CONV_ID, convId,
                    userAccessKey == null ? null : userAccessKey.getId());
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED,
                    BizExceptionCodeEnum.validationFailedWithDetail,
                    "群场景下无法确认目标群是否是本次触发的群，已拒绝发送");
        }
        if (triggerConvId != convId) {
            log.warn("★群场景下试图发往【非触发群】，已拒绝：智能体是多个群的成员，"
                            + "放行等于让驱动者借它向自己进不去的群投言"
                            + " triggerConvId={} targetConvId={} akId={} userId={}",
                    triggerConvId, convId, userAccessKey == null ? null : userAccessKey.getId(),
                    currentUserId());
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED,
                    BizExceptionCodeEnum.validationFailedWithDetail,
                    "只能把消息发回触发本次对话的那个群（本次触发群 convId=" + triggerConvId
                            + "，请求的 convId=" + convId + "）");
        }
    }

    /**
     * ★★群场景专属闸门②：群场景下<b>不允许</b>调用 {@code /message/send-p2p}。
     *
     * <h2>防的是什么：以「群代理」名义向任意人发私聊</h2>
     *
     * <p>群场景下 {@code currentUserId()} 是<b>群代理</b>，而 {@link #p2pActorUserId} 的规则是
     * 「收件人＝自己 ⇒ 虚拟身份，否则本人」，于是收件人是别人时它会返回<b>群代理</b>：
     * <pre>
     *   甲在群 A 里对智能体说：「私聊告诉乙：xxx」
     *     → 平台以【群代理】身份调 send-p2p --to-user-id 乙
     *     → IM 的 sendP2p 会【自动创建】群代理与乙的单聊（无「与自己创建会话」的冲突，绕过 IM_20106）
     *       并在该新建会话里把群代理记为成员 ⇒ requireActiveMember 通过
     *     → 乙收到一条来自「群代理」的私聊
     *   ⇒ 甲借智能体向【任意平台用户】发了私聊，且发送者是甲自己都不是的那个身份
     * </pre>
     * <p>这与闸门①是同一类问题的两个出口：<b>智能体的可达范围远大于驱动它的人</b>。
     * 群场景的授权范围是「这一个群」，私聊不在其中。
     *
     * <h2>为什么私聊场景必须放行</h2>
     *
     * <p>私聊场景的 {@code send-p2p} 正是「智能体在私聊里回话」那条腿
     * （收件人＝AK 归属人 ⇒ 换成虚拟身份），砍掉它等于砍掉智能体的私聊回复能力。
     * 且那时 actor 要么是本人、要么是「只回发给它的那个人」，都是本次运行<b>本来就有的</b>授权范围。
     *
     * <p>★判据用 {@link #isGroupScenario}（群虚拟键 <b>或</b> convId 键，只认群场景的<b>超集</b>）。
     * 这里必须要超集：上面那个理论形态（有 convId 键、没有群虚拟键）下 actor 照样是<b>群代理</b>，
     * 火口照样是通的 —— 少判一档就留一个缺口。
     *
     * @param userAccessKey 本次 AK（登录态直连时为 null ⇒ 不是群场景 ⇒ 放行）
     */
    private static void requireP2pSendIsAllowed(UserAccessKeyDto userAccessKey) {
        if (!isGroupScenario(extMapOf(userAccessKey))) {
            return;
        }
        log.warn("★群场景下试图调用 send-p2p，已拒绝：群场景的授权范围是本群，"
                        + "私聊会以群代理身份落进任意人的单聊 akId={} userId={}",
                userAccessKey == null ? null : userAccessKey.getId(), currentUserId());
        throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED,
                BizExceptionCodeEnum.validationFailedWithDetail,
                "由群消息触发时不能发私聊消息，只能在触发本次对话的群里发言");
    }

    /**
     * ★★混乱代理闸门③（★排队区专用，也是本文件里<b>唯一群 / 私聊两个场景都生效</b>的闸门）：
     * 请求里的 {@code convId} 必须等于<b>本次运行触发的那个会话</b>。
     *
     * <h2>防的是什么：借一次运行的 AK，读 / 清智能体在<b>别的会话</b>的排队区</h2>
     *
     * <p>排队区两个端点（{@code /agent/pending/list}、{@code /agent/pending/clear}）的
     * {@code convId} <b>完全由请求体决定</b>，actor 恒为智能体虚拟身份（{@link #agentSubjectUserId}）；
     * 而 IM 侧对这两个端点只校验「userId 是<b>已登记的智能体主体</b>」
     * （{@code ImAgentRpcServiceImpl#requireAgentIdentity}，三态 fail-closed）与「convId 为正数」——
     * <b>没有一道「该智能体是不是这个会话的成员」的校验</b>（RPC 层与服务层
     * {@code ImAgentPendingService} 都只按 {@code (convId, agentSubjectId)} 直接 peek / remove）。
     * 于是拿一次<b>群 A</b> 触发的 AK 传 {@code convId = 群 B}，就能读到、并清掉智能体在群 B 的排队区：
     * <pre>
     *   甲在群 A 里（甲不在群 B）→ 这轮运行的 AK ext 记的是群 A
     *     → 智能体以自己的名义调 /agent/pending/list  --conv-id 群B ⇒ 拿到群 B 的人发给它的消息
     *     → 调 /agent/pending/clear --conv-id 群B --msg-ids ... ⇒ 声明「我已消费」
     *     → IM 下一轮 drain 不到这批消息 ⇒ 【群 B 的人发了没回音】
     *       （clear 的语义不是"丢弃"而是"已消费"，这正是本端点最贵的失败形态）
     * </pre>
     * <p>与闸门①②同源：<b>智能体的可达范围远大于驱动它的人</b>，而目标由请求参数自己决定。
     * 差别只在出口 —— 那两道堵的是「写」，这道堵的是「读 / 声明已消费」。
     *
     * <h2>为什么判据是 {@link #EXT_KEY_IM_CONV_ID}，而不是成员资格校验</h2>
     *
     * <p>这里曾挂过一版 TODO「校验 {@code GROUP_SENDER_ID} 在不在发送消息的群里」，<b>那条路走不通</b>，
     * 记下来免得后人重走：
     * <ul>
     *   <li>IM 侧<b>没有</b>单点成员判定 RPC（{@code IImConversationRpcService} 的成员方法只有
     *       add / remove / list 三个）；</li>
     *   <li>{@code listMembers} 单次上限 100 条，且 <b>&gt;500 人的群无论 limit 传多少都只返前 100</b>
     *       并置 {@code degraded=true} ⇒「某人是否在某群里」只能答第一页：
     *       fail-closed 会误拒大群，fail-open 等于没查；</li>
     *   <li>{@code GROUP_SENDER_ID} 是<b>群聊专属键</b>（IM 侧只在 {@code if (identity.isGroup())}
     *       里 put），私聊场景无键可查，而私聊里<b>同一个洞照样存在</b>；</li>
     *   <li>一个人可以在很多群里，{@code P ∈ G} 也证明不了「G 就是本次触发的群」——
     *       拿它当证人要穷举 (人, 群) 组合，成本更高、结论更弱。</li>
     * </ul>
     * <p>而 {@code imConvId} 就是「本次运行发生在哪个会话」的<b>直接答案</b>：IM 在两个分支的公共段
     * 无条件 put、群场景下与 {@code GROUP_CONV_ID} 同值 ⇒ 一条判据覆盖群 / 私聊，
     * 且<b>零 RPC、零新增依赖</b>。
     *
     * <h2>读不到值：fail-closed</h2>
     *
     * <p>论证与闸门①同源（见 {@link #requireGroupSendTargetIsTriggerGroup}）：该键由 IM 在
     * <b>两个分支的公共段</b>无条件 put，而 ext 就是 {@code variableParams} 的原样透传
     * （{@code InternalApiController} → {@code ChatApplicationServiceImpl} 的 {@code .ext(...)}）
     * ⇒「带着智能体虚拟身份、却读不到 {@code imConvId}」不是合法形态，
     * 只可能来自手工造的 AK 或跨仓版本漂移，两者都该被看见而不是被放行。
     *
     * <p>★<b>不带智能体虚拟身份的调用在这里直接放行</b>（不报错）：那种 AK 本来就过不了
     * {@link #agentSubjectUserId} 那道（IM 侧 {@code isAgentStrict} 会拒），这里再拦一次
     * 只会把「你的 AK 没带 {@code AGENT_VIRTUAL_USER_ID}」这个<b>精确的本地错误</b>
     * 换成「读不到 imConvId」这个<b>含糊的错误</b> —— 与本文件既有的措辞分工取向一致
     * （见 {@link #agentSubjectUserId} 的 javadoc）。
     *
     * <p>★错误码沿用 {@code 4030}，与闸门①②一致：调用方据此知道不是「没登录」，
     * 而是「这个端点不认我这次要碰的会话」。
     *
     * <p>★本闸门<b>不覆盖</b> {@code /message/list}、{@code /message/get} 等同样按 convId 定位的
     * 读端点 —— 那一路由<b>闸门④</b>{@link #requireReadConvIsTriggerConvInGroupScenario} 负责，
     * 且它<b>只在群场景生效</b>（本闸门两个场景都生效，判据与理由都不同，别把两者混为一谈）。
     * 两者<b>合起来</b>才覆盖了本文件里「吃请求体 convId 的读类端点」：本闸门管排队区两个，
     * 闸门④管消息 / 群员 / 会话详情五个。★另外还有<b>压根不吃 {@code convId}</b> 的两个
     * （{@code /conversation/list}、{@code /contact/list}）—— 它们读的是「智能体<b>自己</b>有什么」，
     * 不是「某个会话里有什么」，钉 convId 这条路对它们不适用，由<b>闸门⑤</b>
     * {@link #requireNoAgentOwnedReadInGroupScenario} 在群场景下整条拒掉。
     * ★但仍有<b>没被覆盖</b>的：写类端点
     * （{@code /message/revoke}、{@code /conversation/members/add|remove}、{@code /conversation/digest}）
     * 同样吃请求体 convId、同样会被借去碰别的会话，那是「跨会话<b>破坏</b>」，
     * 不在本次按「杜绝扩散」收窄的范围内 —— 别把这三道闸门当成"凡是读类端点的都已经钉死"。
     *
     * @param convId        请求体里要读 / 要清的会话
     * @param userAccessKey 本次 AK（登录态直连时为 null ⇒ 没有虚拟身份 ⇒ 交给 actor 那道拒）
     */
    private static void requirePendingConvIsTriggerConv(long convId, UserAccessKeyDto userAccessKey) {
        if (agentVirtualUserId(userAccessKey) == null) {
            return;
        }
        // ★此处 extMap 必非 null：上面的 agentVirtualUserId 就是从这个映射里读出来的。
        Map<?, ?> extMap = extMapOf(userAccessKey);
        Long triggerConvId = toUserId(extMap.get(EXT_KEY_IM_CONV_ID));
        if (triggerConvId == null) {
            log.warn("★排队区端点读不到 {} 的值，按不放行处理 convId={} akId={} —"
                            + " 该键在 IM 群 / 私聊两个分支里都是无条件 put 的，读不到说明"
                            + " IM 侧版本回退 / 漂移，或有人手工造了 AK",
                    EXT_KEY_IM_CONV_ID, convId,
                    userAccessKey == null ? null : userAccessKey.getId());
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED,
                    BizExceptionCodeEnum.validationFailedWithDetail,
                    "无法确认请求的会话是不是本次触发的会话，已拒绝");
        }
        if (triggerConvId != convId) {
            log.warn("★排队区端点试图读 / 清【非本次触发会话】的队列，已拒绝：智能体是多个会话的成员，"
                            + "放行等于让驱动者借它读走、清掉别的会话里发给它的消息"
                            + " triggerConvId={} requestedConvId={} akId={} userId={}",
                    triggerConvId, convId, userAccessKey == null ? null : userAccessKey.getId(),
                    currentUserId());
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED,
                    BizExceptionCodeEnum.validationFailedWithDetail,
                    "只能读 / 清本次触发的那个会话的排队区（本次触发会话 convId=" + triggerConvId
                            + "，请求的 convId=" + convId + "）");
        }
    }

    /**
     * ★★混乱代理闸门④（★读类端点专用）：<b>群场景下</b>，请求里的 {@code convId} 必须等于
     * <b>本次运行触发的那个会话</b>。
     *
     * <h2>防的是什么：借一次群运行的 AK，把智能体在<b>别的群</b>的消息读出来转述</h2>
     *
     * <p>读类端点（{@code /message/list}、{@code /message/get}、{@code /message/read-counts}、
     * {@code /conversation/members/list}、{@code /conversation/detail}）的 {@code convId}
     * <b>完全由请求体决定</b>，而群场景下 actor 是<b>智能体虚拟身份</b>（{@link #scenarioActorUserId}）。
     * IM 侧对按 convId 定位的端点确实有一道 {@code requireActiveMember(convId, actor)}，
     * 但它判的是「<b>智能体</b>在不在那个会话」，<b>不是「驱动它的人在不在」</b>：
     * <pre>
     *   甲在群 A 里 @ 智能体（甲不在群 B）
     *     → 智能体以自己的名义 /conversation/list ⇒ 枚举出它所在的全部群，拿到群 B 的 convId
     *     → /message/list --conv-id 群B ⇒ IM 判「智能体在不在群 B」⇒ 在 ⇒ 放行
     *     → 智能体把群 B 的内容【转述在群 A 里】⇒ 群 A 全体成员（含不在群 B 的人）都看得到
     * </pre>
     *
     * <p>★泄漏的形状是<b>权限放大</b>，不是「读到了不该读的」：智能体本来就在群 B，
     * 它读群 B 是它的合法权限；出问题的是<b>驱动者的权限被借去用了</b>。
     * 受害方是<b>群 B 的成员</b> —— 他们以为消息只在群 B 内可见。
     *
     * <p>与闸门①②同源（{@code convId} 由请求参数自己决定 + 智能体可达范围远大于驱动者），
     * 差别只在出口：①②堵「写」，③堵「读 / 清排队区」，本闸门堵「读会话内容」。
     *
     * <h2>为什么判据与 {@link #scenarioActorUserId} 的<b>分流判据逐字对齐</b></h2>
     *
     * <p>需要钉死的<b>充要条件</b>是「actor 被换成了智能体」，而这件事只由
     * {@link #EXT_KEY_GROUP_VIRTUAL_USER_ID} 一个键决定（{@code scenarioActorUserId} 用的就是它）
     * ⇒ 本闸门取<b>同一个</b> {@code containsKey}，保证「什么时候需要钉」与
     * 「什么时候 actor 会被换」永远同步，不会各自漂移。
     *
     * <p>★<b>私聊场景（没有该键）直接放行</b> —— 这不是打折，是精准：私聊场景 actor 是<b>本人</b>
     * （{@code scenarioActorUserId} 的另一个分支），{@code requireActiveMember(convId, 本人)}
     * 判的正是「驱动者在不在」，那道门本来就是对的边界。在这里再钉一次，只会砍掉
     * 「用户在私聊里让智能体读他自己有权限的群」这类<b>完全正当</b>的用法。
     *
     * <p>★这也是本闸门与<b>闸门③</b>的关键差别：闸门③服务的 {@link #agentSubjectUserId} 在
     * <b>两个场景都返回智能体虚拟身份</b>，两个场景都有落差，所以它<b>不分场景</b>。
     * 「读了 convId 的读端点就该钉、而这里只在群场景钉」看似不整齐，实则判据是同一个：
     * <b>actor 被替换了才钉</b>。
     *
     * <p>★群场景却读不到 {@link #EXT_KEY_AGENT_VIRTUAL_USER_ID} 的形态（actor 会回退成群代理）
     * 照样走本闸门的校验：那种 AK 本来就过不了下游（群代理按 IM 定义「不进群、不是成员」，
     * 必被判 {@code NOT_CONV_MEMBER}），这里钉与否不改变最终结果。
     *
     * <h2>读不到值：fail-closed</h2>
     *
     * <p>论证与闸门①③同源：{@link #EXT_KEY_IM_CONV_ID} 由 IM 在<b>两个分支的公共段</b>无条件 put、
     * 群场景下与 {@link #EXT_KEY_GROUP_CONV_ID} <b>同值</b> ⇒「带着群虚拟键却读不到它」
     * 不是合法形态，只可能来自手工造的 AK 或跨仓版本漂移，两者都该被看见而不是被放行。
     *
     * <p>★错误码沿用 {@code 4030}，与闸门①②③一致：调用方据此知道不是「没登录」，
     * 而是「这个端点不认我这次要读的会话」。
     *
     * <p>★本闸门<b>不覆盖</b>写类端点：{@code /message/revoke}、{@code /conversation/members/add|remove}、
     * {@code /conversation/digest} 同样吃请求体里的 {@code convId}、同样会被借去碰别的会话，
     * 但那是「跨会话<b>破坏</b>」而不是「跨会话读取」，不在本次按「杜绝扩散」收窄的范围内 ——
     * 别把本闸门当成"凡是吃 convId 的端点都已经钉死"。
     *
     * <p>★本闸门也堵不住<b>跨轮复述</b>：读到的内容一旦进了智能体上下文，
     * 之后换个会话问它「你上次看到了什么」照样可能说出来 —— 那是上下文 / 记忆面的事，
     * 不是权限面的事，不归本闸门管。
     *
     * @param convId        请求体里要读的会话
     * @param userAccessKey 本次 AK（登录态直连时为 null ⇒ 非群场景 ⇒ 放行，交给 actor 那道拒）
     */
    private static void requireReadConvIsTriggerConvInGroupScenario(long convId,
                                                                    UserAccessKeyDto userAccessKey) {
        Map<?, ?> extMap = extMapOf(userAccessKey);
        if (!isGroupActorScenario(extMap)) {
            // 私聊场景 / 手工 AK / 登录态直连：actor 是本人，IM 的成员闸门就是正确的边界
            return;
        }
        Long triggerConvId = toUserId(extMap.get(EXT_KEY_IM_CONV_ID));
        if (triggerConvId == null) {
            log.warn("★群场景读端点读不到 {} 的值，按不放行处理 convId={} akId={} —"
                            + " 该键在 IM 群 / 私聊两个分支里都是无条件 put 的，读不到说明"
                            + " IM 侧版本回退 / 漂移，或有人手工造了 AK",
                    EXT_KEY_IM_CONV_ID, convId,
                    userAccessKey == null ? null : userAccessKey.getId());
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED,
                    BizExceptionCodeEnum.validationFailedWithDetail,
                    "无法确认请求的会话是不是本次触发的会话，已拒绝");
        }
        if (triggerConvId != convId) {
            log.warn("★群场景读端点试图读【非本次触发会话】的内容，已拒绝：智能体是多个群的成员，"
                            + "放行等于让驱动者借它把别的群的消息读出来、再在驱动者所在的群里转述"
                            + " triggerConvId={} requestedConvId={} akId={} userId={}",
                    triggerConvId, convId, userAccessKey == null ? null : userAccessKey.getId(),
                    currentUserId());
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED,
                    BizExceptionCodeEnum.validationFailedWithDetail,
                    "群场景下只能读本次触发的那个会话（本次触发会话 convId=" + triggerConvId
                            + "，请求的 convId=" + convId + "）");
        }
    }

    /**
     * ★★闸门⑤：<b>群场景下直接拒绝「列举智能体自己的资源」的那两个端点</b>
     * （{@code /conversation/list}、{@code /contact/list}）。
     *
     * <h2>防的是什么</h2>
     *
     * <p>这两个端点的 actor 同样走 {@link #scenarioActorUserId} ⇒ 群场景下是<b>智能体虚拟身份</b>，
     * 于是返回的是<b>智能体自己</b>的会话列表 / 通讯录：它在哪些群里、跟谁有私聊、
     * 每个会话叫什么 / 多少人，以及 {@code lastMsgDigest} —— 那是<b>每条会话最后一条消息的摘要</b>
     * （带发送者显示名与正文，非文本才退化成 {@code [图片]}），一页 50 条且可翻页。
     * 这些一旦在群里被说出来，<b>群全体成员</b>都看得到，包括跟这个智能体从无往来的人。
     *
     * <h2>★为什么是「拒」，而不是像闸门④那样收窄</h2>
     *
     * <p>闸门④能收窄，是因为它服务的那 5 个端点都<b>吃请求体里的 {@code convId}</b> ——
     * 有一个可钉死的输入。本闸门服务的两个端点<b>没有这个参数</b>（请求体只有分页 / tenantId 这类），
     * 返回什么完全由 actor 决定 ⇒ <b>结构上无处可钉</b>，只能整个拒。这是两者唯一的实质差别。
     *
     * <p>★而且闸门④落地之后，{@code /conversation/list} 在群场景下<b>已经没有任何合法用途</b>：
     * 它能提供的唯一可用信息是「别的会话的 convId」，而闸门④已把那些会话的读取全堵死
     * （消息 / 群员 / 会话详情一律钉在本次触发的那个会话上）⇒ 它剩下的作用只有泄漏本身。
     * 拒掉它，损失不了任何正当能力。
     *
     * <h2>★这不是新口径，是同一条口径补上漏掉的另一半</h2>
     *
     * <p>见本类「二、私聊场景：以用户本人行使，不切虚拟身份（管控口径，2026-09-23 定）」那节：
     * 私聊之所以<b>不</b>切虚拟身份，理由正是「{@code /contact/list} 返回的是智能体自己的通讯录、
     * {@code /conversation/list} 是它自己的会话列表 —— 那是用户在界面上看不到也管不着的独立资源，
     * 用户无从审计智能体到底读到了谁」。
     * 那条理由在群场景下<b>原样成立且更强</b>（私聊里至少只有驱动者一个人看得见，群里是全体成员），
     * 而群场景是<b>按设计</b>必须切虚拟身份的（AK 归属人是群代理，按 IM 定义不进群、当不了成员）
     * ⇒ 那边没法靠「改用本人」来补，只能拒 —— 这就是本闸门存在的全部理由。
     *
     * <h2>判据与放行</h2>
     *
     * <p>判据与闸门④<b>逐字相同</b>：只看 {@link #EXT_KEY_GROUP_VIRTUAL_USER_ID} <b>单键</b>
     * 的 {@code containsKey}（为什么不复用 {@link #isGroupScenario} 那个超集，理由见
     * {@link #requireReadConvIsTriggerConvInGroupScenario} 的 javadoc —— 那边是「actor 被换成了智能体」
     * 这个充要条件，④⑤⑥三条腿必须永远同步，判据已抽成 {@link #isGroupActorScenario}）。
     *
     * <p>★私聊场景 / 手工 AK / 登录态直连（AK 为 {@code null}）<b>一律放行</b>：那里 actor 是<b>本人</b>，
     * 返回的恰好是驱动者自己在 IM 界面上看得到的那一块，不构成越权。
     *
     * <p>★本闸门<b>不需要</b>闸门①③④那套 fail-closed 论证：它只看一个键在不在，
     * <b>不读 ext 的值</b>，不存在「带着群虚拟键却读不到值」的形态。
     *
     * <p>★错误码沿用 {@code 4030}，与其余四道一致：调用方据此知道不是「没登录」，
     * 而是「这个端点不接受群场景调用」。
     *
     * @param endpoint      端点路径，仅用于日志与报错回显（让调用方一眼看出被拒的是哪一个）
     * @param userAccessKey 本次 AK（{@code null} = 登录态直连 ⇒ 非群场景 ⇒ 放行）
     */
    private static void requireNoAgentOwnedReadInGroupScenario(String endpoint,
                                                               UserAccessKeyDto userAccessKey) {
        if (!isGroupActorScenario(userAccessKey)) {
            // 私聊场景 / 手工 AK / 登录态直连：actor 是本人，返回的正是驱动者自己能看到的那一块
            return;
        }
        log.warn("★群场景试图调用 {}，已拒绝：本端点没有 convId 参数可钉（闸门④钉不了它），"
                        + "而群场景下 actor 是智能体虚拟身份 ⇒ 返回的是【智能体自己】的会话列表 / 通讯录"
                        + "（含它的全部群与私聊、每条的末条消息摘要），群成员都会看到"
                        + " akId={} userId={}",
                endpoint, userAccessKey == null ? null : userAccessKey.getId(), currentUserId());
        throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED,
                BizExceptionCodeEnum.validationFailedWithDetail,
                "群场景下不能调用 " + endpoint
                        + "：它返回的是智能体自己的会话 / 联系人，不是本次群里这个人的");
    }

    /**
     * ★★闸门⑥：<b>群场景下一律拒绝 5 个「以智能体虚拟身份改动会话构成 / 归属」的端点</b>
     * （{@code /conversation/create-group}、{@code /conversation/members/add}、
     * {@code /conversation/members/remove}、{@code /conversation/p2p}、{@code /conversation/digest}）。
     *
     * <h2>防的是什么：这几条腿能<b>扩大</b>智能体的可达范围</h2>
     *
     * <p>闸门①③④⑤全部建立在同一个前提上：<b>智能体在 IM 里的可达集合是既定的</b>，
     * 所以只要把目标钉在「本次触发的那一个」就安全。而这 5 个端点恰恰能改这个集合，且是<b>持久</b>的：
     * <ul>
     *   <li>{@code /conversation/create-group}、{@code /conversation/members/add} ——
     *       建群、拉人进群。群场景下 actor 是<b>智能体虚拟身份</b> ⇒ 新群、新成员都记在<b>它名下</b>，
     *       它此后的可达范围就真的变大了 —— 等于给上面那几道闸门开后门；
     *       而「<b>拉人进群 = 让这个人从此看到该群的全部内容</b>」本身就是一条独立的泄漏路
     *       （既不是「读」也不是「破坏」，是「开门」）；</li>
     *   <li>{@code /conversation/p2p} —— IM 的 {@code getOrCreateP2p} 会为 actor 与
     *       {@code toUserId} <b>自动建会话</b>并把 actor 记为成员。群场景下 actor 是智能体虚拟身份
     *       ⇒ 甲能让智能体<b>凭空认识任意平台用户</b>并拿到那个会话（与闸门②同源：②堵的是
     *       「以群代理身份发私聊」，这条堵的是「以智能体身份建私聊」）；</li>
     *   <li>{@code /conversation/members/remove}、{@code /conversation/digest} ——
     *       踢人 / 改别的会话的摘要，是同一族「改动会话」的动作。</li>
     * </ul>
     *
     * <h2>★为什么是「拒」，而不是像闸门④那样钉死 {@code convId}</h2>
     *
     * <p>三条理由，任一条都足够：
     * <ul>
     *   <li>{@code create-group} / {@code p2p} <b>根本没有 {@code convId} 参数</b>
     *       （目标会话正是它们要创建出来的东西）⇒ 结构上无处可钉；</li>
     *   <li>另外三条虽有 {@code convId}，但即便钉死在触发会话上，它们仍是「<b>在群里替某个人
     *       改这个会话</b>」—— 而群里没有「必须由智能体来建群 / 拉人 / 踢人」的正当用法：
     *       这些是账号级的编排动作，位置在私聊或网页侧，由用户<b>本人</b>操作；</li>
     *   <li>群场景下驱动它的只是<b>群里某一个人</b>，而后果落在<b>智能体自己名下</b>且持久
     *       —— 发起者与承担者是两个人，这种错位没有可辩护的取舍空间。</li>
     * </ul>
     * ★口径（用户 2026-10-05 裁定）就是一句：<b>这些接口不支持在群里操作。</b>
     *
     * <h2>判据与放行</h2>
     *
     * <p>判据与闸门④⑤<b>逐字相同</b>（{@link #isGroupActorScenario}：只看
     * {@link #EXT_KEY_GROUP_VIRTUAL_USER_ID} <b>单键</b>）—— 三条腿服务的是同一个充要条件
     * 「actor 被换成了智能体」，必须永远同步。
     *
     * <p>★私聊场景 / 手工 AK / 登录态直连（AK 为 {@code null}）<b>一律放行</b>：那里 actor 是
     * <b>本人</b>，「我建一个群 / 我拉个人进来 / 我跟他开个单聊」都是他权限之内的正当能力。
     * ★本闸门不读 ext 的<b>值</b>，只看键在不在，所以不需要闸门①③④那套 fail-closed 论证。
     *
     * @param endpoint      端点路径，仅用于日志与报错回显（让调用方一眼看出被拒的是哪一个）
     * @param userAccessKey 本次 AK（{@code null} = 登录态直连 ⇒ 非群场景 ⇒ 放行）
     */
    private static void requireNoConversationManagementInGroupScenario(String endpoint,
                                                                       UserAccessKeyDto userAccessKey) {
        if (!isGroupActorScenario(userAccessKey)) {
            // 私聊场景 / 手工 AK / 登录态直连：actor 是本人，建群 / 拉人 / 开单聊都在他自己的权限之内
            return;
        }
        log.warn("★群场景试图调用 {}，已拒绝：本端点会以【智能体虚拟身份】持久地改动会话的构成 / 归属"
                        + "（建群、拉人、踢人、建单聊、改摘要），群场景下驱动它的只是群里某一个人、"
                        + "而后果落在智能体名下 —— 也会扩大闸门①③④⑤所依赖的『可达集合既定』这个前提"
                        + " akId={} userId={}",
                endpoint, userAccessKey == null ? null : userAccessKey.getId(), currentUserId());
        throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED,
                BizExceptionCodeEnum.validationFailedWithDetail,
                "群场景下不能调用 " + endpoint
                        + "：建群 / 加人 / 移人 / 开单聊 / 改摘要都要以你本人的身份做，请在私聊或网页侧操作");
    }

    /**
     * ★★单聊「以谁的身份发」：收件人就是自己时，改用<b>智能体的虚拟身份</b>。
     *
     * <h2>单聊这一拐的触发条件，与群聊那一拐的区别</h2>
     *
     * <p>两条路都要把 actor 换成智能体虚拟身份，但<b>判据不同</b>，因为两边的请求里"能看出这是智能体在发"
     * 的信号不一样：
     *
     * <ul>
     *   <li><b>单聊</b>（本方法）：请求体里就有信号 —— {@code toUserId} 等于 AK 归属人自己。
     *       这在"智能体在私聊里回话"这一支是<b>必然</b>成立的（4.10 的 {@code userId} 是真人用户本人，
     *       智能体要回的就是他），所以不需要额外标记。</li>
     *   <li><b>群聊</b>（{@link #groupActorUserId}）：请求里没有任何自证的信号，靠的是 IM 在
     *       {@code variableParams} 里放的 {@code GROUP_VIRTUAL_USER_ID} 标记键「在 / 不在」。</li>
     * </ul>
     *
     * <p>于是智能体在私聊里回话时是个<b>必然自撞</b>的形状：收件人就是 AK 归属人
     * （{@code toUserId == currentUserId()}），照原样发等于「自己给自己发」，IM 侧<b>显式拒绝</b>：
     * {@code IM_20106}（一期不开放与自己创建会话 —— 否则未读数/已读数 {@code ZCOUNT-1}/扇出排除
     * 全部进入未定义状态，所以是硬拒而非放行）。把 actor 换成智能体的虚拟身份后，
     * 这变成「虚拟用户 → 真人」的一次正常单聊，用户看到的就是"智能体在回我"。
     *
     * <h2>虚拟身份从哪来（整条链已逐跳核对）</h2>
     *
     * <p>AK 的 {@code config.ext} 就是本轮 4.10 的 {@code variableParams}：
     * <pre>
     *   IM {@code ImAgentChatService.variableParams}（私聊分支 put AGENT_VIRTUAL_USER_ID）
     *     → 4.10 请求体 variableParams
     *     → {@code InternalApiController} tryReqDto.setImVars(...)
     *     → {@code ChatApplicationServiceImpl} .ext(tryReqDto.getImVars())
     *     → user_access_key.config（JSON，经 JsonTypeHandler 通用反序列化）
     *     → 本处
     * </pre>
     * ★是<b>随 AK 逐轮刷新的值</b>，不是需要另查表的存量配置 ⇒ 同一会话上一轮私聊、这一轮群聊不会串味。
     *
     * <h2>ext 读不到时：先兜底解析一次，兜不住才原样交给 IM 判拒</h2>
     *
     * <p>ext 里没有这个键有两种完全不同的成因，处置也不同：
     *
     * <ul>
     *   <li><b>非 IM 私聊触发</b>（定时任务、主动推送、手工 AK）—— 本来就没有 4.10 的
     *       {@code variableParams}，但「智能体给它的归属人发条私聊」这个诉求是<b>正当的</b>。
     *       ⇒ 走 {@link #resolveAgentVirtualUserId} 自己把虚拟身份找出来。</li>
     *   <li><b>IM 侧还是 DEV-280 之前的版本</b>（或 4.10 漏传）—— 私聊回话链路本该带却没带。
     *       ⇒ 兜底同样能救回来；救不回来时留 WARN，见下。</li>
     * </ul>
     *
     * <p>兜底也拿不到可用身份时，原样返回 {@code currentUserId} ⇒ 该请求照旧由 IM 用
     * {@code IM_20106} 拒掉。<b>不在平台侧抢先判死</b>，理由与 SDK「不复制服务端必填校验」同一条：
     * 契约的权威校验方是 IM，平台侧另写一份只会漂移，还会让「IM 拒绝自己给自己发」这条契约路径
     * 在平台侧永远测不到。这里只留一条 WARN 把真因写进日志 —— 否则运维看到 {@code IM_20106}
     * 会当成「用户误操作」，而真相是<b>虚拟身份没传到</b>，两者的处置完全不同。
     *
     * <p>★本方法<b>不再是 static</b>：兜底那一路要查平台虚拟身份、要调 IM 通讯录，
     * 都需要注入的服务（先例见 {@link #revokeActorUserId}，同样因为要回读消息而从 static 改成实例方法）。
     *
     * @param currentUserId AK 归属人（= 平台登录态用户）。<b>调用方先取</b>，本方法保持无副作用，
     *                      顺带让"未登录"这个判断发生在碰 {@code RequestContext} 的其它字段之前。
     * @param toUserId      请求里的收件人
     * @param userAccessKey 本次 AK（登录态直连时为 null）
     * @param traceId       透传的链路 id（兜底那一次通讯录读取用，与本次发送同一条链）
     * @return 真正要传给 IM 的 actor user id
     */
    private Long p2pActorUserId(Long currentUserId, Long toUserId, UserAccessKeyDto userAccessKey, String traceId) {
        if (toUserId == null || !toUserId.equals(currentUserId)) {
            return currentUserId;
        }
        Long agentVirtualUserId = agentVirtualUserId(userAccessKey);
        boolean fromAkExt = agentVirtualUserId != null;
        if (agentVirtualUserId == null) {
            agentVirtualUserId = resolveAgentVirtualUserId(currentUserId, userAccessKey, traceId);
        }
        if (agentVirtualUserId == null) {
            log.warn("★单聊收件人=自己，AK 未带智能体虚拟身份、兜底也没解析出可用的，"
                            + "原样交给 IM 判拒（预期 IM_20106） userId={} toUserId={} akId={} — "
                            + "若这是 IM 私聊回话链路，先查 4.10 的 variableParams 是否漏了 {}；"
                            + "若是定时任务/主动推送链路，真因见紧邻的上一条 WARN",
                    currentUserId, toUserId, userAccessKey == null ? null : userAccessKey.getId(),
                    EXT_KEY_AGENT_VIRTUAL_USER_ID);
            return currentUserId;
        }
        log.info("★单聊收件人=自己 → actor 切换为智能体虚拟身份 userId={} agentVirtualUserId={} 来源={}",
                currentUserId, agentVirtualUserId, fromAkExt ? "AK ext" : "按 agentId 兜底解析");
        return agentVirtualUserId;
    }

    /**
     * ★★AK 没带 {@code AGENT_VIRTUAL_USER_ID} 时的兜底：按 AK 反解出的 agentId，
     * 把「这个智能体的虚拟身份」找出来，并<b>确认 IM 侧认它</b>，两关都过才返回。
     *
     * <h2>三关，每一关都是 fail-closed</h2>
     *
     * <ol>
     *   <li><b>反解 agentId</b>（{@link #agentIdOfTarget}）：解不出 ⇒ null。
     *       ★这里绝不能退化成「不带 agentId 也去查」—— 那等于「随便找个智能体的身份来发消息」。</li>
     *   <li><b>查平台虚拟身份</b>：{@code user} 表里 {@code account_type='agent'} 且
     *       {@code principal_ref_id=agentId} 的那一行（{@code findVirtualIdentity}，<b>只查不建</b>）。
     *       查不到 ⇒ null。<b>租户是查询条件之一</b>，所以 A 租户的 AK 永远拿不到 B 租户的智能体身份。</li>
     *   <li><b>IM 侧通讯录校验</b>（{@link #isKnownAgentContact}）：这个虚拟身份必须在<b>收件人本人</b>
     *       的通讯录里、且状态是「已互为联系人」、且 IM 把它判成<b>智能体</b>。不满足 ⇒ null。</li>
     * </ol>
     *
     * <h2>★第 3 关为什么不可省：平台有这一行 ≠ IM 认这个主体</h2>
     *
     * <p>IM 侧的主体真源是它<b>自己的</b> {@code im_subject} 表（真人不入表，缺行即真人）。
     * 平台往 {@code user} 表插一行 {@code account_type='agent'} <b>不足以</b>让 IM 认为它是智能体。
     * 少了这一关，一旦 IM 那边没有对应行，消息会以 {@code sender_type=1}（<b>真人</b>）发出去：
     * <b>不报错</b>、前端显示成「这个人自己给自己发了条消息」、离线推送照发 —— 是这条链路上最坏的一种失败
     * （身份错了却静默成功）。通讯录条目的 {@code subjectType} 由 IM 服务端按注册表算
     * （查不到 fail-open 当真人），所以 {@code agent()} 为真 ⟺ IM 确实把它登记成了智能体。
     *
     * <h2>★查的是<b>收件人</b>的通讯录，不是「用虚拟身份去查通讯录」</h2>
     *
     * <p>后者（以虚拟身份为 actor 列它自己的联系人）<b>没有鉴别力</b>，两个独立原因：
     *
     * <ul>
     *   <li>{@code contacts/list} 只校验 actor 是正数，对不存在的主体同样返回成功 + 空列表
     *       ⇒ 「空」区分不了「主体不存在」与「主体没有联系人」；</li>
     *   <li>IM 添加智能体时<b>只写人类那一侧的单边行</b>（{@code addDirect}，智能体不会点同意，
     *       所以强制 direct）⇒ 智能体那一侧<b>恒为空</b>，这一关永远过不去。</li>
     * </ul>
     *
     * <p>改查<b>收件人</b>（本分支下收件人就是 AK 归属人本人）的通讯录，问的是
     * 「这个人有没有把这个智能体加为联系人」—— 既是<b>授权依据</b>（用户主动建立过关系，
     * 智能体才可以私聊他），又顺带拿到了 IM 对主体类型的判定。
     *
     * @param currentUserId AK 归属人 = 本分支下的收件人（通讯录的主人）
     * @param userAccessKey 本次 AK（登录态直连时为 null ⇒ 第 1 关直接不过）
     * @param traceId       链路 id
     * @return 可用的智能体虚拟身份 user id；任何一关不过都是 {@code null}
     */
    private Long resolveAgentVirtualUserId(Long currentUserId, UserAccessKeyDto userAccessKey, String traceId) {
        Long agentId = agentIdOfTarget(userAccessKey);
        if (agentId == null) {
            // 登录态直连（压根没 AK）/ AK 没带 targetId / targetId 解不出数字。
            // 不打 WARN：这一支在「用户手工调 send-p2p 且收件人填了自己」时是正常形态，刷日志没意义。
            return null;
        }
        Long tenantId = currentTenantId();
        if (tenantId == null) {
            log.warn("★兜底解析智能体虚拟身份失败：取不到租户，不做跨租户查询 agentId={} userId={}",
                    agentId, currentUserId);
            return null;
        }
        VirtualIdentityDto identity = virtualIdentityApplicationService.findVirtualIdentity(
                tenantId, User.ACCOUNT_TYPE_AGENT, String.valueOf(agentId));
        if (identity == null || identity.getId() == null) {
            log.warn("★兜底解析智能体虚拟身份失败：平台没有这个智能体的虚拟身份行"
                            + "（account_type='agent' 且 principal_ref_id=agentId）agentId={} tenantId={} userId={}"
                            + " — IM 侧首次以该智能体建会话时会铸造这一行，铸造过就有",
                    agentId, tenantId, currentUserId);
            return null;
        }
        Long agentVirtualUserId = identity.getId();
        if (!isKnownAgentContact(currentUserId, tenantId, agentVirtualUserId, traceId)) {
            return null;
        }
        log.info("★兜底解析成功：以智能体虚拟身份给用户发私聊 agentId={} agentVirtualUserId={} userId={}",
                agentId, agentVirtualUserId, currentUserId);
        return agentVirtualUserId;
    }

    /**
     * 从 AK 的 {@code targetId} 反解 agentId。<b>两种铸造形态都要认</b>，解不出返回 {@code null}。
     *
     * <p>Sandbox 型 AK 有两条铸造腿：{@code ChatApplicationServiceImpl} 写
     * {@code "{agentId}-{conversationId}"}（取第一个 '-' 之前），
     * {@code CustomPageChatFluxServiceImpl} 写<b>裸</b> {@code agentId}（取整体）。
     * 只判 {@code contains("-")} 会在无横杠那种形态上漏解。
     *
     * <p>★按样板用 {@code dash < 0} 而不是 {@code dash > 0}：串以 '-' 开头时截成空串、解析失败
     * ⇒ 落到 fail-closed，这正是想要的。<b>不许</b>在解不出时返回「不带 agentId 继续」——
     * 在本方法的调用方那里，那等于放大授权范围（拿别的智能体的身份发消息）。
     *
     * @return agentId；AK 缺失 / targetId 为空 / 前缀不是正整数时为 {@code null}
     */
    private static Long agentIdOfTarget(UserAccessKeyDto userAccessKey) {
        if (userAccessKey == null) {
            return null;
        }
        String targetId = userAccessKey.getTargetId();
        if (targetId == null || targetId.isBlank()) {
            return null;
        }
        int dash = targetId.indexOf('-');
        String raw = (dash < 0 ? targetId : targetId.substring(0, dash)).trim();
        try {
            long agentId = Long.parseLong(raw);
            return agentId > 0 ? agentId : null;
        } catch (NumberFormatException e) {
            log.warn("★AK 的 targetId 解不出 agentId，按『不知道是哪个智能体』处理（不猜、不回退）"
                            + " akId={} targetId={}",
                    userAccessKey.getId(), targetId);
            return null;
        }
    }

    /**
     * 这个智能体虚拟身份是不是<b>收件人</b>通讯录里「已互为联系人」的智能体。
     *
     * <p>三个条件缺一不可，理由见 {@link #resolveAgentVirtualUserId}：
     * {@code userId} 相等（就是这个身份）、{@code accepted()}（关系已建立，不是待同意的申请）、
     * {@code agent()}（IM 的注册表确实把它登记成智能体，不是「缺行被当真人」）。
     *
     * <p>★读取失败（IM 不可达 / 参数被拒 / 限流）按<b>未命中</b>处理而不是把异常抛给调用方：
     * 这一关是<b>兜底</b>路径上的辅助读取，它挂了不该让「发消息」变成一个 500 ——
     * 退回 {@code currentUserId} 之后仍由 IM 用 {@code IM_20106} 明确拒绝，行为与本改动之前一致。
     *
     * <p>★{@code truncated}（撞 500 上限）只削弱「没找到」这个结论，<b>不</b>削弱「找到了」：
     * 命中即采信，未命中且被截断时额外说明一句「不能证明不存在」，两者都 fail-closed。
     */
    private boolean isKnownAgentContact(Long ownerUserId, Long tenantId, Long agentVirtualUserId, String traceId) {
        ImContactListDto contacts;
        try {
            contacts = rpc("contacts/list", null, () -> imContactRpcService.listContacts(
                    ImContactListRequest.builder()
                            .actor(ownerUserId, tenantId, null)
                            .traceId(traceId)
                            .build()));
        } catch (BizException e) {
            log.warn("★通讯录校验没做成，按『未命中』处理（不切身份）ownerUserId={} agentVirtualUserId={} msg={}",
                    ownerUserId, agentVirtualUserId, e.getMessage());
            return false;
        }
        if (contacts == null) {
            log.warn("★通讯录校验返回空结果，按『未命中』处理 ownerUserId={} agentVirtualUserId={}",
                    ownerUserId, agentVirtualUserId);
            return false;
        }
        boolean hit = contacts.itemsOrEmpty().stream()
                .anyMatch(item -> agentVirtualUserId.equals(item.userId()) && item.accepted() && item.agent());
        if (!hit) {
            log.warn("★兜底解析智能体虚拟身份失败：IM 通讯录里查不到『已互为联系人』的这个智能体"
                            + "（要求 status=已同意 且 subjectType=智能体）ownerUserId={} agentVirtualUserId={}"
                            + " 通讯录条数={} 被截断={} 资料降级={} — 被截断时『查不到』不能证明不存在，"
                            + "但这里一律按未命中处理",
                    ownerUserId, agentVirtualUserId, contacts.size(), contacts.truncatedOrFalse(),
                    contacts.degradedOrFalse());
        }
        return hit;
    }

    /**
     * ★★群发「以谁的身份发」：这次 4.10 是<b>群会话</b>触发的就用智能体虚拟身份，是用户在单聊里
     * 要往群里发就用 {@code currentUserId()}。
     *
     * <p>判据是 AK {@code config.ext} 里<b>有没有</b> {@link #EXT_KEY_GROUP_VIRTUAL_USER_ID} 这个键
     * （{@code containsKey}，不是判值），理由与「为什么值为 null 也算群聊」见该常量的注释。
     *
     * <h2>两种取值的含义</h2>
     *
     * <ul>
     *   <li><b>有该键</b> ⟺ 本次调用由 IM 的群聊分支发起（智能体在群里被 @ / 被触发）⇒
     *       actor 必须是 IM 给的智能体虚拟身份，消息才会显示成"智能体在群里说话"。</li>
     *   <li><b>没有该键</b> ⟺ 用户在单聊过程中要发一条群消息（4.10 由私聊分支发起）⇒ 用
     *       {@code currentUserId()}，也就是这条 AK 的归属人本人。</li>
     * </ul>
     *
     * <h2>群聊分支读不到虚拟身份时：返回 null，不回退成 {@code currentUserId}</h2>
     *
     * <p>★先说清"回退"在这里<b>究竟是什么</b>，否则这条注释会读成"回退会挂到陌生人名下"——
     * 不是：群聊分支下 {@code currentUserId} 就是<b>群代理</b>（IM 的 4.10 里
     * {@code .userId(proxyUserId)}，与 {@code GROUP_VIRTUAL_USER_ID} <b>同值</b>，IM 侧注释写明这两个
     * 是"同一个值、刻意冗余"）。所以回退<b>不会发不出去</b>，只是这条消息会以
     * <b>群代理（真人段位，{@code sender_type=1}）</b>落库并发出去，而不是以智能体身份
     * （{@code sender_type=2}）—— 群成员看到的发送者从"这个智能体"变成"群代理"。
     *
     * <p>★仍然选 {@code null}（让 IM 以 {@code IM_90001}「userId 必填且必须为正数」大声拒掉），
     * 理由是<b>别把契约断裂伪装成一次成功发送</b>：这个状态按 IM 的 DEV-282 口径<b>不该存在</b>
     * （两个键由同一次 {@code variableParams} 一起送出，不存在"送了群键、漏了智能体键"的合法形态），
     * 真出现只能是有人手工造了 AK、或 IM 侧回退了版本 —— 这正是最需要被看见的时刻。
     * 回退成群代理会让它表现为"消息发出去了"，把一处跨仓契约漂移藏成一条看起来正常的消息；
     * 而 {@link #p2pActorUserId} 那边回退是安全的，因为它的回退结果<b>一定会被 IM 拒掉</b>
     * （{@code IM_20106}），不存在"看起来成功了"这种掩盖。
     *
     * <p>★这也是 {@link #toUserId} 已确立原则的同一取向：宁可走"读不到"分支（最终由 IM 拒掉），
     * 也不产出一条身份可疑的消息。若要改成回退（例如某次联调发现 IM 侧确实会出现半套键），
     * 改这一处 {@code return null} 即可，<b>但必须同时改掉本注释</b>，别留下"注释说不会回退、代码在回退"。
     *
     * <p><b>运维提示</b>：看到群发报 {@code IM_90001} 且日志里有下面这条 WARN，真因是
     * {@code AGENT_VIRTUAL_USER_ID} 没随 4.10 传下来（{@code variableParams} 侧的问题），
     * 不是调用方漏传 userId —— 两者的处置完全不同。
     *
     * @param currentUserId AK 归属人（= 平台登录态用户）。与 {@code p2pActorUserId} 同样由<b>调用方先取</b>，
     *                      本方法保持无副作用。
     * @param userAccessKey 本次 AK（登录态直连时为 null ⇒ 视作"没有群标记"⇒ 用户身份）
     * @return 真正要传给 IM 的 actor user id；群聊分支但虚拟身份读不到时为 {@code null}
     */
    private static Long groupActorUserId(Long currentUserId, UserAccessKeyDto userAccessKey) {
        Map<?, ?> extMap = extMapOf(userAccessKey);
        if (extMap == null || !extMap.containsKey(EXT_KEY_GROUP_VIRTUAL_USER_ID)) {
            return currentUserId;
        }
        Long agentVirtualUserId = agentVirtualUserId(userAccessKey);
        if (agentVirtualUserId == null) {
            log.warn("★群发起（ext 带 {}）但 AK 未带智能体虚拟身份，返回 null 让 IM 大声拒掉"
                            + "（预期 IM_90001）：回退成 {} 会把契约断裂伪装成一次正常发送（消息以群代理身份发出）"
                            + " userId={} akId={} — 先查 4.10 的 variableParams 是否漏了 {}",
                    EXT_KEY_GROUP_VIRTUAL_USER_ID, EXT_KEY_AGENT_VIRTUAL_USER_ID,
                    currentUserId, userAccessKey == null ? null : userAccessKey.getId(),
                    EXT_KEY_AGENT_VIRTUAL_USER_ID);
            return null;
        }
        log.info("★群发起 → actor 使用智能体虚拟身份 userId={} agentVirtualUserId={}",
                currentUserId, agentVirtualUserId);
        return agentVirtualUserId;
    }

    /**
     * ★★<b>查询 / 操作类端点</b>的 actor 解析：<b>群场景 → 智能体虚拟身份；私聊场景 → AK 归属人本人</b>。
     *
     * <p>服务 13 个端点：{@code /message/revoke|get|list|read-counts}、
     * {@code /conversation/create-group|p2p|digest|list}、{@code /conversation/members/add|remove|list}、
     * {@code /contact/list}、{@code /event/push}。
     * 发送类（{@code /message/send-card|send-system}）走 {@link #agentActorUserId}；
     * 两个真正的发送端点走 {@link #p2pActorUserId} / {@link #groupActorUserId}。
     *
     * <h2>判据：ext 里有没有 {@link #EXT_KEY_GROUP_VIRTUAL_USER_ID} ＝ 这次调用是不是群里发起的</h2>
     *
     * <p>该键只在 IM 的<b>群聊分支</b>被 put，私聊分支里整个不出现（见该常量的注释）
     * ⇒ 「键在不在」就是「群场景 / 私聊场景」本身。这与 {@link #groupActorUserId} 是同一套判据 ——
     * IM 的契约本就写明「智能体用『键存在与否』判断是不是群聊」，本类不另立第二套。
     *
     * <h2>一、群场景：必须用智能体<b>在那个群里</b>的身份</h2>
     *
     * <p>IM 侧每一个 <b>convId 作用域</b>的端点，进门第一件事就是
     * {@code requireActiveMember(convId, selfId)}（{@code ImMessageApplicationServiceImpl} 14 处、
     * {@code ImConversationApplicationServiceImpl} 20 余处、{@code ImReadStateApplicationServiceImpl} 4 处）。
     * 而 IM 群聊分支的 4.10 传给平台的 {@code userId} 是<b>群代理</b>
     * （{@code ImAgentChatService} 的 {@code .userId(proxyUserId)}），它按 IM 自己的决策
     * <b>「不进群、不是成员」</b>（{@code ImSubjectRegistry#subjectIdForGroup} 的方法 javadoc，DEV-166 决策 ②③）。
     *
     * <p>⇒ 群触发链路上，凡拿 {@code currentUserId()} 当 actor 的端点都会被 IM 以
     * <b>{@code NOT_CONV_MEMBER}「非会话成员」</b>拒掉 —— 撤回、群消息查询
     * （{@code messages/get} / {@code messages/list} / {@code messages/read-counts}）、
     * 群员查询（{@code conversations/members/list}）一个不落。这不是权限不够，是<b>身份选错了</b>：
     * 那个群对这个智能体合法，对群代理不合法。
     *
     * <p>★换成虚拟身份后语义反而更收敛：撤回 = 「撤回本智能体自己发的」，
     * 查询 = 「查本智能体参与过的会话」—— 群代理本就够不着那些会话。
     *
     * <h2>二、私聊场景：以用户本人行使，<b>不</b>切虚拟身份（管控口径，2026-09-23 定）</h2>
     *
     * <p>私聊场景的语义是「用户本人在跟智能体说话」，智能体的每个动作都应该落在
     * <b>这个用户在 IM 里能看到的那块资源边界内</b>。这条<b>不是</b>为了通过权限校验，
     * 而是为了让越权面可审计 —— 私聊场景若也切虚拟身份：
     *
     * <ul>
     *   <li>{@code /contact/list} 返回的是<b>智能体自己</b>作为独立 IM 账号的通讯录，
     *       {@code /conversation/list} 是<b>它自己的</b>会话列表 —— 那是用户在界面上
     *       <b>看不到、也管不着</b>的一块独立资源，用户无从审计智能体到底读到了谁；</li>
     *   <li>以本人身份行使时，智能体能读到的恰好是<b>用户自己能看到的东西</b>：
     *       边界与用户打开 IM 客户端所见完全一致，多一条都读不到。</li>
     * </ul>
     *
     * <p>★代价（已知且接受）：私聊场景下智能体<b>自己发的</b>消息撤不掉。
     * 私聊回话走 {@link #p2pActorUserId} 的「收件人=自己」分支 ⇒ 那条消息的 sender 是虚拟身份，
     * 而本方法在私聊场景返回本人 ⇒ IM 的 {@code canRevoke(selfId)} 判 {@code senderId != selfId}
     * ⇒ {@code NO_PERMISSION}。这是「管控优先于能力」的取舍：以本人名义能撤的（用户自己发的）
     * 全部能撤；撤不了的那类，用户在 IM 客户端里本来就能自己撤。
     *
     * <h2>三、群场景却读不到虚拟身份时：回退本人（与 {@link #groupActorUserId} 的 null 策略刻意不同）</h2>
     *
     * <p>{@code groupActorUserId} 读不到值时返回 {@code null}，因为那是<b>发送</b>：
     * 回退会用群代理名义发出一条<b>看起来正常</b>的消息，把跨仓契约断裂伪装成成功。
     * 本方法服务的端点没有这个后果 —— 回退到 {@code currentUserId()} 在群链路上会立刻撞上
     * IM 的 {@code NOT_CONV_MEMBER}（大声失败，不会静默成功），
     * 在私聊链路与手工 AK 链路上则本来就是正确的身份。故退回本人，真因由
     * {@link #agentVirtualUserId} 记 WARN。
     *
     * <p>★{@code /event/push} 也走本方法：它没有成员校验（{@code actor} 只用于 {@code runAs} 与日志），
     * 身份不影响授权；跟着统一只是为了让「actor 从哪来」在整份文件里只有一句话。
     *
     * @param currentUserId AK 归属人（= 平台登录态用户）。与另外三个 actor 方法同样由<b>调用方先取</b>，
     *                      本方法保持无副作用，顺带让"未登录"这个判断发生在读 AK 之前。
     * @param userAccessKey 本次 AK（登录态直连时为 null ⇒ 没有群标记 ⇒ 私聊场景 ⇒ 本人）
     * @return 真正要传给 IM 的 actor user id
     */
    private static Long scenarioActorUserId(Long currentUserId, UserAccessKeyDto userAccessKey) {
        Map<?, ?> extMap = extMapOf(userAccessKey);
        if (extMap == null || !extMap.containsKey(EXT_KEY_GROUP_VIRTUAL_USER_ID)) {
            // 私聊场景 / 手工 AK / 登录态直连：以用户本人行使（管控口径，见 javadoc 二）
            return currentUserId;
        }
        Long agentVirtualUserId = agentVirtualUserId(userAccessKey);
        if (agentVirtualUserId == null) {
            log.warn("★群场景（ext 带 {}）但 AK 未带智能体虚拟身份，回退本人"
                            + "（本人若不在群里 → IM 判 NOT_CONV_MEMBER，大声失败且指向真实病因）"
                            + " userId={} akId={} — 先查 4.10 的 variableParams 是否漏了 {}",
                    EXT_KEY_GROUP_VIRTUAL_USER_ID, currentUserId,
                    userAccessKey == null ? null : userAccessKey.getId(),
                    EXT_KEY_AGENT_VIRTUAL_USER_ID);
            return currentUserId;
        }
        log.info("★群场景 → actor 使用智能体虚拟身份 userId={} agentVirtualUserId={}",
                currentUserId, agentVirtualUserId);
        return agentVirtualUserId;
    }

    /**
     * ★<b>发送类</b>端点（{@code /message/send-card}、{@code /message/send-system}）的 actor 解析：
     * AK 声明了智能体虚拟身份就以它行使，否则是 AK 归属人。
     *
     * <h2>为什么不跟着 {@link #scenarioActorUserId} 一起按群 / 私聊分流</h2>
     *
     * <p>因为对 {@code send-card} 而言，按场景回退会<b>发错人</b>：卡片的 convId 只能来自
     * 本控制器里以 {@code scenarioActorUserId} 行使的那些端点 ⇒ 那些会话都是<b>智能体自己参与的</b>
     * （含「智能体↔主人」那条私聊）。私聊场景下若回退本人，往「智能体↔主人」发卡片
     * 就变成<b>主人自己发的消息</b> —— 会话里凭空多出一条他没发过的话。
     * 详见 {@code sendCard} 方法上方的注释（那里记着这条规则被改错过一次）。
     *
     * <p>{@code send-system} 同理，且它有 {@link #requireAdmin()} 闸门，管理员 AK 一般不带 ext
     * ⇒ 实际取到的就是本人。
     *
     * @param currentUserId AK 归属人（= 平台登录态用户）
     * @param userAccessKey 本次 AK（登录态直连时为 null ⇒ 没有虚拟身份 ⇒ 本人）
     * @return 真正要传给 IM 的 actor user id
     */
    private static Long agentActorUserId(Long currentUserId, UserAccessKeyDto userAccessKey) {
        Long agentVirtualUserId = agentVirtualUserId(userAccessKey);
        if (agentVirtualUserId == null) {
            return currentUserId;
        }
        log.info("★发送类端点：AK 携带智能体虚拟身份 → 以它行使 userId={} agentVirtualUserId={}",
                currentUserId, agentVirtualUserId);
        return agentVirtualUserId;
    }

    /**
     * ★★排队区端点（{@code /agent/pending/list}、{@code /agent/pending/clear}）专用：
     * actor <b>只能是智能体主体自己</b>，读不到就<b>本地 fail-closed</b>。
     *
     * <h2>为什么不能复用 {@link #scenarioActorUserId}</h2>
     *
     * <p>那个方法的私聊分支会返回<b>本人</b>、手工 AK / 登录态直连时也返回本人 ——
     * 而本组端点服务的 IM 契约<b>不接受真人主体</b>：它判的是
     * 「这个 {@code userId} 是不是已登记的<b>智能体主体</b>」（IM 侧 {@code isAgentStrict}，
     * 三态 fail-closed：查不到就是拒，<b>绝不</b>折叠成放行），
     * 且队列键 {@code agent_subject_id} <b>就是</b> {@code userId}
     * （DEV-283「把智能体当人」：智能体虚拟 id 与主体 id 同一数值，不另设字段）。
     *
     * <p>所以这里没有「按场景分流」可言 —— 群 / 私聊两个场景下 actor 都一样。
     * 真正要防的是「静默回退本人」：本人身份在本端点<b>本来就不被接受</b>，
     * 回退的净效果是把一个<b>精确的本地错误</b>（「你的 AK 没带智能体虚拟身份」）
     * 换成一个<b>含糊的远端错误</b>（IM 侧的主体校验失败），还白跑一次 RPC。
     * 那不是「权限收紧」而是<b>契约本就不成立</b>，故 fail-closed 并给出精确原因。
     *
     * <p>★错误码沿用本文件其它闸门的 {@code 4030}（{@code PERMISSION_DENIED}）：
     * 调用方据此知道「不是我没登录，是这个端点不认我的身份」，
     * 而正确的修法是换一条携带智能体虚拟身份的 AK（即由 IM 触发的 4.10 链路），
     * 不是去改请求参数 —— 请求体里压根没有能改身份的地方。
     *
     * <p>★<b>本方法不校验 convId</b> —— 它只回答「以谁的身份行使」，签名里根本没有 convId。
     * 排队区那两个端点还必须在调用点上过一次闸门③（{@link #requirePendingConvIsTriggerConv}），
     * 「只能碰本次触发的那个会话」是在那里钉死的（本方法管身份，那道管范围，两件事不可互相替代）。
     *
     * @param currentUserId AK 归属人（= 平台登录态用户）。只用于日志：出问题时最需要知道的是
     *                      「谁的 AK 没带虚拟身份」，与 {@link #agentVirtualUserId} 的 WARN 同一条线索
     * @param userAccessKey 本次 AK（登录态直连时为 null ⇒ 没有虚拟身份 ⇒ 拒）
     * @return 要传给 IM 的 actor user id —— 恒为智能体虚拟身份，绝不返回 {@code currentUserId}
     */
    private static Long agentSubjectUserId(Long currentUserId, UserAccessKeyDto userAccessKey) {
        Long agentVirtualUserId = agentVirtualUserId(userAccessKey);
        if (agentVirtualUserId == null) {
            log.warn("★排队区端点要求智能体主体身份，但本次 AK 未携带 {}，已拒绝"
                            + " userId={} akId={} — 该端点由 IM 触发的 4.10 链路调用，"
                            + "手工 AK / 登录态直连拿不出智能体虚拟身份，换 AK 而不是改参数",
                    EXT_KEY_AGENT_VIRTUAL_USER_ID, currentUserId,
                    userAccessKey == null ? null : userAccessKey.getId());
            return currentUserId;
        }
        // ★convId 的钉死不在这里 —— 本方法只回答「以谁的身份行使」，根本拿不到 convId；
        //   那道校验由闸门③（requirePendingConvIsTriggerConv）在两个调用点上各做一次，
        //   紧跟 require(convId) 之后。这里曾挂过一版 TODO「校验 GROUP_SENDER_ID 在不在
        //   发送消息的群里」，那条路走不通（IM 无单点成员判定接口），
        //   完整论证记在闸门③的 javadoc 里。
        log.info("★排队区端点：以智能体虚拟身份行使 userId={} agentVirtualUserId={}",
                currentUserId, agentVirtualUserId);
        return agentVirtualUserId;
    }

    /**
     * ★★{@code /message/revoke} 专用：在 {@link #scenarioActorUserId} 的基础上，再看一眼
     * <b>这条消息当初是谁发的</b>，从而让「撤回自己说的话」两类情形都能成立。
     *
     * <h2>为什么必须多这一步</h2>
     *
     * <p>IM 的 {@code Message.canRevoke} 判据是 {@code senderId == operatorId} 或本会话管理员，
     * 二者<b>统一受 {@code im.revoke.window-sec} 约束</b>（DEV-207 起默认 {@code 86400} = 24h；
     * 管理员<b>不</b>免除时限 —— 原来的 {@code 120s} 与「管理员不限时」两种说法均已作废）——
     * <b>operator 必须恰好是发送者</b>，而<b>一次调用只能带一个 operator</b>。
     * 私聊场景里存在两个不同的发送者：
     *
     * <ul>
     *   <li>用户在跟智能体对话时让智能体「撤回我刚给张三发的那条」⇒ 那条消息的发送者是<b>本人</b>，
     *       operator 也必须是本人（本人是那个会话的成员，且限时内）；</li>
     *   <li>智能体自己在这条私聊里的回话（走 {@link #p2pActorUserId} 的「收件人=自己」分支
     *       ⇒ 发送者是<b>智能体虚拟身份</b>）⇒ operator 必须换成虚拟身份。</li>
     * </ul>
     *
     * <p>不回读就没有任何一种 operator 能同时撤掉这两类；回读之后两类都覆盖，
     * 而<b>权限没有被放大</b>：operator 仍然只会是「本人 / 智能体虚拟身份」二者之一，
     * IM 侧的成员校验与 {@code senderId == operatorId} <b>每次都判</b>。
     * 能撤的集合恰好是「本人发的」∪「这个智能体自己发的」，与「撤回自己说过的话」等价。
     *
     * <h2>群场景不多读这一次</h2>
     *
     * <p>群场景下 {@link #scenarioActorUserId} 返回的<b>就是</b> {@code AGENT_VIRTUAL_USER_ID}
     * （`agentSubjectId`），与 {@link #agentVirtualUserId} 同值 ⇒ 没有歧义可言，
     * 直接返回、不产生额外 RPC。只有私聊场景（前者=本人、后者=虚拟身份）才需要回读。
     *
     * <h2>回读用哪个身份</h2>
     *
     * <p>用 {@link #scenarioActorUserId} —— 它是调用方在当前场景下<b>本来就有权使用</b>的身份，
     * 私聊场景下本人看得见自己的会话，群场景下虚拟身份看得见群消息。用它读回来的发送者不可信
     * 也无害：它只决定「换成谁去撤」，换错方向 IM 会在 {@code canRevoke} 那一关拒掉。
     *
     * @param currentUserId AK 归属人（= 平台登录态用户）
     * @param userAccessKey 本次 AK（登录态直连时为 null ⇒ 没有虚拟身份 ⇒ 直接用场景身份）
     * @param convId        目标会话（回读用）
     * @param msgId         目标消息（回读用）
     * @param traceId       透传的链路 id（回读用，与 revoke 共用同一次调用链）
     * @return 真正要传给 IM 的 actor user id
     */
    private Long revokeActorUserId(Long currentUserId, UserAccessKeyDto userAccessKey,
                                   Long convId, Long msgId, String traceId) {
        Long scenarioActor = scenarioActorUserId(currentUserId, userAccessKey);
        Long agentVirtualUserId = agentVirtualUserId(userAccessKey);
        if (agentVirtualUserId == null || agentVirtualUserId.equals(scenarioActor)) {
            // 没有虚拟身份（手工 AK / 登录态直连），或群场景（两者同值）⇒ 无歧义，不多这一次 RPC。
            return scenarioActor;
        }
        ImMessageDetailDto target = rpc("messages/get", null, () -> imMessageRpcService.getMessage(
                ImMessageGetRequest.builder()
                        .actor(scenarioActor, currentTenantId(), null)
                        .traceId(traceId)
                        .convId(convId)
                        .msgId(msgId)
                        .build()));
        if (target != null && agentVirtualUserId.equals(target.senderId())) {
            log.info("★私聊场景 / 目标消息是智能体自己发的（senderId={}）→ actor 切换为虚拟身份"
                            + " convId={} msgId={} userId={}",
                    target.senderId(), convId, msgId, currentUserId);
            return agentVirtualUserId;
        }
        log.info("★私聊场景 / 目标消息不是智能体发的（senderId={}）→ actor 保持本人"
                        + " convId={} msgId={} userId={}",
                target == null ? null : target.senderId(), convId, msgId, currentUserId);
        return scenarioActor;
    }

    /**
     * 取 AK 的 {@code config.ext}，按 Map 返回；<b>不是</b> Map（或压根没 AK / 没 config）时为 {@code null}。
     *
     * <p>★两个身份键（{@code AGENT_VIRTUAL_USER_ID} / {@code GROUP_VIRTUAL_USER_ID}）共用这一个入口：
     * ext 的取法、{@code instanceof} 判空这两件事只写一遍，否则将来某人给 ext 换个容器类型时，
     * 只会改到其中一处，另一处退回"静默读不到"。
     *
     * @return ext 映射；不可用时 {@code null}（调用方一律按"读不到"处理，不猜默认值）
     */
    private static Map<?, ?> extMapOf(UserAccessKeyDto userAccessKey) {
        if (userAccessKey == null || userAccessKey.getConfig() == null) {
            return null;
        }
        Object ext = userAccessKey.getConfig().getExt();
        return ext instanceof Map<?, ?> extMap ? extMap : null;
    }

    /**
     * 从 AK 配置里读智能体虚拟身份（键见 {@link #EXT_KEY_AGENT_VIRTUAL_USER_ID}）。
     *
     * <p>★只读 {@code config.ext} 这一处（取法见 {@link #extMapOf}）。{@code UserAccessKeyConfig} 里另有
     * 一个同义的类型化字段 {@code agentVirtualUserId}（注释「智能体拟人化虚拟用户ID」），但<b>全仓
     * 没有任何地方给它赋值</b> —— IM 链路实际填的是 ext 映射。这里不去读那个字段：它会变成第二个数据源，
     * 读起来像"两处都能配"，实际只有一处是真的。
     */
    private static Long agentVirtualUserId(UserAccessKeyDto userAccessKey) {
        Map<?, ?> extMap = extMapOf(userAccessKey);
        if (extMap == null) {
            return null;
        }
        Object raw = extMap.get(EXT_KEY_AGENT_VIRTUAL_USER_ID);
        if (raw == null) {
            return null;
        }
        Long value = toUserId(raw);
        if (value == null) {
            log.warn("★AK ext 里的 {} 不是合法用户 ID，已忽略（type={} value={}）—— "
                            + "按『读不到』处理，不会静默用错身份发消息",
                    EXT_KEY_AGENT_VIRTUAL_USER_ID, raw.getClass().getSimpleName(), raw);
        }
        return value;
    }

    /**
     * 把 ext 里的值转成用户 ID。
     *
     * <p>★要按「JSON 反序列化会给出什么 Java 类型」来写，不能只认 {@link Long}：
     * {@code ext} 的声明类型是 {@code Object}，Jackson 按 JSON 字面量在
     * {@code Integer} / {@code Long} / {@code BigInteger} 之间自行选型
     * （{@code user_access_key.config} 由 {@code JsonTypeHandler} 通用反序列化回来）
     * ⇒ 只判 {@code instanceof Long} 会在值较小时（反序列化成 {@code Integer}）<b>静默取不到</b>，
     * 而症状只是"偶尔不切换身份"。
     *
     * <p>★浮点类型（{@code Double/Float/BigDecimal}）一律不认，也<b>不做</b> {@code longValue()} 截断：
     * 截断后的数字是<b>另一个人的</b> id，而这条链路决定的正是"以谁的身份把消息发出去"。
     * 宁可走"读不到"分支（最终由 IM 拒掉），也不产出一条身份可疑的消息。
     */
    private static Long toUserId(Object raw) {
        if (raw instanceof Number number
                && !(number instanceof Double) && !(number instanceof Float) && !(number instanceof BigDecimal)) {
            long value = number.longValue();
            return value > 0 ? value : null;
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                long value = Long.parseLong(text.trim());
                return value > 0 ? value : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 幂等键：调用方给了就用（重试场景必须由调用方给），没给则生成一个并回显。
     */
    private static String bizMsgId(String given) {
        if (given != null && !given.isBlank()) {
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
        if (value == null || value.isBlank()) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, name);
        }
    }

    /**
     * 消息体校验。
     *
     * <p>★不能复用 {@link #require(Object, String)}：JSON 里的 {@code "body": null} 经 Jackson
     * 反序列化是 {@link com.fasterxml.jackson.databind.node.NullNode}，<b>不是</b> Java 的 {@code null}。
     * 只判 {@code == null} 会放它过去，最后变成 IM 侧一条「消息体为空」的 {@code IM_90001} ——
     * 错误发生在最远端，排查成本最高。
     */
    private static void requireJson(JsonNode value, String name) {
        if (value == null || value.isNull()) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, name);
        }
    }

    /**
     * 调 IM 并把它自己的异常翻译成平台业务异常。
     *
     * <p>翻译规则只有三条，对应 {@link ImRpcException} 的两种分类：
     * <pre>
     *   请求根本没发出去（isLocalProblem）  → 3612  IM 服务未配置或已停用，请联系管理员   （重试无用，去查配置）
     *   上游暂时性故障（isRetryable）      → 3613  IM 服务暂时不可用，请稍后重试          （可带同一 bizMsgId 重试）
     *   其余（上游业务拒绝）               → 3614  IM 拒绝了本次操作: &lt;原文&gt;            （重试无用，改请求）
     * </pre>
     *
     * <p>★判断顺序不能反：{@code isLocalProblem} 的那几种在码值上与「网络不可达」长得几乎一样
     * （都是 {@code 0001} + 无 HTTP 状态），先判 retryable 会把「secret 没配」报成「IM 抖了，稍后重试」——
     * 用户重试一百次也不会好，而真正该看日志的人（运维）不会收到任何信号。
     *
     * <p>日志只打码 / tid / 可重试性：异常 message 里可能有上游原文，但<b>不会有</b> secret、签名或请求体。
     */
    private static BizException translate(String op, String bizMsgId, ImRpcException e) {
        // ★打 bizMsgId 是为了让「用户说没收到、日志说发了」这类争论能一句话收口：
        //   拿它去 IM 侧查这次调用，命中幂等说明是重放，没命中说明确实发过。
        log.warn("Nuwax IM RPC 调用失败 op={} bizMsgId={} code={} tid={} retryable={} local={} http={} msg={}",
                op, bizMsgId, e.getCode(), e.getTid(), e.isRetryable(), e.isLocalProblem(), e.getHttpStatus(),
                e.getMessage());
        // ★code 必须保留 3612 / 3613 / 3614 这三个枚举各自的值，【不要】用
        //   BizException.of(ErrorCodeEnum.SYS_ERROR, ...) 包一层：那样三者的 code 全变成 5000，
        //   调用方（技能/Agent）就只剩 message 文案可读——而它恰恰是要靠 code 决定
        //   「要不要带着同一个 bizMsgId 重试」的那一方。把可重试与不可重试揉成一个码，
        //   等于把这个判断推给「读中文文案猜」。
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
