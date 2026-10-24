package com.xspaceagi.system.application.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.system.application.dto.*;
import com.xspaceagi.system.application.service.AuthIdpApplicationService;
import com.xspaceagi.system.application.service.AuthService;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.application.util.DefaultIconUrlUtil;
import com.xspaceagi.system.infra.dao.entity.AuthIdpConfig;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.infra.dao.entity.UserAuthIdentity;
import com.xspaceagi.system.infra.dao.service.AuthIdpConfigService;
import com.xspaceagi.system.infra.dao.service.UserAuthIdentityService;
import com.xspaceagi.system.infra.idp.*;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.AuthIdpTypeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.utils.RedisUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AuthIdpApplicationServiceImpl implements AuthIdpApplicationService {

    private static final String STATE_KEY_PREFIX = "auth:idp:state:";
    private static final String PENDING_KEY_PREFIX = "auth:idp:pending:";
    /** state 有效期：授权跳转往返足够 */
    private static final long STATE_TTL_SECONDS = 300;
    /** pending 有效期：留出「跳登录页→登录→回中间页」的往返时间 */
    private static final long PENDING_TTL_SECONDS = 1800;
    private static final String CALLBACK_PATH = "/api/auth/idp/callback";
    private static final String BIND_PAGE_PATH = "/auth/bind-or-register";
    /** token 的 clientId 前缀，可用于按登录方式批量失效 */
    private static final String TOKEN_CLIENT_PREFIX = "idp:";
    private static final String PKCE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Resource
    private AuthIdpConfigService authIdpConfigService;

    @Resource
    private UserAuthIdentityService userAuthIdentityService;

    @Resource
    private AuthService authService;

    @Resource
    private UserApplicationService userApplicationService;

    @Resource
    private RedisUtil redisUtil;

    @Resource
    private IdpBrandIcon idpBrandIcon;

    private final Map<AuthIdpTypeEnum, IdentityProviderClient> clients = new EnumMap<>(AuthIdpTypeEnum.class);

    public AuthIdpApplicationServiceImpl(List<IdentityProviderClient> clientList) {
        for (IdentityProviderClient client : clientList) {
            clients.put(client.type(), client);
        }
    }

    @Override
    public AuthIdpLoginListVo listForLogin() {
        List<AuthIdpLoginItemVo> items = authIdpConfigService.listEnabled().stream().map(config -> {
            AuthIdpLoginItemVo item = new AuthIdpLoginItemVo();
            item.setId(config.getId());
            item.setType(config.getType());
            item.setName(config.getName());
            item.setIcon(resolveIcon(config));
            if (AuthIdpTypeEnum.WECHAT.getCode().equals(config.getType())) {
                item.setWechatMode(IdpConfigParser.parseWeChat(config).getMode());
            }
            return item;
        }).collect(Collectors.toList());
        AuthIdpConfig autoRedirect = findAutoRedirectConfig();
        return AuthIdpLoginListVo.builder()
                .items(items)
                .autoRedirectIdpId(autoRedirect == null ? null : autoRedirect.getId())
                .build();
    }

    @Override
    public String buildAuthorizeUrl(Long providerId, String redirect, boolean bindMode) {
        Long tenantId = currentTenantId();
        AuthIdpConfig config = loadEnabled(providerId);
        // 回调地址基于租户站点地址拼装，未配置则整条授权流程无法闭环，直接报配置错误
        if (StringUtils.isBlank(siteBase())) {
            log.error("site url not configured, cannot start idp authorize, tenantId={}", tenantId);
            throw new BizException("0001", "站点访问地址未配置（siteUrl），无法发起外部登录");
        }
        String target = validateRedirect(redirect);

        String state = randomToken();
        String codeVerifier = needPkceVerifier(config) ? randomPkceVerifier() : null;
        AuthIdpStateData data = new AuthIdpStateData();
        data.setTenantId(tenantId);
        data.setProviderId(providerId);
        data.setMode(bindMode ? "bind" : "login");
        data.setRedirect(target);
        data.setCodeVerifier(codeVerifier);
        if (bindMode) {
            UserDto user = currentUser();
            if (user == null) {
                throw new BizException("4010", "请先登录后再绑定外部账号");
            }
            data.setUserId(user.getId());
        }
        redisUtil.set(STATE_KEY_PREFIX + state, JSON.toJSONString(data), STATE_TTL_SECONDS);
        return requireClient(config).buildAuthorizeUrl(config, callbackUrl(providerId), state, codeVerifier);
    }

    @Override
    public AuthIdpCallbackResult handleCallback(Long providerId, String code, String ticket, String state) {
        Long tenantId = currentTenantId();
        AuthIdpConfig config = loadEnabled(providerId);
        if (StringUtils.isBlank(state)) {
            throw new BizException("0001", "回调缺少 state 参数");
        }
        Object stateRaw = redisUtil.getAndDelete(STATE_KEY_PREFIX + state);
        if (stateRaw == null) {
            throw new BizException("0001", "登录状态已过期或已被使用，请重新发起登录");
        }
        AuthIdpStateData data = JSON.parseObject(stateRaw.toString(), AuthIdpStateData.class);
        // state 与当前站点/登录方式强绑定，防跨租户重放
        if (!Objects.equals(data.getTenantId(), tenantId)) {
            log.warn("idp state tenant mismatch, stateTenant={}, currentTenant={}, providerId={}",
                    data.getTenantId(), tenantId, providerId);
            throw new BizException("0001", "登录状态与当前站点不匹配");
        }
        if (!Objects.equals(data.getProviderId(), providerId)) {
            throw new BizException("0001", "登录状态与登录方式不匹配");
        }
        String credential = StringUtils.defaultIfBlank(code, ticket);
        if (StringUtils.isBlank(credential)) {
            throw new BizException("0001", "未收到有效的授权凭证");
        }

        ExternalIdentity identity = requireClient(config).exchange(config, IdpExchangeContext.builder()
                .redirectUri(callbackUrl(providerId))
                .credential(credential)
                .state(state)
                .codeVerifier(data.getCodeVerifier())
                .build());

        if ("bind".equals(data.getMode())) {
            Long userId = data.getUserId();
            UserDto current = currentUser();
            // 回跳时必须仍是发起绑定的登录用户本人（state 记录的 userId 与当前会话一致）：
            // 否则攻击者可用自己账号发起 bind、诱导受害者完成 IdP 授权，把受害者的外部身份绑到自己账号上
            if (userId == null || current == null || !Objects.equals(userId, current.getId())) {
                throw new BizException("4010", "绑定会话已失效，请重新发起绑定");
            }
            upsertIdentity(config, userId, identity);
            log.info("idp bind success, providerId={}, userId={}", providerId, userId);
            return AuthIdpCallbackResult.builder()
                    .resultType(AuthIdpCallbackResult.ResultType.BIND)
                    .target(data.getRedirect())
                    .build();
        }

        // 身份查找链：精确绑定 → unionid 跨应用同人 → 未命中进中间页
        UserAuthIdentity bound = userAuthIdentityService.getByIdpExternal(providerId, identity.getExternalId());
        if (bound == null && StringUtils.isNotBlank(identity.getUnionId())) {
            UserAuthIdentity samePerson = userAuthIdentityService.getFirstByTenantUnion(identity.getUnionId());
            if (samePerson != null) {
                bound = createIdentity(config, samePerson.getUserId(), identity);
                log.info("idp unionid matched existing user, userId={}, providerId={}", samePerson.getUserId(), providerId);
            }
        }
        if (bound != null) {
            UserDto user = userApplicationService.queryById(bound.getUserId());
            if (user == null || user.getStatus() == User.Status.Deleted) {
                // 悬挂绑定：指向的用户已被删除。清理绑定后按未绑定继续
                // （自动注册/中间页），让同一外部身份可重新建立账号，而不是被永久锁在"账号已被禁用"
                userAuthIdentityService.removeById(bound.getId());
                log.warn("idp binding points to deleted user, removed and continue as unbound, providerId={}, userId={}, identityId={}",
                        providerId, bound.getUserId(), bound.getId());
                bound = null;
            } else if (user.getStatus() == User.Status.Disabled) {
                // 管理员主动封禁：保持拦截
                throw new BizException("0001", "账号已被禁用");
            } else {
                updateLastLoginTime(user);
            updateIdentityLogin(bound, identity);
            String token = authService.createToken(user, TOKEN_CLIENT_PREFIX + providerId);
            log.info("idp login success, providerId={}, userId={}", providerId, user.getId());
            return AuthIdpCallbackResult.builder()
                    .resultType(AuthIdpCallbackResult.ResultType.LOGIN)
                    .token(token)
                    .target(data.getRedirect())
                    .build();
            }
        }

        // 自动注册/绑定模式：
        // 邮箱/手机号命中已有账号 → 直接绑定该账号登录；未命中 → 自动建号并绑定。
        // 开启即等于信任该 IdP 返回的邮箱/手机号（建议仅对机构 IdP：CAS/飞书/企微/钉钉开启）；
        // 租户关闭注册时自动建号不可用，回落中间页（用户仍可选绑定已有账号）
        if (config.getAutoRegisterBind() != null && config.getAutoRegisterBind() == 1) {
            UserDto matched = matchRegisteredUser(identity);
            if (matched != null) {
                if (matched.getStatus() == User.Status.Disabled) {
                    // 管理员主动封禁：保持拦截（Deleted 用户已在匹配阶段跳过，视为未注册）
                    throw new BizException("0001", "账号已被禁用");
                }
                createIdentity(config, matched.getId(), identity);
                updateLastLoginTime(matched);
                String token = authService.createToken(matched, TOKEN_CLIENT_PREFIX + providerId);
                log.info("idp auto bind by email/phone, providerId={}, userId={}", providerId, matched.getId());
                return AuthIdpCallbackResult.builder()
                        .resultType(AuthIdpCallbackResult.ResultType.LOGIN)
                        .token(token)
                        .target(data.getRedirect())
                        .build();
            }
            TenantConfigDto tenantConfig = (TenantConfigDto) RequestContext.get().getTenantConfig();
            boolean registerClosed = tenantConfig != null && tenantConfig.getOpenRegister() != null
                    && tenantConfig.getOpenRegister() == 0;
            if (!registerClosed) {
                String token = registerAndLogin(config, identity);
                return AuthIdpCallbackResult.builder()
                        .resultType(AuthIdpCallbackResult.ResultType.LOGIN)
                        .token(token)
                        .target(data.getRedirect())
                        .build();
            }
            log.info("idp auto register skipped (registration closed), fallback to bind-or-register page, providerId={}", providerId);
        }

        String pendingToken = randomToken();
        AuthIdpPendingData pending = new AuthIdpPendingData();
        pending.setTenantId(tenantId);
        pending.setProviderId(providerId);
        pending.setProviderType(config.getType());
        pending.setProviderName(config.getName());
        // 管理端配置图标优先，未配置时取内置品牌图标
        pending.setIcon(idpBrandIcon.resolve(config));
        pending.setExternalId(identity.getExternalId());
        pending.setUnionId(identity.getUnionId());
        pending.setUserName(identity.getUserName());
        pending.setNickName(identity.getNickName());
        pending.setAvatar(identity.getAvatar());
        pending.setEmail(identity.getEmail());
        pending.setPhone(identity.getPhone());
        pending.setRedirect(data.getRedirect());
        redisUtil.set(PENDING_KEY_PREFIX + pendingToken, JSON.toJSONString(pending), PENDING_TTL_SECONDS);
        log.info("idp identity not bound, enter bind-or-register page, providerId={}, providerType={}",
                providerId, config.getType());
        return AuthIdpCallbackResult.builder()
                .resultType(AuthIdpCallbackResult.ResultType.PENDING)
                .target(BIND_PAGE_PATH + "?token=" + urlEnc(pendingToken))
                .build();
    }

    @Override
    public AuthIdpPendingVo getPendingInfo(String pendingToken) {
        AuthIdpPendingData pending = readPending(pendingToken);
        AuthIdpPendingVo vo = new AuthIdpPendingVo();
        vo.setProviderName(pending.getProviderName());
        vo.setIcon(pending.getIcon());
        // 信息透明展示：只暴露是否包含，不回传邮箱/手机号值
        vo.setHasEmail(StringUtils.isNotBlank(pending.getEmail()));
        vo.setHasPhone(StringUtils.isNotBlank(pending.getPhone()));
        vo.setProviderType(pending.getProviderType());
        vo.setUserName(StringUtils.defaultIfBlank(pending.getNickName(), pending.getUserName()));
        vo.setAvatar(pending.getAvatar());
        vo.setRedirect(pending.getRedirect());
        return vo;
    }

    @Override
    public AuthIdpLoginResult registerPending(String pendingToken) {
        // 先校验后消费：校验不通过（注册关闭/配置失效/邮箱手机号被占用）时 token 保留，
        // 用户可直接改点「绑定已有账号」；全部通过后 GETDEL 原子消费，防并发双注册
        AuthIdpPendingData pending = readPending(pendingToken, false);
        TenantConfigDto tenantConfig = (TenantConfigDto) RequestContext.get().getTenantConfig();
        if (tenantConfig != null && tenantConfig.getOpenRegister() != null && tenantConfig.getOpenRegister() == 0) {
            throw new BizException("0001", "当前站点已关闭注册，请选择绑定已有账号");
        }
        AuthIdpConfig config = authIdpConfigService.getById(pending.getProviderId());
        if (config == null || config.getEnabled() == null || config.getEnabled() != 1) {
            throw new BizException("0001", "登录方式不存在或已停用");
        }
        // 邮箱/手机号已被占用不静默丢弃：注册者大概率已有平台账号（同人在先注册过），
        // 明确报错引导走「绑定已有账号」，避免同一人建出两个账号
        if (StringUtils.isNotBlank(pending.getEmail())
                && userApplicationService.queryUserByEmail(pending.getEmail()) != null) {
            throw new BizException("0001", "该邮箱已关联平台账号：若是您的账号，请返回选择「绑定已有账号」");
        }
        if (StringUtils.isNotBlank(pending.getPhone())
                && userApplicationService.queryUserByPhone(pending.getPhone()) != null) {
            throw new BizException("0001", "该手机号已关联平台账号：若是您的账号，请返回选择「绑定已有账号」");
        }
        if (redisUtil.getAndDelete(PENDING_KEY_PREFIX + pendingToken) == null) {
            // 已被并发请求消费
            throw new BizException("0001", "页面已过期，请重新发起登录");
        }

        String token = registerAndLogin(config, toIdentity(pending));
        return AuthIdpLoginResult.builder().token(token).redirect(pending.getRedirect()).build();
    }

    /**
     * 按邮箱→手机号顺序匹配租户内已有账号（自动注册/绑定模式用；邮箱优先，命中即止）。
     * 已删除（Deleted）用户视为未注册跳过——其邮箱/手机号可能是被删前遗留，命中只会造成
     * "账号已被禁用"死锁；Disabled 用户返回，由调用方拦截
     */
    private UserDto matchRegisteredUser(ExternalIdentity identity) {
        if (StringUtils.isNotBlank(identity.getEmail())) {
            UserDto byEmail = userApplicationService.queryUserByEmail(identity.getEmail());
            if (byEmail != null && byEmail.getStatus() != User.Status.Deleted) {
                return byEmail;
            }
        }
        if (StringUtils.isNotBlank(identity.getPhone())) {
            UserDto byPhone = userApplicationService.queryUserByPhone(identity.getPhone());
            if (byPhone != null && byPhone.getStatus() != User.Status.Deleted) {
                return byPhone;
            }
        }
        return null;
    }

    /**
     * 用外部身份自动建号并登录，返回平台 token。调用方保证：注册开放、邮箱/手机号未被占用
     * （中间页 registerPending 前置校验过；自动模式在 matchRegisteredUser 未命中后调用，仅剩极小并发窗口）
     */
    private String registerAndLogin(AuthIdpConfig config, ExternalIdentity identity) {
        UserDto user = new UserDto();
        user.setNickName(sanitizeNickName(identity.getNickName()));
        user.setAvatar(identity.getAvatar());
        // 外部登录名（CAS 映射的工号/uid、GitHub login、企微 userid、自定义 preferred_username 等）
        // 规整通过且租户内未被占用时，直接作为平台登录名；否则留空由平台自动生成
        String externalLoginName = sanitizeLoginName(identity.getUserName());
        if (StringUtils.isNotBlank(externalLoginName)
                && userApplicationService.queryUserByUserName(externalLoginName) == null) {
            user.setUserName(externalLoginName);
        }
        user.setEmail(StringUtils.trimToNull(identity.getEmail()));
        user.setPhone(StringUtils.trimToNull(identity.getPhone()));
        userApplicationService.add(user);

        createIdentity(config, user.getId(), identity);
        updateLastLoginTime(user);
        // 建号审计：中间页注册与自动注册两条路径统一在此记录新用户
        log.info("idp register success, providerId={}, newUserId={}, userName={}",
                config.getId(), user.getId(), user.getUserName());
        return authService.createToken(user, TOKEN_CLIENT_PREFIX + config.getId());
    }

    @Override
    public String bindPending(String pendingToken) {
        UserDto user = requireCurrentUser();
        AuthIdpPendingData pending = readPending(pendingToken);
        AuthIdpConfig config = authIdpConfigService.getById(pending.getProviderId());
        if (config == null) {
            throw new BizException("0001", "登录方式不存在或已删除");
        }
        upsertIdentity(config, user.getId(), toIdentity(pending));
        redisUtil.expire(PENDING_KEY_PREFIX + pendingToken, 0);
        log.info("idp bind-pending success, providerId={}, userId={}", pending.getProviderId(), user.getId());
        return pending.getRedirect();
    }

    // ==================== 身份绑定落库 ====================

    private UserAuthIdentity upsertIdentity(AuthIdpConfig config, Long userId, ExternalIdentity identity) {
        UserAuthIdentity existing = userAuthIdentityService.getByIdpExternal(config.getId(), identity.getExternalId());
        if (existing != null) {
            if (!Objects.equals(existing.getUserId(), userId)) {
                throw new BizException("0001", "该外部账号已绑定其他用户");
            }
            updateIdentityLogin(existing, identity);
            return existing;
        }
        return createIdentity(config, userId, identity);
    }

    private UserAuthIdentity createIdentity(AuthIdpConfig config, Long userId, ExternalIdentity identity) {
        UserAuthIdentity row = new UserAuthIdentity();
        row.setTenantId(config.getTenantId());
        row.setUserId(userId);
        row.setIdpId(config.getId());
        row.setIdpType(config.getType());
        row.setExternalId(truncate(identity.getExternalId(), 128));
        row.setUnionId(truncate(identity.getUnionId(), 128));
        row.setExternalUserName(truncate(
                StringUtils.defaultIfBlank(identity.getNickName(), identity.getUserName()), 128));
        row.setRawProfile(buildRawProfile(identity));
        row.setLastLoginTime(new Date());
        userAuthIdentityService.save(row);
        return row;
    }

    private void updateIdentityLogin(UserAuthIdentity identity, ExternalIdentity external) {
        UserAuthIdentity update = new UserAuthIdentity();
        update.setId(identity.getId());
        update.setExternalUserName(truncate(
                StringUtils.defaultIfBlank(external.getNickName(), external.getUserName()), 128));
        update.setRawProfile(buildRawProfile(external));
        update.setLastLoginTime(new Date());
        userAuthIdentityService.updateById(update);
    }

    /** 资料快照：只存展示性字段，不存邮箱/手机号等联系方式 */
    private static String buildRawProfile(ExternalIdentity identity) {
        if (identity == null) {
            return null;
        }
        JSONObject profile = new JSONObject();
        profile.put("userName", identity.getUserName());
        profile.put("nickName", identity.getNickName());
        profile.put("avatar", identity.getAvatar());
        return profile.toJSONString();
    }

    private static ExternalIdentity toIdentity(AuthIdpPendingData pending) {
        ExternalIdentity identity = new ExternalIdentity();
        identity.setExternalId(pending.getExternalId());
        identity.setUnionId(pending.getUnionId());
        identity.setUserName(pending.getUserName());
        identity.setNickName(pending.getNickName());
        identity.setAvatar(pending.getAvatar());
        identity.setEmail(pending.getEmail());
        identity.setPhone(pending.getPhone());
        return identity;
    }

    // ==================== 公共辅助 ====================

    /** 未登录自动跳转目标决策：配置了 autoRedirect 的启用登录方式时给 authorize 地址，否则给默认登录页 */
    @Override
    public String resolveUnauthRedirectUrl(String defaultLoginUrl, String originalTarget) {
        try {
            if (StringUtils.contains(originalTarget, "local=1")) {
                // 逃生门：用户主动要求账号密码登录
                return defaultLoginUrl;
            }
            String authorizeUrl = buildAutoAuthorizeUrl(originalTarget);
            return authorizeUrl == null ? defaultLoginUrl : authorizeUrl;
        } catch (Exception e) {
            // 决策失败不影响未登录主流程，回落登录页
            log.warn("resolve unauth redirect failed, fallback to login page: {}", e.getMessage());
            return defaultLoginUrl;
        }
    }

    @Override
    public String buildAutoAuthorizeUrl(String redirect) {
        AuthIdpConfig auto = findAutoRedirectConfig();
        if (auto == null) {
            return null;
        }
        return "/api/auth/idp/authorize?provider=" + auto.getId()
                + "&redirect=" + urlEnc(StringUtils.defaultIfBlank(redirect, "/"));
    }

    /** 当前会话的 IdP 联动登出地址：仅 CAS 型登录会话返回，其余返回 null（调用方只登出平台） */
    @Override
    public String buildSessionIdpLogoutUrl() {
        UserDto user = currentUser();
        if (user == null) {
            return null;
        }
        String token = RequestContext.get().getToken();
        String clientId = authService.getClientId(user.getId(), token);
        // 非外部登录建立的会话（密码/验证码等）没有 IdP 可联动登出
        if (clientId == null || !clientId.startsWith(TOKEN_CLIENT_PREFIX)) {
            return null;
        }
        long providerId;
        try {
            providerId = Long.parseLong(clientId.substring(TOKEN_CLIENT_PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
        AuthIdpConfig config = authIdpConfigService.getById(providerId);
        if (config == null || !AuthIdpTypeEnum.CAS.getCode().equals(config.getType())) {
            // 微信/OAuth2 无标准登出端点（微信无、部分 OIDC 有 end_session，需要时再扩展）
            return null;
        }
        String serverUrl = IdpConfigParser.parseCas(config).getServerUrl();
        // 登出后的落点带 local=1：避免「未登录自动跳转」把刚登出的用户又送回 IdP 登录页
        return serverUrl + "/cas/logout?service=" + urlEnc(siteBase() + "/login?local=1");
    }

    /**
     * 图标三级兜底：管理端配置 icon → 内置品牌图标（S3）→ 平台字母图标服务（/api/logo，匿名可访问）
     */
    private String resolveIcon(AuthIdpConfig config) {
        String icon = idpBrandIcon.resolve(config);
        if (StringUtils.isNotBlank(icon)) {
            return icon;
        }
        try {
            return DefaultIconUrlUtil.setDefaultIconUrl(null, config.getName());
        } catch (Exception e) {
            // 兜底失败（如 siteUrl 未配置）不影响列表
            return null;
        }
    }

    private AuthIdpConfig findAutoRedirectConfig() {
        return authIdpConfigService.lambdaQuery()
                .eq(AuthIdpConfig::getEnabled, 1)
                .eq(AuthIdpConfig::getAutoRedirect, 1)
                .last("limit 1")
                .one();
    }

    private AuthIdpConfig loadEnabled(Long providerId) {
        if (providerId == null) {
            throw new BizException("4000", "缺少登录方式参数");
        }
        AuthIdpConfig config = authIdpConfigService.getById(providerId);
        if (config == null || config.getEnabled() == null || config.getEnabled() != 1) {
            throw new BizException("0001", "登录方式不存在或已停用");
        }
        return config;
    }

    private IdentityProviderClient requireClient(AuthIdpConfig config) {
        AuthIdpTypeEnum type = AuthIdpTypeEnum.of(config.getType());
        IdentityProviderClient client = type == null ? null : clients.get(type);
        if (client == null) {
            throw new BizException("0001", "不支持的登录方式类型: " + config.getType());
        }
        return client;
    }

    private AuthIdpPendingData readPending(String pendingToken) {
        return readPending(pendingToken, false);
    }

    private AuthIdpPendingData readPending(String pendingToken, boolean consume) {
        if (StringUtils.isBlank(pendingToken)) {
            throw new BizException("4000", "缺少 token 参数");
        }
        Object raw = consume ? redisUtil.getAndDelete(PENDING_KEY_PREFIX + pendingToken)
                : redisUtil.get(PENDING_KEY_PREFIX + pendingToken);
        if (raw == null) {
            throw new BizException("0001", "页面已过期，请重新发起登录");
        }
        AuthIdpPendingData pending = JSON.parseObject(raw.toString(), AuthIdpPendingData.class);
        if (!Objects.equals(pending.getTenantId(), currentTenantId())) {
            throw new BizException("0001", "页面已过期，请重新发起登录");
        }
        return pending;
    }

    /** 仅自定义 OAuth2 且启用 PKCE 时需要 code_verifier */
    private static boolean needPkceVerifier(AuthIdpConfig config) {
        if (!AuthIdpTypeEnum.OAUTH2.getCode().equals(config.getType())) {
            return false;
        }
        OAuth2IdpConfig oauth2 = IdpConfigParser.parseOAuth2(config);
        return Boolean.TRUE.equals(oauth2.getUsePkce());
    }

    /**
     * 回跳目标校验：本站相对路径（/ 开头且非 //、/\）或 host 与租户站点一致的绝对地址，其余一律回退 "/"
     */
    private String validateRedirect(String redirect) {
        if (StringUtils.isBlank(redirect)) {
            return "/";
        }
        String trimmed = redirect.trim();
        if (trimmed.startsWith("/") && !trimmed.startsWith("//") && !trimmed.startsWith("/\\")) {
            return trimmed;
        }
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            try {
                String host = new URL(trimmed).getHost();
                String siteBase = siteBase();
                if (StringUtils.isNotBlank(siteBase) && StringUtils.equalsIgnoreCase(host, new URL(siteBase).getHost())) {
                    return trimmed;
                }
            } catch (MalformedURLException ignored) {
                // fall through
            }
        }
        return "/";
    }

    /**
     * 租户站点基准地址：优先取配置原值（AuthInterceptor 会按 Referer 改写 siteUrl，
     * IdP 回跳请求的 Referer 是 IdP 域名，取 siteConfigUrl 才是本站地址）
     */
    private String siteBase() {
        TenantConfigDto tenantConfig = (TenantConfigDto) RequestContext.get().getTenantConfig();
        if (tenantConfig == null) {
            return "";
        }
        String base = StringUtils.defaultIfBlank(tenantConfig.getSiteConfigUrl(), tenantConfig.getSiteUrl());
        return StringUtils.removeEnd(StringUtils.trimToEmpty(base), "/");
    }

    /** IdP 侧登记的回调地址：{租户站点}/api/auth/idp/callback?provider={id} */
    private String callbackUrl(Long providerId) {
        return siteBase() + CALLBACK_PATH + "?provider=" + providerId;
    }

    private void updateLastLoginTime(UserDto user) {
        UserDto update = new UserDto();
        update.setId(user.getId());
        update.setLastLoginTime(new Date());
        if (StringUtils.isNotBlank(RequestContext.get().getLang())) {
            update.setLang(RequestContext.get().getLang());
        }
        userApplicationService.update(update);
    }

    private static String sanitizeNickName(String nickName) {
        String trimmed = StringUtils.trimToEmpty(nickName);
        // 平台约束昵称不能为纯数字
        if (StringUtils.isBlank(trimmed) || trimmed.matches("^[0-9]+$")) {
            return null;
        }
        return truncate(trimmed, 64);
    }

    /**
     * 外部登录名规整：
     * 不能纯数字、不能是系统生成格式 user+10位数字、长度≤64；不满足返回 null（建号时自动生成登录名）
     */
    private static String sanitizeLoginName(String userName) {
        String trimmed = StringUtils.trimToEmpty(userName);
        if (StringUtils.isBlank(trimmed) || trimmed.matches("^[0-9]+$") || trimmed.matches("^user[0-9]{10}$")) {
            return null;
        }
        return truncate(trimmed, 64);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String randomToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String randomPkceVerifier() {
        StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i < 64; i++) {
            sb.append(PKCE_ALPHABET.charAt(SECURE_RANDOM.nextInt(PKCE_ALPHABET.length())));
        }
        return sb.toString();
    }

    private static String urlEnc(String value) {
        return URLEncoder.encode(StringUtils.defaultString(value), StandardCharsets.UTF_8);
    }

    private static Long currentTenantId() {
        return RequestContext.get().getTenantId();
    }

    private static UserDto currentUser() {
        Object user = RequestContext.get().getUser();
        return user instanceof UserDto userDto ? userDto : null;
    }

    private static UserDto requireCurrentUser() {
        UserDto user = currentUser();
        if (user == null) {
            throw new BizException("4010", "请先登录");
        }
        return user;
    }
}
