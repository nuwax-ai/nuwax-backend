package com.xspaceagi.connector.application.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xspaceagi.connector.application.support.ConnectorArgsCodec;
import com.xspaceagi.connector.application.support.HttpSpecCodec;
import com.xspaceagi.connector.domain.command.ActionCommand;
import com.xspaceagi.connector.domain.command.ProviderCommand;
import com.xspaceagi.connector.domain.entity.ConnectorAction;
import com.xspaceagi.connector.domain.entity.ConnectorProvider;
import com.xspaceagi.connector.domain.service.ConnectorCatalogService;
import com.xspaceagi.connector.infra.repository.ConnectorActionRepository;
import com.xspaceagi.connector.infra.repository.ConnectorConnectionRepository;
import com.xspaceagi.connector.infra.repository.ConnectorProviderRepository;
import com.xspaceagi.connector.spec.enums.AuthTypeEnum;
import com.xspaceagi.connector.spec.enums.ConnectorSourceEnum;
import com.xspaceagi.connector.spec.enums.ExecTypeEnum;
import com.xspaceagi.connector.spec.enums.ManagedByEnum;
import com.xspaceagi.connector.spec.error.ConnectorErrorCode;
import com.xspaceagi.connector.spec.exception.ConnectorException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Catalog 服务实现（设计文档 §4.1）。
 */
@Slf4j
@Service
public class ConnectorCatalogServiceImpl implements ConnectorCatalogService {

    private static final Pattern SERVICE_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{1,63}$");
    private static final Pattern ACTION_KEY_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]{0,127}$");

    /** 扫码类（{@code oauth2_device}）连接器自动附带的凭证发放工具，运行时见 {@code AuthConfigRuntime} */
    private static final String AUTH_CONFIG_ACTION_KEY = "get_auth_config";
    private static final String AUTH_CONFIG_EXEC_REF = "connector.auth-config";

    @Resource
    private ConnectorProviderRepository providerRepository;

    @Resource
    private ConnectorActionRepository actionRepository;

    @Resource
    private ConnectorConnectionRepository connectionRepository;

    // ---------- 查询 ----------

    @Override
    public IPage<ConnectorProvider> pageProviders(Long tenantId, Long userId, Collection<Long> spaceIds, String category,
                                                  String tag, String keyword, String connectedFilter,
                                                  Collection<String> connectedServices,
                                                  String connectionEnabledFilter,
                                                  Collection<String> enabledServices,
                                                  String scope, String status, long pageNum, long pageSize) {
        return providerRepository.pageVisible(tenantId, userId, spaceIds, category, tag, keyword,
                connectedFilter, connectedServices, connectionEnabledFilter, enabledServices,
                scope, status, pageNum, pageSize);
    }

    @Override
    public List<ConnectorProvider> listVisibleProviders(Long tenantId, Long spaceId) {
        return providerRepository.listVisible(tenantId, spaceId);
    }

    @Override
    public List<ConnectorProvider> listProvidersForOverview(Long tenantId, Long userId, Collection<Long> spaceIds,
                                                            String status, String scope) {
        return providerRepository.listOverview(tenantId, userId, spaceIds, status, scope);
    }

    @Override
    public ConnectorProvider requireProvider(String service) {
        ConnectorProvider provider = providerRepository.getByService(service);
        if (provider == null) {
            throw new ConnectorException(ConnectorErrorCode.PROVIDER_NOT_FOUND, "连接器不存在: " + service);
        }
        return provider;
    }

    @Override
    public List<ConnectorAction> listActions(String providerService, String tag, String keyword,
                                             boolean includeDeprecated) {
        return actionRepository.listByProvider(providerService, tag, keyword, includeDeprecated, false);
    }

    @Override
    public ConnectorAction requireAction(Long actionId) {
        ConnectorAction action = actionRepository.getById(actionId);
        if (action == null) {
            throw new ConnectorException(ConnectorErrorCode.ACTION_NOT_FOUND, "动作不存在: " + actionId);
        }
        return action;
    }

    @Override
    public ConnectorAction requireActionByKey(String providerService, String actionKey) {
        ConnectorAction action = actionRepository.getByKey(providerService, actionKey);
        if (action == null) {
            throw new ConnectorException(ConnectorErrorCode.ACTION_NOT_FOUND,
                    "动作不存在: " + providerService + "/" + actionKey);
        }
        return action;
    }

    // ---------- 用户/空间侧创建 ----------

    @Override
    public ConnectorProvider createProvider(Long tenantId, Long spaceId, ProviderCommand command) {
        // 空间自建连接器 service 统一 s_ 前缀（幂等：已带 s_ 不再追加），标识空间私有
        command.setService(normalizeSpaceService(command.getService()));
        validateProviderCommand(command, true);
        if (providerRepository.getByService(command.getService()) != null) {
            throw new ConnectorException(ConnectorErrorCode.PROVIDER_EXISTS);
        }
        ConnectorProvider provider = new ConnectorProvider();
        provider.setTenantId(tenantId);
        provider.setSpaceId(spaceId);
        provider.setService(command.getService());
        provider.setDisplayName(command.getDisplayName());
        provider.setDescription(command.getDescription());
        provider.setIcon(command.getIcon());
        provider.setCategory(command.getCategory());
        provider.setTags(toJson(command.getTags()));
        provider.setAuthType(AuthTypeEnum.fromCode(command.getAuthType()).getCode());
        provider.setBaseUrl(command.getBaseUrl());
        provider.setAuthConfig(toJson(command.getAuthConfig()));
        provider.setOauthAppMode(defaultOauthAppMode(command.getOauthAppMode()));
        provider.setSource(ConnectorSourceEnum.NUWAX.getCode());
        provider.setManagedBy(ManagedByEnum.USER.getCode());
        provider.setProxyEnabled(Boolean.TRUE.equals(command.getProxyEnabled()) ? 1 : 0);
        provider.setStatus("enabled");
        provider.setSortOrder(providerRepository.nextSortOrder());
        Date now = new Date();
        provider.setCreated(now);
        provider.setModified(now);
        providerRepository.insert(provider);
        ensureAuthConfigAction(provider);
        return provider;
    }

    @Override
    public ConnectorProvider createSystemProvider(Long tenantId, ProviderCommand command) {
        validateProviderCommand(command, true);
        if (providerRepository.getByService(command.getService()) != null) {
            throw new ConnectorException(ConnectorErrorCode.PROVIDER_EXISTS);
        }
        ConnectorProvider provider = new ConnectorProvider();
        provider.setTenantId(tenantId);
        provider.setSpaceId(0L);
        provider.setService(command.getService());
        provider.setDisplayName(command.getDisplayName());
        provider.setDescription(command.getDescription());
        provider.setIcon(command.getIcon());
        provider.setCategory(command.getCategory());
        provider.setTags(toJson(command.getTags()));
        provider.setAuthType(AuthTypeEnum.fromCode(command.getAuthType()).getCode());
        provider.setBaseUrl(command.getBaseUrl());
        provider.setAuthConfig(toJson(command.getAuthConfig()));
        provider.setOauthAppMode(defaultOauthAppMode(command.getOauthAppMode()));
        provider.setSource(ConnectorSourceEnum.NUWAX.getCode());
        provider.setManagedBy(ManagedByEnum.SYSTEM.getCode());
        provider.setProxyEnabled(Boolean.TRUE.equals(command.getProxyEnabled()) ? 1 : 0);
        provider.setStatus("enabled");
        provider.setSortOrder(providerRepository.nextSortOrder());
        Date now = new Date();
        provider.setCreated(now);
        provider.setModified(now);
        providerRepository.insert(provider);
        ensureAuthConfigAction(provider);
        return provider;
    }

    @Override
    public ConnectorProvider updateProvider(String service, ProviderCommand command) {
        ConnectorProvider existing = requireProvider(service);
        if (StringUtils.isNotBlank(command.getDisplayName())) {
            existing.setDisplayName(command.getDisplayName());
        }
        if (command.getDescription() != null) {
            existing.setDescription(command.getDescription());
        }
        if (command.getIcon() != null) {
            existing.setIcon(command.getIcon());
        }
        if (command.getCategory() != null) {
            existing.setCategory(command.getCategory());
        }
        if (command.getTags() != null) {
            existing.setTags(toJson(command.getTags()));
        }
        if (StringUtils.isNotBlank(command.getAuthType())) {
            existing.setAuthType(AuthTypeEnum.fromCode(command.getAuthType()).getCode());
        }
        if (command.getBaseUrl() != null) {
            existing.setBaseUrl(command.getBaseUrl());
        }
        if (command.getAuthConfig() != null) {
            existing.setAuthConfig(toJson(command.getAuthConfig()));
        }
        if (StringUtils.isNotBlank(command.getOauthAppMode())) {
            existing.setOauthAppMode(defaultOauthAppMode(command.getOauthAppMode()));
        }
        if (command.getProxyEnabled() != null) {
            existing.setProxyEnabled(command.getProxyEnabled() ? 1 : 0);
        }
        // 改过即进手工保护区（设计文档 §4.1 维护机制）
        existing.setManagedBy(ManagedByEnum.USER.getCode());
        existing.setModified(new Date());
        providerRepository.updateById(existing);
        ensureAuthConfigAction(existing);
        return existing;
    }

    @Override
    public ConnectorProvider updateSystemProvider(String service, ProviderCommand command) {
        ConnectorProvider existing = requireProvider(service);
        if (StringUtils.isNotBlank(command.getDisplayName())) {
            existing.setDisplayName(command.getDisplayName());
        }
        if (command.getDescription() != null) {
            existing.setDescription(command.getDescription());
        }
        if (command.getIcon() != null) {
            existing.setIcon(command.getIcon());
        }
        if (command.getCategory() != null) {
            existing.setCategory(command.getCategory());
        }
        if (command.getTags() != null) {
            existing.setTags(toJson(command.getTags()));
        }
        if (StringUtils.isNotBlank(command.getAuthType())) {
            existing.setAuthType(AuthTypeEnum.fromCode(command.getAuthType()).getCode());
        }
        if (command.getBaseUrl() != null) {
            existing.setBaseUrl(command.getBaseUrl());
        }
        if (command.getAuthConfig() != null) {
            existing.setAuthConfig(toJson(command.getAuthConfig()));
        }
        if (StringUtils.isNotBlank(command.getOauthAppMode())) {
            existing.setOauthAppMode(defaultOauthAppMode(command.getOauthAppMode()));
        }
        if (command.getProxyEnabled() != null) {
            existing.setProxyEnabled(command.getProxyEnabled() ? 1 : 0);
        }
        // 官方目录维护：保持 managed_by=system（与 updateProvider 的「改过即进保护区」相反）
        existing.setModified(new Date());
        providerRepository.updateById(existing);
        ensureAuthConfigAction(existing);
        return existing;
    }

    @Override
    public void deleteProvider(String service) {
        ConnectorProvider provider = requireProvider(service);
        if (connectionRepository.countByProvider(service) > 0) {
            throw new ConnectorException(ConnectorErrorCode.PROVIDER_IN_USE);
        }
        actionRepository.deleteByProvider(service);
        providerRepository.deleteById(provider.getId());
    }

    @Override
    public ConnectorAction saveAction(ActionCommand command) {
        if (StringUtils.isBlank(command.getProviderService()) || StringUtils.isBlank(command.getActionKey())) {
            throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR, "providerService/actionKey 必填");
        }
        if (!ACTION_KEY_PATTERN.matcher(command.getActionKey()).matches()) {
            throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR, "actionKey 格式非法");
        }
        requireProvider(command.getProviderService());
        // 先取既有动作：本方法同时服务「新建」与「部分更新」，更新时省略的字段必须沿用旧值，
        // 不能回落成创建期缺省值，否则只改个名字就会把 execType 降级、清掉 execRef。
        ConnectorAction existing = actionRepository.getByKey(command.getProviderService(), command.getActionKey());
        String execType = StringUtils.defaultIfBlank(command.getExecType(),
                existing == null ? ExecTypeEnum.DECLARATIVE.getCode() : existing.getExecType());
        ExecTypeEnum execTypeEnum = ExecTypeEnum.fromCode(execType);
        // §4.8 硬约束 1：用户只能建声明式连接器，CUSTOM 仅官方层
        if (execTypeEnum == ExecTypeEnum.CUSTOM && !command.isSystemLevel()) {
            throw new ConnectorException(ConnectorErrorCode.CUSTOM_RUNTIME_FORBIDDEN);
        }
        // httpSpec 齐备性按「合并后」的状态判断：部分更新时库里已有的那份也算数
        Object effectiveHttpSpec = command.getHttpSpec() != null ? command.getHttpSpec()
                : (existing == null ? null : existing.getHttpSpec());
        if (execTypeEnum == ExecTypeEnum.DECLARATIVE && effectiveHttpSpec == null) {
            throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR, "DECLARATIVE 动作必须提供 httpSpec");
        }

        // 手工保护区：系统级操作（导入）不得覆盖 user 条目
        if (existing != null && command.isSystemLevel()
                && ManagedByEnum.USER.getCode().equals(existing.getManagedBy())) {
            throw new ConnectorException(ConnectorErrorCode.PROTECTED_ZONE,
                    "动作处于手工保护区: " + command.getProviderService() + "/" + command.getActionKey());
        }

        ConnectorAction action = existing == null ? new ConnectorAction() : existing;
        action.setProviderService(command.getProviderService());
        action.setActionKey(command.getActionKey());
        if (StringUtils.isNotBlank(command.getName())) {
            action.setName(command.getName());
        }
        if (command.getDescription() != null) {
            action.setDescription(command.getDescription());
        }
        if (command.getTags() != null) {
            action.setTags(toJson(command.getTags()));
        }
        if (command.getInputArgs() != null) {
            // 类型化 Arg 树校验归一（旧扁平 map 在此被拒绝）
            action.setInputArgs(ConnectorArgsCodec.normalize(command.getInputArgs(),
                    "action " + command.getActionKey() + " 的 inputArgs"));
        }
        if (command.getOutputArgs() != null) {
            action.setOutputArgs(toJson(command.getOutputArgs()));
        }
        action.setExecType(execTypeEnum.getCode());
        // execRef 同其他字段：未传即不动，避免部分更新把它清空
        if (command.getExecRef() != null) {
            action.setExecRef(command.getExecRef());
        }
        if (command.getHttpSpec() != null) {
            // 结构校验归一（与上面的 inputArgs 对称：这边此前一条都不校验，写错的 spec
            // 要等到调用方执行时才炸，其中几条还是静默的——见 HttpSpecCodec 类注释）
            action.setHttpSpec(HttpSpecCodec.normalize(command.getHttpSpec(),
                    "action " + command.getActionKey() + " 的 httpSpec"));
        }
        action.setManagedBy(command.isSystemLevel() ? ManagedByEnum.SYSTEM.getCode() : ManagedByEnum.USER.getCode());
        Date now = new Date();
        if (existing == null) {
            action.setDeprecated(0);
            action.setStatus("enabled");
            action.setCreated(now);
        }
        action.setModified(now);
        if (existing == null) {
            actionRepository.insert(action);
        } else {
            actionRepository.updateById(action);
        }
        return action;
    }

    /**
     * 为<b>扫码类</b>（{@code oauth2_device}）连接器补一条内置工具 {@code get_auth_config}，
     * 把建连后托管的凭证发放给需要直接使用它们的客户端（如沙箱里的 lark-cli）。
     * <p>
     * <b>只有扫码类需要它。</b>这类连接的客户端是命令行工具，自己不会走 OAuth，除了平台把令牌
     * 发出来没有别的办法拿到凭证；而授权码（{@code oauth2}）连接的令牌由引擎在每次调用时自动
     * 注入 {@code Authorization} 头，客户端手里本来就不该有令牌——给它发一份明文副本，等于凭空
     * 多出一处泄露面，没有对应的收益。所以判据是 {@code == OAUTH2_DEVICE} 而不是
     * {@link AuthTypeEnum#isOauth2TokenFlow()}（后者是「凭证形态是不是会过期的 OAuth 令牌」，
     * 另一个问题，注入与刷新仍按它判断）。
     * <p>
     * 反过来，连接器从扫码改成别的方式时要把这条工具<b>摘掉</b>，见
     * {@link #removeAuthConfigAction}——否则它会以「平台内置」的身份留在动作列表里，
     * 用户既删不掉（managed_by=system）又用不上。
     * <p>
     * 走既有 {@link #saveAction} 而不是自己插行，为的是复用全部校验与保护区判断；
     * saveAction 内部先 getByKey，因此天然幂等——重复调用是更新，不会插出重复行。
     * 挂到创建与更新共四个入口，更新路径覆盖「把存量连接器的 authType 改成 oauth2_device」。
     * <p>
     * {@code systemLevel=true} 是必须的：§4.8 硬约束禁止非系统级创建 CUSTOM 动作，而空间自建
     * 连接器（s_ 前缀）走的正是用户侧入口。代价是这条动作 managed_by=system，空间用户看到的
     * 是一个只读附加工具——运行时是平台代码，本就不该由用户改。
     * <p>
     * 失败一律吞掉只告警：附加工具不该让「创建/编辑连接器」这个主操作失败（例如用户此前手工
     * 建过同名动作、该条目已进手工保护区，saveAction 会抛 PROTECTED_ZONE）。
     */
    private void ensureAuthConfigAction(ConnectorProvider provider) {
        if (provider == null) {
            return;
        }
        if (AuthTypeEnum.fromCode(provider.getAuthType()) != AuthTypeEnum.OAUTH2_DEVICE) {
            removeAuthConfigAction(provider);
            return;
        }
        try {
            ActionCommand command = new ActionCommand();
            command.setProviderService(provider.getService());
            command.setActionKey(AUTH_CONFIG_ACTION_KEY);
            command.setName("获取认证信息");
            command.setDescription("返回本连接的认证信息（appId / clientSecret / accessToken / refreshToken / "
                    + "tokenType / identity / expiresAt / scope），供需要直接使用这些凭证的客户端查询。"
                    + "无需输入参数。identity 取 user 或 tenant，标明这张令牌是谁的身份——注入命令行工具时"
                    + "按它选变量：tenant 用 LARKSUITE_CLI_TENANT_ACCESS_TOKEN，user 用 "
                    + "LARKSUITE_CLI_USER_ACCESS_TOKEN，两者都还要配 LARKSUITE_CLI_APP_ID=appId。"
                    + "令牌剩余有效期不足 10 分钟时会先自动续期再返回，所以每次要用之前重新调用即可，"
                    + "不要把取到的令牌缓存起来长期使用。"
                    + "注意：输出为敏感凭证明文，不会记入执行审计，请勿原样转贴到不受控的环境。");
            command.setExecType(ExecTypeEnum.CUSTOM.getCode());
            command.setExecRef(AUTH_CONFIG_EXEC_REF);
            command.setSystemLevel(true);
            saveAction(command);
        } catch (Exception e) {
            log.warn("[connector] 自动附带 {} 失败（不影响连接器本身）: provider={}, reason={}",
                    AUTH_CONFIG_ACTION_KEY, provider.getService(), e.getMessage());
        }
    }

    /**
     * 摘掉平台自带的那条 {@code get_auth_config}（认证方式不是扫码时就该没有它）。
     * <p>
     * 只删自己放的：凭 {@code exec_ref} 认领，对不上的说明是别人手写的同名动作，留着不动——
     * 用户手工建的动作不该被平台的维护动作顺手删掉。找不到行是常态（绝大多数连接器本就没有），
     * 静默返回。
     */
    private void removeAuthConfigAction(ConnectorProvider provider) {
        try {
            ConnectorAction existing = actionRepository.getByKey(provider.getService(), AUTH_CONFIG_ACTION_KEY);
            if (existing == null || !AUTH_CONFIG_EXEC_REF.equals(existing.getExecRef())) {
                return;
            }
            actionRepository.deleteById(existing.getId());
            log.info("[connector] 已摘掉不再适用的内置工具 {}: provider={}, authType={}",
                    AUTH_CONFIG_ACTION_KEY, provider.getService(), provider.getAuthType());
        } catch (Exception e) {
            log.warn("[connector] 摘掉内置工具 {} 失败（不影响连接器本身）: provider={}, reason={}",
                    AUTH_CONFIG_ACTION_KEY, provider.getService(), e.getMessage());
        }
    }

    @Override
    public void deleteAction(Long actionId) {
        ConnectorAction action = requireAction(actionId);
        actionRepository.deleteById(action.getId());
    }

    // ---------- 治理 ----------

    @Override
    public void setProviderStatus(String service, boolean enabled) {
        ConnectorProvider provider = requireProvider(service);
        provider.setStatus(enabled ? "enabled" : "disabled");
        provider.setModified(new Date());
        providerRepository.updateById(provider);
    }

    @Override
    public void setActionStatus(Long actionId, boolean enabled) {
        ConnectorAction action = requireAction(actionId);
        action.setStatus(enabled ? "enabled" : "disabled");
        action.setModified(new Date());
        actionRepository.updateById(action);
    }

    @Override
    public void setActionDeprecated(Long actionId, boolean deprecated) {
        ConnectorAction action = requireAction(actionId);
        action.setDeprecated(deprecated ? 1 : 0);
        action.setModified(new Date());
        actionRepository.updateById(action);
    }

    // ---------- 内部 ----------

    /** 空间私有连接器 service 统一 s_ 前缀（幂等：已带 s_ 不重复追加），标识空间私有 */
    private String normalizeSpaceService(String service) {
        if (StringUtils.isBlank(service) || service.startsWith("s_")) {
            return service;
        }
        return "s_" + service;
    }

    private void validateProviderCommand(ProviderCommand command, boolean requireAll) {
        if (command == null) {
            throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR, "请求体不能为空");
        }
        if (requireAll) {
            if (StringUtils.isBlank(command.getService()) || !SERVICE_PATTERN.matcher(command.getService()).matches()) {
                throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR,
                        "service 必填且须为小写字母开头的 [a-z0-9_]（2-64位）");
            }
            if (StringUtils.isBlank(command.getDisplayName())) {
                throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR, "displayName 必填");
            }
            if (StringUtils.isBlank(command.getAuthType())) {
                throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR, "authType 必填");
            }
        }
        if (StringUtils.isNotBlank(command.getAuthType())) {
            try {
                AuthTypeEnum.fromCode(command.getAuthType());
            } catch (IllegalArgumentException e) {
                throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR, "authType 非法: " + command.getAuthType());
            }
        }
    }

    private String defaultOauthAppMode(String mode) {
        return "byo".equals(mode) ? "byo" : "platform";
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s.isBlank() ? null : s;
        }
        return JSON.toJSONString(value);
    }
}
