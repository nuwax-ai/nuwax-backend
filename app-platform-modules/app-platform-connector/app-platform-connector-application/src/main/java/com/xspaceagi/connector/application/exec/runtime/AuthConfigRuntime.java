package com.xspaceagi.connector.application.exec.runtime;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.connector.domain.dto.ExecResultDto;
import com.xspaceagi.connector.domain.entity.ConnectorConnection;
import com.xspaceagi.connector.domain.entity.ConnectorProvider;
import com.xspaceagi.connector.domain.exec.ConnectorRuntime;
import com.xspaceagi.connector.domain.exec.RuntimeContext;
import com.xspaceagi.connector.domain.service.ConnectorOAuthService;
import com.xspaceagi.connector.domain.service.CredentialVaultService;
import com.xspaceagi.connector.spec.ConnectorConstants;
import com.xspaceagi.connector.spec.enums.AuthTypeEnum;
import com.xspaceagi.connector.spec.error.ConnectorErrorCode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置工具 {@code get_auth_config} 的运行时：把连接里托管的凭证发放给客户端。
 * <p>
 * 这是本模块第一个内置 CUSTOM 运行时（{@code ConnectorRuntime} 原 javadoc 写的「本期不提供
 * 任何内置实现」到此为止）。{@code ConnectorRuntimeRegistry} 构造器注入
 * {@code List<ConnectorRuntime>} 自动收集，注册表本身无需改动。
 * <p>
 * 典型用途：沙箱里的 lark-cli 支持 {@code LARKSUITE_CLI_APP_ID} +
 * {@code LARKSUITE_CLI_USER_ACCESS_TOKEN} 环境变量注入（secret 与 token 二选一即可），
 * 正好对上这里下发的 {@code (appId, accessToken)}。连接器的定位因此不只是「存凭证的地方」，
 * 而是凭证托管 + 发放服务。
 *
 * <h2>⚠️ 这是对凭证边界铁律的有意放宽，不是 bug</h2>
 *
 * 本模块有三处成文契约都禁止凭证明文出边界：{@code CredentialVaultService}「凭证明文不出
 * connector 模块边界」、{@code ConnectorRuntime#execute}「实现方不得把 credentialJson 传出
 * 模块边界或写入日志」、{@code ExecResultDto}「Agent/LLM 只见 data 与 meta.connectionProfile」。
 * 本类刻意突破第二条与第三条：{@code get_auth_config} 的职责本身就是把凭证交给客户端，
 * 不发就等于这个工具不存在。
 * <p>
 * <b>由谁决定</b>：用户于 2026-09-10 在两个方案里明确选了 {@code full}
 * （appId + clientSecret + accessToken + refreshToken + tokenType + identity + expiresAt + scope），
 * 理由是客户端拿到完整应用身份后能自行换取各类 token、不依赖平台在线。当时已向其指出这与
 * 其早先记录的设计取向（app_secret 全程不出平台，对外只下发 7200 秒短效 user_access_token）
 * 相反，用户仍选 full。
 * <p>
 * <b>因此把 secret 的落地面收到最窄</b>，改动本类时请继续保持：
 * <ul>
 *   <li>client_secret 只在 {@code data} 里出现一次；<b>不进 meta、不进日志、不进异常消息</b>；</li>
 *   <li>结果打上 {@link ConnectorConstants#META_SENSITIVE_PAYLOAD} 标记，审计侧据此整体省略
 *       outputPreview——否则 client_secret 会被逐字写进 connector_run_log（它不在 credentialJson
 *       里，执行引擎那套「按凭证叶子值替换」的脱敏覆盖不到）；</li>
 *   <li>解密复用 {@code ConnectorOAuthService#resolveAppCredential} 既有路径，本类不新写解密逻辑。</li>
 * </ul>
 * 若日后要收紧成「连接器把配置直接写入沙箱 config.json、技能只拿文件路径」，只改这一个类即可。
 */
@Slf4j
@Component
public class AuthConfigRuntime implements ConnectorRuntime {

    /** 与 connector_action.exec_ref 对应；带命名空间前缀，避免与用户自定义 action key 撞 */
    public static final String EXEC_REF = "connector.auth-config";

    /** 自动附带的动作 key（由 ConnectorCatalogServiceImpl 在保存扫码类连接器时创建） */
    public static final String ACTION_KEY = "get_auth_config";

    /** 飞书应用身份令牌的 {@code token_type} 字面量，是 IdP 私有约定，只在这一处出现 */
    private static final String TOKEN_TYPE_TENANT = "Tenant";

    /** 归一后的身份标识：应用身份。对应 lark-cli 的 LARKSUITE_CLI_TENANT_ACCESS_TOKEN */
    public static final String IDENTITY_TENANT = "tenant";

    /** 归一后的身份标识：用户身份。对应 lark-cli 的 LARKSUITE_CLI_USER_ACCESS_TOKEN */
    public static final String IDENTITY_USER = "user";

    /**
     * 发放出去的令牌至少要剩这么多有效期，不够就先续期再发。
     * <p>
     * 10 分钟是客户端「拿到手 → 真正用上」的容错窗口：客户端可能要先落进环境变量、起子进程、
     * 甚至排队等一会儿才发第一条请求。执行引擎自己的 60 秒余量扛不住这段延迟。
     */
    private static final long ISSUE_MIN_VALIDITY_MS = 10 * 60 * 1000L;

    @Resource
    private CredentialVaultService vaultService;

    @Resource
    private ConnectorOAuthService oauthService;

    @Override
    public String execRef() {
        return EXEC_REF;
    }

    @Override
    public ExecResultDto execute(RuntimeContext context) {
        ConnectorProvider provider = context.getProvider();
        ConnectorConnection connection = context.getConnection();
        // 只放行扫码类：授权码连接的令牌由引擎在调用时注入 Authorization 头，客户端拿不到也不需要
        // 一份明文副本；发出去只是白白多一处泄露面。判据刻意不是 isOauth2TokenFlow()——那个回答的是
        // 「凭证是不是会过期的 OAuth 令牌」，用于刷新与注入，两者不是同一个问题。
        if (AuthTypeEnum.fromCode(provider.getAuthType()) != AuthTypeEnum.OAUTH2_DEVICE) {
            return ExecResultDto.fail(ConnectorErrorCode.UNSUPPORTED_AUTH_TYPE.getCode(),
                    "get_auth_config 只适用于扫码（oauth2_device）连接器，当前认证方式为 "
                            + provider.getAuthType()
                            + "；授权码（oauth2）连接的令牌由平台在每次调用时自动注入，无需本工具");
        }
        if (connection == null || StringUtils.isBlank(context.getCredentialJson())) {
            return ExecResultDto.fail(ConnectorErrorCode.CONNECTION_REQUIRED.getCode(),
                    "尚未建立连接，请先完成授权");
        }

        // 引擎在 runCustom 之前已按 60 秒余量刷过一次，那是给它自己「马上用掉」准备的。
        // 本工具是把令牌发出去给别人离线持有，口径不同，所以这里按 10 分钟复核一次。
        JSONObject credential = ensureIssueable(connection,
                JSON.parseObject(context.getCredentialJson()));
        // App 配置读不到不该让整个工具失败：accessToken 仍然可用，客户端只是少一个 clientSecret
        ConnectorOAuthService.AppCredentialView app = null;
        try {
            app = oauthService.resolveAppCredential(provider.getService(), context.getUserId());
        } catch (Exception e) {
            log.warn("[connector] get_auth_config 读取 App 配置失败，本次仅下发令牌: provider={}, connectionId={}",
                    provider.getService(), connection.getId());
        }
        // appId 优先取凭证里存的那份：建连时写入，与这张 token 必然同源；
        // 凭证里没有（本次改动之前建的老连接）才回落到当前生效的 App 配置。
        String appId = StringUtils.defaultIfBlank(credential.getString("appId"),
                app == null ? null : app.appId());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("appId", appId);
        data.put("clientSecret", app == null ? null : app.clientSecret());
        data.put("accessToken", credential.getString("accessToken"));
        data.put("refreshToken", credential.getString("refreshToken"));
        data.put("tokenType", StringUtils.defaultIfBlank(credential.getString("tokenType"), "Bearer"));
        data.put("identity", resolveIdentity(credential.getString("tokenType")));
        data.put("expiresAt", expiresAtMillis(connection));
        data.put("scope", parseScopes(connection.getGrantedScopes()));

        // 只记标识与 id，绝不记 data：本类的 data 就是凭证本身
        log.info("[connector] get_auth_config issued: provider={}, connectionId={}, userId={}",
                provider.getService(), connection.getId(), context.getUserId());
        return ExecResultDto.ok(data, "已返回本连接的认证信息（含应用密钥，请仅在受控环境中使用）")
                .meta(ConnectorConstants.META_SENSITIVE_PAYLOAD, true)
                .meta("connectionId", connection.getId())
                .meta("providerService", provider.getService())
                .meta("authType", provider.getAuthType());
    }

    /**
     * 把 IdP 的 {@code token_type} 归一成两种身份，供客户端决定把令牌注入到哪个环境变量。
     * <p>
     * 为什么必须有这个字段：{@code token_type} 是 IdP 自造的自由文本——飞书的应用身份给
     * {@code "Tenant"}，用户身份走标准 OAuth2 给 {@code "Bearer"}。客户端若照这个字符串去猜
     * 「该塞进 LARKSUITE_CLI_TENANT_ACCESS_TOKEN 还是 LARKSUITE_CLI_USER_ACCESS_TOKEN」，
     * 就等于把飞书的私有约定提升成了跨模块契约。这里显式归一，客户端只认这两个值。
     * <p>
     * 缺省判 {@code user} 而非 {@code tenant}：标准授权码/设备码流程换到的本就是用户令牌，
     * {@code "Tenant"} 只是飞书应用身份这一条支路的特例。
     * <p>
     * public 且 static：建连时也要把同一套归一结果写进 {@code oauth_meta.identity}（界面按它区分
     * 「应用身份」与「用户身份」两条连接）。建连方拿到的 {@code token_type} 与这里发放时读的是
     * 同一个字段，若各写一份归一规则，两边迟早会在「什么样的 tokenType 算 tenant」上分叉，
     * 表现为界面标注与实际注入的环境变量对不上。
     */
    public static String resolveIdentity(String tokenType) {
        return TOKEN_TYPE_TENANT.equalsIgnoreCase(StringUtils.trimToEmpty(tokenType))
                ? IDENTITY_TENANT : IDENTITY_USER;
    }

    /**
     * 发放前复核剩余有效期：不足 {@link #ISSUE_MIN_VALIDITY_MS} 就先续期，再从保险库重读凭证。
     * <p>
     * 引擎在 runCustom 之前跑过的 {@code ensureFreshOAuthToken} 用的是 60 秒余量——它假设令牌
     * 「马上就要被这次请求用掉」。本工具恰恰相反：令牌发出去后客户端就脱离平台了。递一张只剩几分钟
     * 的票出去，失效现场在客户端而不在平台，排查成本高得多。
     * <p>
     * 续期会同时改写 accessToken / refreshToken / tokenType（部分 IdP 轮换 refresh_token），
     * 所以必须重读，不能沿用 {@code context.getCredentialJson()} 那份续期前的快照。
     * <p>
     * 续期失败<b>不降级</b>返回旧令牌：宁可当场报错，也不发一张几分钟后就作废的票。异常原样上抛
     * （连接同时已被标记为 EXPIRED）。
     */
    private JSONObject ensureIssueable(ConnectorConnection connection, JSONObject credential) {
        if (connection.getCredentialId() == null) {
            return credential;
        }
        CredentialVaultService.Meta meta = vaultService.peek(connection.getCredentialId());
        // expiresAt 为空表示「永不过期」（如 GitHub 不返回 expires_in），没有可续的东西
        if (meta == null || meta.expiresAt() == null) {
            return credential;
        }
        long remainingMs = meta.expiresAt().getTime() - System.currentTimeMillis();
        if (remainingMs >= ISSUE_MIN_VALIDITY_MS) {
            return credential;
        }
        log.info("[connector] get_auth_config 发放前续期: connectionId={}, 剩余={}秒",
                connection.getId(), remainingMs / 1000);
        oauthService.validAccessToken(connection, ISSUE_MIN_VALIDITY_MS);
        CredentialVaultService.Meta renewed = vaultService.peek(connection.getCredentialId());
        if (renewed != null && renewed.expiresAt() != null
                && renewed.expiresAt().getTime() - System.currentTimeMillis() < ISSUE_MIN_VALIDITY_MS) {
            // 「续期调用成功了，但没换来更长的有效期」——飞书的应用身份令牌就是这样：旧票没过期时
            // 再申请，它把缓存那张原样还回来，expire 给的是剩余秒数而不是新的 7200。
            // 这不是故障，但客户端确实还是会拿到一张很快作废的票，所以不能静默。
            log.warn("[connector] get_auth_config 续期未延长有效期（IdP 返回缓存令牌，需等其自然过期）:"
                            + " connectionId={}, 仍剩={}秒",
                    connection.getId(),
                    (renewed.expiresAt().getTime() - System.currentTimeMillis()) / 1000);
        }
        return JSON.parseObject(vaultService.reveal(connection.getCredentialId()));
    }

    /**
     * access_token 的过期时刻（epoch 毫秒）。
     * <p>
     * 读的是保险库里的当前值，所以 {@link #ensureIssueable} 刚续过期的话，这里拿到的就是续期后的
     * 新过期时间——下发出去的 {@code expiresAt} 与令牌本身始终对得上。
     */
    private Long expiresAtMillis(ConnectorConnection connection) {
        if (connection.getCredentialId() == null) {
            return null;
        }
        CredentialVaultService.Meta meta = vaultService.peek(connection.getCredentialId());
        return meta == null || meta.expiresAt() == null ? null : meta.expiresAt().getTime();
    }

    /** granted_scopes 建连时以 JSON 数组字符串落库；解析不出就空数组，不值得为此失败 */
    private List<String> parseScopes(String grantedScopesJson) {
        if (StringUtils.isBlank(grantedScopesJson)) {
            return List.of();
        }
        try {
            return JSON.parseArray(grantedScopesJson, String.class);
        } catch (Exception e) {
            return List.of();
        }
    }
}
