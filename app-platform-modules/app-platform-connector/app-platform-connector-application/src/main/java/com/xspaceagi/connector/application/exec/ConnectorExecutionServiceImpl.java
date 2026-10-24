package com.xspaceagi.connector.application.exec;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.agent.core.sdk.IPluginWorkflowRpcService;
import com.xspaceagi.connector.domain.dto.ExecResultDto;
import com.xspaceagi.connector.domain.entity.ConnectorAction;
import com.xspaceagi.connector.domain.entity.ConnectorConnection;
import com.xspaceagi.connector.domain.entity.ConnectorProvider;
import com.xspaceagi.connector.domain.entity.ConnectorRunLog;
import com.xspaceagi.connector.domain.exec.ConnectorRuntime;
import com.xspaceagi.connector.domain.exec.OAuthTokenProvider;
import com.xspaceagi.connector.domain.exec.RuntimeContext;
import com.xspaceagi.connector.domain.service.ConnectorCatalogService;
import com.xspaceagi.connector.domain.service.ConnectorConnectionService;
import com.xspaceagi.connector.domain.service.ConnectorExecutionService;
import com.xspaceagi.connector.domain.service.CredentialVaultService;
import com.xspaceagi.connector.infra.http.GuardedHttpClient;
import com.xspaceagi.connector.infra.repository.ConnectorConnectionRepository;
import com.xspaceagi.connector.infra.repository.ConnectorRunLogRepository;
import com.xspaceagi.connector.spec.ConnectorConstants;
import com.xspaceagi.connector.spec.enums.AuthTypeEnum;
import com.xspaceagi.connector.spec.enums.ConnectionStatusEnum;
import com.xspaceagi.connector.spec.enums.RunStatusEnum;
import com.xspaceagi.connector.spec.error.ConnectorErrorCode;
import com.xspaceagi.connector.spec.exception.ConnectorException;
import com.xspaceagi.system.spec.common.RequestContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 执行编排（路径 A/B/C，设计文档 §4）：动作解析 → 连接解析 → 凭证解密（模块内）→
 * 鉴权注入 → 出站 → 结果信封 → 脱敏审计。
 * <p>
 * 审计摘要只含输入字段名/输出预览/HTTP 状态，凭证值永不落日志。
 */
@Slf4j
@Service
public class ConnectorExecutionServiceImpl implements ConnectorExecutionService {

    /**
     * whoami 类动作识别（连接验证用）。
     * <p>
     * 关键词必须落在词边界上：早前是无边界的子串匹配，`get_me` 会把 `im_get_message`、
     * `get_metadata`、`get_member` 一并命中，而 {@code verifyConnection} 取的是第一个命中的动作、
     * 用**空参数**执行——于是验证动作被换成一条需要入参的业务动作，必然失败，
     * 连接明明是好的也会被判成不可用。这里用「前后不能再接字母数字」代替 {@code \b}
     * （下划线在正则里属于 word 字符，{@code \b} 挡不住 `get_me`ssage 这种）。
     */
    private static final Pattern VERIFY_ACTION = Pattern.compile(
            "(?<![a-z0-9])(whoami|current_user|get_me|get_self|userinfo|user_info)(?![a-z0-9])|^me$",
            Pattern.CASE_INSENSITIVE);
    /** profile.accountId 候选字段 */
    private static final Pattern PROFILE_ID_KEY = Pattern.compile(
            "^(id|login|user_id|userid|openid|union_id|uid|account_id|email)$", Pattern.CASE_INSENSITIVE);

    @Resource
    private ConnectorCatalogService catalogService;

    @Resource
    private ConnectorConnectionService connectionService;

    @Resource
    private ConnectorConnectionRepository connectionRepository;

    @Resource
    private CredentialVaultService vaultService;

    @Resource
    private DeclarativeActionExecutor declarativeExecutor;

    @Resource
    private ConnectorRuntimeRegistry runtimeRegistry;

    /** 插件/工作流绑定执行 SPI（agent 侧可选装配；未装配时绑定型工具执行报能力未启用） */
    @Resource
    private ObjectProvider<IPluginWorkflowRpcService> bindingSpiProvider;

    @Resource
    private ConnectorRunLogRepository runLogRepository;

    @Resource
    private GuardedHttpClient guardedHttpClient;

    @Resource
    private ObjectProvider<OAuthTokenProvider> oauthTokenProviders;

    @Override
    public ExecResultDto execute(Long actionId, String providerService, String actionKey, Long connectionId,
                                 Map<String, Object> args, Long agentId) {
        long start = System.currentTimeMillis();
        String executionId = UUID.randomUUID().toString();
        Long tenantId = RequestContext.get().getTenantId();
        Long userId = RequestContext.get().getUserId();

        ConnectorAction action;
        try {
            action = actionId != null
                    ? catalogService.requireAction(actionId)
                    : catalogService.requireActionByKey(providerService, actionKey);
        } catch (ConnectorException e) {
            return ExecResultDto.fail(e.getCode(), e.getMessage()).meta("executionId", executionId);
        }
        ConnectorProvider provider;
        try {
            provider = catalogService.requireProvider(action.getProviderService());
        } catch (ConnectorException e) {
            return ExecResultDto.fail(e.getCode(), e.getMessage()).meta("executionId", executionId);
        }
        if (!"enabled".equals(provider.getStatus())) {
            return ExecResultDto.fail(ConnectorErrorCode.PROVIDER_DISABLED.getCode(),
                    "连接器已停用: " + provider.getService()).meta("executionId", executionId);
        }
        if (!"enabled".equals(action.getStatus())) {
            return ExecResultDto.fail(ConnectorErrorCode.ACTION_DISABLED.getCode(),
                    "动作已停用: " + action.getActionKey()).meta("executionId", executionId);
        }

        ExecResultDto result;
        ConnectorConnection connection = null;
        String credentialJson = null;
        try {
            String execType = action.getExecType();
            if (isBindingType(execType)) {
                // 绑定型工具（插件/工作流）无连接需求，跳过连接解析与凭证注入
                result = runBinding(action, args, tenantId, userId);
            } else {
                connection = resolveConnection(provider, connectionId, tenantId, userId);
                if (connection != null && connection.getCredentialId() != null) {
                    credentialJson = vaultService.reveal(connection.getCredentialId());
                    credentialJson = ensureFreshOAuthToken(provider, connection, credentialJson);
                }
                if ("CUSTOM".equalsIgnoreCase(execType)) {
                    result = runCustom(provider, action, connection, credentialJson, args, tenantId, userId);
                } else {
                    result = runDeclarative(provider, action, credentialJson, args);
                }
            }
        } catch (ConnectorException e) {
            result = ExecResultDto.fail(e.getCode(), e.getMessage()).meta("executionId", executionId);
            if (e.getMeta() != null) {
                result.getMeta().putAll(e.getMeta());
            }
            if (ConnectorErrorCode.CONNECTION_REQUIRED.getCode().equals(e.getCode())) {
                result.getMeta().put("authorizeHint", authorizeHint(provider));
            }
        } catch (Exception e) {
            log.error("[connector] 执行异常 action={}", action.getActionKey(), e);
            result = ExecResultDto.fail(ConnectorErrorCode.UPSTREAM_ERROR.getCode(),
                    "执行异常: " + e.getMessage()).meta("executionId", executionId);
        }

        result.getMeta().putIfAbsent("executionId", executionId);
        if (action.getDeprecated() != null && action.getDeprecated() == 1) {
            result.getMeta().put("warning", "该动作已弃用，请尽快迁移");
        }
        if (connection != null && StringUtils.isNotBlank(connection.getProfile())) {
            result.getMeta().put("connectionProfile", JSON.parse(connection.getProfile()));
        }

        writeRunLog(tenantId, userId, connection, action, agentId, result, args,
                sensitiveValues(credentialJson), System.currentTimeMillis() - start);
        return result;
    }

    @Override
    public ExecResultDto verifyConnection(Long connectionId) {
        Long userId = RequestContext.get().getUserId();
        ConnectorConnection connection = connectionService.requireOwned(connectionId, userId);
        ConnectorProvider provider = catalogService.requireProvider(connection.getProviderService());

        ConnectorAction verifyAction = catalogService.listActions(provider.getService(), null, null, false)
                .stream()
                .filter(action -> "enabled".equals(action.getStatus())
                        && (matchesVerifyKey(action) || matchesVerifyTags(action)))
                .findFirst()
                .orElse(null);
        if (verifyAction == null) {
            return ExecResultDto.ok(null, "该连接器未定义验证动作，连接已保存")
                    .meta("profileUpdated", false);
        }

        ExecResultDto result = execute(verifyAction.getId(), null, null, connectionId,
                Map.of(), null);
        if (result.isSuccess()) {
            boolean updated = backfillProfile(connection, result.getData());
            result.setSuccess(true);
            result.setMessage(updated ? "连接可用，已回填 profile" : "连接可用");
            result.getMeta().put("profileUpdated", updated);
        } else {
            result.getMeta().put("profileUpdated", false);
        }
        return result;
    }

    @Override
    public ExecResultDto proxy(String providerService, String method, String relativePath,
                               Map<String, String> query, String body, Long connectionId) {
        long start = System.currentTimeMillis();
        String executionId = UUID.randomUUID().toString();
        Long tenantId = RequestContext.get().getTenantId();
        Long userId = RequestContext.get().getUserId();

        ConnectorProvider provider;
        try {
            provider = catalogService.requireProvider(providerService);
        } catch (ConnectorException e) {
            return ExecResultDto.fail(e.getCode(), e.getMessage()).meta("executionId", executionId);
        }
        if (!"enabled".equals(provider.getStatus())) {
            return ExecResultDto.fail(ConnectorErrorCode.PROVIDER_DISABLED.getCode(),
                    "连接器已停用: " + providerService).meta("executionId", executionId);
        }
        if (provider.getProxyEnabled() == null || provider.getProxyEnabled() != 1) {
            return ExecResultDto.fail(ConnectorErrorCode.PROXY_NOT_ENABLED.getCode(),
                    "该连接器未开启通用代理: " + providerService).meta("executionId", executionId);
        }
        if (StringUtils.isBlank(relativePath) || !relativePath.startsWith("/")
                || relativePath.contains("..")) {
            return ExecResultDto.fail(ConnectorErrorCode.PROXY_PATH_INVALID.getCode(),
                    "路径必须以 / 开头且不得包含 ..").meta("executionId", executionId);
        }

        ConnectorConnection connection = null;
        String credentialJson = null;
        try {
            connection = resolveConnection(provider, connectionId, tenantId, userId);
            if (connection != null && connection.getCredentialId() != null) {
                credentialJson = vaultService.reveal(connection.getCredentialId());
                credentialJson = ensureFreshOAuthToken(provider, connection, credentialJson);
            }
            Map<String, String> authHeaders = new LinkedHashMap<>();
            Map<String, String> authQuery = new LinkedHashMap<>();
            injectAuth(provider, credentialJson, authHeaders, authQuery);

            // 客户端自带的 Authorization/Cookie 等头一律不转发（凭证由模块内注入）
            Map<String, String> headers = new LinkedHashMap<>(authHeaders);
            String url = buildProxyUrl(provider.getBaseUrl(), relativePath, mergeQuery(authQuery, query));
            byte[] bodyBytes = body == null ? null : body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            GuardedHttpClient.GuardedHttpResponse response =
                    guardedHttpClient.execute(method, url, headers, bodyBytes, 0L);

            if (response.status() >= 200 && response.status() < 300) {
                ExecResultDto ok = ExecResultDto.ok(
                        DeclarativeActionExecutor.responseToData(response.bodyAsString()),
                        "上游返回 " + response.status());
                ok.getMeta().put("executionId", executionId);
                ok.getMeta().put("httpStatus", response.status());
                ok.getMeta().put("durationMs", response.durationMs());
                if (connection != null && StringUtils.isNotBlank(connection.getProfile())) {
                    ok.getMeta().put("connectionProfile", JSON.parse(connection.getProfile()));
                }
                writeProxyLog(tenantId, userId, connection, provider, null, ok, method + " " + relativePath,
                        sensitiveValues(credentialJson), System.currentTimeMillis() - start);
                return ok;
            }
            ExecResultDto fail = ExecResultDto.fail(ConnectorErrorCode.UPSTREAM_ERROR.getCode(),
                    "上游返回 " + response.status());
            fail.setData(DeclarativeActionExecutor.responseToData(response.bodyAsString()));
            fail.getMeta().put("executionId", executionId);
            fail.getMeta().put("httpStatus", response.status());
            writeProxyLog(tenantId, userId, connection, provider, fail.getErrorCode(), fail,
                    method + " " + relativePath, sensitiveValues(credentialJson),
                    System.currentTimeMillis() - start);
            return fail;
        } catch (ConnectorException e) {
            ExecResultDto fail = ExecResultDto.fail(e.getCode(), e.getMessage())
                    .meta("executionId", executionId);
            if (e.getMeta() != null) {
                fail.getMeta().putAll(e.getMeta());
            }
            if (ConnectorErrorCode.CONNECTION_REQUIRED.getCode().equals(e.getCode())) {
                fail.getMeta().put("authorizeHint", authorizeHint(provider));
            }
            writeProxyLog(tenantId, userId, connection, provider, e.getCode(), fail,
                    method + " " + relativePath, sensitiveValues(credentialJson),
                    System.currentTimeMillis() - start);
            return fail;
        } catch (Exception e) {
            log.error("[connector] 代理执行异常 service={}", providerService, e);
            return ExecResultDto.fail(ConnectorErrorCode.UPSTREAM_ERROR.getCode(),
                    "代理执行异常: " + e.getMessage()).meta("executionId", executionId);
        }
    }

    // ---------- 内部 ----------

    private ExecResultDto runDeclarative(ConnectorProvider provider, ConnectorAction action,
                                         String credentialJson, Map<String, Object> args) {
        Map<String, String> authHeaders = new LinkedHashMap<>();
        Map<String, String> authQuery = new LinkedHashMap<>();
        injectAuth(provider, action, credentialJson, authHeaders, authQuery);
        return declarativeExecutor.execute(provider, action, authHeaders, authQuery, args);
    }

    /** exec_type 是否为插件/工作流绑定型 */
    private boolean isBindingType(String execType) {
        return "PLUGIN".equalsIgnoreCase(execType) || "WORKFLOW".equalsIgnoreCase(execType);
    }

    /**
     * 绑定型工具执行：exec_ref 存插件/工作流 id，http_spec 存 {bindType,name,icon,description}。
     * 经 SPI 在进程内调用 agent 的插件/工作流执行，不涉凭证、不经出站 HTTP（SSRF 防护面不变）。
     */
    private ExecResultDto runBinding(ConnectorAction action, Map<String, Object> args,
                                     Long tenantId, Long userId) {
        JSONObject bind = action.getHttpSpec() == null ? null : JSON.parseObject(action.getHttpSpec());
        String bindType = bind == null ? null : bind.getString("bindType");
        Long refId = null;
        if (StringUtils.isNotBlank(action.getExecRef())) {
            try {
                refId = Long.parseLong(action.getExecRef().trim());
            } catch (NumberFormatException ignored) {
                // 下方统一报配置不完整
            }
        }
        if (StringUtils.isBlank(bindType) || refId == null) {
            throw new ConnectorException(ConnectorErrorCode.EXEC_CUSTOM_NOT_REGISTERED,
                    "绑定型工具配置不完整: bindType=" + bindType + ", ref=" + action.getExecRef());
        }
        IPluginWorkflowRpcService bindingSpi = bindingSpiProvider.getIfAvailable();
        if (bindingSpi == null) {
            throw new ConnectorException(ConnectorErrorCode.EXEC_CUSTOM_NOT_REGISTERED,
                    "插件/工作流绑定执行能力未启用");
        }
        Object data = bindingSpi.execute(bindType, refId, args == null ? Map.of() : args, userId, tenantId);
        return ExecResultDto.ok(data, "执行成功");
    }

    private ExecResultDto runCustom(ConnectorProvider provider, ConnectorAction action,
                                    ConnectorConnection connection, String credentialJson,
                                    Map<String, Object> args, Long tenantId, Long userId) {
        ConnectorRuntime runtime = runtimeRegistry.lookup(action.getExecRef());
        if (runtime == null) {
            throw new ConnectorException(ConnectorErrorCode.EXEC_CUSTOM_NOT_REGISTERED,
                    "CUSTOM 运行时未注册: " + action.getExecRef());
        }
        RuntimeContext context = RuntimeContext.builder()
                .provider(provider)
                .action(action)
                .connection(connection)
                .credentialJson(credentialJson)
                .args(args == null ? Map.of() : args)
                .tenantId(tenantId)
                .userId(userId)
                .build();
        ExecResultDto result = runtime.execute(context);
        if (result == null) {
            throw new ConnectorException(ConnectorErrorCode.UPSTREAM_ERROR, "CUSTOM 运行时返回空结果");
        }
        return result;
    }

    /**
     * 连接解析：显式 connectionId（归属校验）优先；否则取用户该 provider 的第一个可执行连接；
     * no_auth 的 provider 仅在<b>一条连接都没建过</b>时允许无连接执行。
     * <p>
     * 「被用户停用」在本方法内被挡两次，缺一不可：显式分支若不看 {@code enabled}，调用方传一个
     * connectionId 就能静默绕过用户的停用决定；自动分支已由 {@code firstExecutable} 的
     * {@code enabled = 1} 天然过滤，但落空后仍要能区分三种原因——它们指向三个不同的下一步动作。
     * <p>
     * {@code enabled} 与 {@code status} 正交，故这里分两次判断：先看用户要不要用，再看凭证能不能用。
     * 顺序不能反——一条「被停用且已过期」的连接，该告诉用户的是去启用，而不是去重新授权，
     * 因为他明明已经主动关掉了它。
     */
    private ConnectorConnection resolveConnection(ConnectorProvider provider, Long connectionId,
                                                  Long tenantId, Long userId) {
        if (connectionId != null) {
            ConnectorConnection connection = connectionRepository.getOwned(connectionId, userId);
            if (connection == null) {
                throw new ConnectorException(ConnectorErrorCode.CONNECTION_NOT_FOUND);
            }
            if (!provider.getService().equals(connection.getProviderService())) {
                throw new ConnectorException(ConnectorErrorCode.CONNECTION_NOT_FOUND,
                        "连接与动作的连接器不匹配");
            }
            if (!Boolean.TRUE.equals(connection.getEnabled())) {
                throw new ConnectorException(ConnectorErrorCode.CONNECTION_DISABLED,
                        "连接已停用，请先启用或改用其他连接: " + connectionId);
            }
            if (!ConnectionStatusEnum.ACTIVE.getCode().equals(connection.getStatus())) {
                throw new ConnectorException(ConnectorErrorCode.CONNECTION_EXPIRED,
                        "连接凭证已失效（" + connection.getStatus() + "），请重新授权或改用其他连接: "
                                + connectionId);
            }
            return connection;
        }
        ConnectorConnection connection = connectionService.resolveForExecution(tenantId, userId,
                provider.getService());
        if (connection == null) {
            // 「存在但不可用」的判断必须先于 no_auth 例外：no_auth 只是不需要凭证，不等于不受启停约束。
            // 用户为它建过连接后，那条连接的开关就要算数——否则停用在 no_auth 上是空转，
            // 用户会看到连接列表里明明是「已停用」的连接器，调用却照样打得通。
            ConnectorConnection blocked = connectionRepository.firstBlocked(tenantId, userId,
                    provider.getService());
            if (blocked != null) {
                if (!Boolean.TRUE.equals(blocked.getEnabled())) {
                    throw new ConnectorException(ConnectorErrorCode.CONNECTION_DISABLED,
                            "连接已停用，请先启用或改用其他连接: " + provider.getService());
                }
                throw new ConnectorException(ConnectorErrorCode.CONNECTION_EXPIRED,
                        "连接凭证已失效（" + blocked.getStatus() + "），请重新授权: " + provider.getService());
            }
            // 一条连接都没建过：只有 no_auth 允许这样执行（它本来就不需要凭证）
            if (AuthTypeEnum.fromCode(provider.getAuthType()) != AuthTypeEnum.NO_AUTH) {
                throw new ConnectorException(ConnectorErrorCode.CONNECTION_REQUIRED,
                        "请先建立连接: " + provider.getService());
            }
        }
        return connection;
    }

    /**
     * OAuth 连接取"当前可用" access_token：优先走 OAuthTokenProvider（S7 实现，负责刷新轮换），
     * 未实现时退回凭证内存储值。
     * <p>
     * 判断用 {@code isOauth2TokenFlow()} 而非 {@code == OAUTH2}：授权码与扫码两种机制建出的连接
     * 装的是同一种会过期的令牌，漏掉 OAUTH2_DEVICE 会让扫码连接在 7200 秒后静默僵死。
     */
    private String ensureFreshOAuthToken(ConnectorProvider provider, ConnectorConnection connection,
                                         String credentialJson) {
        if (!AuthTypeEnum.fromCode(provider.getAuthType()).isOauth2TokenFlow()) {
            return credentialJson;
        }
        OAuthTokenProvider tokenProvider = oauthTokenProviders.getIfAvailable();
        if (tokenProvider == null) {
            return credentialJson;
        }
        String accessToken = tokenProvider.validAccessToken(connection);
        JSONObject credential = JSON.parseObject(credentialJson);
        credential.put("accessToken", accessToken);
        return credential.toJSONString();
    }

    /**
     * 按 provider auth_config 注入凭证到 header/query。
     */
    private void injectAuth(ConnectorProvider provider, String credentialJson,
                            Map<String, String> authHeaders, Map<String, String> authQuery) {
        injectAuth(provider, null, credentialJson, authHeaders, authQuery);
    }

    /**
     * 注入凭证到 header/query。action 的 http_spec.auth 可做工具级认证覆盖
     * （优先于 provider auth_config），用于同 provider 下个别工具用不同认证头
     * （如 deepseek 的 Anthropic 兼容端点要 x-api-key 而非 Authorization: Bearer）。
     */
    private void injectAuth(ConnectorProvider provider, ConnectorAction action, String credentialJson,
                            Map<String, String> authHeaders, Map<String, String> authQuery) {
        JSONObject authOverride = parseAuthOverride(action);
        AuthTypeEnum authType = AuthTypeEnum.fromCode(provider.getAuthType());
        if (authType == AuthTypeEnum.NO_AUTH || StringUtils.isBlank(credentialJson)) {
            return;
        }
        JSONObject credential = JSON.parseObject(credentialJson);
        switch (authType) {
            case API_KEY -> {
                JSONObject authConfig = parseAuthConfig(provider);
                String keyName = firstOverride(authOverride, authConfig, "keyName", "apiKey");
                String value = credential.getString(keyName);
                if (StringUtils.isBlank(value)) {
                    throw new ConnectorException(ConnectorErrorCode.CREDENTIAL_NOT_FOUND,
                            "凭证缺少字段: " + keyName);
                }
                // 认证注入扩展（向后兼容：旧配置不含这些字段时行为不变）：
                //   base64=true     先对 base64Prefix+value+suffix 做 Base64 编码，再拼 prefix（支持 Basic(base64) 型）
                //   suffix           拼在 value 后、进入编码内容（open-connector api_key_basic 语义）
                //   base64Prefix     拼在 value 前、进入编码内容（如 mailchimp base64("connect:"+key)）
                //   queryName        injectTo=query 时的 query 参数名（默认=keyName，支持凭证字段名与参数名分离）
                String base64Prefix = authConfig == null ? null : authConfig.getString("base64Prefix");
                String suffix = authConfig == null ? null : authConfig.getString("suffix");
                StringBuilder payload = new StringBuilder();
                if (StringUtils.isNotBlank(base64Prefix)) {
                    payload.append(base64Prefix);
                }
                payload.append(value);
                if (StringUtils.isNotBlank(suffix)) {
                    payload.append(suffix);
                }
                boolean base64 = authConfig != null && Boolean.TRUE.equals(authConfig.getBoolean("base64"));
                String encoded = base64
                        ? java.util.Base64.getEncoder().encodeToString(
                                payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        : payload.toString();
                String prefix = firstOverride(authOverride, authConfig, "prefix", null);
                String injected = StringUtils.isBlank(prefix) ? encoded : prefix + encoded;
                String injectTo = firstOverride(authOverride, authConfig, "injectTo", "header");
                if ("query".equalsIgnoreCase(injectTo)) {
                    String queryName = firstOverride(authOverride, authConfig, "queryName", keyName);
                    authQuery.put(queryName, injected);
                } else {
                    String headerName = firstOverride(authOverride, authConfig, "headerName", keyName);
                    authHeaders.put(headerName, injected);
                }
            }
            // 三种类型都是 Bearer 注入，差别只在令牌取自哪个字段：
            // BEARER 是用户直填的 token，两种 OAuth 是流程换来的 accessToken。
            case BEARER, OAUTH2, OAUTH2_DEVICE -> {
                String token = authType == AuthTypeEnum.BEARER
                        ? credential.getString("token")
                        : credential.getString("accessToken");
                if (StringUtils.isBlank(token)) {
                    throw new ConnectorException(ConnectorErrorCode.CREDENTIAL_NOT_FOUND,
                            "凭证缺少访问令牌");
                }
                authHeaders.put("Authorization", "Bearer " + token);
            }
            case CUSTOM -> DeclarativeActionExecutor.applyCustomInject(parseAuthConfig(provider),
                    credential, authHeaders, authQuery);
            default -> throw new ConnectorException(ConnectorErrorCode.UNSUPPORTED_AUTH_TYPE);
        }
    }

    /**
     * 解析工具级认证覆盖：action.http_spec.auth（如 {"headerName":"x-api-key","prefix":""}）。
     * 无覆盖配置或 http_spec 非法时返回 null（回退 provider auth_config）。
     */
    private JSONObject parseAuthOverride(ConnectorAction action) {
        if (action == null || StringUtils.isBlank(action.getHttpSpec())) {
            return null;
        }
        try {
            JSONObject spec = JSON.parseObject(action.getHttpSpec());
            return spec == null ? null : spec.getJSONObject("auth");
        } catch (Exception e) {
            return null;
        }
    }

    /** 取工具级认证覆盖值（http_spec.auth），其次 provider auth_config，最后默认值。空串视为有效覆盖。 */
    private String firstOverride(JSONObject override, JSONObject config, String key, String def) {
        if (override != null && override.containsKey(key) && override.getString(key) != null) {
            return override.getString(key);
        }
        if (config != null && config.containsKey(key) && config.getString(key) != null) {
            return config.getString(key);
        }
        return def;
    }

    private JSONObject parseAuthConfig(ConnectorProvider provider) {
        if (StringUtils.isBlank(provider.getAuthConfig())) {
            return null;
        }
        try {
            return JSON.parseObject(provider.getAuthConfig());
        } catch (Exception e) {
            return null;
        }
    }

    private String authorizeHint(ConnectorProvider provider) {
        AuthTypeEnum authType = AuthTypeEnum.fromCode(provider.getAuthType());
        if (authType == AuthTypeEnum.OAUTH2) {
            return "/api/connector/oauth/authorize?service=" + provider.getService();
        }
        if (authType == AuthTypeEnum.OAUTH2_DEVICE) {
            return "/api/connector/oauth/device/authorize?service=" + provider.getService();
        }
        return "通过 /api/connector/connections/api-key 直填凭证建连接";
    }

    private boolean matchesVerifyKey(ConnectorAction action) {
        return VERIFY_ACTION.matcher(StringUtils.defaultString(action.getActionKey())).find();
    }

    private boolean matchesVerifyTags(ConnectorAction action) {
        return StringUtils.isNotBlank(action.getTags()) && action.getTags().contains("\"whoami\"");
    }

    /**
     * 从验证动作响应尽力回填 profile（accountId/displayName/avatar）。
     */
    private boolean backfillProfile(ConnectorConnection connection, Object data) {
        if (!(data instanceof JSONObject obj)) {
            return false;
        }
        JSONObject profile = new JSONObject();
        for (String key : obj.keySet()) {
            String lower = key.toLowerCase(Locale.ROOT);
            if (profile.get("accountId") == null && PROFILE_ID_KEY.matcher(lower).matches()) {
                profile.put("accountId", String.valueOf(obj.get(key)));
            } else if (profile.get("displayName") == null
                    && (lower.equals("name") || lower.equals("display_name") || lower.equals("nickname"))) {
                profile.put("displayName", obj.getString(key));
            } else if (profile.get("avatar") == null
                    && (lower.equals("avatar_url") || lower.equals("avatar"))) {
                profile.put("avatar", obj.getString(key));
            }
        }
        if (profile.isEmpty()) {
            return false;
        }
        connection.setProfile(profile.toJSONString());
        connection.setModified(new Date());
        connectionRepository.updateById(connection);
        return true;
    }

    // ---------- 审计（脱敏） ----------

    private void writeRunLog(Long tenantId, Long userId, ConnectorConnection connection,
                             ConnectorAction action, Long agentId, ExecResultDto result,
                             Map<String, Object> args, Collection<String> sensitive, long durationMs) {
        try {
            ConnectorRunLog runLog = new ConnectorRunLog();
            runLog.setTenantId(tenantId);
            runLog.setSpaceId(connection == null ? null : connection.getSpaceId());
            runLog.setUserId(userId);
            runLog.setConnectionId(connection == null ? null : connection.getId());
            runLog.setActionId(action.getId());
            runLog.setAgentId(agentId);
            runLog.setStatus(result.isSuccess() ? RunStatusEnum.SUCCESS.getCode()
                    : isPolicyDenied(result.getErrorCode())
                    ? RunStatusEnum.POLICY_DENIED.getCode()
                    : RunStatusEnum.FAILED.getCode());
            runLog.setErrorCode(result.getErrorCode());
            runLog.setDurationMs(durationMs);
            runLog.setRedactedSummary(buildRedactedSummary(args, result, sensitive));
            runLog.setCreated(new Date());
            runLogRepository.insertAndTrim(runLog);
        } catch (Exception e) {
            // 审计失败不影响主流程
            log.warn("[connector] 审计写入失败 action={}", action.getActionKey(), e);
        }
    }

    private void writeProxyLog(Long tenantId, Long userId, ConnectorConnection connection,
                               ConnectorProvider provider, String errorCode, ExecResultDto result,
                               String requestLine, Collection<String> sensitive, long durationMs) {
        try {
            ConnectorRunLog runLog = new ConnectorRunLog();
            runLog.setTenantId(tenantId);
            runLog.setSpaceId(connection == null ? null : connection.getSpaceId());
            runLog.setUserId(userId);
            runLog.setConnectionId(connection == null ? null : connection.getId());
            runLog.setAgentId(null);
            runLog.setStatus(result.isSuccess() ? RunStatusEnum.SUCCESS.getCode()
                    : isPolicyDenied(errorCode)
                    ? RunStatusEnum.POLICY_DENIED.getCode()
                    : RunStatusEnum.FAILED.getCode());
            runLog.setErrorCode(errorCode);
            runLog.setDurationMs(durationMs);
            JSONObject summary = new JSONObject();
            summary.put("proxy", requestLine);
            summary.put("outputPreview", preview(result.getData(), sensitive));
            runLog.setRedactedSummary(summary.toJSONString());
            runLog.setCreated(new Date());
            runLogRepository.insertAndTrim(runLog);
        } catch (Exception e) {
            log.warn("[connector] 代理审计写入失败 service={}", provider.getService(), e);
        }
    }

    /**
     * 脱敏摘要：只留输入字段名与输出预览，凭证值永不入审计。
     */
    private String buildRedactedSummary(Map<String, Object> args, ExecResultDto result,
                                        Collection<String> sensitive) {
        JSONObject summary = new JSONObject();
        summary.put("inputKeys", args == null ? List.of() : args.keySet().stream().sorted().toList());
        Object httpStatus = result.getMeta().get("httpStatus");
        if (httpStatus != null) {
            summary.put("httpStatus", httpStatus);
        }
        summary.put("outputPreview", outputPreview(result, sensitive));
        return summary.toJSONString();
    }

    /**
     * 输出预览。运行时若声明本次输出即凭证明文（meta.sensitivePayload=true），整体省略。
     * <p>
     * {@link #preview} 的脱敏是「把 credentialJson 的叶子值逐个替换成 ***」，只能盖住凭证
     * JSON 里本来就有的字段。像 get_auth_config 下发的 client_secret 来自 oauth-config，
     * 不在那个集合里，靠 preview 兜不住——所以由产出凭证的运行时显式声明，审计侧直接不记。
     */
    private String outputPreview(ExecResultDto result, Collection<String> sensitive) {
        if (Boolean.TRUE.equals(result.getMeta().get(ConnectorConstants.META_SENSITIVE_PAYLOAD))) {
            return "[已省略：本次输出为凭证明文]";
        }
        return preview(result.getData(), sensitive);
    }

    /**
     * 输出预览：先用本次注入的凭证值反向擦洗（防上游回显泄漏），再截断 500 字。
     */
    private String preview(Object data, Collection<String> sensitive) {
        if (data == null) {
            return "";
        }
        String text = data instanceof String s ? s : JSON.toJSONString(data);
        for (String value : sensitive) {
            if (StringUtils.isNotEmpty(value)) {
                text = text.replace(value, "***");
            }
        }
        return text.length() <= 500 ? text : text.substring(0, 500) + "...(truncated)";
    }

    /**
     * 从凭证 JSON 提取敏感值（长度≥8 的叶子字符串），用于审计预览反向擦洗。
     * 解析失败时整段视为敏感，宁错杀不漏出。
     */
    private Collection<String> sensitiveValues(String credentialJson) {
        List<String> values = new ArrayList<>();
        if (StringUtils.isBlank(credentialJson)) {
            return values;
        }
        try {
            collectLeaves(JSON.parseObject(credentialJson), values);
        } catch (Exception e) {
            values.add(credentialJson);
        }
        return values;
    }

    private void collectLeaves(Object node, List<String> out) {
        if (node instanceof JSONObject obj) {
            for (Map.Entry<String, Object> entry : obj.entrySet()) {
                collectLeaves(entry.getValue(), out);
            }
        } else if (node instanceof JSONArray arr) {
            for (Object item : arr) {
                collectLeaves(item, out);
            }
        } else if (node instanceof String s && s.length() >= 8) {
            out.add(s);
        }
    }

    private boolean isPolicyDenied(String errorCode) {
        return ConnectorErrorCode.SSRF_BLOCKED.getCode().equals(errorCode);
    }

    private String buildProxyUrl(String baseUrl, String relativePath, Map<String, String> query) {
        if (StringUtils.isBlank(baseUrl)) {
            throw new ConnectorException(ConnectorErrorCode.PROXY_NOT_ENABLED, "provider 缺少 base_url");
        }
        StringBuilder url = new StringBuilder(baseUrl.replaceAll("/+$", "")).append(relativePath);
        if (!query.isEmpty()) {
            url.append('?');
            boolean first = true;
            for (Map.Entry<String, String> entry : query.entrySet()) {
                if (!first) {
                    url.append('&');
                }
                url.append(java.net.URLEncoder.encode(entry.getKey(), java.nio.charset.StandardCharsets.UTF_8))
                        .append('=')
                        .append(java.net.URLEncoder.encode(entry.getValue(), java.nio.charset.StandardCharsets.UTF_8));
                first = false;
            }
        }
        return url.toString();
    }

    private Map<String, String> mergeQuery(Map<String, String> authQuery, Map<String, String> query) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (query != null) {
            merged.putAll(query);
        }
        if (authQuery != null) {
            merged.putAll(authQuery);
        }
        return merged;
    }
}
