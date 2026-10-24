package com.xspaceagi.connector.domain.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xspaceagi.connector.domain.command.ActionCommand;
import com.xspaceagi.connector.domain.command.ProviderCommand;
import com.xspaceagi.connector.domain.entity.ConnectorAction;
import com.xspaceagi.connector.domain.entity.ConnectorProvider;

import java.util.Collection;
import java.util.List;

/**
 * Catalog 服务（provider + action 两表，设计文档 §4.1）。
 */
public interface ConnectorCatalogService {

    // ---------- 查询 ----------

    /**
     * 分页查询可见 provider。
     * <p>
     * 排序固定为「我的连接优先」四档：可用 → 已启用但凭证异常 → 已连接未启用 → 未连接，
     * 档内按目录权重 sort_order, id。排序在 SQL 内完成（分页使然，见 repository 注释）。
     *
     * @param userId                  当前用户：其连接决定排序档位；null 退回纯权重序
     * @param connectedFilter         "connected"/"unconnected"（基于用户连接集合过滤），null 不过滤
     * @param connectedServices       用户已建立连接的 service 集合（connectedFilter 判定依据）
     * @param connectionEnabledFilter "enabled"/"disabled"（基于用户已启用的连接集合过滤），null 不过滤
     * @param enabledServices         用户已启用（连接行 enabled=1）的 service 集合（connectionEnabledFilter 判定依据）
     * @param spaceIds                空间范围（调用方已按用户权限解析）：单元素=该空间；多元素=用户全部空间；空=无空间自建可见
     * @param scope                   "official"=仅官方目录；"space"=仅这些空间的自建（含 disabled，供空间端管理）；
     *                                null/其他=官方(恒 enabled) + 这些空间自建（按 status 过滤）
     * @param status                  "all"/null=不过滤；"enabled"/"disabled"=按状态过滤（space 模式缺省 all）
     */
    IPage<ConnectorProvider> pageProviders(Long tenantId, Long userId, Collection<Long> spaceIds, String category,
                                           String tag, String keyword, String connectedFilter,
                                           Collection<String> connectedServices,
                                           String connectionEnabledFilter,
                                           Collection<String> enabledServices,
                                           String scope, String status, long pageNum, long pageSize);

    List<ConnectorProvider> listVisibleProviders(Long tenantId, Long spaceId);

    /**
     * 总览：当前用户全部可见 provider（官方 enabled + 我有权限空间的自建 provider），
     * 不分页全量返回，供总览页前端分组排序。排序同 {@link #pageProviders}（我的连接优先四档）。
     *
     * @param userId   当前用户（排序档位依据其连接）；null 退回纯权重序
     * @param spaceIds 用户有权限的空间 id 集合（为空则仅官方）
     * @param status   空间自建连接器状态过滤（"enabled"/"disabled"/"all"；官方恒 enabled）
     * @param scope    "official"=仅官方，"space"=仅空间自建，其余=官方+空间自建
     */
    List<ConnectorProvider> listProvidersForOverview(Long tenantId, Long userId, Collection<Long> spaceIds,
                                                     String status, String scope);

    /**
     * 取 provider，不存在抛 PROVIDER_NOT_FOUND。
     */
    ConnectorProvider requireProvider(String service);

    List<ConnectorAction> listActions(String providerService, String tag, String keyword, boolean includeDeprecated);

    ConnectorAction requireAction(Long actionId);

    ConnectorAction requireActionByKey(String providerService, String actionKey);

    // ---------- 用户/空间侧创建（§4.8：仅声明式，managed_by=user） ----------

    ConnectorProvider createProvider(Long tenantId, Long spaceId, ProviderCommand command);

    /**
     * 管理侧创建官方全局 provider（space_id=0、managed_by=system、全员可见）。
     * 仅供管理侧接口（/api/system/connector）创建官方 provider 使用。
     */
    ConnectorProvider createSystemProvider(Long tenantId, ProviderCommand command);

    /**
     * 编辑即进手工保护区（managed_by=user）。
     */
    ConnectorProvider updateProvider(String service, ProviderCommand command);

    /**
     * 官方目录维护：字段更新与 updateProvider 一致，但保持 managed_by=system（不置 user）。
     * 仅供管理侧接口（/api/system/connector）维护官方 provider 使用。
     */
    ConnectorProvider updateSystemProvider(String service, ProviderCommand command);

    void deleteProvider(String service);

    ConnectorAction saveAction(ActionCommand command);

    void deleteAction(Long actionId);

    // ---------- 治理（管理侧） ----------

    void setProviderStatus(String service, boolean enabled);

    void setActionStatus(Long actionId, boolean enabled);

    void setActionDeprecated(Long actionId, boolean deprecated);
}
