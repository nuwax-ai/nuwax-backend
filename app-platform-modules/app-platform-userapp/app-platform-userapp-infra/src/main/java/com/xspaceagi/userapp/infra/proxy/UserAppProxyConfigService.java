package com.xspaceagi.userapp.infra.proxy;

import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.xspaceagi.system.application.dto.I18nLangDto;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.AuthService;
import com.xspaceagi.system.application.service.I18nLangApplicationService;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.application.service.TenantConfigApplicationService;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.spec.cache.SimpleJvmHashCache;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import com.xspaceagi.userapp.domain.gateway.UserAppSandboxHandle;
import com.xspaceagi.userapp.domain.model.UserAppDomainModel;
import com.xspaceagi.userapp.domain.model.UserAppModel;
import com.xspaceagi.userapp.domain.repository.IUserAppDomainRepository;
import com.xspaceagi.userapp.domain.repository.IUserAppRepository;
import com.xspaceagi.userapp.domain.service.UserAppEndpointResolver;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * userapp 代理的鉴权与后端解析。
 *
 * 先按 appId 免租户查项目用于确定租户，之后的登录用户查询、权限校验全部运行在该租户上下文内——
 * 跨租户 token 解不出用户直接拒绝，不存在任何回退路径。
 *
 * 访问策略：
 * 应用： dev（路径访问）：登录 + 同空间成员或租户管理员；prod：免登录访问；
 * 域名直连（dev/prod 域名）：一律免登录；
 * prod 应用已停止（部署过且未运行，见 prodStoppedApp）：不转发下游，由请求侧返回停止页；从未部署照旧透传下游。
 * dbx（dev/prod）：登录 + 同空间成员或租户管理员
 * vnc/ttyd/ime/audio：登录 + 同空间成员或租户管理员
 *
 * 登录态仅用于访问控制，平台用户身份不向下游传递；
 * 需要平台用户身份的应用自行接入平台 OAuth2 获取。
 */
@Slf4j
@Service
public class UserAppProxyConfigService {

    private static final String CACHE_PROJECT = "userapp_proxy_project";

    private static final String CACHE_TENANT_CONFIG = "userapp_proxy_tenant_config";

    private static final String CACHE_BACKEND = "userapp_proxy_backend";

    private static final String CACHE_DOMAIN = "userapp_proxy_domain";

    /** 各级缓存时长（秒）：避免页面资源请求反复打库 */
    private static final int CACHE_SECONDS = 10;

    @Resource
    private IUserAppRepository userAppRepository;
    @Resource
    private IUserAppDomainRepository userAppDomainRepository;
    @Resource
    private UserAppEndpointResolver userAppEndpointResolver;
    @Resource
    private AuthService authService;
    @Resource
    private SpaceApplicationService spaceApplicationService;
    @Resource
    private TenantConfigApplicationService tenantConfigApplicationService;
    @Resource
    private I18nLangApplicationService i18nLangApplicationService;

    /**
     * 鉴权并解析后端
     *
     * @return 后端目标；项目不存在返回 null（404）
     * @throws BizException UNAUTHORIZED_REDIRECT 未登录/登录失效；PERMISSION_DENIED 无访问权限
     */
    public UserAppProxyBackend authorizeAndResolve(UserAppProxyRoute route, String token) {
        String cacheKey = StringUtils.defaultString(token) + "|" + route.getService() + "|" + route.getEnv()
                + "|" + route.getAppId();
        Object cached = SimpleJvmHashCache.getHash(CACHE_BACKEND, cacheKey);
        if (cached != null) {
            return (UserAppProxyBackend) cached;
        }
        // RequestContext 由本方法设置、调用方（Netty handler）在读完后统一清理
        UserAppProxyBackend backend = doAuthorizeAndResolve(route, token);
        if (backend != null) {
            SimpleJvmHashCache.putHash(CACHE_BACKEND, cacheKey, backend, CACHE_SECONDS);
        }
        return backend;
    }

    private UserAppProxyBackend doAuthorizeAndResolve(UserAppProxyRoute route, String token) {
        // 防御性：路由正则之外的服务按不存在处理
        if (!route.isSupportedService()) {
            return null;
        }
        UserAppModel project = loadProject(route.getAppId());
        if (project == null) {
            return null;
        }
        // 预删除（等待物理删除）的应用一律 404；项目缓存 TTL 10s，标记后短暂窗口内自动收敛
        if (project.getPreDeleteTime() != null) {
            return null;
        }

        // 以项目所属租户构建上下文（URL 公开标识引导，后续查询/校验全部受限于此租户）
        RequestContext requestContext = new RequestContext<>();
        requestContext.setTenantId(project.getTenantId());
        requestContext.setTenantConfig(loadTenantConfig(project.getTenantId()));
        RequestContext.set(requestContext);

        // 需登录访问的场景
        if (requiresLogin(route)) {
            // 应用 dev、dbx 与 vnc/ttyd/ime/audio（dev/prod）：登录 + 同空间成员（或租户管理员）
            UserDto user = authService.getLoginUserInfo(token);
            if (user == null) {
                throw BizException.of(ErrorCodeEnum.UNAUTHORIZED_REDIRECT, BizExceptionCodeEnum.userappProxyAuthFailed);
            }
            // 用户查询本身受租户拦截约束（跨租户 token 查不出用户），此处再显式比对一次，不依赖隐式行为
            if (!Objects.equals(user.getTenantId(), project.getTenantId())) {
                log.warn("[UserAppProxy] tenant mismatch rejected, appId={}, userTenant={}, projectTenant={}",
                        route.getAppId(), user.getTenantId(), project.getTenantId());
                throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.userappProxyNoPermission);
            }
            requestContext.setUserId(user.getId());
            requestContext.setUser(user);
            if (spaceApplicationService.querySpaceUser(project.getSpaceId(), user.getId()) == null
                    && user.getRole() != User.Role.Admin) {
                throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.userappProxySpaceMemberOnly);
            }
        } else {
            // 免登录公开访问：应用 prod（路径/域名）与域名直连（dev/prod 域名）。
            // prod 环境要求已部署（prod_deployed），但这里可不必强制校验，直接透传
            // if (UserAppProxyRoute.ENV_PROD.equals(route.getEnv()) && !Boolean.TRUE.equals(project.getProdDeployed())) {
            //    throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.userappProxyNotDeployed);
            // }
        }

        UserAppSandboxHandle handle = userAppEndpointResolver.resolve(project,
                UserAppProxyRoute.ENV_PROD.equals(route.getEnv()));
        UserAppProxyBackend backend = UserAppProxyBackend.of(handle, project.getCreatorId());
        if (backend == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappSandboxServerNotFound);
        }
        return backend;
    }

    /** 应用 prod 与域名直连（dev/prod 域名）免登录；其余（路径访问的 app/dev、dbx、交互服务）需登录 */
    private boolean requiresLogin(UserAppProxyRoute route) {
        if (route.isDomainDirect()) {
            return false;
        }
        return !(route.isApp() && UserAppProxyRoute.ENV_PROD.equals(route.getEnv()));
    }

    /**
     * prod 应用停止状态（供代理拦截）：仅 app 服务 + prod 环境且「部署过但未运行」时返回项目（名称供页面渲染）。
     * 从未部署（prod_deployed=false）不拦、照旧透传下游；项目走 10s 缓存，停止/恢复至多延迟一个缓存周期生效。
     */
    public UserAppModel prodStoppedApp(UserAppProxyRoute route) {
        if (!route.isApp() || !UserAppProxyRoute.ENV_PROD.equals(route.getEnv())) {
            return null;
        }
        UserAppModel project = loadProject(route.getAppId());
        return project != null && project.prodStopped() ? project : null;
    }

    /**
     * 页面语言（停止页/错误页共用），按系统惯例逐级回退（同 AuthInterceptor 的语言解析顺序）：
     * 登录用户资料语言 > lang cookie > 租户默认语言 > Accept-Language 首个有效语言段（可能为 null，页面缺省中文）。
     * appId 能定位到项目时，用户与租户默认语言查询运行在项目租户上下文内（跨租户 token 查不出用户，自然回退）；
     * 定位不到（路由未命中/项目已删）跳过该两级；上下文由本方法设置、调用方（Netty handler）finally 统一清理。
     */
    public String resolvePageLang(Long appId, String token, String langCookie, String acceptLanguage) {
        UserAppModel project = appId == null ? null : loadProject(appId);
        if (project != null) {
            RequestContext<?> requestContext = new RequestContext<>();
            requestContext.setTenantId(project.getTenantId());
            requestContext.setTenantConfig(loadTenantConfig(project.getTenantId()));
            RequestContext.set(requestContext);
            if (StringUtils.isNotBlank(token)) {
                try {
                    UserDto user = authService.getLoginUserInfo(token);
                    if (user != null && StringUtils.isNotBlank(user.getLang())) {
                        return user.getLang();
                    }
                } catch (Exception e) {
                    log.warn("[UserAppProxy] resolve user lang failed, appId={}", appId, e);
                }
            }
        }
        if (StringUtils.isNotBlank(langCookie)) {
            return langCookie;
        }
        if (project != null) {
            try {
                I18nLangDto defaultLang = i18nLangApplicationService.getDefault(project.getTenantId());
                if (defaultLang != null && StringUtils.isNotBlank(defaultLang.getLang())) {
                    return defaultLang.getLang();
                }
            } catch (Exception e) {
                log.warn("[UserAppProxy] resolve tenant default lang failed, appId={}", appId, e);
            }
        }
        return firstLangSegment(acceptLanguage);
    }

    /** Accept-Language 首个非空语言段（忽略空段与权重参数，如 en-US,en;q=0.9,zh;q=0.8 → en-us）；无有效段返回 null */
    private static String firstLangSegment(String acceptLanguage) {
        if (StringUtils.isBlank(acceptLanguage)) {
            return null;
        }
        for (String segment : acceptLanguage.split(",")) {
            String lang = segment.split(";")[0].trim().toLowerCase();
            if (!lang.isEmpty()) {
                return lang;
            }
        }
        return null;
    }

    /** _ticket 兑换登录 token（与登录侧一次性票据机制一致） */
    public String exchangeTicket(String ticket) {
        return authService.getTokenByTicket(ticket);
    }

    private UserAppModel loadProject(Long appId) {
        Object cached = SimpleJvmHashCache.getHash(CACHE_PROJECT, appId.toString());
        if (cached != null) {
            return (UserAppModel) cached;
        }
        // 免租户仅用于按公开 appId 引导出租户；随后的用户/权限查询都在租户上下文内
        UserAppModel project = TenantFunctions.callWithIgnoreCheck(
                () -> userAppRepository.getById(appId));
        if (project != null) {
            SimpleJvmHashCache.putHash(CACHE_PROJECT, appId.toString(), project, CACHE_SECONDS);
        }
        return project;
    }

    /**
     * 按 Host 域名查绑定应用。
     */
    public UserAppDomainModel resolveDomainBinding(String domain) {
        Object cached = SimpleJvmHashCache.getHash(CACHE_DOMAIN, domain);
        if (cached != null) {
            return (UserAppDomainModel) cached;
        }
        UserAppDomainModel binding = TenantFunctions.callWithIgnoreCheck(
                () -> userAppDomainRepository.getByDomain(domain));
        if (binding != null) {
            SimpleJvmHashCache.putHash(CACHE_DOMAIN, domain, binding, CACHE_SECONDS);
        }
        return binding;
    }

    /**
     * 请求 Origin 与租户站点（siteUrl）白名单匹配：命中返回归一化 Origin 用于回写 ACAO，
     * 未配置/不匹配/查询失败一律返回 null（fail-closed，绝不回显任意 Origin）。
     */
    public String matchSiteOrigin(Long appId, String originHeader) {
        if (StringUtils.isBlank(originHeader)) {
            return null;
        }
        try {
            UserAppModel project = loadProject(appId);
            if (project == null) {
                return null;
            }
            // siteUrl 查询须运行在项目租户上下文内（租户拦截不回退）；调用方（Netty handler）finally 统一清理
            RequestContext<?> requestContext = new RequestContext<>();
            requestContext.setTenantId(project.getTenantId());
            RequestContext.set(requestContext);
            TenantConfigDto tenantConfig = loadTenantConfig(project.getTenantId());
            requestContext.setTenantConfig(tenantConfig);
            if (tenantConfig == null || StringUtils.isBlank(tenantConfig.getSiteUrl())) {
                return null;
            }
            return UserAppProxyCors.match(originHeader, tenantConfig.getSiteUrl());
        } catch (Exception e) {
            // 白名单解析失败只影响跨域头（不加即拒绝），不拖垮代理请求本身
            log.warn("[UserAppProxy] resolve cors whitelist failed, appId={}", appId, e);
            return null;
        }
    }

    private TenantConfigDto loadTenantConfig(Long tenantId) {
        Object cached = SimpleJvmHashCache.getHash(CACHE_TENANT_CONFIG, tenantId.toString());
        if (cached != null) {
            return (TenantConfigDto) cached;
        }
        TenantConfigDto tenantConfig = tenantConfigApplicationService.getTenantConfig(tenantId);
        if (tenantConfig != null) {
            SimpleJvmHashCache.putHash(CACHE_TENANT_CONFIG, tenantId.toString(), tenantConfig, CACHE_SECONDS);
        }
        return tenantConfig;
    }
}
