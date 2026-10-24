package com.xspaceagi.connector.infra.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.connector.domain.entity.ConnectorProvider;
import com.xspaceagi.connector.infra.dao.mapper.ConnectorProviderMapper;
import com.xspaceagi.connector.infra.tenant.ConnectorTenantSupport;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * provider 目录存取。
 * <p>
 * 可见性规则（设计文档 §4.8）：官方目录 space_id=0 全局可见；
 * 空间自建目录仅本租户本空间可见。租户拦截器豁免，条件在此显式表达。
 */
@Service
public class ConnectorProviderRepository extends ServiceImpl<ConnectorProviderMapper, ConnectorProvider> {

    private static final long OFFICIAL_SPACE_ID = 0L;

    /**
     * 分页查询当前租户/空间可见的 provider。
     *
     * @param connectedFilter        "connected"/"unconnected" 基于用户连接集合过滤；null/"all" 不过滤
     * @param connectedServices      用户已建立连接的 service 集合（connectedFilter 判定依据）
     * @param connectionEnabledFilter "enabled"/"disabled" 基于用户**启用了的**连接集合过滤；null 不过滤
     * @param enabledServices        用户已启用（连接行 enabled=1）的 service 集合（connectionEnabledFilter 判定依据）
     * @param spaceIds               空间范围：单元素=该空间视图；多元素=用户全部空间（调用方按用户空间权限解析，
     *                               见 ConnectorCatalogController#listProviders）；空=无空间自建可见
     * @param scope                  "official"=仅官方目录（space_id=0，不依赖 spaceIds）；"space"=仅这些空间的自建（含 disabled，空间端「我的连接器」管理视图）；
     *                               null/其他=官方(恒 enabled) + 这些空间自建（status 过滤，缺省 enabled）
     * @param status                 "all"/null=不过滤；"enabled"/"disabled"=按状态过滤
     */
    public IPage<ConnectorProvider> pageVisible(Long tenantId, Long userId, Collection<Long> spaceIds, String category,
                                                String tag, String keyword, String connectedFilter,
                                                Collection<String> connectedServices,
                                                String connectionEnabledFilter,
                                                Collection<String> enabledServices,
                                                String scope, String status,
                                                long pageNum, long pageSize) {
        return ConnectorTenantSupport.withoutTenantFilter(() -> {
            LambdaQueryWrapper<ConnectorProvider> wrapper =
                    visibleWrapper(tenantId, spaceIds, scope, status);
            wrapper.eq(StringUtils.isNotBlank(category), ConnectorProvider::getCategory, category)
                    .like(StringUtils.isNotBlank(tag), ConnectorProvider::getTags, "\"" + tag + "\"")
                    .and(StringUtils.isNotBlank(keyword), w -> w
                            .like(ConnectorProvider::getDisplayName, keyword)
                            .or().like(ConnectorProvider::getService, keyword));
            if ("connected".equals(connectedFilter)) {
                filterByServices(wrapper, connectedServices, true);
            } else if ("unconnected".equals(connectedFilter)) {
                filterByServices(wrapper, connectedServices, false);
            }
            if ("enabled".equals(connectionEnabledFilter)) {
                filterByServices(wrapper, enabledServices, true);
            } else if ("disabled".equals(connectionEnabledFilter)) {
                filterByServices(wrapper, enabledServices, false);
            }
            orderByConnectionFirst(wrapper, tenantId, userId);
            return baseMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        });
    }

    /**
     * 按 service 集合做 in / notIn 过滤，正反向的空集合语义不同。
     * <p>
     * 正向（只看这些）在集合为空时必须是**空结果**——但 MyBatis-Plus 的 {@code in(condition=false, ...)}
     * 会把条件整条跳过、退化成全量，正好反了。这里用恒假条件顶替（与 {@link #visibleWrapper} 同法，
     * 也避免 {@code in ()} 语法错）。反向（排除这些）在集合为空时本就该「一条都不排除」，不拼条件即可。
     */
    private void filterByServices(LambdaQueryWrapper<ConnectorProvider> wrapper,
                                  Collection<String> services, boolean include) {
        boolean empty = services == null || services.isEmpty();
        if (include) {
            if (empty) {
                wrapper.eq(ConnectorProvider::getId, -1L);
            } else {
                wrapper.in(ConnectorProvider::getService, services);
            }
        } else if (!empty) {
            wrapper.notIn(ConnectorProvider::getService, services);
        }
    }

    /**
     * 管理端官方全量（含 disabled，供治理/筛选/排序），按排序权重 order by sort_order, id。
     */
    public List<ConnectorProvider> listAllOfficial() {
        return listAllOfficial(null);
    }

    /**
     * 管理端官方全量（含 disabled），支持按分类过滤。
     *
     * @param category 分类过滤，null/空白=不过滤
     */
    public List<ConnectorProvider> listAllOfficial(String category) {
        return ConnectorTenantSupport.withoutTenantFilter(() ->
                baseMapper.selectList(new LambdaQueryWrapper<ConnectorProvider>()
                        .eq(ConnectorProvider::getSpaceId, OFFICIAL_SPACE_ID)
                        .eq(StringUtils.isNotBlank(category), ConnectorProvider::getCategory, category)
                        .orderByAsc(ConnectorProvider::getSortOrder)
                        .orderByAsc(ConnectorProvider::getId)));
    }

    /** 下一个排序权重：当前最大 sort_order + 1（新建排最后；历史 0=未排序，1 起排其后） */
    public Integer nextSortOrder() {
        return ConnectorTenantSupport.withoutTenantFilter(() -> {
            List<ConnectorProvider> top = baseMapper.selectList(new LambdaQueryWrapper<ConnectorProvider>()
                    .orderByDesc(ConnectorProvider::getSortOrder).last("LIMIT 1"));
            Integer max = top.isEmpty() ? null : top.get(0).getSortOrder();
            return (max == null) ? 1 : max + 1;
        });
    }

    /** 批量写排序权重（key=service，value=sortOrder）。
     *  单条 CASE WHEN 批量 UPDATE：远程库逐条往返约 0.6s/条，1258 条逐条写需数分钟；
     *  单条 SQL 一次性写入 <1s。service 单引号转义，sortOrder 为整数序列无注入面。 */
    public void batchUpdateSortOrder(Map<String, Integer> orderByService) {
        if (orderByService == null || orderByService.isEmpty()) {
            return;
        }
        ConnectorTenantSupport.withoutTenantFilter(() -> {
            StringBuilder caseSql = new StringBuilder("sort_order = CASE service ");
            orderByService.forEach((svc, order) ->
                    caseSql.append("WHEN '").append(svc.replace("'", "''")).append("' THEN ").append(order).append(' '));
            caseSql.append("END");
            baseMapper.update(null, new LambdaUpdateWrapper<ConnectorProvider>()
                    .in(ConnectorProvider::getService, orderByService.keySet())
                    .setSql(caseSql.toString()));
            return null;
        });
    }

    /**
     * 列出可见 provider 的 service 集合（供元工具检索限定范围用，不分页）。
     * <p>
     * spaceId 为 null 表示调用方没有空间上下文（如官方连接器场景），此时只剩官方目录可见。
     */
    public List<ConnectorProvider> listVisible(Long tenantId, Long spaceId) {
        Collection<Long> spaceIds = spaceId == null ? List.of() : List.of(spaceId);
        return ConnectorTenantSupport.withoutTenantFilter(() ->
                baseMapper.selectList(visibleWrapper(tenantId, spaceIds, null, "enabled")
                        .orderByAsc(ConnectorProvider::getId)));
    }

    /**
     * 总览：当前用户全部可见 provider（官方 enabled + 我有权限空间的自建 enabled），
     * 不分页全量返回，供前端总览页分组排序（全部/我的/官方/已连接）。
     * <p>
     * 排序同 {@link #pageVisible}：可用 → 已启用但凭证异常 → 已连接未启用 → 未连接。总览页前端按
     * {@code connected} 分组时用的是稳定排序，档内不再重排，因此这里的顺序会原样透到页面上。
     *
     * @param userId 当前用户（排序档位依据其连接）；null 退回纯权重序
     * @param status 空间自建连接器的状态过滤（"enabled"/"disabled"/"all"）；官方恒 enabled。
     * @param scope  官方/空间视图过滤："official"=仅官方，"space"=仅我有权限空间的自建（不含官方），其余=官方+空间自建
     */
    public List<ConnectorProvider> listOverview(Long tenantId, Long userId, Collection<Long> spaceIds,
                                                String status, String scope) {
        return ConnectorTenantSupport.withoutTenantFilter(() -> {
            String spaceStatus = StringUtils.isBlank(status) ? "enabled" : status;
            boolean filterSpace = !"all".equalsIgnoreCase(spaceStatus);
            LambdaQueryWrapper<ConnectorProvider> wrapper = new LambdaQueryWrapper<>();
            if ("official".equals(scope)) {
                // 仅官方目录（space_id=0，恒 enabled），不依赖 spaceIds
                wrapper.eq(ConnectorProvider::getSpaceId, OFFICIAL_SPACE_ID)
                        .eq(ConnectorProvider::getStatus, "enabled");
            } else if ("space".equals(scope)) {
                // 仅我有权限空间的自建（不含官方）；无任何权限空间则返回空
                if (spaceIds == null || spaceIds.isEmpty()) {
                    wrapper.eq(ConnectorProvider::getId, -1L);
                } else {
                    wrapper.eq(ConnectorProvider::getTenantId, tenantId)
                            .in(ConnectorProvider::getSpaceId, spaceIds)
                            .eq(filterSpace, ConnectorProvider::getStatus, spaceStatus);
                }
            } else if (spaceIds == null || spaceIds.isEmpty()) {
                wrapper.eq(ConnectorProvider::getSpaceId, OFFICIAL_SPACE_ID)
                        .eq(ConnectorProvider::getStatus, "enabled");
            } else {
                wrapper.and(w -> w.eq(ConnectorProvider::getSpaceId, OFFICIAL_SPACE_ID)
                                .eq(ConnectorProvider::getStatus, "enabled")
                        .or(n -> n.eq(ConnectorProvider::getTenantId, tenantId)
                                .in(ConnectorProvider::getSpaceId, spaceIds)
                                .eq(filterSpace, ConnectorProvider::getStatus, spaceStatus)));
            }
            orderByConnectionFirst(wrapper, tenantId, userId);
            return baseMapper.selectList(wrapper);
        });
    }

    /**
     * 「我的连接优先」排序，四档依次为：可用 → 已启用但凭证异常 → 已连接未启用 → 未连接。
     * <p>
     * 档位的依据必须来自 {@code connector_connection}，而 provider 列表是**分页**的，
     * 所以在 Java 里补排序已经太晚——某一页可能一条已连接的都没有，正确的行根本不在这一页。
     * 因此排序只能落在 SQL 里，用三个相关子查询算档位（命中为 1，DESC 把 1 排前面）。
     * 档内回落 {@code sort_order, id}，即原有的目录权重序，保证未连接的那一大档顺序与改动前完全一致。
     * <p>
     * 三个布尔位恰好切出四种组合，不需要写 {@code NOT EXISTS}：第二个子查询既当「档 0 之外」的分界，
     * 又当「档 2 之内」的下界。
     * <table>
     *   <tr><th>档</th><th>子查询1 可用</th><th>子查询2 已启用</th><th>子查询3 已连接</th></tr>
     *   <tr><td>0 可用</td><td>1</td><td>1</td><td>1</td></tr>
     *   <tr><td>1 已启用但凭证异常</td><td>0</td><td>1</td><td>1</td></tr>
     *   <tr><td>2 已连接未启用</td><td>0</td><td>0</td><td>1</td></tr>
     *   <tr><td>3 未连接</td><td>0</td><td>0</td><td>0</td></tr>
     * </table>
     * <p>
     * 为什么档 0 与档 1 要分开：调用方要的「已启用优先」只看用户开关（{@code enabled = 1}），
     * 而档 0 额外要求凭证健康（{@code enabled = 1 AND status = 'active'}，与执行侧
     * {@code firstExecutable} 同源）。若把两者并成一档，「启用了但 token 已过期」的连接器会占住首位，
     * 而它点进去必然失败——分开之后，真能跑起来的仍排最前，用户自己的启用决定紧随其后。
     * <p>
     * 第三个子查询只看行是否存在：{@code connector_connection} 里一条不删行就代表「已连接」，
     * 与目录侧判定（{@code ConnectorCatalogController#connectionsOfUser}，走 {@code listByUser}）同源，
     * 因此档位与列表上那颗「已连接」标签永远一致——停用的连接仍算已连接，只是落到档 2。
     * <p>
     * 用 {@code last()} 而非 {@code orderByAsc} 承载：MyBatis-Plus 的 lambda wrapper 的排序方法只认
     * SFunction，表达不了子查询，且 {@code last()} 会追加在既有 ORDER BY 之后，所以这里不再调用
     * {@code orderByAsc}，完整 ORDER BY 一次给出。
     * <p>
     * 只内联 {@code Long}（tenantId/userId）：二者取自登录态而非请求参数，{@code Long.toString()}
     * 不含引号、没有拼接注入面；反过来这里**不能**用 {@code ?} 占位符——分页的 count 语句会剥掉
     * ORDER BY，占位符与参数绑定表就此错位。
     *
     * @param userId null 表示没有用户上下文（内部调用），退回纯权重序
     */
    private void orderByConnectionFirst(LambdaQueryWrapper<ConnectorProvider> wrapper, Long tenantId, Long userId) {
        if (userId == null) {
            wrapper.orderByAsc(ConnectorProvider::getSortOrder).orderByAsc(ConnectorProvider::getId);
            return;
        }
        String owner = (tenantId == null ? "" : "cc._tenant_id = " + tenantId + " AND ") + "cc.user_id = " + userId;
        String scope = owner + " AND cc.provider_service = connector_provider.service";
        String existsExecutable = "EXISTS(SELECT 1 FROM connector_connection cc WHERE " + scope
                + " AND cc.enabled = 1 AND cc.status = 'active')";
        String existsEnabled = "EXISTS(SELECT 1 FROM connector_connection cc WHERE " + scope
                + " AND cc.enabled = 1)";
        String existsAny = "EXISTS(SELECT 1 FROM connector_connection cc WHERE " + scope + ")";
        wrapper.last("ORDER BY " + existsExecutable + " DESC, " + existsEnabled + " DESC, "
                + existsAny + " DESC, sort_order ASC, id ASC");
    }

    public ConnectorProvider getByService(String service) {
        return ConnectorTenantSupport.withoutTenantFilter(() ->
                baseMapper.selectOne(new LambdaQueryWrapper<ConnectorProvider>()
                        .eq(ConnectorProvider::getService, service)));
    }

    public boolean insert(ConnectorProvider provider) {
        return ConnectorTenantSupport.withoutTenantFilter(() -> baseMapper.insert(provider) > 0);
    }

    public boolean updateById(ConnectorProvider provider) {
        return ConnectorTenantSupport.withoutTenantFilter(() -> baseMapper.updateById(provider) > 0);
    }

    public boolean deleteById(Long id) {
        return ConnectorTenantSupport.withoutTenantFilter(() -> baseMapper.deleteById(id) > 0);
    }

    private LambdaQueryWrapper<ConnectorProvider> visibleWrapper(Long tenantId, Collection<Long> spaceIds,
                                                                 String scope, String status) {
        boolean filterStatus = StringUtils.isNotBlank(status) && !"all".equalsIgnoreCase(status);
        String spaceStatus = filterStatus ? status : "enabled";
        boolean hasSpace = spaceIds != null && !spaceIds.isEmpty();
        LambdaQueryWrapper<ConnectorProvider> wrapper = new LambdaQueryWrapper<>();
        if ("space".equals(scope)) {
            // 空间端「我的连接器」：仅空间自建，状态按需过滤（含 disabled 供停用后重新启用）；
            // 无可查空间时返回空结果——用恒假条件而非 in 空集合（后者会拼出 in () 语法错）
            if (!hasSpace) {
                return wrapper.eq(ConnectorProvider::getId, -1L);
            }
            wrapper.eq(ConnectorProvider::getTenantId, tenantId)
                    .in(ConnectorProvider::getSpaceId, spaceIds);
            if (filterStatus) {
                wrapper.eq(ConnectorProvider::getStatus, status);
            }
        } else if ("official".equals(scope)) {
            // 仅官方目录（space_id=0，恒 enabled），不依赖 spaceIds
            wrapper.eq(ConnectorProvider::getSpaceId, OFFICIAL_SPACE_ID)
                    .eq(ConnectorProvider::getStatus, "enabled");
        } else {
            // 官方(恒 enabled) + 我的空间自建（按 status，缺省 enabled）——目录兼容视图
            wrapper.and((LambdaQueryWrapper<ConnectorProvider> w) -> {
                w.eq(ConnectorProvider::getSpaceId, OFFICIAL_SPACE_ID)
                        .eq(ConnectorProvider::getStatus, "enabled");
                if (hasSpace) {
                    w.or(nested -> nested.eq(ConnectorProvider::getTenantId, tenantId)
                            .in(ConnectorProvider::getSpaceId, spaceIds)
                            .eq(ConnectorProvider::getStatus, spaceStatus));
                }
                // 没有任何空间可查时不留 or 分支，等价于只查官方
            });
        }
        return wrapper;
    }
}
