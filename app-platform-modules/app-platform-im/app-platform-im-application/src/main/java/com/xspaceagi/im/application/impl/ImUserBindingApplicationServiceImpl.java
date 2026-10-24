package com.xspaceagi.im.application.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xspaceagi.im.application.ImUserBindingApplicationService;
import com.xspaceagi.im.application.dto.ImBindStartResult;
import com.xspaceagi.im.application.dto.ImBindStatusResult;
import com.xspaceagi.im.application.dto.ImChannelConfigDto;
import com.xspaceagi.im.application.dto.ImNotifyChannel;
import com.xspaceagi.im.application.dto.ImUserBindingDto;
import com.xspaceagi.im.application.dto.ImUserBindingsView;
import com.xspaceagi.im.application.support.ImBindProperties;
import com.xspaceagi.im.application.support.ImBindSessionKeys;
import com.xspaceagi.im.application.support.ImNotifyChannelConfigResolver;
import com.xspaceagi.im.infra.dao.enitity.ImUserBinding;
import com.xspaceagi.im.infra.dao.mapper.ImUserBindingMapper;
import com.xspaceagi.im.infra.enums.ImChannelEnum;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import com.xspaceagi.system.spec.utils.RedisUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@Service
public class ImUserBindingApplicationServiceImpl implements ImUserBindingApplicationService {

    @Resource
    private ImUserBindingMapper imUserBindingMapper;
    @Resource
    private RedisUtil redisUtil;
    @Resource
    private ImNotifyChannelConfigResolver channelConfigResolver;
    @Resource
    private ImBindProperties imBindProperties;

    @Override
    public ImBindStartResult startBind(String channelRaw, Long channelConfigId, String appReturnUrl) {
        String channel = ImNotifyChannel.normalize(channelRaw);
        Long userId = RequestContext.get().getUserId();
        Long tenantId = RequestContext.get().getTenantId();
        if (userId == null || tenantId == null) {
            throw BizException.of(ErrorCodeEnum.UNAUTHORIZED, BizExceptionCodeEnum.userNotLoggedIn);
        }
        String returnUrl = resolveAppReturnUrl(appReturnUrl);

        ImChannelConfigDto config = channelConfigResolver.resolve(channel, channelConfigId, userId);
        String bindId = UUID.randomUUID().toString().replace("-", "");
        JSONObject session = new JSONObject();
        session.put("status", "pending");
        session.put("channel", channel);
        session.put("userId", userId);
        session.put("tenantId", tenantId);
        session.put("channelConfigId", config.getId());
        session.put("platformUserLabel", resolvePlatformUserLabel());
        session.put("appReturnUrl", returnUrl);
        redisUtil.set(ImBindSessionKeys.key(bindId), session.toJSONString(), ImBindSessionKeys.TTL_SECONDS);

        String authorizeUrl = buildAuthorizeUrl(channel, config, bindId);
        return ImBindStartResult.builder()
                .bindId(bindId)
                .authorizeUrl(authorizeUrl)
                .channel(ImNotifyChannel.toExternal(channel))
                .openHint(openHintFor(channel))
                .build();
    }

    @Override
    public ImBindStatusResult bindStatus(String bindId) {
        if (StringUtils.isBlank(bindId)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "bindId");
        }
        Object raw = redisUtil.get(ImBindSessionKeys.key(bindId));
        if (raw == null) {
            return ImBindStatusResult.builder().status("expired").message("bind session expired").build();
        }
        JSONObject session = JSON.parseObject(String.valueOf(raw));
        Long sessionUserId = session.getLong("userId");
        Long currentUserId = RequestContext.get() != null ? RequestContext.get().getUserId() : null;
        if (sessionUserId == null || currentUserId == null || !sessionUserId.equals(currentUserId)) {
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
        }
        String status = session.getString("status");
        // 会话主人可凭 status 拿到 confirmToken，以便 Universal Link 失败时轮询后调 bind/confirm
        String confirmToken = "authorized".equals(status) ? session.getString("confirmToken") : null;
        return ImBindStatusResult.builder()
                .status(status)
                .channel(ImNotifyChannel.toExternal(session.getString("channel")))
                .displayName(session.getString("displayName"))
                .message(session.getString("message"))
                .confirmToken(confirmToken)
                .build();
    }

    @Override
    public ImUserBindingDto getBinding(Long userId, String channelRaw) {
        String channel = ImNotifyChannel.normalize(channelRaw);
        Long tenantId = RequestContext.get().getTenantId();
        ImUserBinding row = find(tenantId, userId, channel);
        if (row == null) {
            return ImUserBindingDto.builder()
                    .channel(ImNotifyChannel.toExternal(channel))
                    .userId(userId)
                    .bound(false)
                    .build();
        }
        // 不对外暴露 imUserId
        return ImUserBindingDto.builder()
                .channel(ImNotifyChannel.toExternal(channel))
                .userId(row.getUserId())
                .displayName(row.getDisplayName())
                .channelConfigId(row.getChannelConfigId())
                .source(row.getSource())
                .bound(true)
                .build();
    }

    @Override
    public ImUserBindingsView listBindings(Long userId, String channelRaw) {
        List<ImUserBindingDto> list = new ArrayList<>(3);
        if (StringUtils.isNotBlank(channelRaw)) {
            list.add(getBinding(userId, channelRaw));
            return ImUserBindingsView.builder().bindings(list).build();
        }
        list.add(getBinding(userId, ImChannelEnum.FEISHU.getCode()));
        list.add(getBinding(userId, ImChannelEnum.WEWORK.getCode()));
        list.add(getBinding(userId, ImChannelEnum.DINGTALK.getCode()));
        return ImUserBindingsView.builder().bindings(list).build();
    }

    @Override
    public void unbind(Long userId, String channelRaw) {
        String channel = ImNotifyChannel.normalize(channelRaw);
        Long tenantId = RequestContext.get().getTenantId();
        ImUserBinding row = find(tenantId, userId, channel);
        if (row != null) {
            imUserBindingMapper.deleteById(row.getId());
        }
    }

    @Override
    public void upsertBinding(ImUserBinding binding) {
        TenantFunctions.runWithIgnoreCheck(() -> {
            ImUserBinding existing = findIgnoreTenant(binding.getTenantId(), binding.getUserId(), binding.getChannel());
            Date now = new Date();
            if (existing == null) {
                binding.setCreated(now);
                binding.setModified(now);
                imUserBindingMapper.insert(binding);
                return;
            }
            existing.setImUserId(binding.getImUserId());
            existing.setDisplayName(binding.getDisplayName());
            existing.setChannelConfigId(binding.getChannelConfigId());
            existing.setSource(binding.getSource());
            existing.setModified(now);
            imUserBindingMapper.updateById(existing);
        });
    }

    @Override
    public ImUserBinding requireBinding(Long tenantId, Long userId, String channel) {
        ImUserBinding row = TenantFunctions.callWithIgnoreCheck(() -> findIgnoreTenant(tenantId, userId, channel));
        if (row == null || StringUtils.isBlank(row.getImUserId())) {
            throw BizException.of(BizExceptionCodeEnum.imNotifyUserNotBound);
        }
        return row;
    }

    @Override
    public String stashOauthPendingConsent(String bindId, String imUserId, String displayName) {
        JSONObject session = requireSession(bindId);
        String status = session.getString("status");
        if ("bound".equals(status) || "authorized".equals(status)) {
            throw BizException.of(BizExceptionCodeEnum.imNotifyBindAlreadyCompleted);
        }
        Long tenantId = session.getLong("tenantId");
        Long userId = session.getLong("userId");
        String channel = session.getString("channel");
        assertImIdentityAvailable(tenantId, channel, imUserId, userId);

        String consentToken = UUID.randomUUID().toString().replace("-", "");
        session.put("status", "awaiting_consent");
        session.put("imUserId", imUserId);
        session.put("displayName", displayName);
        session.put("consentToken", consentToken);
        session.remove("confirmToken");
        session.put("message", "awaiting IM user consent");
        redisUtil.set(ImBindSessionKeys.key(bindId), session.toJSONString(), ImBindSessionKeys.TTL_SECONDS);
        log.info("IM OAuth pending consent bindId={} channel={} userId={} imUserId={}",
                bindId, channel, userId, imUserId);
        return consentToken;
    }

    @Override
    public boolean acceptOauthConsent(String bindId, String consentToken) {
        if (StringUtils.isBlank(consentToken)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "consentToken");
        }
        JSONObject session = requireSession(bindId);
        String status = session.getString("status");
        if ("bound".equals(status)) {
            throw BizException.of(BizExceptionCodeEnum.imNotifyBindAlreadyCompleted);
        }
        if ("authorized".equals(status) && StringUtils.isNotBlank(session.getString("confirmToken"))) {
            // 幂等：已同意。consentToken 已销毁，不可再凭任意 token 套出 confirmToken 重定向。
            return false;
        }
        if (!"awaiting_consent".equals(status)) {
            throw BizException.of(BizExceptionCodeEnum.imNotifyBindConfirmRequired);
        }
        if (!constantTimeEquals(consentToken, session.getString("consentToken"))) {
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
        }
        String imUserId = session.getString("imUserId");
        Long tenantId = session.getLong("tenantId");
        Long userId = session.getLong("userId");
        String channel = session.getString("channel");
        if (StringUtils.isBlank(imUserId) || tenantId == null || userId == null) {
            throw BizException.of(BizExceptionCodeEnum.imNotifyOauthFailed, "missing im user id");
        }
        assertImIdentityAvailable(tenantId, channel, imUserId, userId);

        String confirmToken = UUID.randomUUID().toString().replace("-", "");
        session.put("status", "authorized");
        session.put("confirmToken", confirmToken);
        session.remove("consentToken");
        session.put("message", "awaiting app confirm");
        redisUtil.set(ImBindSessionKeys.key(bindId), session.toJSONString(), ImBindSessionKeys.TTL_SECONDS);
        log.info("IM OAuth consent accepted bindId={} channel={} userId={} imUserId={}",
                bindId, channel, userId, imUserId);
        return true;
    }

    @Override
    public void confirmOauthBind(String bindId, String confirmToken) {
        if (StringUtils.isBlank(confirmToken)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "confirmToken");
        }
        Long currentUserId = RequestContext.get() != null ? RequestContext.get().getUserId() : null;
        if (currentUserId == null) {
            throw BizException.of(ErrorCodeEnum.UNAUTHORIZED, BizExceptionCodeEnum.userNotLoggedIn);
        }
        JSONObject session = requireSession(bindId);
        Long sessionUserId = session.getLong("userId");
        if (sessionUserId == null || !sessionUserId.equals(currentUserId)) {
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
        }
        String status = session.getString("status");
        if ("bound".equals(status)) {
            return;
        }
        if (!"authorized".equals(status)) {
            throw BizException.of(BizExceptionCodeEnum.imNotifyBindConfirmRequired);
        }
        if (!constantTimeEquals(confirmToken, session.getString("confirmToken"))) {
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
        }
        String imUserId = session.getString("imUserId");
        String displayName = session.getString("displayName");
        String channel = session.getString("channel");
        Long tenantId = session.getLong("tenantId");
        Long channelConfigId = session.getLong("channelConfigId");
        if (StringUtils.isBlank(imUserId) || tenantId == null) {
            session.put("status", "failed");
            session.put("message", "missing im user id");
            redisUtil.set(ImBindSessionKeys.key(bindId), session.toJSONString(), ImBindSessionKeys.TTL_SECONDS);
            throw BizException.of(BizExceptionCodeEnum.imNotifyOauthFailed, "missing im user id");
        }
        assertImIdentityAvailable(tenantId, channel, imUserId, sessionUserId);

        upsertBinding(ImUserBinding.builder()
                .channel(channel)
                .userId(sessionUserId)
                .tenantId(tenantId)
                .imUserId(imUserId)
                .displayName(displayName)
                .channelConfigId(channelConfigId)
                .source("oauth_web")
                .build());

        session.put("status", "bound");
        session.put("message", "bound");
        session.remove("confirmToken");
        redisUtil.set(ImBindSessionKeys.key(bindId), session.toJSONString(), 120L);
        log.info("IM bind confirmed bindId={} channel={} userId={} imUserId={}", bindId, channel, sessionUserId, imUserId);
    }

    @Override
    public String getBindSessionRaw(String bindId) {
        if (StringUtils.isBlank(bindId)) {
            return null;
        }
        Object raw = redisUtil.get(ImBindSessionKeys.key(bindId));
        return raw == null ? null : String.valueOf(raw);
    }

    @Override
    public String buildAppReturnRedirect(String bindId) {
        JSONObject session = requireSession(bindId);
        String appReturnUrl = session.getString("appReturnUrl");
        String confirmToken = session.getString("confirmToken");
        if (StringUtils.isBlank(appReturnUrl) || StringUtils.isBlank(confirmToken)) {
            return null;
        }
        String channel = ImNotifyChannel.toExternal(session.getString("channel"));
        String sep = appReturnUrl.contains("?") ? "&" : "?";
        return appReturnUrl + sep
                + "bindId=" + urlEncode(bindId)
                + "&confirmToken=" + urlEncode(confirmToken)
                + "&channel=" + urlEncode(channel == null ? "" : channel)
                + "&status=authorized";
    }

    private JSONObject requireSession(String bindId) {
        Object raw = redisUtil.get(ImBindSessionKeys.key(bindId));
        if (raw == null) {
            throw BizException.of(BizExceptionCodeEnum.imNotifyBindSessionInvalid);
        }
        return JSON.parseObject(String.valueOf(raw));
    }

    private void assertImIdentityAvailable(Long tenantId, String channel, String imUserId, Long userId) {
        ImUserBinding occupied = TenantFunctions.callWithIgnoreCheck(
                () -> findByImUserIgnoreTenant(tenantId, channel, imUserId));
        if (occupied != null && occupied.getUserId() != null && !occupied.getUserId().equals(userId)) {
            throw BizException.of(BizExceptionCodeEnum.imNotifyImIdentityAlreadyBound);
        }
    }

    private ImUserBinding find(Long tenantId, Long userId, String channel) {
        return findIgnoreTenant(tenantId, userId, channel);
    }

    private ImUserBinding findIgnoreTenant(Long tenantId, Long userId, String channel) {
        if (tenantId == null || userId == null || StringUtils.isBlank(channel)) {
            return null;
        }
        return imUserBindingMapper.selectOne(new LambdaQueryWrapper<ImUserBinding>()
                .eq(ImUserBinding::getTenantId, tenantId)
                .eq(ImUserBinding::getUserId, userId)
                .eq(ImUserBinding::getChannel, channel)
                .last("limit 1"));
    }

    private ImUserBinding findByImUserIgnoreTenant(Long tenantId, String channel, String imUserId) {
        if (tenantId == null || StringUtils.isBlank(channel) || StringUtils.isBlank(imUserId)) {
            return null;
        }
        return imUserBindingMapper.selectOne(new LambdaQueryWrapper<ImUserBinding>()
                .eq(ImUserBinding::getTenantId, tenantId)
                .eq(ImUserBinding::getChannel, channel)
                .eq(ImUserBinding::getImUserId, imUserId)
                .last("limit 1"));
    }

    private String buildAuthorizeUrl(String channel, ImChannelConfigDto config, String bindId) {
        String redirectUri = resolveCallbackUrl(channel);
        if (ImChannelEnum.WEWORK.getCode().equals(channel)) {
            ImChannelConfigDto.WeworkAppConfig app = config.getWeworkApp();
            if (app == null || StringUtils.isBlank(app.getCorpId()) || StringUtils.isBlank(app.getAgentId())) {
                throw BizException.of(BizExceptionCodeEnum.imNotifyChannelNotConfigured);
            }
            return "https://open.weixin.qq.com/connect/oauth2/authorize"
                    + "?appid=" + urlEncode(app.getCorpId())
                    + "&redirect_uri=" + urlEncode(redirectUri)
                    + "&response_type=code"
                    + "&scope=snsapi_base"
                    + "&state=" + urlEncode(bindId)
                    + "&agentid=" + urlEncode(app.getAgentId())
                    + "#wechat_redirect";
        }
        if (ImChannelEnum.FEISHU.getCode().equals(channel)) {
            ImChannelConfigDto.FeishuConfig feishu = config.getFeishu();
            if (feishu == null || StringUtils.isBlank(feishu.getAppId())) {
                throw BizException.of(BizExceptionCodeEnum.imNotifyChannelNotConfigured);
            }
            return "https://accounts.feishu.cn/open-apis/authen/v1/authorize"
                    + "?client_id=" + urlEncode(feishu.getAppId())
                    + "&redirect_uri=" + urlEncode(redirectUri)
                    + "&state=" + urlEncode(bindId);
        }
        if (ImChannelEnum.DINGTALK.getCode().equals(channel)) {
            ImChannelConfigDto.DingtalkConfig ding = config.getDingtalk();
            if (ding == null || StringUtils.isBlank(ding.getClientId())) {
                throw BizException.of(BizExceptionCodeEnum.imNotifyChannelNotConfigured);
            }
            return "https://login.dingtalk.com/oauth2/auth"
                    + "?client_id=" + urlEncode(ding.getClientId())
                    + "&redirect_uri=" + urlEncode(redirectUri)
                    + "&response_type=code"
                    + "&scope=" + urlEncode("openid")
                    + "&state=" + urlEncode(bindId)
                    + "&prompt=consent";
        }
        throw BizException.of(BizExceptionCodeEnum.imNotifyChannelUnsupported, channel);
    }

    private static String openHintFor(String channel) {
        return "将离开本应用完成授权；请在确认页核对平台账号后再确认，随后会跳转回站点并打开 App。";
    }

    /**
     * 解析并校验 appReturnUrl：未传则用租户 siteUrl + returnPath（域名回跳）。
     */
    private String resolveAppReturnUrl(String appReturnUrl) {
        if (StringUtils.isBlank(appReturnUrl)) {
            return requireValidAppReturnUrl(defaultReturnUrlFromSite());
        }
        return requireValidAppReturnUrl(appReturnUrl);
    }

    private String defaultReturnUrlFromSite() {
        String base = resolveSiteUrlBase();
        String path = normalizeReturnPath(
                imBindProperties != null ? imBindProperties.getReturnPath() : "/api/v1/im-notify/bind/return");
        return base + path;
    }

    private String resolveSiteUrlBase() {
        TenantConfigDto tenantConfig = RequestContext.get() != null
                ? (TenantConfigDto) RequestContext.get().getTenantConfig() : null;
        String siteUrl = tenantConfig != null ? tenantConfig.getSiteUrl() : null;
        if (StringUtils.isBlank(siteUrl)) {
            throw BizException.of(BizExceptionCodeEnum.imNotifyOauthFailed,
                    "tenant siteUrl is required for IM bind return url");
        }
        String base = siteUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    private static String normalizeReturnPath(String path) {
        if (StringUtils.isBlank(path)) {
            return "/api/v1/im-notify/bind/return";
        }
        String p = path.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /**
     * 校验 appReturnUrl：仅 http(s)；默认须精确等于 siteUrl+returnPath；额外白名单按 host 精确 + path 前缀。
     */
    private String requireValidAppReturnUrl(String appReturnUrl) {
        if (StringUtils.isBlank(appReturnUrl)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "appReturnUrl");
        }
        String url = appReturnUrl.trim();
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("javascript:") || lower.startsWith("data:") || lower.startsWith("vbscript:")) {
            throw BizException.of(BizExceptionCodeEnum.imNotifyAppReturnUrlNotAllowed);
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException ex) {
            throw BizException.of(BizExceptionCodeEnum.imNotifyAppReturnUrlNotAllowed);
        }
        String scheme = uri.getScheme();
        if (scheme == null || uri.getHost() == null) {
            throw BizException.of(BizExceptionCodeEnum.imNotifyAppReturnUrlNotAllowed);
        }
        String schemeLower = scheme.toLowerCase(Locale.ROOT);
        boolean allowHttp = imBindProperties != null && imBindProperties.isAllowHttpReturnUrl();
        if ("https".equals(schemeLower)) {
            // ok
        } else if ("http".equals(schemeLower) && allowHttp) {
            // ok for local/dev
        } else {
            throw BizException.of(BizExceptionCodeEnum.imNotifyAppReturnUrlNotAllowed);
        }
        if (isAllowedReturnUrl(url, uri)) {
            return url;
        }
        throw BizException.of(BizExceptionCodeEnum.imNotifyAppReturnUrlNotAllowed);
    }

    private boolean isAllowedReturnUrl(String url, URI uri) {
        try {
            String canonical = defaultReturnUrlFromSite();
            if (url.equals(canonical) || url.startsWith(canonical + "?")) {
                return true;
            }
            // 与默认回跳同 host/path 但带 query 以外差异时，按 URI 再比一次
            if (matchesAllowEntry(uri, canonical)) {
                return true;
            }
        } catch (BizException ignored) {
            // siteUrl 缺失时仅走额外白名单
        }
        List<String> prefixes = imBindProperties != null ? imBindProperties.getReturnUrlAllowPrefixes() : null;
        if (prefixes == null || prefixes.isEmpty()) {
            return false;
        }
        for (String prefix : prefixes) {
            if (StringUtils.isNotBlank(prefix) && matchesAllowEntry(uri, prefix.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * scheme 一致、host（+port）精确匹配；url path 须等于或位于 allow 的 path 前缀之下（防 example.com.evil.com）。
     */
    private static boolean matchesAllowEntry(URI url, String allowRaw) {
        URI allow;
        try {
            allow = URI.create(allowRaw);
        } catch (IllegalArgumentException ex) {
            return false;
        }
        if (allow.getScheme() == null || allow.getHost() == null
                || url.getScheme() == null || url.getHost() == null) {
            return false;
        }
        if (!allow.getScheme().equalsIgnoreCase(url.getScheme())) {
            return false;
        }
        if (!allow.getHost().equalsIgnoreCase(url.getHost())) {
            return false;
        }
        int allowPort = effectivePort(allow);
        int urlPort = effectivePort(url);
        if (allowPort != urlPort) {
            return false;
        }
        String allowPath = normalizePath(allow.getPath());
        String urlPath = normalizePath(url.getPath());
        if (allowPath.equals("/")) {
            // 裸 host（无业务 path）不允许作为宽松前缀
            return false;
        }
        return urlPath.equals(allowPath) || urlPath.startsWith(allowPath.endsWith("/") ? allowPath : allowPath + "/");
    }

    private static int effectivePort(URI uri) {
        int port = uri.getPort();
        if (port >= 0) {
            return port;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ("https".equals(scheme)) {
            return 443;
        }
        if ("http".equals(scheme)) {
            return 80;
        }
        return -1;
    }

    private static String normalizePath(String path) {
        if (StringUtils.isBlank(path)) {
            return "/";
        }
        String p = path.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        // 拒绝 path traversal；再做 URI normalize
        if (p.contains("..")) {
            return "/\0";
        }
        try {
            String normalized = URI.create("https://normalize.local" + p).normalize().getPath();
            if (StringUtils.isBlank(normalized)) {
                return "/";
            }
            if (normalized.contains("..")) {
                return "/\0";
            }
            if (normalized.length() > 1 && normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            return normalized;
        } catch (IllegalArgumentException ex) {
            return "/\0";
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(x, y);
    }

    private String resolveCallbackUrl(String channel) {
        TenantConfigDto tenantConfig = (TenantConfigDto) RequestContext.get().getTenantConfig();
        String siteUrl = tenantConfig != null ? tenantConfig.getSiteUrl() : null;
        if (StringUtils.isBlank(siteUrl)) {
            throw BizException.of(BizExceptionCodeEnum.imNotifyOauthFailed,
                    "tenant siteUrl is required for OAuth redirect");
        }
        String base = siteUrl.endsWith("/") ? siteUrl.substring(0, siteUrl.length() - 1) : siteUrl;
        return base + "/api/v1/im-notify/oauth/" + channel + "/callback";
    }

    private static String resolvePlatformUserLabel() {
        Object userObj = RequestContext.get() != null ? RequestContext.get().getUser() : null;
        if (userObj instanceof UserDto user) {
            if (StringUtils.isNotBlank(user.getNickName())) {
                return user.getNickName();
            }
            if (StringUtils.isNotBlank(user.getUserName())) {
                return user.getUserName();
            }
            if (user.getId() != null) {
                return "用户#" + user.getId();
            }
        }
        Long userId = RequestContext.get() != null ? RequestContext.get().getUserId() : null;
        return userId != null ? "用户#" + userId : "当前账号";
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
