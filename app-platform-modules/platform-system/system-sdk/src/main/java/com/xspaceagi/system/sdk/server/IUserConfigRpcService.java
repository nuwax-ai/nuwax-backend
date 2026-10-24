package com.xspaceagi.system.sdk.server;

import java.util.Map;

/**
 * 用户个人配置查询 RPC 接口（只读）
 *
 * <p>数据来源是用户个人 key-value 配置（{@code user_config} 表，一行一 key），由用户本人通过
 * {@code /api/user/config/**} 自助写入；本接口只提供跨模块读取能力，<b>不提供写入入口</b>。
 *
 * <p>使用示例：
 * <pre>{@code
 * // 注入服务
 * @Resource
 * private IUserConfigRpcService userConfigRpcService;
 *
 * // 读单个配置项，未设置返回 null
 * Object sidebar = userConfigRpcService.getUserConfig(userId, "ui.sidebar");
 *
 * // 一次读全量（仅 key -> value，按 key 升序）
 * Map<String, Object> configMap = userConfigRpcService.getUserConfigMap(userId);
 * String theme = (String) configMap.get("theme");
 * }</pre>
 *
 * <p><b>调用约束：</b>
 * <ul>
 *   <li>本接口按传入的 userId 读取，<b>不校验 userId 是否等于当前登录用户</b>，同租户内可读任意用户。
 *       调用方必须是同进程可信模块，不得把外部不可信输入直接透传为 userId。</li>
 *   <li>租户取自 {@link com.xspaceagi.system.spec.common.RequestContext}，本接口不接收 tenantId、也不做租户绕过。
 *       传入其它租户的 userId 会因 SQL 层的 {@code _tenant_id} 过滤而返回空，属 fail-closed，不会跨租户读取。</li>
 *   <li>必须在带租户上下文的线程内调用；后台/异步线程请先
 *       {@code RequestContext.setThreadTenantId(tenantId)}。</li>
 * </ul>
 */
public interface IUserConfigRpcService {

    /**
     * 查询指定用户的单个配置项
     *
     * @param userId 用户ID
     * @param key    配置项 key（1-128 位，仅允许字母、数字、下划线、点、冒号、短横线；格式非法抛业务错误码 3175）
     * @return 配置项值（任意 JSON：标量/对象/数组），该 key 未设置时返回 null
     */
    Object getUserConfig(Long tenantId, Long userId, String key);

    /**
     * 查询指定用户的全部配置项
     *
     * @param userId 用户ID
     * @return 仅 key -&gt; value 的 Map（按 key 升序）；该用户无任何配置时返回空 Map
     */
    Map<String, Object> getUserConfigMap(Long userId);
}
