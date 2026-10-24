package com.xspaceagi.userapp.web.controller;

import com.xspaceagi.file.sdk.IFileAccessService;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.utils.MD5;
import com.xspaceagi.userapp.application.oauth2.OAuth2GrantContext;
import com.xspaceagi.userapp.application.oauth2.OAuth2Properties;
import com.xspaceagi.userapp.application.oauth2.OAuth2ScopeRegistry;
import com.xspaceagi.userapp.application.oauth2.OAuth2TokenPair;
import com.xspaceagi.userapp.application.service.IOAuth2AuthorizationService;
import com.xspaceagi.userapp.application.service.IThirdAppOAuth2ApplicationService;
import com.xspaceagi.userapp.domain.model.UserProjectModel;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 平台 OAuth2 协议端点（授权方角色）。
 * <p>
 * 响应体刻意不是 {@code ReqResult}：拦截器的响应包装只作用于 ReqResult，普通对象原样输出，
 * 从而保持 RFC 6749 要求的扁平 token 响应结构。
 * <ul>
 * <li>{@code /authorize}、{@code /userinfo}、{@code /user/search}：需登录态（后两者走 access_token 的拦截器分支，
 * 分别对应 {@code profile} 与 {@code user:search} 两个 scope）；</li>
 * <li>{@code /token}、{@code /revoke}：免登录（auth.excludePath），靠客户端凭证认证。</li>
 * </ul>
 */
@Tag(name = "OAuth2", description = "Platform OAuth2 authorization endpoints")
@RestController
@RequestMapping("/api/oauth2")
@Slf4j
public class OAuth2Controller extends BaseController {

    private static final String GRANT_AUTHORIZATION_CODE = "authorization_code";

    private static final String GRANT_REFRESH_TOKEN = "refresh_token";

    private static final String GRANT_CLIENT_CREDENTIALS = "client_credentials";

    /**
     * PKCE 变换方式：SHA-256 后 base64url（RFC 7636 推荐，默认值 plain 仅为兼容老客户端）
     */
    private static final String PKCE_METHOD_S256 = "S256";

    private static final String PKCE_METHOD_PLAIN = "plain";

    /**
     * PKCE challenge / verifier 长度区间（RFC 7636 §4.1、§4.2：43~128）
     */
    private static final int PKCE_MIN_LENGTH = 43;

    private static final int PKCE_MAX_LENGTH = 128;

    /**
     * code_verifier 合法字符集（RFC 7636 §4.1：unreserved = ALPHA / DIGIT / "-" / "." / "_" / "~"）
     */
    private static final Pattern PKCE_VERIFIER_PATTERN = Pattern.compile("^[A-Za-z0-9\\-._~]{43,128}$");

    @Resource
    private IThirdAppOAuth2ApplicationService thirdAppOAuth2ApplicationService;

    @Resource
    private IOAuth2AuthorizationService oAuth2AuthorizationService;

    @Resource
    private OAuth2Properties oAuth2Properties;

    @Resource
    private UserApplicationService userApplicationService;

    @Resource
    private IFileAccessService iFileAccessService;

    /**
     * 授权端点。
     * <p>
     * ★支持 PKCE（RFC 7636）：{@code code_challenge} / {@code code_challenge_method} 随授权码一起存进
     * {@link OAuth2GrantContext}，由 {@code /token} 用 {@code code_verifier} 校验。
     * 平台令牌是不透明随机串 + Redis，PKCE 在这里的作用不是防令牌泄露，而是<b>防授权码被截获后盗换</b>
     * ——公共客户端（SPA / 移动端 / 桌面端）没有 client_secret，授权码是它唯一的凭据。
     * <p>
     * 不强制：未带 challenge 的授权码走原逻辑（{@code context.codeChallenge == null} → 跳过校验），
     * 存量接入方不受影响。
     */
    @Operation(summary = "授权端点：校验客户端与回调地址后签发授权码并 302 回跳（支持 PKCE）")
    @GetMapping("/authorize")
    public ResponseEntity<?> authorize(
            @RequestParam(value = "client_id", required = false) String clientId,
            @RequestParam(value = "redirect_uri", required = false) String redirectUri,
            @RequestParam(value = "response_type", required = false) String responseType,
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "code_challenge", required = false) String codeChallenge,
            @RequestParam(value = "code_challenge_method", required = false) String codeChallengeMethod) {
        UserContext userContext = getUser();
        UserProjectModel project;
        try {
            project = thirdAppOAuth2ApplicationService.requireEnabledClient(clientId);
            thirdAppOAuth2ApplicationService.verifyRedirectUri(project, redirectUri);
        } catch (BizException e) {
            // 客户端或回调地址不可信时不得跳转（RFC 6749 §4.1.2.1），直接返回 JSON 错误
            return errorResponse(HttpStatus.BAD_REQUEST, rfcError(e), e.getMessage());
        }
        // 客户端只在本租户内反查得到（getByOAuthClientId 沿用多租户过滤），故 project 必然与登录用户同租户
        if (!"code".equals(responseType)) {
            return errorRedirect(redirectUri, state, "unsupported_response_type", "response_type must be code");
        }
        if (!oAuth2Properties.isAutoConsent()) {
            // 同意页尚未实现（原型未覆盖、前端契约未定），开关关闭时拒绝授权而非静默放行
            return errorRedirect(redirectUri, state, "temporarily_unavailable", "consent page is not available");
        }
        List<String> scopes;
        try {
            scopes = thirdAppOAuth2ApplicationService.resolveRequestedScopes(project, scope);
        } catch (BizException e) {
            return errorRedirect(redirectUri, state, "invalid_scope", e.getMessage());
        }
        // PKCE 参数校验放在客户端与回调地址校验之后：此刻 redirectUri 已可信，错误可以安全回跳
        String challenge = StringUtils.trimToNull(codeChallenge);
        String challengeMethod = null;
        if (challenge != null) {
            challengeMethod = StringUtils.isBlank(codeChallengeMethod)
                    ? PKCE_METHOD_PLAIN
                    : StringUtils.trim(codeChallengeMethod);
            if (!PKCE_METHOD_S256.equals(challengeMethod) && !PKCE_METHOD_PLAIN.equals(challengeMethod)) {
                return errorRedirect(redirectUri, state, "invalid_request",
                        "code_challenge_method must be S256 or plain");
            }
            if (challenge.length() < PKCE_MIN_LENGTH || challenge.length() > PKCE_MAX_LENGTH) {
                return errorRedirect(redirectUri, state, "invalid_request",
                        "code_challenge must be 43-128 characters");
            }
        }
        OAuth2GrantContext context = new OAuth2GrantContext();
        context.setProjectId(project.getId());
        context.setClientId(project.getOauthClientId());
        context.setUserId(userContext.getUserId());
        context.setTenantId(userContext.getTenantId());
        context.setRedirectUri(redirectUri);
        context.setScope(String.join(",", scopes));
        context.setCodeChallenge(challenge);
        context.setCodeChallengeMethod(challengeMethod);
        String code = oAuth2AuthorizationService.createAuthorizationCode(context);
        // 记下常驻授权：刷新令牌时据此判断用户授权是否仍然有效
        oAuth2AuthorizationService.saveGrant(project.getOauthClientId(), userContext.getUserId(), scopes);
        log.info("OAuth2 authorization code issued, clientId={}, userId={}, scope={}, pkce={}",
                project.getOauthClientId(), userContext.getUserId(), context.getScope(),
                challengeMethod == null ? "off" : challengeMethod);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("code", code);
        params.put("state", state);
        return redirect(redirectUri, params);
    }

    @Operation(summary = "令牌端点：authorization_code / refresh_token / client_credentials")
    @PostMapping("/token")
    public ResponseEntity<Map<String, Object>> token(
            @RequestParam(value = "grant_type", required = false) String grantType,
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "redirect_uri", required = false) String redirectUri,
            @RequestParam(value = "code_verifier", required = false) String codeVerifier,
            @RequestParam(value = "refresh_token", required = false) String refreshToken,
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "client_id", required = false) String clientId,
            @RequestParam(value = "client_secret", required = false) String clientSecret,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String[] credential = resolveClientCredential(clientId, clientSecret, authorization);
        UserProjectModel project;
        try {
            project = thirdAppOAuth2ApplicationService.requireEnabledClient(credential[0]);
            thirdAppOAuth2ApplicationService.verifyClientSecret(project, credential[1]);
        } catch (BizException e) {
            return errorResponse(HttpStatus.UNAUTHORIZED, "invalid_client", e.getMessage());
        }
        if (!GRANT_AUTHORIZATION_CODE.equals(grantType) && !GRANT_REFRESH_TOKEN.equals(grantType)
                && !GRANT_CLIENT_CREDENTIALS.equals(grantType)) {
            return errorResponse(HttpStatus.BAD_REQUEST, "unsupported_grant_type",
                    "grant_type must be one of authorization_code, refresh_token, client_credentials");
        }
        try {
            if (GRANT_AUTHORIZATION_CODE.equals(grantType)) {
                return authorizationCodeGrant(project, code, redirectUri, codeVerifier);
            }
            if (GRANT_REFRESH_TOKEN.equals(grantType)) {
                return refreshTokenGrant(project, refreshToken);
            }
            return clientCredentialsGrant(project, scope);
        } catch (BizException e) {
            return errorResponse(HttpStatus.BAD_REQUEST, rfcError(e), e.getMessage());
        }
    }

    @Operation(summary = "用户信息端点：返回 access_token 所属授权用户的公开信息")
    @RequestMapping(value = "/userinfo", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<Map<String, Object>> userinfo() {
        return ResponseEntity.ok(userInfoBody());
    }

    /**
     * 用户检索端点：按用户名 / 邮箱 / 手机号在<b>授权租户内</b>查一个用户。
     * <p>
     * 这是与 {@link #userinfo()} 不同的能力——{@code /userinfo} 只能读令牌所属用户，本端点能读任意用户——
     * 因此归 {@code user:search} scope，且<b>不在</b> {@code OAuth2ScopeRegistry.DEFAULT_SCOPES} 里，
     * 接入方必须显式登记才拿得到。
     * <ul>
     * <li>三个入参三选一，全空返回 {@code 400 invalid_request}；同时给出多个时按
     * userName &gt; email &gt; phone 取第一个（与平台 {@code /api/user/query} 的口径一致）；</li>
     * <li>命中返回与 {@code /userinfo} <b>同字段</b>的对象；查不到返回<b>空对象</b> {@code {}}；</li>
     * <li>已删除用户、不在授权租户内的用户一律按「查不到」处理且不区分原因——否则本端点会退化成
     * 「某个手机号 / 邮箱是否存在于别租户」的探测器。</li>
     * </ul>
     */
    @Operation(summary = "用户检索端点：按 userName / email / phone 查用户（需 user:search scope，查不到返回空对象）")
    @RequestMapping(value = "/user/search", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<Map<String, Object>> userSearch(
            @RequestParam(value = "userName", required = false) String userName,
            @RequestParam(value = "email", required = false) String email,
            @RequestParam(value = "phone", required = false) String phone) {
        UserContext userContext = getUser();
        UserDto target;
        if (StringUtils.isNotBlank(userName)) {
            target = userApplicationService.queryUserByUserName(userName.trim());
        } else if (StringUtils.isNotBlank(email)) {
            target = userApplicationService.queryUserByEmail(email.trim());
        } else if (StringUtils.isNotBlank(phone)) {
            target = userApplicationService.queryUserByPhone(phone.trim());
        } else {
            return errorResponse(HttpStatus.BAD_REQUEST, "invalid_request",
                    "at least one of userName, email or phone is required");
        }
        if (target != null && !Objects.equals(userContext.getTenantId(), target.getTenantId())) {
            // 用户域底层查询在部分路径上刻意豁免租户过滤（登录链路需要），这里必须自己收口。
            // 日志只记租户与是否命中，不记手机号 / 邮箱（PII）
            log.warn("OAuth2 user search cross-tenant rejected, authorizedUserId={}, authorizedTenantId={}, hitTenantId={}",
                    userContext.getUserId(), userContext.getTenantId(), target.getTenantId());
            target = null;
        }
        if (target == null || target.getStatus() == User.Status.Deleted) {
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(new LinkedHashMap<>());
        }
        String avatar = resetUserAvatarUrl((TenantConfigDto) RequestContext.get().getTenantConfig(), target.getId(), target.getAvatar());
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(buildUserBody(target.getId(), target.getUserName(), target.getNickName(), avatar,
                        target.getTenantId()));
    }

    /**
     * 授权用户的公开信息体，供 {@link #userinfo()} 使用。
     * <p>
     * {@code phone} 是字段级权限：仅当令牌的有效 scope 含 {@code user:phone} 时才追加。
     * 普通登录态（非 {@code oa-} 令牌）没有 OAuth2 scope，不会因此多暴露手机号。
     */
    private Map<String, Object> userInfoBody() {
        UserContext userContext = getUser();
        String avatar = resetUserAvatarUrl((TenantConfigDto) RequestContext.get().getTenantConfig(), userContext.getUserId(), userContext.getAvatar());
        Map<String, Object> body = buildUserBody(userContext.getUserId(), userContext.getUserName(),
                userContext.getNickName(), avatar, userContext.getTenantId());
        if (hasScope(OAuth2ScopeRegistry.SCOPE_USER_PHONE)) {
            body.put("phone", userContext.getPhone());
        }
        return body;
    }

    /** 当前请求的 OAuth2 有效 scope 是否包含指定项；普通登录态恒为 false */
    private static boolean hasScope(String scope) {
        RequestContext<?> requestContext = RequestContext.get();
        return requestContext != null
                && OAuth2ScopeRegistry.toList(requestContext.getOauth2Scope()).contains(scope);
    }

    /**
     * 用户公开信息体：{@code /userinfo} 返回授权者本人，{@code /user/search} 返回被检出的人，字段完全一致。
     */
    private Map<String, Object> buildUserBody(Long userId, String userName, String nickName, String avatar,
                                              Long tenantId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sub", String.valueOf(userId));
        body.put("user_id", userId);
        body.put("name", StringUtils.isNotBlank(nickName) ? nickName : userName);
        body.put("avatar", accessibleAvatarUrl(avatar));
        body.put("tenant_id", tenantId);
        return body;
    }

    /**
     * 把库里存的头像换成<b>平台外可直接打开</b>的地址（即需求里的「认证过后的地址」）。
     * <p>
     * ★<b>为什么必须换</b>：{@code user.avatar} 存的是上传接口原样返回的值，形如
     * {@code {siteUrl}/api/f/{fileKey}}（{@code FileManagementServiceImpl} 用 {@code siteUrl} 拼）。而
     * {@code /api/f/**} 虽在 {@code auth.excludePath} 里免登录，{@code FileManagementController} 紧接着做了
     * 二次校验：<b>未登录必须带对得上 Redis 的 {@code ?ak=}</b>，否则 401。第三方系统没有平台登录态，
     * 直接把这个值塞进 {@code <img src>} 只会拿到 401 —— 头像永远是破图。
     * <p>
     * 换法沿用 OAuth2 门面 {@code GET /api/oauth2/file/url} 的同一口径
     * （{@code getFileUrlWithAk(..., true)}）：云存储签发 24h 预签名直链（不经平台），
     * 本地存储签发 24h 有效的 {@code ?ak=} 地址。因此本端点的 {@code avatar} 与
     * 「拿同样的值去调一次 {@code /file/url}」等价 —— 第三方不必再自己换。
     * <ul>
     * <li>非平台文件（用户自填的第三方外链、{@code /api/logo/} 默认图标）不以 {@code /api/f/} 打头，
     * 原样返回，不会被改写；</li>
     * <li>空值原样返回（未设置头像时字段保持 {@code null}，不凭空造地址）；</li>
     * <li>签名失败或没签出结果时降级回原值并告警，<b>不能让整个 {@code /userinfo} 挂掉</b>——
     * 第三方拿不到身份是致命的，拿不到头像只是显示问题；回原值至少维持了「字段有值」的既有表现。</li>
     * </ul>
     * ⚠️ 地址带有效期（24h）。第三方<b>不要长期缓存</b>，每次取身份时重新调 {@code /userinfo} 即可拿到新地址。
     */
    private String accessibleAvatarUrl(String avatar) {
        if (StringUtils.isBlank(avatar)) {
            return avatar;
        }
        try {
            String signed = iFileAccessService.getFileUrlWithAk(avatar, true);
            return StringUtils.isBlank(signed) ? avatar : signed;
        } catch (Exception e) {
            log.warn("OAuth2 userinfo: failed to sign avatar url, falling back to the stored value", e);
            return avatar;
        }
    }

    @Operation(summary = "撤销端点：撤销令牌并清除该用户对该客户端的授权")
    @PostMapping("/revoke")
    public ResponseEntity<Map<String, Object>> revoke(
            @RequestParam(value = "token", required = false) String token,
            @RequestParam(value = "token_type_hint", required = false) String tokenTypeHint,
            @RequestParam(value = "client_id", required = false) String clientId,
            @RequestParam(value = "client_secret", required = false) String clientSecret,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String[] credential = resolveClientCredential(clientId, clientSecret, authorization);
        UserProjectModel project;
        try {
            project = thirdAppOAuth2ApplicationService.requireEnabledClient(credential[0]);
            thirdAppOAuth2ApplicationService.verifyClientSecret(project, credential[1]);
        } catch (BizException e) {
            return errorResponse(HttpStatus.UNAUTHORIZED, "invalid_client", e.getMessage());
        }
        // RFC 7009：令牌本身无效同样返回 200，避免向调用方暴露令牌状态
        OAuth2GrantContext context = oAuth2AuthorizationService.isRefreshToken(token)
                ? oAuth2AuthorizationService.loadRefreshToken(token)
                : oAuth2AuthorizationService.loadAccessToken(token);
        if (context != null && project.getOauthClientId().equals(context.getClientId())) {
            oAuth2AuthorizationService.revoke(context.getClientId(), context.getUserId());
            log.info("OAuth2 token revoked, clientId={}, userId={}, tokenTypeHint={}", context.getClientId(),
                    context.getUserId(), tokenTypeHint);
        }
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(new LinkedHashMap<>());
    }

    /**
     * 授权码换令牌：授权码一次性、必须属于同一客户端、回调地址需与授权请求一致、PKCE 校验通过
     */
    private ResponseEntity<Map<String, Object>> authorizationCodeGrant(UserProjectModel project, String code,
                                                                       String redirectUri, String codeVerifier) {
        OAuth2GrantContext context = oAuth2AuthorizationService.consumeAuthorizationCode(code);
        if (context == null) {
            return errorResponse(HttpStatus.BAD_REQUEST, "invalid_grant", "authorization code is invalid, expired or already used");
        }
        if (!project.getOauthClientId().equals(context.getClientId())) {
            return errorResponse(HttpStatus.BAD_REQUEST, "invalid_grant", "authorization code was issued to another client");
        }
        if (StringUtils.isBlank(redirectUri) || !redirectUri.equals(context.getRedirectUri())) {
            return errorResponse(HttpStatus.BAD_REQUEST, "invalid_grant",
                    "redirect_uri does not match the authorization request");
        }
        // PKCE（RFC 7636 §4.6）：授权码带了 challenge 就<b>必须</b>校验 verifier，校验不过一律 invalid_grant。
        // 授权码此刻已被 consume 删除（一次性），所以失败即终结，无法被重试爆破
        if (StringUtils.isNotBlank(context.getCodeChallenge()) && !verifyPkce(context, codeVerifier)) {
            log.warn("OAuth2 PKCE verification failed, clientId={}, method={}", context.getClientId(),
                    context.getCodeChallengeMethod());
            return errorResponse(HttpStatus.BAD_REQUEST, "invalid_grant", "code_verifier is missing or invalid");
        }
        return tokenResponse(oAuth2AuthorizationService.issueTokens(context, true));
    }

    /**
     * PKCE 校验：按授权码里记的 method 变换 verifier 后与 challenge 常量时间比对。
     * <p>
     * 用 {@link MessageDigest#isEqual} 而不是 {@code String.equals}：verifier 是攻击者可反复尝试的输入，
     * 逐字符短路的比较会留出时间侧信道。
     *
     * @return true = 校验通过；challenge 为空（未启用 PKCE）时返回 false，调用方需先判空
     */
    private static boolean verifyPkce(OAuth2GrantContext context, String codeVerifier) {
        String challenge = StringUtils.trimToNull(context.getCodeChallenge());
        String verifier = StringUtils.trimToNull(codeVerifier);
        if (challenge == null || verifier == null || !PKCE_VERIFIER_PATTERN.matcher(verifier).matches()) {
            return false;
        }
        byte[] expected;
        if (PKCE_METHOD_S256.equals(context.getCodeChallengeMethod())) {
            expected = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(sha256(verifier.getBytes(StandardCharsets.US_ASCII)))
                    .getBytes(StandardCharsets.US_ASCII);
        } else {
            // plain：challenge 就是 verifier 原文（method 缺省即按 plain，与 RFC 7636 §4.3 一致）
            expected = verifier.getBytes(StandardCharsets.US_ASCII);
        }
        return MessageDigest.isEqual(expected, challenge.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] sha256(byte[] source) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(source);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，走到这里说明 JVM 不完整
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /**
     * 刷新令牌：轮换旧 refresh_token；授权已被撤销时不再续签
     */
    private ResponseEntity<Map<String, Object>> refreshTokenGrant(UserProjectModel project, String refreshToken) {
        OAuth2GrantContext context = oAuth2AuthorizationService.loadRefreshToken(refreshToken);
        if (context == null || !project.getOauthClientId().equals(context.getClientId())) {
            return errorResponse(HttpStatus.BAD_REQUEST, "invalid_grant", "refresh token is invalid or expired");
        }
        if (!oAuth2AuthorizationService.hasGrant(context.getClientId(), context.getUserId())) {
            return errorResponse(HttpStatus.BAD_REQUEST, "invalid_grant", "the authorization has been revoked");
        }
        oAuth2AuthorizationService.discardRefreshToken(refreshToken);
        context.setGrantType(GRANT_REFRESH_TOKEN);
        return tokenResponse(oAuth2AuthorizationService.issueTokens(context, true));
    }

    /**
     * 客户端凭证（M2M）：无用户上下文，绑定接入应用创建者（与平台 AK 的语义一致），不签发 refresh_token。
     * <p>
     * ★<b>高危 scope 不给 M2M</b>：{@code im:*}、{@code model:*} 标记为
     * {@link OAuth2ScopeRegistry.ScopeMeta#authorizationCodeOnly()}，必须由真人走一次授权码流程才拿得到。
     * 理由是 M2M 的身份是「应用创建者」而不是任何真实授权者，让它以创建者身份发 IM 消息、
     * 签可计费的模型 Key，等于把创建者的私域能力无条件交给持有 client_secret 的任何进程。
     * <p>
     * 两种失败方式刻意区分开：
     * <ul>
     * <li>客户端<b>显式</b>申请了禁用 scope → 整个请求判 {@code invalid_scope}。静默收窄会让接入方
     * 以为拿到了权限，直到调用时才 403，排查成本远高于当场报错；</li>
     * <li>客户端<b>没申请</b>（scope 为空，取登记全集）→ 收窄到可用子集并照常签发。否则「为授权码流程
     * 登记了 {@code im:read}」会连带把该应用的 M2M 整个打死，是个反直觉的坑。收窄结果通过 token
     * 响应的 {@code scope} 字段如实回告（RFC 6749 §3.3 要求）。</li>
     * </ul>
     */
    private ResponseEntity<Map<String, Object>> clientCredentialsGrant(UserProjectModel project, String scope) {
        Long userId = project.getCreatorId();
        if (userId == null) {
            return errorResponse(HttpStatus.BAD_REQUEST, "invalid_client", "the client has no owner");
        }
        List<String> scopes = thirdAppOAuth2ApplicationService.resolveRequestedScopes(project, scope);
        List<String> forbidden = OAuth2ScopeRegistry.findAuthorizationCodeOnly(scopes);
        if (!forbidden.isEmpty()) {
            if (StringUtils.isNotBlank(scope)) {
                return errorResponse(HttpStatus.BAD_REQUEST, "invalid_scope",
                        "scope [" + String.join(", ", forbidden)
                                + "] requires an end-user authorization and is not available with client_credentials;"
                                + " use grant_type=authorization_code");
            }
            scopes = scopes.stream().filter(s -> !OAuth2ScopeRegistry.isAuthorizationCodeOnly(s)).toList();
            log.info("OAuth2 client_credentials narrowed scope, clientId={}, dropped={}",
                    project.getOauthClientId(), forbidden);
            if (scopes.isEmpty()) {
                return errorResponse(HttpStatus.BAD_REQUEST, "invalid_scope",
                        "this client only has user-authorization scopes registered;"
                                + " nothing is available with client_credentials");
            }
        }
        OAuth2GrantContext context = new OAuth2GrantContext();
        context.setProjectId(project.getId());
        context.setClientId(project.getOauthClientId());
        context.setUserId(userId);
        context.setTenantId(project.getTenantId());
        context.setScope(String.join(",", scopes));
        context.setGrantType(GRANT_CLIENT_CREDENTIALS);
        return tokenResponse(oAuth2AuthorizationService.issueTokens(context, false));
    }

    private ResponseEntity<Map<String, Object>> tokenResponse(OAuth2TokenPair pair) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", pair.getAccessToken());
        body.put("token_type", pair.getTokenType());
        body.put("expires_in", pair.getExpiresIn());
        if (StringUtils.isNotBlank(pair.getRefreshToken())) {
            body.put("refresh_token", pair.getRefreshToken());
        }
        body.put("scope", pair.getScope());
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("Pragma", "no-cache").body(body);
    }

    private static ResponseEntity<Map<String, Object>> errorResponse(HttpStatus status, String error,
                                                                     String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        if (StringUtils.isNotBlank(description)) {
            body.put("error_description", description);
        }
        return ResponseEntity.status(status).header("Cache-Control", "no-store").header("Pragma", "no-cache").body(body);
    }

    private static ResponseEntity<Void> errorRedirect(String redirectUri, String state, String error,
                                                      String description) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("error", error);
        params.put("error_description", description);
        params.put("state", state);
        return redirect(redirectUri, params);
    }

    /**
     * 拼装回跳地址：参数统一百分号编码（state 原值对客户端仍是原样语义）
     */
    private static ResponseEntity<Void> redirect(String redirectUri, Map<String, String> params) {
        StringBuilder url = new StringBuilder(redirectUri);
        char separator = redirectUri.contains("?") ? '&' : '?';
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (StringUtils.isBlank(entry.getValue())) {
                continue;
            }
            url.append(separator).append(entry.getKey()).append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            separator = '&';
        }
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url.toString())).build();
    }

    /**
     * 业务异常 → RFC 6749 错误码
     */
    private static String rfcError(BizException e) {
        String msgName = e.getMsgName();
        if (msgName == null) {
            return "invalid_request";
        }
        return switch (msgName) {
            case "oauth2InvalidGrant" -> "invalid_grant";
            case "oauth2ClientNotFound", "oauth2ClientSecretInvalid" -> "invalid_client";
            case "oauth2InsufficientScope", "oauth2InvalidScope" -> "invalid_scope";
            default -> "invalid_request";
        };
    }

    /**
     * 客户端凭证：表单参数优先，兼容 HTTP Basic（RFC 6749 §2.3.1）
     */
    private static String[] resolveClientCredential(String clientId, String clientSecret, String authorization) {
        if (StringUtils.isNotBlank(clientId)) {
            return new String[]{clientId, clientSecret};
        }
        if (authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            try {
                String decoded = new String(Base64.getDecoder().decode(authorization.substring(6).trim()),
                        StandardCharsets.UTF_8);
                int index = decoded.indexOf(':');
                if (index >= 0) {
                    return new String[]{decoded.substring(0, index), decoded.substring(index + 1)};
                }
            } catch (IllegalArgumentException e) {
                // 非法 Basic：按未提供凭证处理，最终以 invalid_client 返回
                log.warn("Invalid basic authorization header");
            }
        }
        return new String[]{null, null};
    }

    private String resetUserAvatarUrl(TenantConfigDto tenantConfigDto, Long id, String originalAvatarUrl) {
        String siteUrl = tenantConfigDto.getSiteUrl();
        if (siteUrl.endsWith("/")) {
            siteUrl = siteUrl.substring(0, siteUrl.length() - 1);
        }
        return siteUrl + "/api/user/avatar/" + id + (originalAvatarUrl != null ? "/" + MD5.MD5Encode(originalAvatarUrl) : "");
    }
}
