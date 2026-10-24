package com.xspaceagi.connector.application.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.connector.application.exec.runtime.AuthConfigRuntime;
import com.xspaceagi.connector.application.support.ConnectorFieldLimits;
import com.xspaceagi.connector.domain.entity.ConnectorConnection;
import com.xspaceagi.connector.domain.entity.ConnectorOauthConfig;
import com.xspaceagi.connector.domain.entity.ConnectorOauthState;
import com.xspaceagi.connector.domain.entity.ConnectorProvider;
import com.xspaceagi.connector.domain.service.ConnectorCatalogService;
import com.xspaceagi.connector.domain.service.ConnectorOAuthService;
import com.xspaceagi.connector.domain.service.CredentialVaultService;
import com.xspaceagi.connector.infra.crypto.AesGcmCipher;
import com.xspaceagi.connector.infra.http.GuardedHttpClient;
import com.xspaceagi.connector.infra.repository.ConnectorConnectionRepository;
import com.xspaceagi.connector.infra.repository.ConnectorOauthConfigRepository;
import com.xspaceagi.connector.infra.repository.ConnectorOauthStateRepository;
import com.xspaceagi.connector.spec.enums.AuthTypeEnum;
import com.xspaceagi.connector.spec.enums.ConnectionStatusEnum;
import com.xspaceagi.connector.spec.enums.CredTypeEnum;
import com.xspaceagi.connector.spec.enums.OAuthScopeTypeEnum;
import com.xspaceagi.connector.spec.error.ConnectorErrorCode;
import com.xspaceagi.connector.spec.exception.ConnectorException;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.spec.common.RequestContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

/**
 * OAuth2 建连实现，含两种机制（设计文档 §4.3 / §9.3）：
 * <ul>
 *   <li><b>授权码 + PKCE</b>（{@link AuthTypeEnum#OAUTH2}）：state 随 URL 传给 IdP，回调换 token</li>
 *   <li><b>设备码扫码</b>（{@link AuthTypeEnum#OAUTH2_DEVICE}，RFC 8628）：出二维码让用户在 App 内
 *       确认，服务端轮询令牌端点。不需要 redirect_uri，也不依赖公网回调地址</li>
 * </ul>
 * 两者的<b>凭证形态完全相同</b>，故建连尾段收口在 {@link #completeConnection}，令牌刷新
 * （{@link #refresh}）也共用一份——飞书两种流程打的是同一个 token 端点。
 * <p>
 * <b>扫码自注册</b>（{@link AuthTypeEnum#OAUTH2_DEVICE} 的一种形态）：上面两条路径都要求先有人手工
 * 注册好 OAuth App 并把 client_id/client_secret 填进 {@code connector_oauth_config}。自注册把顺序反
 * 过来——<b>扫码这个动作本身就是在 IdP 上注册一个 App</b>，扫完拿到 client_id/client_secret，落到
 * 用户维度（{@code scope_type=byo}、{@code owner_id=当前用户}）。因为
 * {@link ConnectorOauthConfigRepository#resolveForUser} 本来就是「先 byo 后 platform」，
 * 「后续调用工具优先取用户维度配置」由落点本身保证，解析逻辑一行没改。
 * <p>
 * 走不走自注册只由一个事实决定，见 {@link #needsSelfRegistration}：<b>该用户此刻解析得到的那行配置
 * 有没有 client_id</b>。没有，就是「用户自注册类型」，注册完 client_id 落地，第二次扫码自动转成常规
 * 设备授权——同一条连接器两种形态来回切，不靠任何开关。<b>注意 {@code oauth_app_mode} 不参与这个判定</b>。
 * <p>
 * 注册协议参数从哪来，见 {@link #resolveRegistrationSpec}：provider 声明了
 * {@value #AUTH_CONFIG_APP_REGISTRATION} 就用声明的；没声明但设备授权端点命中已知 IdP 画像
 * （{@link #presetAppRegistration}，目前是飞书/Lark）就自动补齐。所以「{@code oauth2_device} 只要填
 * authUrl + tokenUrl」这句在飞书上直接成立，其它 IdP 仍要显式声明。两个端点与 scopes 由
 * {@link #persistRegisteredApp} 从手上那行配置兜底。
 * provider 行本身<b>不承载任何密钥</b>——协议参数（端点、确认页模板、archetype 等）在
 * {@code auth_config}，密钥在用户维度的 {@code connector_oauth_config}。
 * <p>
 * 共同的安全约束：
 * <ul>
 *   <li>state：32B 随机值，SHA-256 哈希为主键落库，原子消费防重放</li>
 *   <li>PKCE verifier / device_code：加密存储，明文只在服务端内存里出现</li>
 *   <li>client_secret：AES-256-GCM 加密存 connector_oauth_config.client_secret_enc</li>
 *   <li>token：只进凭证保险库；刷新轮换走 revision 乐观锁写回</li>
 *   <li>OAuth 只用于建立连接，不颁发平台会话（§9.3）</li>
 * </ul>
 */
@Slf4j
@Service
public class ConnectorOAuthServiceImpl implements ConnectorOAuthService {

    private static final long STATE_TTL_MS = 15 * 60 * 1000L;
    private static final long TOKEN_EXCHANGE_TIMEOUT_MS = 30_000L;
    /**
     * access_token 剩余有效期低于该值时提前刷新
     */
    private static final long REFRESH_SKEW_MS = 60_000L;
    private static final String CALLBACK_PATH = "/api/connector/oauth/callback";
    /**
     * provider.oauth_app_mode 的 byo 取值。与 {@code connector_oauth_config.scope_type} 的 byo
     * 字面相同但含义不同：前者说「这台连接器的 App 归谁提供」，后者说「这行配置属于谁」，
     * 所以不去借 {@code OAuthScopeTypeEnum} 的常量，各留各的。
     */
    private static final String OAUTH_APP_MODE_BYO = "byo";

    /**
     * 设备码流程：RFC 8628 规定的 grant_type 字面值
     */
    private static final String DEVICE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code";
    /**
     * 设备码流程：拿到 refresh_token 的必要 scope，配置里漏了会自动补
     */
    private static final String SCOPE_OFFLINE_ACCESS = "offline_access";
    /**
     * 设备码流程：飞书未返回 expires_in 时的兜底授权窗口（实测返回 600）
     */
    private static final int DEVICE_DEFAULT_EXPIRES_IN = 600;
    /**
     * 设备码流程：飞书未返回 interval 时的兜底轮询间隔（实测返回 5）
     */
    private static final int DEVICE_DEFAULT_INTERVAL = 5;
    /**
     * 设备码流程：轮询间隔上限，防 slow_down 累加失控（与 lark-cli 一致）
     */
    private static final int DEVICE_MAX_INTERVAL = 60;

    /**
     * provider.auth_config 里承载扫码自注册协议参数的对象名：注册端点、archetype、auth_method、
     * request_user_info、确认页模板。单独套一层对象而不是平铺，是为了让「这台连接器支持自注册」
     * 这件事在配置里一眼可辨，也留出与其它 auth_config 键并存的空间。
     */
    private static final String AUTH_CONFIG_APP_REGISTRATION = "appRegistration";

    /**
     * 已知 IdP 的自注册协议画像：设备授权端点所在主机命中这些后缀时，就算 provider 没声明
     * {@value #AUTH_CONFIG_APP_REGISTRATION}，也能把注册协议参数补齐（见 {@link #presetAppRegistration}）。
     * <p>
     * <b>为什么要有这个画像</b>：产品上「{@code oauth2_device} 只需填授权端点 + 令牌端点」这句话要成立，
     * 就得有人知道「去哪注册、以什么 archetype/认证方式注册」。飞书这套参数是固定的公开协议事实，
     * 让每个建连接器的人都手抄一遍既没意义、抄错还只有一句含糊的 IdP 报错。没命中画像的 IdP
     * 行为完全不变：照旧读 {@value #AUTH_CONFIG_APP_REGISTRATION}，不猜。
     */
    private static final Set<String> SELF_REGISTRATION_HOST_SUFFIXES =
            Set.of("feishu.cn", "larksuite.com");
    /**
     * 飞书注册协议里，设备授权端点 → 注册端点的同源同层路径
     */
    private static final String DEVICE_AUTHORIZATION_PATH = "/device_authorization";
    private static final String APP_REGISTRATION_PATH = "/app/registration";

    /**
     * provider.auth_config 里可选的「顺手换一张应用身份令牌」配置（如飞书的 tenant_access_token）。
     * <p>
     * 它是<b>可选的</b>：配了，扫码自注册建连时服务端就用刚拿到的 client_id/client_secret 换一张应用
     * 令牌存进凭证，连接器的代理动作立刻可用；不配，连接只发放 App 凭证本身，由客户端自己去换。
     * 两种形态共用同一条代码路径，差别只在有没有这个键。
     */
    private static final String AUTH_CONFIG_TENANT_TOKEN = "tenantToken";

    /**
     * provider.auth_config 里可选的「该 App 的实时权限清单」配置：一个返回应用权限的端点 +
     * 从中取 scope 名的路径。配了它，发起用户授权前会先拉一次清单，把手写 scope 补齐到「该 App
     * 确实拥有的全部可用权限」。
     * <p>
     * 为什么必须补：设备授权的 scope 参数目前来自配置行里手写的清单，而手写清单必漏。漏一个 scope
     * 对应的动作就是 {@code 99991679 用户权限不足}，且只能重新走一次授权才能补——在用户已经扫过码
     * 之后才发现，代价远高于在发起前多打一次 GET。
     * <p>
     * 不配这个键的连接器行为与改动前完全一致。
     */
    private static final String AUTH_CONFIG_SCOPE_CATALOG = "scopeCatalog";

    /**
     * 把裸令牌拼成 Authorization 头的方案前缀。
     * <p>
     * 特意做成常量而不是从 {@code tenantToken.tokenType} 读：那个字段是<b>令牌种类</b>标记
     * （{@code Tenant}/{@code App}，供 identity 归一），默认值恰好长得像 「Bearer」纯属巧合。
     * 两者混用会静默发出 {@code Authorization: Tenant t-xxx}——飞书按「没带令牌」处理并回 400，
     * 而这个 400 的错误体只说 field validation failed，从现象倒推不回认证头。
     */
    private static final String BEARER_PREFIX = "Bearer ";

    private final SecureRandom secureRandom = new SecureRandom();

    @Resource
    private ConnectorCatalogService catalogService;

    @Resource
    private ConnectorOauthConfigRepository oauthConfigRepository;

    @Resource
    private ConnectorOauthStateRepository oauthStateRepository;

    @Resource
    private ConnectorConnectionRepository connectionRepository;

    @Resource
    private CredentialVaultService vaultService;

    @Resource
    private AesGcmCipher cipher;

    @Resource
    private GuardedHttpClient guardedHttpClient;

    // ---------- 发起授权 ----------

    @Override
    public String buildAuthorizeUrl(String providerService, Long spaceId) {
        Long userId = RequestContext.get().getUserId();
        ConnectorProvider provider = requireAuthCodeProvider(providerService);
        ConnectorOauthConfig config = oauthConfigRepository.resolveForUser(providerService, userId);
        if (config == null) {
            throw new ConnectorException(ConnectorErrorCode.OAUTH_CONFIG_MISSING,
                    oauthAppMissingMessage(provider));
        }
        String redirectBaseUrl = ((TenantConfigDto) RequestContext.get().getTenantConfig()).getSiteUrl();
        if (StringUtils.isBlank(redirectBaseUrl)) {
            throw new ConnectorException(ConnectorErrorCode.OAUTH_CONFIG_MISSING,
                    "未配置 nuwax.connector.oauth.redirect-base-url，无法发起 OAuth 授权");
        }

        String state = randomToken(32);
        String verifier = randomToken(48);
        ConnectorOauthState stateRow = new ConnectorOauthState();
        stateRow.setStateHash(sha256Hex(state));
        stateRow.setProviderService(providerService);
        stateRow.setUserId(userId);
        stateRow.setSpaceId(spaceId);
        stateRow.setPkceVerifierEnc(cipher.encrypt(verifier));
        stateRow.setConsumed(0);
        Date now = new Date();
        stateRow.setExpiresAt(new Date(now.getTime() + STATE_TTL_MS));
        stateRow.setCreated(now);
        oauthStateRepository.insert(stateRow);

        StringBuilder url = new StringBuilder(config.getAuthUrl());
        url.append(config.getAuthUrl().contains("?") ? '&' : '?');
        url.append("response_type=code");
        url.append("&client_id=").append(encode(config.getClientId()));
        url.append("&redirect_uri=").append(encode(buildRedirectUri(redirectBaseUrl)));
        String scope = joinScopes(config.getScopes());
        if (StringUtils.isNotBlank(scope)) {
            url.append("&scope=").append(encode(scope));
        }
        url.append("&state=").append(encode(state));
        url.append("&code_challenge=").append(encode(pkceChallenge(verifier)));
        url.append("&code_challenge_method=S256");
        log.info("[connector] oauth authorize issued: user={}, provider={}, scopeType={}",
                userId, providerService, config.getScopeType());
        return url.toString();
    }

    // ---------- 回调 ----------

    @Override
    public CallbackOutcome handleCallback(String state, String code, String providerError) {
        oauthStateRepository.purgeExpired();

        if (StringUtils.isNotBlank(providerError)) {
            return new CallbackOutcome(false, null, null, "授权被拒绝或中止: " + providerError, null);
        }
        if (StringUtils.isBlank(state) || StringUtils.isBlank(code)) {
            return new CallbackOutcome(false, null, null, "回调参数缺失（state/code）", null);
        }

        ConnectorOauthState stateRow = oauthStateRepository.consumeAtomically(sha256Hex(state));
        if (stateRow == null) {
            boolean existed = oauthStateRepository.exists(sha256Hex(state));
            String message = existed ? "授权链接已失效（已使用或超时），请重新发起" : "state 无效，请重新发起授权";
            return new CallbackOutcome(false, null, null, message, null);
        }

        ConnectorProvider provider;
        try {
            provider = catalogService.requireProvider(stateRow.getProviderService());
        } catch (ConnectorException e) {
            return new CallbackOutcome(false, stateRow.getProviderService(), null, e.getMessage(), null);
        }

        // 有登录态就严格比对，避免「已登录用户拿到他人 state」被静默放行；
        // 无登录态（回调路径已在 auth.excludePath 免登录，浏览器 cookie 可能缺失或过期）
        // 则信任 state 里记录的用户：state 是 32B 随机值、sha256 落库、15 分钟过期、
        // 一次性消费，只有发起授权的那个浏览器拿得到，本身即「已认证用户」的载体。
        Long sessionUserId = RequestContext.get().getUserId();
        if (sessionUserId != null && !stateRow.getUserId().equals(sessionUserId)) {
            log.warn("[connector] oauth callback 用户不匹配: stateUser={}, sessionUser={}",
                    stateRow.getUserId(), sessionUserId);
            return new CallbackOutcome(false, provider.getService(), provider.getDisplayName(),
                    "回调用户与发起授权的用户不一致", null);
        }

        try {
            ConnectorOauthConfig config = oauthConfigRepository.resolveForUser(
                    stateRow.getProviderService(), stateRow.getUserId());
            if (config == null) {
                throw new ConnectorException(ConnectorErrorCode.OAUTH_CONFIG_MISSING);
            }
            String verifier = cipher.decrypt(stateRow.getPkceVerifierEnc());
            JSONObject tokenResponse = exchangeCode(config, code, verifier);
            Long connectionId = completeConnection(stateRow, provider, config, tokenResponse, false);
            return new CallbackOutcome(true, provider.getService(), provider.getDisplayName(),
                    "连接成功", connectionId);
        } catch (ConnectorException e) {
            log.warn("[connector] oauth 回调失败 provider={}: {}", stateRow.getProviderService(), e.getMessage());
            return new CallbackOutcome(false, provider.getService(), provider.getDisplayName(), e.getMessage(), null);
        } catch (Exception e) {
            log.error("[connector] oauth 回调异常 provider={}", stateRow.getProviderService(), e);
            return new CallbackOutcome(false, provider.getService(), provider.getDisplayName(),
                    "授权完成处理异常，请重试", null);
        }
    }

    /**
     * 建连尾段：token 响应 → 凭证入库 → 建 connection。授权码回调与设备码轮询<b>共用这一份</b>，
     * 以保证两条路径产出的凭证形状、别名截断、grantedScopes、oauthMeta 结构必然一致——
     * 两者本就打同一个 token 端点、返回同一套字段，没有理由各写一遍。
     *
     * @param deviceFlow 仅影响 oauthMeta 是否记录设备码特有字段
     * @return 新建连接的 id
     */
    private Long completeConnection(ConnectorOauthState stateRow, ConnectorProvider provider,
                                    ConnectorOauthConfig config, JSONObject tokenResponse,
                                    boolean deviceFlow) {
        String accessToken = tokenResponse.getString("access_token");
        if (StringUtils.isBlank(accessToken)) {
            throw new ConnectorException(ConnectorErrorCode.OAUTH_EXCHANGE_FAILED,
                    "token 响应缺少 access_token");
        }
        JSONObject credential = new JSONObject();
        credential.put("accessToken", accessToken);
        credential.put("refreshToken", tokenResponse.getString("refresh_token"));
        credential.put("tokenType", tokenResponse.getString("token_type"));
        // appId 一并存进凭证：get_auth_config 要把 (appId, accessToken) 成对发给客户端
        // （对应 lark-cli 的 LARKSUITE_CLI_APP_ID + LARKSUITE_CLI_USER_ACCESS_TOKEN 注入组合）。
        credential.put("appId", config.getClientId());
        Date expiresAt = resolveExpiresAt(tokenResponse);
        long credentialId = vaultService.store(CredTypeEnum.OAUTH2_TOKEN.getCode(),
                credential.toJSONString(), expiresAt);

        String grantedScope = StringUtils.defaultIfBlank(tokenResponse.getString("scope"),
                joinScopes(config.getScopes()));
        JSONObject oauthMeta = new JSONObject();
        oauthMeta.put("scopeType", config.getScopeType());
        oauthMeta.put("configId", config.getId());
        // 身份写进 oauthMeta：同一个 provider 下会并存「应用身份」与「用户身份」两条连接
        // （扫码自注册产出前者，之后再次扫码做用户授权产出后者），而两条连接的 name 完全相同，
        // 界面上原本分不出谁是谁、也解释不了「怎么多了一条连接」。
        oauthMeta.put("identity", AuthConfigRuntime.resolveIdentity(tokenResponse.getString("token_type")));
        if (deviceFlow) {
            oauthMeta.put("deviceFlow", true);
            if (StringUtils.isBlank(tokenResponse.getString("refresh_token"))) {
                // 没拿到 refresh_token 意味着这条连接无法自救，过期后只能重新扫码。
                // 最常见的原因是配置的 scope 里缺 offline_access（本类会自动补，但 IdP 可能不认）。
                log.warn("[connector] device flow 未拿到 refresh_token，连接将在 access_token 过期后失效:"
                        + " provider={}", provider.getService());
            }
            // refresh_token 单独有效期（飞书缺省 604800 秒 = 7 天）。记下来便于提前提示用户
            // 重新扫码，而不是等刷新失败才发现。IdP 没给就退化成与 access_token 同期。
            Long refreshExpiresIn = tokenResponse.getLong("refresh_token_expires_in");
            if (refreshExpiresIn == null || refreshExpiresIn <= 0) {
                refreshExpiresIn = tokenResponse.getLong("expires_in");
            }
            if (refreshExpiresIn != null && refreshExpiresIn > 0) {
                oauthMeta.put("refreshTokenExpiresAt",
                        System.currentTimeMillis() + refreshExpiresIn * 1000L);
            }
        }
        Long connectionId = insertConnection(stateRow, provider, credentialId, grantedScope, null, oauthMeta);
        log.info("[connector] oauth connection established: user={}, provider={}, connectionId={}, deviceFlow={}",
                stateRow.getUserId(), provider.getService(), connectionId, deviceFlow);
        return connectionId;
    }

    /**
     * 建 connection 行。三条建连路径（授权码回调、设备码轮询、扫码自注册）共用这一份，
     * 保证别名截断、status/enabled 初值、oauth_meta 结构必然一致——它们的差别只在凭证形状与
     * oauth_meta 内容，行本身的写法没有理由各写一遍。
     *
     * <p>
     * <b>同 provider 同身份只保留一条</b>：命中既有行时改为更新它并收敛重复行，而不是再插一条。
     * 「重新扫码」在语义上是刷新那条连接、不是堆一条新的，理由见 {@link #reuseConnection}。
     *
     * @param profile 连接画像（JSON 字符串），可为 null；目前只有自注册路径回填
     * @return 连接 id：新建的行，或复用/更新的既有行
     */
    private Long insertConnection(ConnectorOauthState stateRow, ConnectorProvider provider,
                                  long credentialId, String grantedScope, String profile,
                                  JSONObject oauthMeta) {
        List<ConnectorConnection> sameIdentity = findBySameIdentity(
                RequestContext.get().getTenantId(), stateRow.getUserId(),
                provider.getService(), oauthMeta.getString("identity"));
        if (!sameIdentity.isEmpty()) {
            return reuseConnection(sameIdentity, stateRow, provider, credentialId, grantedScope,
                    profile, oauthMeta);
        }
        ConnectorConnection connection = new ConnectorConnection();
        connection.setTenantId(RequestContext.get().getTenantId());
        connection.setSpaceId(stateRow.getSpaceId());
        connection.setUserId(stateRow.getUserId());
        connection.setProviderService(provider.getService());
        // 别名取 provider 展示名（可到 128），按 connector_connection.name 列宽 64 截断
        connection.setName(ConnectorFieldLimits.connectionAlias(null, provider.getDisplayName()));
        connection.setStatus(ConnectionStatusEnum.ACTIVE.getCode());
        // 显式置启用，不依赖列默认值：刚建立的连接默认可用
        connection.setEnabled(true);
        connection.setCredentialId(credentialId);
        connection.setGrantedScopes(JSON.toJSONString(splitScopes(grantedScope)));
        connection.setProfile(profile);
        connection.setOauthMeta(oauthMeta.toJSONString());
        Date now = new Date();
        connection.setCreated(now);
        connection.setModified(now);
        connectionRepository.insert(connection);
        return connection.getId();
    }

    /**
     * 取该用户在该 provider 下、身份与本次建连相同且尚未断开的连接，按 id <b>升序</b>。
     * <p>
     * 升序是为了让 {@link #reuseConnection} 留下的那条与执行时选中那条是同一行：
     * {@code ConnectorConnectionRepository.firstExecutable} 用 {@code orderByAsc(id)} 取可执行连接，
     * 而 {@code listByUser} 是倒序——两者顺序正好相反。照 {@code listByUser} 的顺序复用会更新
     * 「最新」那条，执行却仍取「最旧」那条，重新扫码等于白扫。
     * <p>
     * identity 为空时返回空列表（不复用）：存量行与 api_key 直连都没有这个键，拿默认值猜出来的
     * 身份会让复用张冠李戴，宁可新建一条。
     */
    private List<ConnectorConnection> findBySameIdentity(Long tenantId, Long userId,
                                                         String providerService, String identity) {
        if (StringUtils.isBlank(identity)) {
            return List.of();
        }
        return connectionRepository.listByUser(tenantId, userId, providerService).stream()
                .filter(connection -> identity.equals(readConnectionIdentity(connection)))
                .sorted(Comparator.comparing(ConnectorConnection::getId))
                .toList();
    }

    /**
     * 从连接的 oauth_meta 里读建连时写下的 {@code identity}。
     * <p>
     * 解析不出来返回 null 而不是兜底成 {@code user}：存量连接（以及所有 api_key/静态令牌建连）
     * 都没有这个键，猜出来的身份会直接参与复用判定。
     * <p>
     * 与 UI 层 {@code ConnectorDtoConverter.readIdentity} 是同一份规则，但那边的方向是 UI 依赖
     * application，反过来复用会形成反向依赖，故各自实现一份。
     */
    private String readConnectionIdentity(ConnectorConnection connection) {
        if (StringUtils.isBlank(connection.getOauthMeta())) {
            return null;
        }
        try {
            JSONObject meta = JSON.parseObject(connection.getOauthMeta());
            return meta == null ? null : meta.getString("identity");
        } catch (Exception e) {
            // oauth_meta 非 JSON（历史数据）等同于「身份未知」
            return null;
        }
    }

    /**
     * 把本次扫码换来的凭证与元数据写回既有连接，并收敛同身份的重复行。
     * <p>
     * <b>为什么必须复用而不是新建</b>：建连是纯 insert 时，同一个身份会随每次扫码再多一行，
     * 而 {@code firstExecutable} 恒取 id 最小的那条——用户因为旧连接过期、或想换账号而重新扫码，
     * 界面列出的是新行（{@code listByUser} 倒序），执行用的却仍是最旧的死行。自愈动作静默失效，
     * 而且不报任何错。
     * <p>
     * <b>为什么收敛多余行</b>：同 provider 同身份的多行指向同一个 IdP 身份，执行时永远轮不到
     * （{@code firstExecutable} 只取一条），留着只会让界面看起来像「连接凭空多了一条」。
     * 收敛<b>只在同 identity 内</b>进行：应用身份（{@code tenant}）与用户身份（{@code user}）
     * 是这个连接器上按设计并存的两条不同连接，互不影响。
     *
     * @param sameIdentity 同身份的既有连接，按 id 升序，非空
     * @return 保留下来的那条连接 id
     */
    private Long reuseConnection(List<ConnectorConnection> sameIdentity, ConnectorOauthState stateRow,
                                 ConnectorProvider provider, long credentialId, String grantedScope,
                                 String profile, JSONObject oauthMeta) {
        ConnectorConnection keeper = sameIdentity.get(0);
        long staleCredentialId = keeper.getCredentialId();
        // 别名不动：建连路径的别名恒为 provider 展示名，但该列允许用户改名，复用不该把它冲掉
        keeper.setSpaceId(stateRow.getSpaceId());
        keeper.setCredentialId(credentialId);
        keeper.setGrantedScopes(JSON.toJSONString(splitScopes(grantedScope)));
        keeper.setProfile(profile);
        keeper.setOauthMeta(oauthMeta.toJSONString());
        // 本次扫码换来了新凭证，健康度与用户开关都回到可用——用户的动作就是「连接」，
        // 留下一个 enabled=false 的结果等于让他再点一次启用
        keeper.setStatus(ConnectionStatusEnum.ACTIVE.getCode());
        keeper.setEnabled(true);
        keeper.setModified(new Date());
        connectionRepository.updateById(keeper);

        for (ConnectorConnection duplicate : sameIdentity.subList(1, sameIdentity.size())) {
            connectionRepository.deleteById(duplicate.getId());
            deleteCredentialQuietly(duplicate.getCredentialId());
            log.info("[connector] duplicate connection collapsed: user={}, provider={}, kept={},"
                            + " removed={}, identity={}", keeper.getUserId(), provider.getService(),
                    keeper.getId(), duplicate.getId(), oauthMeta.getString("identity"));
        }
        if (staleCredentialId != credentialId) {
            deleteCredentialQuietly(staleCredentialId);
        }
        log.info("[connector] oauth connection refreshed: user={}, provider={}, connectionId={},"
                        + " identity={}", keeper.getUserId(), provider.getService(), keeper.getId(),
                oauthMeta.getString("identity"));
        return keeper.getId();
    }

    /**
     * 删凭证，失败只记一条 warn。
     * <p>
     * 调用点都在建连主流程已经落库之后：孤儿凭证只占一行、不影响任何连接的可用性，
     * 不该把一次成功建连带成失败（用户会看到「连接失败」，而连接其实已经好了）。
     */
    private void deleteCredentialQuietly(long credentialId) {
        try {
            vaultService.delete(credentialId);
        } catch (Exception e) {
            log.warn("[connector] 旧凭证清理失败，遗留孤儿凭证: credentialId={}", credentialId, e);
        }
    }

    // ---------- token 刷新（OAuthTokenProvider） ----------

    @Override
    public String validAccessToken(ConnectorConnection connection) {
        return validAccessToken(connection, REFRESH_SKEW_MS);
    }

    @Override
    public String validAccessToken(ConnectorConnection connection, long refreshSkewMs) {
        CredentialVaultService.Meta meta = vaultService.peek(connection.getCredentialId());
        if (meta == null) {
            throw new ConnectorException(ConnectorErrorCode.CREDENTIAL_NOT_FOUND);
        }
        // 未过期（留 refreshSkewMs 余量）直接取
        if (meta.expiresAt() == null
                || meta.expiresAt().getTime() > System.currentTimeMillis() + refreshSkewMs) {
            return readAccessToken(connection.getCredentialId());
        }
        return refresh(connection, meta);
    }

    private String refresh(ConnectorConnection connection, CredentialVaultService.Meta meta) {
        ConnectorOauthConfig config = oauthConfigRepository.resolveForUser(
                connection.getProviderService(), connection.getUserId());
        if (config == null) {
            throw new ConnectorException(ConnectorErrorCode.OAUTH_CONFIG_MISSING,
                    "OAuth App 配置缺失，无法刷新令牌: " + connection.getProviderService());
        }
        JSONObject credential = JSON.parseObject(vaultService.reveal(connection.getCredentialId()));
        String refreshToken = credential.getString("refreshToken");
        String appId = credential.getString("appId");
        if (StringUtils.isBlank(refreshToken) && StringUtils.isNotBlank(appId)) {
            // 扫码自注册的连接。它的「续期」不是 OAuth2 的 refresh_token grant——手上根本没有
            // refresh_token，只有那对 App 凭证，所以是拿同一对凭证再换一次应用令牌。
            // 判据与 readAccessToken 里分派「应用身份」的那条一致：有 appId 且无 refreshToken。
            return refreshAppToken(connection, meta, config, credential, appId);
        }
        if (StringUtils.isBlank(refreshToken)) {
            markExpired(connection);
            throw new ConnectorException(ConnectorErrorCode.OAUTH_REFRESH_FAILED,
                    "无 refresh_token，请重新授权: " + connection.getProviderService());
        }

        try {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", "refresh_token");
            form.put("refresh_token", refreshToken);
            form.put("client_id", config.getClientId());
            form.put("client_secret", requireClientSecret(config));
            JSONObject tokenResponse = postTokenRequest(config.getTokenUrl(), form);
            String accessToken = tokenResponse.getString("access_token");
            if (StringUtils.isBlank(accessToken)) {
                throw new ConnectorException(ConnectorErrorCode.OAUTH_REFRESH_FAILED,
                        "刷新响应缺少 access_token");
            }
            // 轮换：部分 IdP 下发新 refresh_token
            String newRefresh = StringUtils.defaultIfBlank(
                    tokenResponse.getString("refresh_token"), refreshToken);
            // 在既有凭证对象上覆盖令牌字段，而不是新建一个：新建会静默丢掉附加字段
            // （completeConnection 存的 appId 供 get_auth_config 发放，刷新一次就没了会很难查）。
            credential.put("accessToken", accessToken);
            credential.put("refreshToken", newRefresh);
            credential.put("tokenType", tokenResponse.getString("token_type"));
            return writeBackRefreshedToken(connection, meta, credential, accessToken,
                    resolveExpiresAt(tokenResponse));
        } catch (ConnectorException e) {
            markExpired(connection);
            throw e;
        } catch (Exception e) {
            markExpired(connection);
            throw new ConnectorException(ConnectorErrorCode.OAUTH_REFRESH_FAILED,
                    "令牌刷新异常: " + e.getMessage(), e);
        }
    }

    /**
     * 应用身份令牌的续期：拿连接自己的 App 凭证（{@code appId} + byo 行里的 client_secret）
     * 再换一张。没有 user 参与，所以不存在「重新授权」这种补救动作——配置还在就能一直续。
     * <p>
     * 端点取自 provider.auth_config 而非 oauth_config：它是协议参数、不是密钥，和注册端点同一处管理。
     */
    private String refreshAppToken(ConnectorConnection connection, CredentialVaultService.Meta meta,
                                   ConnectorOauthConfig config, JSONObject credential, String appId) {
        ConnectorProvider provider = catalogService.requireProvider(connection.getProviderService());
        AppTokenSpec spec = resolveAppTokenSpec(provider);
        if (spec == null) {
            markExpired(connection);
            throw new ConnectorException(ConnectorErrorCode.OAUTH_REFRESH_FAILED,
                    "该连接是扫码自注册的应用身份（appId=" + appId + "），但 provider 未配置 "
                            + AUTH_CONFIG_TENANT_TOKEN + "，无令牌可续: " + connection.getProviderService());
        }
        try {
            JSONObject tokenResponse = postAppTokenRequest(spec, appId, requireClientSecret(config));
            String accessToken = tokenResponse.getString(spec.tokenField());
            if (StringUtils.isBlank(accessToken)) {
                throw new ConnectorException(ConnectorErrorCode.OAUTH_REFRESH_FAILED,
                        "应用令牌续期响应缺少 " + spec.tokenField());
            }
            credential.put("accessToken", accessToken);
            credential.put("tokenType", spec.tokenType());
            // 不动 refreshToken：这条路径上它本来就该缺失，凭空写一个会破坏「有无 refreshToken」
            // 这个判定依据，下次刷新就走错分支了。
            return writeBackRefreshedToken(connection, meta, credential, accessToken,
                    resolveSpecExpiresAt(tokenResponse, spec.expiresField()));
        } catch (ConnectorException e) {
            markExpired(connection);
            throw e;
        } catch (Exception e) {
            markExpired(connection);
            throw new ConnectorException(ConnectorErrorCode.OAUTH_REFRESH_FAILED,
                    "应用令牌续期异常: " + e.getMessage(), e);
        }
    }

    /**
     * 两条刷新路径共用的收尾：写回凭证 + 处理并发冲突 + 记 lastRefreshAt。
     * <p>
     * 调用方必须已经把新令牌塞进 {@code credential}——本方法只管落盘。
     */
    private String writeBackRefreshedToken(ConnectorConnection connection, CredentialVaultService.Meta meta,
                                           JSONObject credential, String accessToken, Date expiresAt) {
        boolean written = vaultService.writeBack(connection.getCredentialId(),
                credential.toJSONString(), expiresAt, meta.revision());
        if (!written) {
            // 并发刷新：另一节点已写回，重读即可
            CredentialVaultService.Meta latest = vaultService.peek(connection.getCredentialId());
            if (latest != null && latest.expiresAt() != null
                    && latest.expiresAt().getTime() > System.currentTimeMillis() + REFRESH_SKEW_MS) {
                return readAccessToken(connection.getCredentialId());
            }
            throw new ConnectorException(ConnectorErrorCode.CREDENTIAL_REVISION_CONFLICT,
                    "令牌刷新冲突，请重试");
        }
        JSONObject oauthMeta = new JSONObject();
        if (StringUtils.isNotBlank(connection.getOauthMeta())) {
            oauthMeta = JSON.parseObject(connection.getOauthMeta());
        }
        oauthMeta.put("lastRefreshAt", new Date().getTime());
        connection.setOauthMeta(oauthMeta.toJSONString());
        connection.setStatus(ConnectionStatusEnum.ACTIVE.getCode());
        connection.setModified(new Date());
        connectionRepository.updateById(connection);
        log.info("[connector] oauth token refreshed: connectionId={}", connection.getId());
        return accessToken;
    }

    private String readAccessToken(long credentialId) {
        JSONObject credential = JSON.parseObject(vaultService.reveal(credentialId));
        String accessToken = credential.getString("accessToken");
        if (StringUtils.isBlank(accessToken)) {
            // 扫码自注册的连接只发放 App 身份，本来就没有用户令牌。这时报「凭证缺少 access_token」
            // 会把人引向「凭证是不是坏了」，所以分开说，把真正该做的事讲清楚。
            if (StringUtils.isNotBlank(credential.getString("appId"))
                    && StringUtils.isBlank(credential.getString("refreshToken"))) {
                throw new ConnectorException(ConnectorErrorCode.CREDENTIAL_NOT_FOUND,
                        "该连接是扫码自注册的应用身份，不含用户令牌；调用代理动作需由客户端用"
                                + " get_auth_config 下发的 App 凭证自行换取用户令牌");
            }
            throw new ConnectorException(ConnectorErrorCode.CREDENTIAL_NOT_FOUND, "凭证缺少 access_token");
        }
        return accessToken;
    }

    private void markExpired(ConnectorConnection connection) {
        connection.setStatus(ConnectionStatusEnum.EXPIRED.getCode());
        connection.setModified(new Date());
        connectionRepository.updateById(connection);
    }

    // ---------- 配置管理 ----------

    @Override
    public void saveConfig(String providerService, String scopeType, Long ownerId,
                           String clientId, String clientSecretPlain, String authUrl, String tokenUrl,
                           List<String> scopes) {
        ConnectorProvider provider = requireOauthProvider(providerService);
        OAuthScopeTypeEnum type = OAuthScopeTypeEnum.fromCode(scopeType);
        // 先取既有配置：本方法同时服务「首次配置」与「更新」。更新是部分更新——未传的字段沿用旧值，
        // 否则「只想换个 secret」也得把 clientId 与两个端点原样回传一遍，与连接器/动作编辑的语义也不一致。
        ConnectorOauthConfig existing = oauthConfigRepository.getExact(provider.getService(),
                type.getCode(), ownerId);
        // 扫码（oauth2_device）与授权码（oauth2）要的东西不一样，不能拿同一把尺子量：
        //   - oauth2 授权码：没有 client_id 就没有任何授权请求可发，必须齐；
        //   - oauth2_device：client_id 可以没有。没有时走「扫码自注册」——用户在 App 内扫一次码，
        //     平台拿注册协议换回这个用户自己的 client_id/client_secret，所以建连接器时只需要
        //     两个端点（授权端点 authUrl、令牌端点 tokenUrl）。
        // 早先不分类型一律要求 clientId，逼得用户编一个占位值（线上 feishu_cli 那行 client_id='xxxx'），
        // 占位值又让建连走了「平台自有 App」分支，飞书回一句「The specified app does not exist.」——
        // 用户看到的报错和真正的原因（少填一个端点就够）完全对不上。
        boolean deviceFlow = AuthTypeEnum.fromCode(provider.getAuthType()) == AuthTypeEnum.OAUTH2_DEVICE;
        if (existing == null && (StringUtils.isBlank(authUrl) || StringUtils.isBlank(tokenUrl))) {
            throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR,
                    deviceFlow ? "首次配置必须提供 authUrl/tokenUrl（扫码自注册无需 clientId/clientSecret）"
                            : "首次配置必须提供 authUrl/tokenUrl");
        }
        if (existing == null && !deviceFlow && StringUtils.isBlank(clientId)) {
            throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR,
                    "首次配置必须提供 clientId/authUrl/tokenUrl");
        }
        Date now = new Date();
        ConnectorOauthConfig config = existing == null ? new ConnectorOauthConfig() : existing;
        config.setProviderService(provider.getService());
        config.setScopeType(type.getCode());
        config.setOwnerId(ownerId);
        if (StringUtils.isNotBlank(clientId)) {
            config.setClientId(clientId);
        }
        if (StringUtils.isNotBlank(clientSecretPlain)) {
            config.setClientSecretEnc(cipher.encrypt(clientSecretPlain));
        } else if (existing == null && !deviceFlow) {
            // 扫码自注册的 secret 是注册那一刻由 IdP 发的，事前无人可填
            throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR, "首次配置必须提供 clientSecret");
        }
        if (StringUtils.isNotBlank(authUrl)) {
            config.setAuthUrl(authUrl);
        }
        if (StringUtils.isNotBlank(tokenUrl)) {
            config.setTokenUrl(tokenUrl);
        }
        // scopes 同规矩：不传即沿用，避免「只改 clientId」把已配 scope 静默清空
        // （清空请显式传空数组，null 与 [] 在 JSON 里是可区分的）。
        if (scopes != null) {
            config.setScopes(JSON.toJSONString(scopes));
        } else if (existing == null) {
            config.setScopes(JSON.toJSONString(List.of()));
        }
        config.setModified(now);
        if (existing == null) {
            config.setCreated(now);
            oauthConfigRepository.insert(config);
        } else {
            oauthConfigRepository.updateById(config);
        }
        log.info("[connector] oauth config saved: provider={}, scopeType={}, ownerId={}",
                providerService, type.getCode(), ownerId);
    }

    @Override
    public OauthConfigView getConfig(String providerService, String scopeType, Long ownerId) {
        ConnectorOauthConfig config = oauthConfigRepository.getExact(providerService, scopeType, ownerId);
        if (config == null) {
            return null;
        }
        return new OauthConfigView(config.getProviderService(), config.getScopeType(),
                config.getClientId(), config.getAuthUrl(), config.getTokenUrl(),
                parseScopes(config.getScopes()), StringUtils.isNotBlank(config.getClientSecretEnc()));
    }

    // ---------- 设备码（扫码）流程 ----------

    @Override
    public DeviceBeginView beginDeviceFlow(String providerService, Long spaceId) {
        Long userId = RequestContext.get().getUserId();
        ConnectorProvider provider = requireDeviceProvider(providerService);
        ConnectorOauthConfig config = oauthConfigRepository.resolveForUser(providerService, userId);
        if (needsSelfRegistration(config)) {
            JSONObject registration = resolveRegistrationSpec(provider, config);
            if (registration != null) {
                return beginAppRegistration(provider, registration, userId, spaceId);
            }
            // 既没有 App、也认不出自注册协议：让用户去配 App，而不是丢一个「协议缺键」的错误
            throw new ConnectorException(ConnectorErrorCode.OAUTH_CONFIG_MISSING,
                    oauthAppMissingMessage(provider));
        }
        List<String> scopes = resolveAuthorizeScopes(provider, config);

        JSONObject response = postDeviceBegin(config, scopes);
        String deviceCode = response.getString("device_code");
        if (StringUtils.isBlank(deviceCode)) {
            throw new ConnectorException(ConnectorErrorCode.OAUTH_EXCHANGE_FAILED,
                    "设备授权响应缺少 device_code");
        }
        // complete 版原样使用（内含 user_code，扫到即授权）。注意这里<b>不</b>像 app registration
        // 那样自己拼 URL——那是另一个协议的 CLI 特有行为。为空则退回裸 verification_uri。
        String verificationUrl = StringUtils.defaultIfBlank(
                response.getString("verification_uri_complete"), response.getString("verification_uri"));
        if (StringUtils.isBlank(verificationUrl)) {
            throw new ConnectorException(ConnectorErrorCode.OAUTH_EXCHANGE_FAILED,
                    "设备授权响应缺少 verification_uri");
        }
        int expiresIn = positiveOr(response.getIntValue("expires_in"), DEVICE_DEFAULT_EXPIRES_IN);
        int interval = positiveOr(response.getIntValue("interval"), DEVICE_DEFAULT_INTERVAL);

        String state = randomToken(32);
        ConnectorOauthState stateRow = new ConnectorOauthState();
        stateRow.setStateHash(sha256Hex(state));
        stateRow.setProviderService(providerService);
        stateRow.setUserId(userId);
        stateRow.setSpaceId(spaceId);
        // 复用两列存设备码流程的秘密（详见 ConnectorOauthState 字段注释），避免为扫码加列走 DDL
        stateRow.setPkceVerifierEnc(cipher.encrypt(deviceCode));
        stateRow.setRedirectTarget(cipher.encrypt(verificationUrl));
        stateRow.setConsumed(0);
        Date now = new Date();
        // 授权窗口取 IdP 给的值与本模块 TTL 的较小值：存长了会让前端在 device_code 已死之后
        // 继续轮询，白等一场再报超时。
        stateRow.setExpiresAt(new Date(now.getTime() + Math.min(STATE_TTL_MS, expiresIn * 1000L)));
        stateRow.setCreated(now);
        oauthStateRepository.insert(stateRow);

        // deviceCode 与 verificationUrl 一律不入日志：前者是换令牌的全部凭据，后者含 user_code
        log.info("[connector] device authorize issued: user={}, provider={}, scopeType={}, scopeCount={},"
                        + " expiresIn={}", userId, providerService, config.getScopeType(), scopes.size(),
                expiresIn);
        return new DeviceBeginView(DeviceBeginView.MODE_AUTHORIZE, state, response.getString("user_code"),
                expiresIn, interval);
    }

    @Override
    public String deviceVerificationUrl(String state) {
        return cipher.decrypt(requireOwnDeviceState(state).getRedirectTarget());
    }

    @Override
    public DevicePollView pollDeviceFlow(String state, Integer clientInterval) {
        ConnectorOauthState stateRow = requireOwnDeviceState(state);
        ConnectorProvider provider = catalogService.requireProvider(stateRow.getProviderService());
        ConnectorOauthConfig config = oauthConfigRepository.resolveForUser(
                stateRow.getProviderService(), stateRow.getUserId());
        if (needsSelfRegistration(config)) {
            // 认可同一判定：begin 时没有 client_id，轮询时自然还是没有——自注册的产物要到
            // completeAppRegistration 里才落库。begin 与 poll 必须用同一个判据，否则会出现
            // 「begin 走自注册、poll 走设备码」这种拿 device_code 去换令牌的错配。
            return pollAppRegistration(stateRow, provider, config, clientInterval);
        }

        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", DEVICE_GRANT_TYPE);
        form.put("device_code", cipher.decrypt(stateRow.getPkceVerifierEnc()));
        form.put("client_id", config.getClientId());
        // 认证方式与 begin 不同：token 端点要 client_secret 放 form body，不是 Basic 头
        form.put("client_secret", requireClientSecret(config));

        JSONObject response = postDeviceTokenRequest(config.getTokenUrl(), form);
        if (response == null) {
            // 传输/解析失败按瞬时错误处理：递增间隔后继续等，不直接判死
            return pending(clampInterval(bumpInterval(clientInterval, 1)), "轮询请求失败，将继续重试");
        }

        String error = response.getString("error");
        if (StringUtils.isBlank(error) && StringUtils.isNotBlank(response.getString("access_token"))) {
            return completeDeviceFlow(stateRow, provider, config, response);
        }
        if (StringUtils.isBlank(error)) {
            // 无 error 又无 access_token：协议未定义的状态，继续等而不是判失败
            return pending(clampInterval(clientInterval), "等待用户在飞书内确认");
        }
        return switch (error) {
            case "authorization_pending" -> pending(clampInterval(clientInterval), "等待用户在飞书内确认");
            case "slow_down" -> pending(clampInterval(bumpInterval(clientInterval, 5)), "轮询过快，已放慢");
            case "access_denied" -> new DevicePollView("denied", null, null, provider.getDisplayName(),
                    "用户拒绝了授权");
            case "expired_token", "invalid_grant" -> new DevicePollView("expired", null, null,
                    provider.getDisplayName(), "扫码已过期，请重新发起");
            // 未知 error（如 invalid_client）通常是确定性的，继续轮询没有意义；
            // 但也不抛异常——本端点是被反复轮询的，抛错会让前端每 5 秒弹一次错误提示。
            // 归到终态 failed，前端一次展示 error_description 即可。
            default -> new DevicePollView("failed", null, null, provider.getDisplayName(),
                    "扫码授权失败: " + StringUtils.defaultIfBlank(
                            response.getString("error_description"), error));
        };
    }

    @Override
    public AppCredentialView resolveAppCredential(String providerService, Long userId) {
        ConnectorOauthConfig config = oauthConfigRepository.resolveForUser(providerService, userId);
        if (config == null) {
            throw new ConnectorException(ConnectorErrorCode.OAUTH_CONFIG_MISSING,
                    "OAuth App 配置缺失: " + providerService);
        }
        return new AppCredentialView(config.getClientId(), requireClientSecret(config));
    }

    @Override
    public void releaseRegisteredApp(String providerService, Long userId, Long tenantId) {
        if (StringUtils.isBlank(providerService) || userId == null || tenantId == null) {
            return;
        }
        // 还有别的连接指着这份配置就不能回收。byo 行是用户级的、不是连接级的，同一用户对同一 provider
        // 可以建出多条连接共用同一份 App 凭证（表上没有唯一约束，扫码建连也不查重）；删了配置，剩下的
        // 连接就再也刷不出令牌——断连的副作用不该打到别的连接上。
        // 注意 listByUser 会把用户停用的连接也算进来，这是对的：停用只是不执行，照样要刷令牌维持凭证。
        if (!connectionRepository.listByUser(tenantId, userId, providerService).isEmpty()) {
            log.info("[connector] 该用户在该 provider 下还有连接，保留自注册 App 配置: user={}, provider={}",
                    userId, providerService);
            return;
        }
        ConnectorOauthConfig byo = oauthConfigRepository.getExact(
                providerService, OAuthScopeTypeEnum.BYO.getCode(), userId);
        if (byo == null) {
            // 没扫过码：用的是平台公共 App，或压根没建过连——没有副产品可回收
            return;
        }
        ConnectorProvider provider;
        try {
            provider = catalogService.requireProvider(providerService);
        } catch (ConnectorException e) {
            // provider 已不可查（被删或对本用户不可见）时判据不完整，那就不动手。误删用户手填的 App
            // 比留一行陈旧配置难挽回得多——留着的代价只是他下次扫码报一次错，删错了得重新去 IdP 建应用。
            log.warn("[connector] provider 不可查，跳过自注册配置回收: provider={}, configId={}",
                    providerService, byo.getId());
            return;
        }
        if (!supportsSelfRegistration(provider, byo)) {
            // 用户手填的 App：断连是断开连接，不是没收配置，重连还得靠它
            return;
        }
        // 有就先删，删不到（并发下已被另一路删掉）也不算失败——本方法幂等，且不阻断断连
        oauthConfigRepository.deleteById(byo.getId());
        // clientId 可入日志（公开标识），clientSecret 一律不入
        log.info("[connector] self-registered app released: user={}, provider={}, configId={}, clientId={}",
                userId, providerService, byo.getId(), byo.getClientId());
    }

    /**
     * 拿到令牌后建连。<b>先原子消费再建连</b>：两个标签页同时轮询到成功时只有一个能拿到 state，
     * 另一个走 already_completed，不会建出两条连接、也不会白落一份凭证。
     */
    private DevicePollView completeDeviceFlow(ConnectorOauthState stateRow, ConnectorProvider provider,
                                              ConnectorOauthConfig config, JSONObject tokenResponse) {
        ConnectorOauthState consumed = oauthStateRepository.consumeAtomically(stateRow.getStateHash());
        if (consumed == null) {
            return new DevicePollView("already_completed", null, null, provider.getDisplayName(),
                    "本次扫码已由其他页面完成建连");
        }
        Long connectionId = completeConnection(stateRow, provider, config, tokenResponse, true);
        return new DevicePollView("authorized", null, connectionId, provider.getDisplayName(), "连接成功");
    }

    // ---------- 扫码自注册（provider 不持有 App 身份） ----------

    /**
     * 自注册触发判定：该 provider 名下<b>没有任何可用的 client_id</b>。
     * <p>
     * 「可用」有两层：配置行存在，<b>且</b> client_id 非空。只看行在不在是不够的——库里可以有一行
     * client_id 为空、端点却填好了的配置，照着它去发设备授权请求只会换来一句含糊的 IdP 报错。
     * <p>
     * 这个判据同时是 begin 与 poll 的唯一判据，也必须如此：两处若用不同判据，就会出现
     * 「begin 按自注册发、poll 按设备码换」的错配，拿注册协议的 device_code 去换令牌必然失败。
     * <p>
     * 它同时也是「配置优先」的落点：用户一旦扫出 byo 行（或管理员补了平台 App），判定立刻转假，
     * 后续扫码自动回到常规设备码路径，不需要任何开关。
     */
    private boolean needsSelfRegistration(ConnectorOauthConfig config) {
        return config == null || StringUtils.isBlank(config.getClientId());
    }

    /**
     * 定出这次扫码要用的注册协议参数：<b>先看 provider 声明的，再看已知 IdP 画像</b>。
     * <p>
     * 两处调用（begin 与 poll）必须走同一个方法，理由同 {@link #needsSelfRegistration}：判据一旦分岔，
     * 就会出现「按注册协议发码、按设备码换令牌」的错配。
     * <p>
     * 配置优先意味着：声明了 {@value #AUTH_CONFIG_APP_REGISTRATION} 的 provider 行为与从前逐字一致，
     * 画像只是没声明时的兜底，不会覆盖任何人写下的显式配置。
     */
    private JSONObject resolveRegistrationSpec(ConnectorProvider provider, ConnectorOauthConfig config) {
        JSONObject authConfig = parseAuthConfig(provider);
        JSONObject declared = authConfig == null ? null : authConfig.getJSONObject(AUTH_CONFIG_APP_REGISTRATION);
        return declared != null ? declared : presetAppRegistration(config);
    }

    /**
     * 已知 IdP 的注册协议画像：由<b>设备授权端点</b>推出注册端点与那几项固定参数。
     * <p>
     * 这正是「{@code oauth2_device} 只需填授权端点 + 令牌端点」得以成立的地方——建连接器的人只给两个
     * 端点，注册协议由平台按主机补齐。识别不了就返回 {@code null}，交回原来那条「请先配置 App」的报错，
     * <b>绝不猜</b>：随便拼一个注册端点去发请求，只会换来一句看不懂的上游错误。
     * <p>
     * 飞书/Lark 的这几个值是实测出来的协议事实（注册端点与设备授权端点同源同层，archetype 只有
     * PersonalAgent，认证方式 client_secret，索要 open_id 与 tenant_brand），不是可推导的通则，
     * 所以只对这两个域名生效。
     */
    private JSONObject presetAppRegistration(ConnectorOauthConfig config) {
        java.net.URI uri = knownIdpUri(config == null ? null : config.getAuthUrl());
        if (uri == null) {
            return null;
        }
        String path = uri.getPath();
        JSONObject registration = new JSONObject();
        registration.put("url", uri.getScheme() + "://" + uri.getAuthority()
                + path.substring(0, path.length() - DEVICE_AUTHORIZATION_PATH.length()) + APP_REGISTRATION_PATH);
        registration.put("archetype", "PersonalAgent");
        registration.put("authMethod", "client_secret");
        registration.put("requestUserInfo", "open_id tenant_brand");
        log.info("[connector] 按已知 IdP 画像补齐自注册协议: host={}, registrationUrl={}",
                uri.getHost(), registration.getString("url"));
        return registration;
    }

    /**
     * 已知 IdP 自注册画像的识别：授权端点得在画像主机上，且路径以设备授权段结尾。识别不了返回 null。
     * <p>
     * 单独抽出来是因为它有<b>两个用途</b>：{@link #presetAppRegistration} 拿它构造注册参数去发请求；
     * {@link #supportsSelfRegistration} 只要一个是/否。后者服务于断连回收，<b>不能</b>顺手打日志或
     * 构造参数——否则一次「清理自己的旧配置」在日志里会长得像「正在向 IdP 注册应用」。
     * <p>
     * 判据本身照旧<b>绝不猜</b>：识别不了就不认，随便拼一个注册端点去发请求只会换来一句看不懂的上游错误。
     */
    private java.net.URI knownIdpUri(String authUrl) {
        String trimmed = StringUtils.trimToNull(authUrl);
        if (trimmed == null) {
            return null;
        }
        java.net.URI uri;
        try {
            uri = java.net.URI.create(trimmed);
        } catch (Exception e) {
            return null;
        }
        String host = uri.getHost();
        if (host == null || SELF_REGISTRATION_HOST_SUFFIXES.stream()
                .noneMatch(suffix -> host.equals(suffix) || host.endsWith("." + suffix))) {
            return null;
        }
        // 同源同层换末段：…/oauth/v1/device_authorization → …/oauth/v1/app/registration。
        // 路径形状对不上就不认，免得把任意一条 feishu.cn 上的地址当成注册端点。
        String path = uri.getPath() == null ? "" : uri.getPath();
        return path.endsWith(DEVICE_AUTHORIZATION_PATH) ? uri : null;
    }

    /**
     * 该 provider 名下是否走「扫码自注册」：声明了 {@value #AUTH_CONFIG_APP_REGISTRATION}，或命中
     * 已知 IdP 画像。纯判定——不发请求、不打日志、不构造注册参数。
     * <p>
     * 与 {@link #resolveRegistrationSpec} 是同一判据的两种用途：那个要拿出参数去发注册请求，这个只回答
     * 是不是。断连回收（{@link #releaseRegisteredApp}）靠它把两种 byo 配置分开——<b>扫码注册的产物</b>
     * 是连接的副产品，该回收；{@code oauth_app_mode=byo} 下用户<b>手填</b>的 App 是用户自己的资产，
     * 断了连接还得留着，否则用户重连要再填一遍。
     */
    private boolean supportsSelfRegistration(ConnectorProvider provider, ConnectorOauthConfig config) {
        JSONObject authConfig = parseAuthConfig(provider);
        if (authConfig != null && authConfig.getJSONObject(AUTH_CONFIG_APP_REGISTRATION) != null) {
            return true;
        }
        return knownIdpUri(config == null ? null : config.getAuthUrl()) != null;
    }

    /**
     * 读 provider.auth_config。未配置或非法 JSON 返回 null，由调用方决定怎么报错。
     */
    private JSONObject parseAuthConfig(ConnectorProvider provider) {
        if (StringUtils.isBlank(provider.getAuthConfig())) {
            return null;
        }
        try {
            return JSON.parseObject(provider.getAuthConfig());
        } catch (Exception e) {
            log.warn("[connector] provider.auth_config 非法 JSON: provider={}", provider.getService());
            return null;
        }
    }

    /**
     * 取一个必填的字符串配置项。缺了要报出是哪个键缺，否则排查只能靠猜。
     */
    private String requireKey(JSONObject json, String key, String where) {
        String value = json.getString(key);
        if (StringUtils.isBlank(value)) {
            throw new ConnectorException(ConnectorErrorCode.OAUTH_CONFIG_MISSING,
                    where + " 缺少 " + key);
        }
        return value;
    }

    /**
     * 发起应用注册：拿一组「一次性扫码换 App 凭证」的 device_code。
     * <p>
     * 与设备码流程的 begin 形状几乎一样（同样返回 state/user_code/expires_in/interval，同样复用
     * {@code connector_oauth_state} 的两个秘密列），差别只有两处：<b>请求不带鉴权头</b>，
     * 以及确认页 URL 的来源见 {@link #resolveVerificationUrl}。
     * <p>
     * {@code registration} 由 {@link #resolveRegistrationSpec} 定出，可能是 provider 声明的，
     * 也可能是按已知 IdP 画像补齐的——本方法不关心来源。
     */
    private DeviceBeginView beginAppRegistration(ConnectorProvider provider, JSONObject registration,
                                                 Long userId, Long spaceId) {
        String url = requireKey(registration, "url", "auth_config." + AUTH_CONFIG_APP_REGISTRATION);

        Map<String, String> form = new LinkedHashMap<>();
        form.put("action", "begin");
        form.put("archetype", requireKey(registration, "archetype",
                "auth_config." + AUTH_CONFIG_APP_REGISTRATION));
        form.put("auth_method", requireKey(registration, "authMethod",
                "auth_config." + AUTH_CONFIG_APP_REGISTRATION));
        form.put("request_user_info", requireKey(registration, "requestUserInfo",
                "auth_config." + AUTH_CONFIG_APP_REGISTRATION));

        JSONObject response = postAppRegistrationBegin(url, form);
        String deviceCode = response.getString("device_code");
        String userCode = response.getString("user_code");
        if (StringUtils.isBlank(deviceCode)) {
            throw new ConnectorException(ConnectorErrorCode.OAUTH_EXCHANGE_FAILED,
                    "应用注册响应缺少 device_code");
        }
        if (StringUtils.isBlank(userCode)) {
            throw new ConnectorException(ConnectorErrorCode.OAUTH_EXCHANGE_FAILED,
                    "应用注册响应缺少 user_code");
        }
        String verificationUrl = resolveVerificationUrl(response, registration, userCode);
        int expiresIn = positiveOr(response.getIntValue("expires_in"), DEVICE_DEFAULT_EXPIRES_IN);
        int interval = positiveOr(response.getIntValue("interval"), DEVICE_DEFAULT_INTERVAL);

        String state = randomToken(32);
        ConnectorOauthState stateRow = new ConnectorOauthState();
        stateRow.setStateHash(sha256Hex(state));
        stateRow.setProviderService(provider.getService());
        stateRow.setUserId(userId);
        stateRow.setSpaceId(spaceId);
        // 复用设备码流程同样两列：这里装的是注册协议的 device_code 与拼好的确认页 URL
        stateRow.setPkceVerifierEnc(cipher.encrypt(deviceCode));
        stateRow.setRedirectTarget(cipher.encrypt(verificationUrl));
        stateRow.setConsumed(0);
        Date now = new Date();
        stateRow.setExpiresAt(new Date(now.getTime() + Math.min(STATE_TTL_MS, expiresIn * 1000L)));
        stateRow.setCreated(now);
        oauthStateRepository.insert(stateRow);

        // deviceCode 与 verificationUrl 不入日志：前者是换 App 凭证的全部凭据，后者含 user_code
        log.info("[connector] app registration issued: user={}, provider={}, expiresIn={}",
                userId, provider.getService(), expiresIn);
        return new DeviceBeginView(DeviceBeginView.MODE_REGISTER, state, userCode, expiresIn, interval);
    }

    /**
     * 定出用户要点的那条确认页 URL。
     * <p>
     * <b>优先用服务端回的那条</b>（{@code verification_uri_complete}）——确认页的域名与路径是 IdP
     * 说了算的，配置里写死的模板必然会漂。这里踩过一次：模板写的是
     * {@code accounts.feishu.cn/page/cli}，而注册<b>端点</b>在 accounts 域、用户确认<b>页面</b>在
     * open 域，实测那条是 404，服务端回的 {@code https://open.feishu.cn/page/launcher?user_code=…}
     * 才是通的。既然响应里就有，就没有理由去猜。
     * <p>
     * 兜底顺序：完整 URL → 基础 URL + user_code → 配置模板。三者都没有才报错，且报错要同时点名
     * 「响应里没有」和「配置里也没有」，否则排查时只看到一句「缺配置」会往错的方向找。
     */
    private String resolveVerificationUrl(JSONObject response, JSONObject registration, String userCode) {
        String complete = response.getString("verification_uri_complete");
        if (StringUtils.isNotBlank(complete)) {
            return complete;
        }
        // 基础 URL 不带 user_code，得自己挂上去；用 ? 还是 & 看它原本有没有查询串
        String base = response.getString("verification_uri");
        if (StringUtils.isNotBlank(base)) {
            return base + (base.contains("?") ? "&" : "?") + "user_code=" + encode(userCode);
        }
        String template = registration.getString("verificationUrlTemplate");
        if (StringUtils.isNotBlank(template)) {
            // 用 replace 而不是 String.format：模板里的 % 可能是 URL 字面量（百分号编码），
            // 交给 format 解析会炸。user_code 走一次编码，换个 IdP 也不会拼出坏 URL。
            return template.replace("%s", encode(userCode));
        }
        throw new ConnectorException(ConnectorErrorCode.OAUTH_CONFIG_MISSING,
                "拿不到用户确认页地址：注册响应里没有 verification_uri_complete/verification_uri，"
                        + "auth_config." + AUTH_CONFIG_APP_REGISTRATION + ".verificationUrlTemplate 也没配");
    }

    /**
     * 轮询应用注册。状态机与 {@link #pollDeviceFlow} 一致（authorization_pending / slow_down /
     * access_denied / expired_token 都照搬），差别在成功态：注册协议返回的是
     * {@code client_id + client_secret + user_info}，不是 access_token。
     */
    private DevicePollView pollAppRegistration(ConnectorOauthState stateRow, ConnectorProvider provider,
                                               ConnectorOauthConfig config, Integer clientInterval) {
        JSONObject registration = resolveRegistrationSpec(provider, config);
        if (registration == null) {
            // begin 当时能发出 state 就说明注册协议是定得出来的，这里定不出来只能是配置被中途改掉
            throw new ConnectorException(ConnectorErrorCode.OAUTH_CONFIG_MISSING,
                    "无法确定应用注册协议（auth_config." + AUTH_CONFIG_APP_REGISTRATION
                            + " 缺失且不能被 IdP 画像补齐），无法完成应用注册: " + provider.getService());
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("action", "poll");
        form.put("device_code", cipher.decrypt(stateRow.getPkceVerifierEnc()));

        JSONObject response = postAppRegistrationPoll(
                requireKey(registration, "url", "auth_config." + AUTH_CONFIG_APP_REGISTRATION), form);
        if (response == null) {
            return pending(clampInterval(bumpInterval(clientInterval, 1)), "轮询请求失败，将继续重试");
        }
        String error = response.getString("error");
        if (StringUtils.isBlank(error) && StringUtils.isNotBlank(response.getString("client_id"))) {
            return completeAppRegistration(stateRow, provider, config, response);
        }
        if (StringUtils.isBlank(error)) {
            // 无 error 又无 client_id：协议未定义的状态，继续等而不是判失败
            return pending(clampInterval(clientInterval), "等待用户在飞书内完成应用注册确认");
        }
        return switch (error) {
            case "authorization_pending" -> pending(clampInterval(clientInterval),
                    "等待用户在飞书内完成应用注册确认");
            case "slow_down" -> pending(clampInterval(bumpInterval(clientInterval, 5)), "轮询过快，已放慢");
            case "access_denied" -> new DevicePollView("denied", null, null, provider.getDisplayName(),
                    "用户取消了应用注册");
            case "expired_token", "invalid_grant" -> new DevicePollView("expired", null, null,
                    provider.getDisplayName(), "扫码已过期，请重新发起");
            default -> new DevicePollView("failed", null, null, provider.getDisplayName(),
                    "应用注册失败: " + StringUtils.defaultIfBlank(
                            response.getString("error_description"), error));
        };
    }

    /**
     * 注册成功后收尾。<b>先落 App 配置，再消费 state</b>：写配置是可重入的（同一用户覆盖同一行），
     * 而 state 一旦消费掉就不可回退——顺序反了，两个标签页并发轮询时输的那一方会白扫一次码。
     */
    private DevicePollView completeAppRegistration(ConnectorOauthState stateRow, ConnectorProvider provider,
                                                   ConnectorOauthConfig resolved, JSONObject response) {
        ConnectorOauthConfig config = persistRegisteredApp(provider, stateRow.getUserId(), resolved, response);
        ConnectorOauthState consumed = oauthStateRepository.consumeAtomically(stateRow.getStateHash());
        if (consumed == null) {
            return new DevicePollView("already_completed", null, null, provider.getDisplayName(),
                    "本次扫码已由其他页面完成建连");
        }
        // 不建连接：自注册只产出 App 身份（client_id/client_secret 已落到 byo 配置行），没有令牌可存，
        // 建出来的连接是个取不到任何凭证的空壳。而它 id 最小、建得最早，恰好会被默认连接选中
        // （execute 不传 connectionId 时取第一条 active），让后续调用默认踩空。
        // 真正的连接由用户完成设备授权后在 completeConnection 里建立——那一条才带用户令牌。
        log.info("[connector] self-registered app ready, awaiting user authorization: user={}, provider={},"
                + " configId={}", stateRow.getUserId(), provider.getService(), config.getId());
        return new DevicePollView("authorized", null, null, provider.getDisplayName(),
                "应用已创建，请继续授权（以你的身份）");
    }

    /**
     * 把扫码换来的 App 身份落到<b>用户维度</b>（{@code scope_type=byo}、{@code owner_id=用户id}）。
     * 这一步就是整个需求的支点：{@link ConnectorOauthConfigRepository#resolveForUser} 本来就先看
     * byo 行，「后续调用工具优先取用户维度配置」因此自动成立。
     * <p>
     * 做成<b>可重入</b>（同一用户覆盖既有行，不插新行）有两个理由：一是 resolveForUser 用
     * {@code selectOne} 取 byo 行，插出第二条会直接抛 TooManyResults；二是用户重新注册一次，
     * 本就该以最新那次为准。
     * <p>
     * <b>端点与 scopes 的来源有两个</b>：{@code auth_config} 里写了就用它（声明式的老做法），
     * 没写就沿用手上这行配置（{@code resolved}）——这行正是建连接器时填的那两个端点。
     * 于是「{@code oauth2_device} 只填授权端点 + 令牌端点」这条产品规则才闭环：注册协议由
     * {@link #presetAppRegistration} 补齐，两个端点由配置行提供，谁都不必再写 auth_config。
     * 两条来源都没有才报错，且要同时点名，否则排查只能靠猜。
     */
    private ConnectorOauthConfig persistRegisteredApp(ConnectorProvider provider, Long userId,
                                                      ConnectorOauthConfig resolved, JSONObject response) {
        String clientId = response.getString("client_id");
        String clientSecret = response.getString("client_secret");
        if (StringUtils.isBlank(clientId) || StringUtils.isBlank(clientSecret)) {
            throw new ConnectorException(ConnectorErrorCode.OAUTH_EXCHANGE_FAILED,
                    "应用注册响应缺少 client_id 或 client_secret");
        }
        JSONObject authConfig = parseAuthConfig(provider);
        // scopes 由注册协议换不回来（协议里没有这个参数），只能来自配置：auth_config 或配置行。
        // 两处都空不是错误——后续发起设备授权时 resolveAuthorizeScopes 仍会补上 offline_access，
        // 挡在这里只会让一个本来能用的连接建不起来。
        JSONArray scopes = authConfig == null ? null : authConfig.getJSONArray("scopes");
        if (scopes == null || scopes.isEmpty()) {
            scopes = JSON.parseArray(StringUtils.defaultIfBlank(
                    resolved == null ? null : resolved.getScopes(), "[]"));
        }

        String scopeType = OAuthScopeTypeEnum.BYO.getCode();
        ConnectorOauthConfig existing = oauthConfigRepository.getExact(
                provider.getService(), scopeType, userId);
        ConnectorOauthConfig config = existing != null ? existing : new ConnectorOauthConfig();
        if (existing == null) {
            config.setProviderService(provider.getService());
            config.setScopeType(scopeType);
            config.setOwnerId(userId);
            config.setCreated(new Date());
        }
        config.setClientId(clientId);
        config.setClientSecretEnc(cipher.encrypt(clientSecret));
        // 端点注册响应里也没有，得从配置取：auth_config 优先，没写就沿用手上这行。
        // 写进 byo 行是必要的冗余：令牌刷新（#refresh）只读 oauth_config，不回头读 provider。
        String authUrl = authConfig == null ? null : StringUtils.trimToNull(
                authConfig.getString("deviceAuthorizationUrl"));
        if (authUrl == null) {
            authUrl = resolved == null ? null : StringUtils.trimToNull(resolved.getAuthUrl());
        }
        String tokenUrl = authConfig == null ? null : StringUtils.trimToNull(authConfig.getString("tokenUrl"));
        if (tokenUrl == null) {
            tokenUrl = resolved == null ? null : StringUtils.trimToNull(resolved.getTokenUrl());
        }
        if (authUrl == null || tokenUrl == null) {
            // 两条来源都没有：把两处都点名，否则用户不知道去哪补
            throw new ConnectorException(ConnectorErrorCode.OAUTH_CONFIG_MISSING,
                    "缺少设备授权端点或令牌端点（auth_config.deviceAuthorizationUrl/auth_config.tokenUrl "
                            + "与连接器配置里的 authUrl/tokenUrl 都为空）: " + provider.getService());
        }
        config.setAuthUrl(authUrl);
        config.setTokenUrl(tokenUrl);
        config.setScopes(scopes.toJSONString());
        config.setModified(new Date());

        boolean written = existing != null
                ? oauthConfigRepository.updateById(config)
                : oauthConfigRepository.insert(config);
        if (!written) {
            throw new ConnectorException(ConnectorErrorCode.OAUTH_EXCHANGE_FAILED,
                    "App 配置落库失败: " + provider.getService());
        }
        // clientId 可入日志（它是公开标识），clientSecret 一律不入
        log.info("[connector] self-registered app persisted: user={}, provider={}, configId={},"
                        + " scopeType={}, clientId={}", userId, provider.getService(), config.getId(),
                scopeType, clientId);
        return config;
    }

    /**
     * 打注册端点。<b>不带任何 Authorization 头</b>——这是与 {@link #postDeviceBegin} 的关键差别：
     * 设备授权端点用 client_id:client_secret 做 Basic 鉴权，而注册端点恰恰是这对凭证的产出方，
     * 请求它的时候平台手里什么都没有。
     */
    private GuardedHttpClient.GuardedHttpResponse executeAppRegistration(String url, Map<String, String> form) {
        Map<String, String> headers = Map.of(
                "Content-Type", "application/x-www-form-urlencoded",
                "Accept", "application/json");
        return guardedHttpClient.execute("POST", url, headers,
                encodeForm(form).getBytes(StandardCharsets.UTF_8), TOKEN_EXCHANGE_TIMEOUT_MS);
    }

    /**
     * begin 阶段：失败是确定性的，直接抛，语义在 body 的 error/error_description 里。
     */
    private JSONObject postAppRegistrationBegin(String url, Map<String, String> form) {
        GuardedHttpClient.GuardedHttpResponse response = executeAppRegistration(url, form);
        JSONObject json = requireJsonObject(response.bodyAsString(), "应用注册响应非法 JSON");
        String error = json.getString("error");
        if (response.status() < 200 || response.status() >= 300 || StringUtils.isNotBlank(error)) {
            // 只记 error 字段，不记 body：成功响应里含 device_code
            log.warn("[connector] app registration begin 失败: status={}, error={}",
                    response.status(), error);
            throw new ConnectorException(ConnectorErrorCode.OAUTH_EXCHANGE_FAILED, "发起应用注册失败: "
                    + StringUtils.defaultIfBlank(json.getString("error_description"),
                    StringUtils.defaultIfBlank(error, "HTTP " + response.status())));
        }
        return json;
    }

    /**
     * poll 阶段：与 {@link #postDeviceTokenRequest} 同理，未完成时返回非 2xx 是正常等待态，
     * 「非 2xx 即失败」会把等待误判成失败，于是永远轮不出结果。传输/解析失败返回 {@code null}。
     */
    private JSONObject postAppRegistrationPoll(String url, Map<String, String> form) {
        try {
            return tryParseJsonObject(executeAppRegistration(url, form).bodyAsString());
        } catch (ConnectorException e) {
            // SSRF 拦截、url 未配置等确定性失败不该被当成瞬时错误无限重试，原样抛出
            throw e;
        } catch (Exception e) {
            log.warn("[connector] app registration poll 请求失败（按瞬时错误重试）: {}", e.getMessage());
            return null;
        }
    }

    // ---------- 应用身份令牌（可选） ----------

    /**
     * 应用身份令牌的换取规格，如飞书的 {@code tenant_access_token}。
     * <p>
     * 它与 OAuth2 令牌的差别不只是端点：<b>响应字段名也不同</b>（飞书是
     * {@code tenant_access_token}/{@code expire}，不是 {@code access_token}/{@code expires_in}），
     * 所以字段名做成配置项，缺省按标准名兜底——这样接入一个「字段名标准、只是端点不同」的 IdP
     * 就只需要填 url。
     */
    private record AppTokenSpec(String url, String tokenField, String expiresField, String tokenType) {
    }

    /**
     * 没配 {@value #AUTH_CONFIG_TENANT_TOKEN} 返回 null，调用方据此走「只发放 App 凭证」的形态。
     */
    private AppTokenSpec resolveAppTokenSpec(ConnectorProvider provider) {
        JSONObject authConfig = parseAuthConfig(provider);
        if (authConfig == null) {
            return null;
        }
        JSONObject appToken = authConfig.getJSONObject(AUTH_CONFIG_TENANT_TOKEN);
        if (appToken == null) {
            return null;
        }
        return new AppTokenSpec(
                requireKey(appToken, "url", "auth_config." + AUTH_CONFIG_TENANT_TOKEN),
                StringUtils.defaultIfBlank(appToken.getString("tokenField"), "access_token"),
                StringUtils.defaultIfBlank(appToken.getString("expiresField"), "expires_in"),
                StringUtils.defaultIfBlank(appToken.getString("tokenType"), "Bearer"));
    }

    /**
     * 换应用令牌。<b>请求体是 JSON</b>（{@code {"app_id":..,"app_secret":..}}），不是 OAuth2 惯用的
     * form-urlencoded——飞书的 {@code tenant_access_token/internal} 就是这么定的。
     * <p>
     * 请求形状不参数化，理由是会走这条分支的 IdP 本就属于「非标准形状」那一类，把请求体也做成模板
     * 只会留下一个没人用的空壳；字段名参数化则是因为那正是排查时最常对不上的地方。
     */
    private JSONObject postAppTokenRequest(AppTokenSpec spec, String appId, String appSecret) {
        JSONObject body = new JSONObject();
        body.put("app_id", appId);
        body.put("app_secret", appSecret);
        Map<String, String> headers = Map.of(
                "Content-Type", "application/json; charset=utf-8",
                "Accept", "application/json");
        GuardedHttpClient.GuardedHttpResponse response = guardedHttpClient.execute("POST", spec.url(), headers,
                body.toJSONString().getBytes(StandardCharsets.UTF_8), TOKEN_EXCHANGE_TIMEOUT_MS);
        return requireJsonObject(response.bodyAsString(), "应用令牌响应非法 JSON");
    }

    /**
     * 取属于当前登录用户、未消费未过期的扫码 state。
     * <p>
     * 与授权码回调不同，这里<b>必须</b>有登录态且严格比对用户：设备码三个端点都不在免登录白名单里
     * （白名单只放行 /oauth/callback 一条叶子路径），不存在「授权链接在别的浏览器点开、cookie 缺失」
     * 的情形，所以不给 state 无条件信任的余地——比回调流程更严。
     */
    private ConnectorOauthState requireOwnDeviceState(String state) {
        if (StringUtils.isBlank(state)) {
            throw new ConnectorException(ConnectorErrorCode.OAUTH_STATE_INVALID, "state 不能为空");
        }
        oauthStateRepository.purgeExpired();
        String stateHash = sha256Hex(state);
        ConnectorOauthState stateRow = oauthStateRepository.findUnconsumed(stateHash);
        if (stateRow == null) {
            // purgeExpired 刚清走过期行，此时还能 exists 就说明是「已被消费」
            if (oauthStateRepository.exists(stateHash)) {
                throw new ConnectorException(ConnectorErrorCode.OAUTH_STATE_INVALID,
                        "本次扫码已被使用，请重新发起");
            }
            throw new ConnectorException(ConnectorErrorCode.OAUTH_STATE_EXPIRED,
                    "扫码已超时或 state 无效，请重新发起");
        }
        Long sessionUserId = RequestContext.get().getUserId();
        if (sessionUserId == null || !stateRow.getUserId().equals(sessionUserId)) {
            log.warn("[connector] device state 用户不匹配: stateUser={}, sessionUser={}",
                    stateRow.getUserId(), sessionUserId);
            throw new ConnectorException(ConnectorErrorCode.OAUTH_STATE_INVALID,
                    "state 无效，请重新发起授权");
        }
        return stateRow;
    }

    private DevicePollView pending(int interval, String message) {
        return new DevicePollView("pending", interval, null, null, message);
    }

    /**
     * 在调用方上一轮的间隔上加 delta；调用方没给就用协议默认值
     */
    private int bumpInterval(Integer current, int delta) {
        return positiveOr(current == null ? 0 : current, DEVICE_DEFAULT_INTERVAL) + delta;
    }

    private int clampInterval(Integer interval) {
        return Math.min(positiveOr(interval == null ? 0 : interval, DEVICE_DEFAULT_INTERVAL),
                DEVICE_MAX_INTERVAL);
    }

    private int positiveOr(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    // ---------- token 交换 ----------

    private JSONObject exchangeCode(ConnectorOauthConfig config, String code, String verifier) {
        String redirectBaseUrl = ((TenantConfigDto) RequestContext.get().getTenantConfig()).getSiteUrl();
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", buildRedirectUri(redirectBaseUrl));
        form.put("client_id", config.getClientId());
        form.put("client_secret", cipher.decrypt(config.getClientSecretEnc()));
        form.put("code_verifier", verifier);
        return postTokenRequest(config.getTokenUrl(), form);
    }

    /**
     * 授权码流程 / 令牌刷新用的严格版：非 2xx 即判失败。
     * <p>
     * 设备码轮询<b>不能</b>用它，见 {@link #postDeviceTokenRequest}。
     */
    private JSONObject postTokenRequest(String tokenUrl, Map<String, String> form) {
        GuardedHttpClient.GuardedHttpResponse response = postForm(tokenUrl, form);
        String responseBody = response.bodyAsString();
        if (response.status() < 200 || response.status() >= 300) {
            log.warn("[connector] token endpoint 返回 {}: {}", response.status(),
                    responseBody.length() > 300 ? responseBody.substring(0, 300) : responseBody);
            throw new ConnectorException(ConnectorErrorCode.OAUTH_EXCHANGE_FAILED,
                    "token 端点返回 " + response.status());
        }
        return requireJsonObject(responseBody, "token 响应非法 JSON");
    }

    /**
     * 设备码轮询专用。<b>不能沿用 {@link #postTokenRequest}</b>：实测飞书在
     * {@code authorization_pending} / {@code slow_down} 时返回 <b>HTTP 400</b>
     * （形如 {@code {"error":"authorization_pending","code":20094}}），语义全在 body 的
     * {@code error} 字段里。「非 2xx 即失败」会把正常的等待状态误判成交换失败，
     * 于是永远轮不出结果——用户扫了码也建不上连。
     * <p>
     * 传输或解析失败返回 {@code null}，由调用方按「瞬时错误、递增间隔后重试」处理。
     * 响应 body 一律不入日志：成功响应里含 access_token。
     */
    private JSONObject postDeviceTokenRequest(String tokenUrl, Map<String, String> form) {
        try {
            return tryParseJsonObject(postForm(tokenUrl, form).bodyAsString());
        } catch (ConnectorException e) {
            // SSRF 拦截、tokenUrl 未配置等确定性失败不该被当成瞬时错误无限重试，原样抛出
            throw e;
        } catch (Exception e) {
            log.warn("[connector] device poll 请求失败（按瞬时错误重试）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 调设备授权端点。认证方式与 token 端点不同：begin 用 {@code Authorization: Basic}，
     * token 端点把 client_secret 放 form body。
     */
    private JSONObject postDeviceBegin(ConnectorOauthConfig config, List<String> scopes) {
        if (StringUtils.isBlank(config.getAuthUrl())) {
            throw new ConnectorException(ConnectorErrorCode.OAUTH_CONFIG_MISSING,
                    "authUrl（设备授权端点）未配置");
        }
        String basic = Base64.getEncoder().encodeToString(
                (config.getClientId() + ":" + requireClientSecret(config)).getBytes(StandardCharsets.UTF_8));
        Map<String, String> headers = Map.of(
                "Content-Type", "application/x-www-form-urlencoded",
                "Accept", "application/json",
                "Authorization", "Basic " + basic);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", config.getClientId());
        form.put("scope", String.join(" ", scopes));

        GuardedHttpClient.GuardedHttpResponse response = guardedHttpClient.execute("POST", config.getAuthUrl(),
                headers, encodeForm(form).getBytes(StandardCharsets.UTF_8), TOKEN_EXCHANGE_TIMEOUT_MS);
        // 实测 scope 非法时飞书返回 HTTP 400 + {"error":"invalid_scope",...}，故按 body 的 error 判定
        // 而非只看状态码——否则会丢掉 error_description 里那句真正说明原因的话。
        JSONObject json = requireJsonObject(response.bodyAsString(), "设备授权响应非法 JSON");
        String error = json.getString("error");
        if (response.status() < 200 || response.status() >= 300 || StringUtils.isNotBlank(error)) {
            // 只记 error 字段，不记 body：成功响应里含 device_code
            log.warn("[connector] device begin 失败: provider={}, status={}, error={}",
                    config.getProviderService(), response.status(), error);
            throw new ConnectorException(ConnectorErrorCode.OAUTH_EXCHANGE_FAILED, "发起扫码授权失败: "
                    + StringUtils.defaultIfBlank(json.getString("error_description"),
                    StringUtils.defaultIfBlank(error, "HTTP " + response.status())));
        }
        return json;
    }

    /**
     * 决定本次用户授权请求哪些 scope = 配置 ∪ 实时目录 ∪ {offline_access}。
     * <p>
     * <b>为什么要并集而不是用目录覆盖配置</b>：目录口（见 {@link #resolveCatalogScopes}）在应用
     * 令牌下返回的是<b>应用维度</b>的清单，不列那些只按用户维度授予的 scope。实测某自注册 App 的
     * 目录 36 条里 35 条是 tenant 维度，而该 App 的用户连接实际拿到了
     * {@code contact:user.base:readonly} 这类目录里没有用户条目的 scope——以目录覆盖会把当前
     * <b>已经可用</b>的权限砍掉。并集只会往里加「该 App 确实在控制台申请过」的 scope。
     * <p>
     * 目录拉不到（未配 {@value #AUTH_CONFIG_SCOPE_CATALOG}、令牌换不出来、端点报错、JSON 形状
     * 对不上）一律退回「配置 ∪ {offline_access}」——<b>绝不因为一次辅助性的 GET 失败就挡住授权</b>。
     * <p>
     * {@code offline_access} 是拿到 refresh_token 的前提，与是不是 API 权限无关，所以无论目录里
     * 有没有它都单独补一次；配置里漏了也补（与 lark-cli 行为一致），否则建出的连接 7200 秒后无法
     * 自救、只能让用户重新扫一次码。
     */
    private List<String> resolveAuthorizeScopes(ConnectorProvider provider, ConnectorOauthConfig config) {
        // LinkedHashSet 保序去重：配置里的顺序对用户可读的授权页有意义（飞书确认页按传入顺序列出），
        // 目录项追加在后面，不打乱手写清单的顺序。
        Set<String> scopes = new LinkedHashSet<>(parseScopes(config.getScopes()));
        List<String> catalog = resolveCatalogScopes(provider, config);
        if (catalog != null) {
            scopes.addAll(catalog);
        }
        scopes.add(SCOPE_OFFLINE_ACCESS);
        return new ArrayList<>(scopes);
    }

    /**
     * 拉该 App 当前的权限清单，取出「可用于用户身份授权」的那部分 scope 名。拉不到返回 {@code null}。
     * <p>
     * 走 provider.auth_config 的 {@value #AUTH_CONFIG_SCOPE_CATALOG} 声明块（形如
     * {@code {"url": ".../applications/{appId}", "scopesPath": "data.app.scopes",
     * "nameField": "scope", "tokenTypesField": "token_types", "requireTokenType": "user"}}），
     * 不硬编码任何一家 IdP 的端点——与 {@code appRegistration}/{@code tenantToken} 同一套做法：
     * 端点进 provider 配置，provider 行不持密钥。
     * <p>
     * <b>鉴权只能用自己的应用令牌，不能用待授权的那个用户的令牌</b>：调用发生在用户扫码之前，此刻
     * 没有任何用户令牌可用，而且「这个 App 有哪些权限」本就是应用维度的事实、与哪个用户无关。
     * 故复用 {@link #resolveAppTokenSpec} + {@link #postAppTokenRequest} 那条既有链路。
     * <p>
     * 过滤 {@code token_types} 是这里的关键。<b>不能</b>改用 {@code /application/v6/scopes} 那种
     * 只给单值 {@code scope_type} 的口：应用令牌调用下它只列应用维度条目，拿它当用户授权的 scope
     * 源会得到一个几乎为空的清单（实测 36 条里只有 1 条 user）。而
     * {@code applications/{app_id}} 的 {@code scopes[].token_types} 是数组，能同时表达
     * tenant/user，按 {@code contains} 过滤才是「这个 scope 支持用户授权」的正确判据。
     */
    private List<String> resolveCatalogScopes(ConnectorProvider provider, ConnectorOauthConfig config) {
        JSONObject authConfig = parseAuthConfig(provider);
        JSONObject catalog = authConfig == null ? null : authConfig.getJSONObject(AUTH_CONFIG_SCOPE_CATALOG);
        if (catalog == null) {
            return null;
        }
        String where = "auth_config." + AUTH_CONFIG_SCOPE_CATALOG;
        try {
            AppTokenSpec spec = resolveAppTokenSpec(provider);
            if (spec == null) {
                log.warn("[connector] scope 目录已配置但缺少 {}，跳过: provider={}",
                        AUTH_CONFIG_TENANT_TOKEN, provider.getService());
                return null;
            }
            JSONObject token = postAppTokenRequest(spec, config.getClientId(), requireClientSecret(config));
            String appToken = token.getString(spec.tokenField());
            if (StringUtils.isBlank(appToken)) {
                log.warn("[connector] scope 目录取应用令牌失败，跳过: provider={}", provider.getService());
                return null;
            }
            String url = requireKey(catalog, "url", where)
                    .replace("{appId}", encode(config.getClientId()));
            // 认证方案固定 Bearer，<b>不能</b>用 spec.tokenType()：那是「令牌种类」标记
            // （Tenant/App，供 identity 归一用），不是 HTTP 认证方案。拿它拼头会发出
            // `Authorization: Tenant t-xxx`，飞书按「没有令牌」处理并回 400，而错误体里
            // 只写 field validation failed，看不出是认证头写错了。
            Map<String, String> headers = Map.of(
                    "Authorization", BEARER_PREFIX + appToken,
                    "Accept", "application/json");
            GuardedHttpClient.GuardedHttpResponse response =
                    guardedHttpClient.execute("GET", url, headers, null, TOKEN_EXCHANGE_TIMEOUT_MS);
            if (response.status() < 200 || response.status() >= 300) {
                // 必须把错误体带出来：飞书这类网关对 400 只回一个泛化的 HTTP 状态，
                // 真正的原因（如「lang is required」）只在 body 里。只记状态码的日志会让排查
                // 退化成拿 curl 逐个参数试，而这是一次纯粹的配置问题。
                log.warn("[connector] scope 目录请求失败，跳过: provider={}, status={}, body={}",
                        provider.getService(), response.status(), abbreviate(response.bodyAsString()));
                return null;
            }
            List<JSONObject> items = navigateToArray(
                    JSON.parseObject(response.bodyAsString()), requireKey(catalog, "scopesPath", where));
            String nameField = StringUtils.defaultIfBlank(catalog.getString("nameField"), "scope");
            String tokenTypesField = catalog.getString("tokenTypesField");
            String requireTokenType = catalog.getString("requireTokenType");
            List<String> scopes = new ArrayList<>();
            for (JSONObject item : items) {
                String name = item.getString(nameField);
                if (StringUtils.isBlank(name)) {
                    continue;
                }
                if (StringUtils.isNotBlank(tokenTypesField) && StringUtils.isNotBlank(requireTokenType)
                        && !supportsTokenType(item, tokenTypesField, requireTokenType)) {
                    continue;
                }
                scopes.add(name);
            }
            log.info("[connector] scope 目录命中: provider={}, catalog={}, usable={}",
                    provider.getService(), items.size(), scopes.size());
            return scopes;
        } catch (Exception e) {
            // 辅助信息拿不到不该让用户连授权都发不起来，也不该把上游错误体拼进异常消息
            log.warn("[connector] scope 目录解析失败，按配置清单继续: provider={}, reason={}",
                    provider.getService(), e.getMessage());
            return null;
        }
    }

    /**
     * 判断一条目录项是否支持指定令牌类型。{@code token_types} 是<b>数组</b>（如
     * {@code ["tenant","user"]}），故必须逐元素比对；也容忍它被配成单个字符串的 IdP。
     */
    private boolean supportsTokenType(JSONObject item, String field, String expected) {
        Object raw = item.get(field);
        if (raw instanceof JSONArray array) {
            for (int i = 0; i < array.size(); i++) {
                if (expected.equalsIgnoreCase(array.getString(i))) {
                    return true;
                }
            }
            return false;
        }
        return raw != null && expected.equalsIgnoreCase(String.valueOf(raw));
    }

    /**
     * 按点分路径取出数组元素，支持 {@code a.b.c} 与 {@code a.b[0].c}。
     * <p>
     * 与 {@code DeclarativeActionExecutor} 里那套 {@code $.a.b[*].c} 解析器是同类东西，但那个是
     * private 且服务于另一套哨兵语义（{@code WILDCARD}/{@code NO_MATCH}），跨类提出来会同时改动
     * 声明式动作的执行路径。这里只需要「一路走到底、失败返回空」，单独写十几行比共享更稳妥。
     * <p>
     * 任一环节走不通都返回空列表而不是抛异常：目录是可选增强，路径写错只该降级、不该报错。
     */
    private List<JSONObject> navigateToArray(JSONObject root, String path) {
        Object current = root;
        for (String rawSegment : path.split("\\.")) {
            String segment = rawSegment;
            Integer index = null;
            int bracket = rawSegment.indexOf('[');
            if (bracket >= 0 && rawSegment.endsWith("]")) {
                segment = rawSegment.substring(0, bracket);
                try {
                    index = Integer.valueOf(rawSegment.substring(bracket + 1, rawSegment.length() - 1));
                } catch (NumberFormatException e) {
                    return List.of();
                }
            }
            if (StringUtils.isNotBlank(segment)) {
                if (!(current instanceof JSONObject object)) {
                    return List.of();
                }
                current = object.get(segment);
            }
            if (index != null) {
                if (!(current instanceof JSONArray array) || index < 0 || index >= array.size()) {
                    return List.of();
                }
                current = array.get(index);
            }
        }
        if (!(current instanceof JSONArray array)) {
            return List.of();
        }
        List<JSONObject> items = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            JSONObject item = array.getJSONObject(i);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    /**
     * 截断上游错误体，只留够定位问题的开头。上游把自己那一侧的堆栈或整页 HTML 塞进 body 时，
     * 原样记进日志会把真正的一行原因淹掉，也可能把无关数据写进日志文件。
     */
    private String abbreviate(String body) {
        if (StringUtils.isBlank(body)) {
            return "<空响应体>";
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "...";
    }

    private GuardedHttpClient.GuardedHttpResponse postForm(String url, Map<String, String> form) {
        if (StringUtils.isBlank(url)) {
            throw new ConnectorException(ConnectorErrorCode.OAUTH_CONFIG_MISSING, "tokenUrl 未配置");
        }
        Map<String, String> headers = Map.of(
                "Content-Type", "application/x-www-form-urlencoded",
                "Accept", "application/json");
        return guardedHttpClient.execute("POST", url, headers,
                encodeForm(form).getBytes(StandardCharsets.UTF_8), TOKEN_EXCHANGE_TIMEOUT_MS);
    }

    private String encodeForm(Map<String, String> form) {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (body.length() > 0) {
                body.append('&');
            }
            body.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
        return body.toString();
    }

    private JSONObject requireJsonObject(String body, String errorMessage) {
        JSONObject json = tryParseJsonObject(body);
        if (json == null) {
            throw new ConnectorException(ConnectorErrorCode.OAUTH_EXCHANGE_FAILED, errorMessage);
        }
        return json;
    }

    /**
     * 宽松解析：空 body 或非法 JSON 一律返回 null，不抛异常
     */
    private JSONObject tryParseJsonObject(String body) {
        try {
            return JSON.parseObject(body);
        } catch (Exception e) {
            return null;
        }
    }

    private String requireClientSecret(ConnectorOauthConfig config) {
        if (StringUtils.isBlank(config.getClientSecretEnc())) {
            throw new ConnectorException(ConnectorErrorCode.OAUTH_CONFIG_MISSING,
                    "OAuth App 未配置 client_secret: " + config.getProviderService());
        }
        return cipher.decrypt(config.getClientSecretEnc());
    }

    // ---------- 工具 ----------

    /**
     * 两种 OAuth 机制共用的前置校验：类型属于 OAuth 令牌体系 + provider 已启用。
     * 配置管理（saveConfig/getConfig）走这条——授权码和扫码都要读同一张 oauth_config。
     */
    private ConnectorProvider requireOauthProvider(String providerService) {
        ConnectorProvider provider = catalogService.requireProvider(providerService);
        if (!AuthTypeEnum.fromCode(provider.getAuthType()).isOauth2TokenFlow()) {
            throw new ConnectorException(ConnectorErrorCode.UNSUPPORTED_AUTH_TYPE,
                    "该连接器不是 OAuth 类型: " + providerService);
        }
        if (!"enabled".equals(provider.getStatus())) {
            throw new ConnectorException(ConnectorErrorCode.PROVIDER_DISABLED);
        }
        return provider;
    }

    /**
     * 授权码流程专用。扫码类型的 auth_url 存的是设备授权端点、且没有 redirect_uri，
     * 拿它拼授权码 URL 会得到一个必然失败的链接，故在此明确拦下并指路。
     */
    private ConnectorProvider requireAuthCodeProvider(String providerService) {
        ConnectorProvider provider = requireOauthProvider(providerService);
        if (AuthTypeEnum.fromCode(provider.getAuthType()) != AuthTypeEnum.OAUTH2) {
            throw new ConnectorException(ConnectorErrorCode.UNSUPPORTED_AUTH_TYPE,
                    "该连接器是扫码类型，请改用 /api/connector/oauth/device/authorize: " + providerService);
        }
        return provider;
    }

    /**
     * 设备码（扫码）流程专用。授权码类型走扫码会得到一个无法完成建连的二维码，故同样明确拦下。
     */
    private ConnectorProvider requireDeviceProvider(String providerService) {
        ConnectorProvider provider = requireOauthProvider(providerService);
        if (AuthTypeEnum.fromCode(provider.getAuthType()) != AuthTypeEnum.OAUTH2_DEVICE) {
            throw new ConnectorException(ConnectorErrorCode.UNSUPPORTED_AUTH_TYPE,
                    "该连接器不是扫码类型，请改用 /api/connector/oauth/authorize: " + providerService);
        }
        return provider;
    }

    private String buildRedirectUri(String redirectBaseUrl) {
        return redirectBaseUrl.replaceAll("/+$", "") + CALLBACK_PATH;
    }

    /**
     * 「App 没配」这句话要按 {@code oauth_app_mode} 分岔。
     * <p>
     * byo 模式下缺的是<b>用户自己的 App</b>，该做的是连接时填一份（页面上会弹配置抽屉，
     * 接口是 {@code /oauth/byo-config}）；platform 模式下缺的是<b>公共 App</b>，用户自己填不了，
     * 得找空间管理员配。同一句话盖两件事，用户照着做必然有一半是错的——「点了连接只看到报错、
     * 不知道该干什么」就是这么来的。
     */
    private String oauthAppMissingMessage(ConnectorProvider provider) {
        // 扫码连接器单独一条：它「没有 client_id」不一定等于「没配 App」——没 client_id 本身就是
        // 自注册形态的入口。真正的缺口是「注册协议认不出来」，所以要指的路是端点 + 注册协议声明，
        // 而不是笼统的「先配 App」（用户照那句去填 Client ID/Secret，填的只能是占位值，
        // 结果反而绕回「平台自有 App」分支，报一句与原因无关的 App 不存在）。
        if (AuthTypeEnum.fromCode(provider.getAuthType()) == AuthTypeEnum.OAUTH2_DEVICE) {
            return "该扫码连接器还没有可用的自注册配置：请先配置授权端点（authUrl）与令牌端点（tokenUrl）"
                    + "（POST /api/connector/oauth/shared-config 或 byo-config）；"
                    + "若该 IdP 需要先注册应用才能扫码，再在连接器的 auth_config 里补充 appRegistration"
                    + "（url/archetype/authMethod/requestUserInfo）。service=" + provider.getService();
        }
        if (OAUTH_APP_MODE_BYO.equals(provider.getOauthAppMode())) {
            return "该连接器为 byo 模式：请先填写你自己的 OAuth App"
                    + "（Client ID / Client Secret / 授权端点 authUrl / 令牌端点 tokenUrl），"
                    + "配置仅你本人可见；保存接口 POST /api/connector/oauth/byo-config。service="
                    + provider.getService();
        }
        return "该连接器使用平台公共 App，请先由空间管理员配置"
                + "（POST /api/connector/oauth/shared-config）。service=" + provider.getService();
    }

    private String randomToken(int bytes) {
        byte[] buffer = new byte[bytes];
        secureRandom.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    private String pkceChallenge(String verifier) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(sha256(verifier.getBytes(StandardCharsets.US_ASCII)));
    }

    private String sha256Hex(String value) {
        byte[] digest = sha256(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String joinScopes(String scopesJson) {
        return String.join(" ", parseScopes(scopesJson));
    }

    private List<String> parseScopes(String scopesJson) {
        if (StringUtils.isBlank(scopesJson)) {
            return List.of();
        }
        try {
            JSONArray array = JSON.parseArray(scopesJson);
            List<String> scopes = new ArrayList<>();
            for (int i = 0; i < array.size(); i++) {
                scopes.add(array.getString(i));
            }
            return scopes;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> splitScopes(String scope) {
        if (StringUtils.isBlank(scope)) {
            return List.of();
        }
        List<String> scopes = new ArrayList<>();
        for (String part : scope.trim().split("[,\\s]+")) {
            if (StringUtils.isNotBlank(part)) {
                scopes.add(part);
            }
        }
        return scopes;
    }

    private Date resolveExpiresAt(JSONObject tokenResponse) {
        return resolveSpecExpiresAt(tokenResponse, "expires_in");
    }

    /**
     * 有效期一律是「相对秒数」，但字段名各家常不一样（OAuth2 标准是 {@code expires_in}，
     * 飞书的应用令牌是 {@code expire}），所以字段名做成入参。
     * <p>
     * 取不到就返回 null（视为不过期）而不是猜一个默认时长：猜短了会平白多刷，猜长了会拿着
     * 过期令牌去调用、把「令牌过期」伪装成业务错误。
     */
    private Date resolveSpecExpiresAt(JSONObject tokenResponse, String expiresField) {
        Long expiresIn = tokenResponse.getLong(expiresField);
        if (expiresIn == null || expiresIn <= 0) {
            return null;
        }
        return new Date(System.currentTimeMillis() + expiresIn * 1000L);
    }
}
