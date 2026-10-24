package com.xspaceagi.connector.sdk;

import com.xspaceagi.connector.sdk.dto.ConnectorActionGuide;
import com.xspaceagi.connector.sdk.dto.ConnectorActionInfo;
import com.xspaceagi.connector.sdk.dto.ConnectorExecResult;
import com.xspaceagi.connector.sdk.dto.ConnectorProviderInfo;

import java.util.List;
import java.util.Map;

/**
 * 连接器对外 SDK 门面（供 Agent 运行时 / 其它平台模块调用）。
 * <p>
 * 发现型元工具三件套对应：searchActions(connector_search)、guideAction(connector_guide)、
 * execute(connector_execute)。所有方法绝不返回凭证，只返回目录、profile 与执行结果。
 * <p>
 * execute 依赖平台 RequestContext（调用方需在已建立的请求/会话上下文中调用）。
 */
public interface IConnectorApiService {

    /**
     * 用户可见的连接器列表（官方 + 本空间自建），带已连接标记。
     */
    List<ConnectorProviderInfo> listProviders(Long tenantId, Long spaceId, Long userId, String keyword);

    /**
     * 用户**已连接且已打开**的连接器列表（官方 + 本空间自建中，用户当前真的能用的那些）。
     * <p>
     * 判定口径是「现在能不能跑」，要求同时成立：连接存在、用户开关是打开的（{@code enabled}）、
     * 凭证健康（{@code status = 'active'}）。与另外两个「已连接」不是一回事，别互相替代：
     * <ul>
     *   <li>{@code listProviders} 回来的 {@code connected} 问的是「有没有建立过连接」
     *       ——被用户停用的也算，用于**展示**全套连接器；</li>
     *   <li>目录侧 HTTP 的 {@code connectionEnabled} 问的是「用户开关打开没」
     *       ——凭证过期也算，用于**渲染开关**；</li>
     *   <li>本方法问的是「能不能立刻拿去执行」——用于让调用方直接拿这份名单去调
     *       {@link #execute}，而不必先试一次才知道凭证已经过期。</li>
     * </ul>
     * 因此本方法返回的每一项，其 {@code connected} 恒为 {@code true}。
     * 无需凭证的连接器（{@code no_auth}）不会出现在结果里：它们本来就不需要连接，
     * 直接执行即可，调用方应走 {@code listProviders}。
     *
     * @param spaceId 可见范围，可空（空则不限空间）
     */
    List<ConnectorProviderInfo> listExecutableProviders(Long tenantId, Long spaceId, Long userId);

    /**
     * 用户**已启用**的连接器列表（官方 + 用户全部相关空间中，用户开关打开了的那些）。
     * <p>
     * 判定口径只有一条：存在该 provider 的连接行且用户开关是打开的（{@code enabled = 1}），
     * **不掺凭证健康度**——与目录侧 HTTP 的 {@code connectionEnabled=true} 完全同口径，
     * 「启用了但 token 已过期」照样算已启用（真去执行才会拿到 {@code connection_expired}）。
     * 三个近邻问题各问各的，别互相替代：
     * <ul>
     *   <li>{@code listProviders} 回来的 {@code connected} 用于**展示**全套连接器；</li>
     *   <li>本方法问的是「用户表达了要用哪些」——用于让调用方把这份名单交给模型；
     *       凭证过期与否由执行时的错误码回答，不在名单里替用户做决定；</li>
     *   <li>{@link #listExecutableProviders} 更严，问的是「现在能不能立刻跑」
     *       ——要从名单里挑一个去调 {@link #execute} 时用它。</li>
     * </ul>
     * <p>
     * 可见范围 = 官方目录（{@code space_id = 0}）+ **当前用户有权限的全部空间**的自建连接器，
     * 与目录页 {@code GET /api/connector/providers} 不传 {@code spaceId} 时同一算法
     * （空间列表取自 {@code SpacePermissionService#querySpaceIdList}，与调用方自报无关）。
     * 空间范围**刻意不由调用方指定**：会话可能压根没挂空间（{@code dev_space_id} 为空），
     * 若让调用方自报，这类会话会悄悄退化成只剩官方目录，用户已启用的空间自建连接器就漏掉了。
     * <p>
     * 因此本方法返回的每一项，其 {@code connected} 恒为 {@code true}。
     *
     * @param userId 当前用户：既决定「已启用」名单，也决定空间可见范围；为空则返回空列表
     */
    List<ConnectorProviderInfo> listEnabledProviders(Long tenantId, Long userId);

    /**
     * 跨连接器检索可用动作（发现型元工具 connector_search）。
     *
     * @param keyword 名称/key/描述模糊词，可空
     * @param tag     能力域标签，可空
     * @param limit   上限，缺省 20、最大 50
     */
    List<ConnectorActionInfo> searchActions(Long tenantId, Long spaceId, Long userId,
                                            String keyword, String tag, Integer limit);

    /**
     * 动作使用指南（发现型元工具 connector_guide）。
     */
    ConnectorActionGuide guideAction(Long tenantId, Long userId, String providerService, String actionKey);

    /**
     * 执行动作（发现型元工具 connector_execute）。
     *
     * @param connectionId 指定连接，可空（自动取用户该 provider 第一个 active 连接）
     * @param agentId      来源 agent，可空
     */
    ConnectorExecResult execute(Long tenantId, Long userId, String providerService, String actionKey,
                                Long connectionId, Map<String, Object> args, Long agentId);

    /**
     * 用户对该 provider 是否有可用连接。
     */
    boolean hasActiveConnection(Long tenantId, Long userId, String providerService);
}
