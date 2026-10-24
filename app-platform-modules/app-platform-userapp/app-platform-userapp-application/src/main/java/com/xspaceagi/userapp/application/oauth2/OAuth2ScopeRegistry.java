package com.xspaceagi.userapp.application.oauth2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.util.AntPathMatcher;

import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;

/**
 * scope 白名单注册表：scope → 允许访问的接口路径模式。
 * <p>
 * 这里是「access_token 能调哪些接口」的唯一真相源，token 校验时据此判定；
 * 接入应用可用 scope 取自 user_project.oauth_scope（逗号分隔），为空取平台默认值。
 *
 * <h2>★门面（facade）范式：scope 只映射到 {@code /api/oauth2/**}</h2>
 *
 * 对外开放的能力一律以 {@code /api/oauth2/**} 下的<b>门面端点</b>提供，门面内部转调既有 application service。
 * 这条约定是本注册表能保持简单的根本原因：
 * <ul>
 * <li>{@code /api/oauth2/**} 不在 {@code auth.excludePath} 里（除 {@code /token}、{@code /revoke}），
 * 因此 {@code AuthInterceptor} 会校验 {@code oa-} 令牌并在此做 scope 判定；</li>
 * <li>{@code ApiKeyInterceptor} 只对 {@code /api/v1/} 前缀抛 {@code apiKeyMissing}，
 * 门面路径<b>不经过</b>它，所以无需改动任何拦截器。</li>
 * </ul>
 * 反之，若要把 {@code /api/v1/**} 直接开放给 {@code oa-} 令牌，必须同时改两处，只改这里不会生效：
 * <ol>
 * <li>补 {@link #SCOPE_PATTERNS}；</li>
 * <li>{@code /api/v1/**} 在 {@code auth.excludePath} 中，鉴权完全由 {@code ApiKeyInterceptor}（order 23）
 * 负责，它只认 {@code ak-}/{@code ck-} 前缀并对该路径抛 {@code apiKeyMissing}，
 * 需在那里补 OAuth2 令牌的放行分支——但必须以「AuthInterceptor 已校验通过」为前提。
 * 注意<b>不能</b>用 {@code RequestContext.isLogin()} 当判据：带登录 Cookie 的浏览器用户同样是
 * {@code isLogin=true}，那会连带放开自家前端的免 AK 限制；也不能只按 {@code oa-} 前缀无条件放行，
 * 否则无效令牌会因 {@code /api/v1/} 属免登录路径而绕过 AK 校验。正确做法是由
 * {@code AuthInterceptor} 的 OAuth2 分支往 request attribute 落一个标记再据此放行。</li>
 * </ol>
 *
 * <h2>★路径里带动词（verb-in-path）</h2>
 *
 * {@link #allows} 只看路径、<b>看不到 HTTP 方法</b>，所以「读」与「写」必须靠<b>不同的路径</b>区分，
 * 不能靠 GET/POST 区分。这也是 {@code conversation/detail} 与 {@code conversation/delete}
 * 这类命名的由来——两个都是字面量，Ant 匹配下互不覆盖。
 */
public final class OAuth2ScopeRegistry {

    // ================================================================== 用户信息

    /** 读取授权用户自身信息 */
    public static final String SCOPE_PROFILE = "profile";

    /** 按用户名 / 邮箱 / 手机号在授权租户内检索用户（能读到他人信息，比 {@code profile} 更宽） */
    public static final String SCOPE_USER_SEARCH = "user:search";

    /**
     * 在 {@code profile} 响应中追加授权用户的手机号。
     * <p>
     * 这是<b>字段级权限</b>，不是端点级权限：它不单独放行任何接口，必须与 {@code profile}
     * 一起授予，否则令牌仍然调不了 {@code /api/oauth2/userinfo}。
     */
    public static final String SCOPE_USER_PHONE = "user:phone";

    /** 查询授权用户可见的空间列表 */
    public static final String SCOPE_USER_SPACE = "user:space";

    /** 以授权用户身份上传文件 */
    public static final String SCOPE_FILE_WRITE = "file:write";

    /** 为授权用户名下的文件签发带访问凭证（ak）的下载 / 预览地址 */
    public static final String SCOPE_FILE_READ = "file:read";

    // ================================================================== 任务会话管理

    /** 读取授权用户的会话与消息 */
    public static final String SCOPE_CHAT_READ = "chat:read";

    /** 以授权用户身份创建 / 修改 / 删除会话、发起对话与停止对话 */
    public static final String SCOPE_CHAT_WRITE = "chat:write";

    // ================================================================== 消息通信（★仅授权码模式）

    /** 读取授权用户的 IM 会话 / 群组 / 联系人 / 会话消息 */
    public static final String SCOPE_IM_READ = "im:read";

    /** 以授权用户身份向指定目标推送 IM 消息 */
    public static final String SCOPE_IM_PUSH = "im:push";

    // ================================================================== 模型（★仅授权码模式）

    /** 查询租户可用模型 */
    public static final String SCOPE_MODEL_READ = "model:read";

    /** 创建模型 API Key 并授权可用模型（签出的 key 只能打模型代理，打不进任何业务接口） */
    public static final String SCOPE_MODEL_KEY = "model:key";

    // ================================================================== 工具检索与执行

    /** 检索已发布的插件与工作流、读取其出入参定义 */
    public static final String SCOPE_TOOL_READ = "tool:read";

    /** 执行已发布的插件与工作流 */
    public static final String SCOPE_TOOL_EXEC = "tool:exec";

    /**
     * scope → 允许访问的接口路径模式；漏登记某个路径会让该路径的 access_token 调用被判为 scope 不足。
     * <p>
     * {@code /user/search} 刻意<b>不</b>归入 {@code profile}：它按条件检索<b>任意</b>用户，而不是读令牌所属用户，
     * 同一把令牌可以因此读到与授权者无关的他人姓名/头像。单列 {@code user:search} 并<b>不</b>放进
     * {@link #DEFAULT_SCOPES}，是为了让「只读自己」的既有接入应用不会被默许搜别人。
     * <p>
     * 用 {@code LinkedHashMap} 而不是 {@code Map.of}：条目已超出 {@code Map.of} 的 10 对上限，
     * 且需要保序（便于按声明顺序核对「scope ↔ 路径」的对应关系）。
     */
    private static final Map<String, List<String>> SCOPE_PATTERNS;

    static {
        Map<String, List<String>> patterns = new LinkedHashMap<>();
        patterns.put(SCOPE_PROFILE, List.of("/api/oauth2/userinfo"));
        patterns.put(SCOPE_USER_SEARCH, List.of("/api/oauth2/user/search"));
        // user:phone 只给 /userinfo 的响应追加 phone 字段，不归属任何端点，因此登记为空列表：
        // 单独持有它调不了 /userinfo（fail-closed），必须同时持有 profile。
        patterns.put(SCOPE_USER_PHONE, List.of());
        patterns.put(SCOPE_USER_SPACE, List.of("/api/oauth2/space/list"));
        patterns.put(SCOPE_FILE_WRITE, List.of("/api/oauth2/file/upload"));
        patterns.put(SCOPE_FILE_READ, List.of("/api/oauth2/file/url"));
        patterns.put(SCOPE_CHAT_READ, List.of(
                "/api/oauth2/chat/conversation/list",
                "/api/oauth2/chat/conversation/detail",
                "/api/oauth2/chat/message/list",
                "/api/oauth2/chat/message/subscribe"));
        patterns.put(SCOPE_CHAT_WRITE, List.of(
                "/api/oauth2/chat/conversation/create",
                "/api/oauth2/chat/conversation/update",
                "/api/oauth2/chat/conversation/delete",
                "/api/oauth2/chat/message/send",
                "/api/oauth2/chat/message/stop",
                "/api/oauth2/chat/message/clear",
                "/api/oauth2/chat/message/permission-response"));
        patterns.put(SCOPE_IM_READ, List.of(
                "/api/oauth2/im/conversation/list",
                "/api/oauth2/im/group/list",
                "/api/oauth2/im/contact/list",
                "/api/oauth2/im/message/list"));
        patterns.put(SCOPE_IM_PUSH, List.of("/api/oauth2/im/message/push"));
        patterns.put(SCOPE_MODEL_READ, List.of("/api/oauth2/model/list"));
        patterns.put(SCOPE_MODEL_KEY, List.of("/api/oauth2/model/api-key/**"));
        patterns.put(SCOPE_TOOL_READ, List.of(
                "/api/oauth2/tool/list",
                "/api/oauth2/tool/schema"));
        patterns.put(SCOPE_TOOL_EXEC, List.of(
                "/api/oauth2/tool/plugin/execute",
                "/api/oauth2/tool/workflow/execute",
                "/api/oauth2/tool/workflow/sse-execute"));
        SCOPE_PATTERNS = Collections.unmodifiableMap(patterns);
    }

    /** 接入应用未配置 oauth_scope 时授予的默认 scope（不含任何敏感 scope，需显式登记） */
    public static final List<String> DEFAULT_SCOPES = List.of(SCOPE_PROFILE);

    /**
     * scope 元数据
     *
     * @param scope                 scope 值
     * @param description           设置页展示的能力说明
     * @param sensitive             是否敏感（设置页标红提示，且不进默认授予范围）
     * @param authorizationCodeOnly 是否<b>仅限授权码模式</b>：为 true 时 {@code client_credentials}
     *                              （M2M，无真人授权）拿不到该 scope。判据是「这个能力是否会让第三方
     *                              以某个真人的身份对外产生副作用、或签出可计费凭据」——IM 能以用户
     *                              身份发消息、模型 Key 能签出可计费凭据，都必须由本人点一次授权。
     */
    public record ScopeMeta(String scope, String description, boolean sensitive, boolean authorizationCodeOnly) {

        /** 便捷构造：非「仅授权码」scope */
        public ScopeMeta(String scope, String description, boolean sensitive) {
            this(scope, description, sensitive, false);
        }
    }

    /**
     * 平台支持的 scope 全集（设置页展示用，有序）。
     * <p>
     * {@code profile} 与 {@code user:search} 是首版就有的两个 scope，<b>保持原样不动</b>：
     * 已接入应用的 {@code oauth_scope} 存量值与已签发令牌都不受影响。
     * <p>
     * 其余为门面 scope，逐条对应 {@code /api/oauth2/**} 下的一组端点。
     * {@code im:*} 与 {@code model:*} 标记为 {@code authorizationCodeOnly}，
     * M2M（client_credentials）申请时会被拒（RFC {@code invalid_scope}）。
     */
    private static final List<ScopeMeta> SCOPE_METAS = List.of(
            new ScopeMeta(SCOPE_PROFILE, "读取授权用户自身信息（昵称、头像等）", false),
            new ScopeMeta(SCOPE_USER_SEARCH, "按用户名 / 邮箱 / 手机号检索租户内用户", true),
            new ScopeMeta(SCOPE_USER_PHONE, "在读取授权用户自身信息时返回手机号（需同时授予 profile）", true),
            new ScopeMeta(SCOPE_USER_SPACE, "查询授权用户可见的空间列表", false),
            new ScopeMeta(SCOPE_FILE_WRITE, "以授权用户身份上传文件", false),
            new ScopeMeta(SCOPE_FILE_READ, "为授权用户的文件签发带凭证的下载地址", false),
            new ScopeMeta(SCOPE_CHAT_READ, "读取授权用户的任务会话与消息记录", false),
            new ScopeMeta(SCOPE_CHAT_WRITE, "以授权用户身份创建会话、发起与停止对话", true),
            new ScopeMeta(SCOPE_IM_READ, "读取授权用户的 IM 会话、群组、联系人与消息", true, true),
            new ScopeMeta(SCOPE_IM_PUSH, "以授权用户身份向指定目标推送 IM 消息", true, true),
            new ScopeMeta(SCOPE_MODEL_READ, "查询租户可用模型列表", false, true),
            new ScopeMeta(SCOPE_MODEL_KEY, "创建模型 API Key 并授权可用模型", true, true),
            new ScopeMeta(SCOPE_TOOL_READ, "检索已发布的插件与工作流、读取其出入参定义", false),
            new ScopeMeta(SCOPE_TOOL_EXEC, "执行已发布的插件与工作流", true));

    /** 平台支持的 scope 全集（设置页展示用） */
    public static List<String> supportedScopes() {
        return SCOPE_METAS.stream().map(ScopeMeta::scope).toList();
    }

    /** 平台支持的 scope 元数据（值 + 说明 + 是否敏感 + 是否仅授权码模式） */
    public static List<ScopeMeta> supportedScopeMetas() {
        return SCOPE_METAS;
    }

    /** 该 scope 是否仅限授权码模式（M2M 拿不到）；未知 scope 返回 false（由白名单判定负责拒绝） */
    public static boolean isAuthorizationCodeOnly(String scope) {
        return SCOPE_METAS.stream()
                .anyMatch(meta -> meta.scope().equals(scope) && meta.authorizationCodeOnly());
    }

    /**
     * 从给定 scope 中挑出<b>不允许</b> client_credentials（M2M）签发的项，空列表表示全部允许。
     * 调用方据此返回 RFC {@code invalid_scope}，而不是静默收窄——静默收窄会让接入方以为拿到了权限。
     */
    public static List<String> findAuthorizationCodeOnly(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return List.of();
        }
        return scopes.stream().filter(OAuth2ScopeRegistry::isAuthorizationCodeOnly).toList();
    }

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private OAuth2ScopeRegistry() {
    }

    /**
     * 解析接入应用登记的 scope 字符串：逗号分隔，去空白、去重、丢弃平台不认识的 scope。
     * 非空但全部非法时返回默认 scope，避免出现「零权限」的哑客户端。
     */
    public static List<String> parse(String raw) {
        List<String> scopes = parseRequested(raw);
        return scopes.isEmpty() ? DEFAULT_SCOPES : scopes;
    }

    /**
     * 解析请求方显式申请的 scope：只保留平台支持的项，未知项直接丢弃（空结果由调用方判为非法）。
     * 与 {@link #parse(String)} 的区别：不回落默认值，避免「申请了不存在的 scope 反而拿到全部默认权限」。
     * <p>
     * 分隔符同时接受逗号与空白：RFC 6749 §3.3 规定 scope 以空格分隔，而平台库存串用逗号。
     */
    public static List<String> parseRequested(String raw) {
        if (StringUtils.isBlank(raw)) {
            return List.of();
        }
        Set<String> scopes = new LinkedHashSet<>();
        for (String item : raw.split("[,\\s]+")) {
            String scope = StringUtils.trimToNull(item);
            if (scope != null && SCOPE_PATTERNS.containsKey(scope)) {
                scopes.add(scope);
            }
        }
        return new ArrayList<>(scopes);
    }

    /** 请求路径是否落在给定 scope 集合的放行范围内 */
    public static boolean allows(List<String> scopes, String requestUri) {
        if (scopes == null || scopes.isEmpty() || StringUtils.isBlank(requestUri)) {
            return false;
        }
        // 去掉查询串后再匹配
        int queryIndex = requestUri.indexOf('?');
        String path = queryIndex < 0 ? requestUri : requestUri.substring(0, queryIndex);
        for (String scope : scopes) {
            List<String> patterns = SCOPE_PATTERNS.get(scope);
            if (patterns == null) {
                continue;
            }
            for (String pattern : patterns) {
                if (MATCHER.match(pattern, path)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 拆分存储串为列表：不过滤白名单、不去重（历史值可能含已退役 scope，过滤会让展示失真）。
     * 空 / null 返回空列表。
     */
    public static List<String> toList(String raw) {
        if (StringUtils.isBlank(raw)) {
            return List.of();
        }
        List<String> scopes = new ArrayList<>();
        for (String item : raw.split(",")) {
            String scope = StringUtils.trimToNull(item);
            if (scope != null) {
                scopes.add(scope);
            }
        }
        return scopes;
    }

    /**
     * 列表归一化为存储串（写库唯一入口）：去空白、去重、丢弃平台不认识的项后逗号拼接；
     * 空列表返回空串（= 清空回落平台默认）
     */
    public static String normalizeToStored(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return StringUtils.EMPTY;
        }
        List<String> parsed = parseRequested(String.join(",", scopes));
        if (parsed.isEmpty()) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.oauth2InvalidScope);
        }
        return String.join(",", parsed);
    }
}
