package com.xspaceagi.custompage.infra.service;

import com.xspaceagi.custompage.domain.model.CustomPageBuildModel;
import com.xspaceagi.custompage.domain.model.CustomPageConfigModel;
import com.xspaceagi.custompage.domain.model.CustomPageDomainModel;
import com.xspaceagi.custompage.domain.repository.ICustomPageBuildRepository;
import com.xspaceagi.custompage.domain.repository.ICustomPageConfigRepository;
import com.xspaceagi.custompage.domain.repository.ICustomPageDomainRepository;
import com.xspaceagi.custompage.infra.dao.entity.CustomPageConfig;
import com.xspaceagi.custompage.infra.translator.ICustomPageConfigTranslator;
import com.xspaceagi.custompage.infra.vo.BackendVo;
import com.xspaceagi.custompage.sdk.dto.ProjectType;
import com.xspaceagi.custompage.sdk.dto.ProxyConfig;
import com.xspaceagi.custompage.sdk.dto.ProxyConfigBackend;
import com.xspaceagi.sandbox.sdk.server.ISandboxConfigRpcService;
import com.xspaceagi.sandbox.sdk.service.dto.SandboxConfigRpcDto;
import com.xspaceagi.sandbox.sdk.service.dto.SandboxConfigValue;
import com.xspaceagi.system.spec.cache.SimpleJvmHashCache;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.jackson.JsonSerializeUtil;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ProxyConfigService {

    @Resource
    private ICustomPageConfigRepository customPageConfigRepository;
    @Resource
    private ICustomPageConfigTranslator customPageConfigTranslator;

    @Resource
    private ICustomPageBuildRepository customPageBuildRepository;

    @Resource
    private ICustomPageDomainRepository customPageDomainRepository;

    @Resource
    private ISandboxConfigRpcService sandboxConfigRpcService;

    @Value("${custom-page.dev-server-host}")
    private String customPageDevServerHost;

    @Value("${custom-page.prod-server-host}")
    private String customPageProdServerHost;

    @Value("${custom-page.docker-proxy.base-url}")
    private String customPageDockerProxyBaseUrl;

    // 启动 docker 代理
    @Value("${custom-page.docker-proxy.enable}")
    private boolean enableDockerProxy;

    // rcoder 预览 nginx 端口（prod sandbox 动态分支 buildDirectDevServerUrl 用）。
    // 默认 8099（兼容旧部署）；部署侧按实际 rcoder nginx NodePort 配置，如 .13 K8s 为 31203。
    @Value("${custom-page.nginx-port:8099}")
    private Integer customPageNginxPort;

    public BackendVo selectBackend(String basePath, String realUri, ProxyConfig.ProxyEnv env, Long agentId) {

        log.debug("select Backend start - base Path: {}, real Uri: {}, env: {}", basePath, realUri, env);

        if (env == null) {
            log.warn("select Backend failed - env is null");
            return null;
        }

        CustomPageConfig customPageConfig = (CustomPageConfig) SimpleJvmHashCache.getHash(CustomPageConfig.class.getName(), basePath);
        if (customPageConfig == null) {
            synchronized (this) {
                if (customPageConfig == null) {
                    CustomPageConfigModel customPageConfigModel = customPageConfigRepository.getByBasePath(basePath);
                    log.debug("query config result - base Path: {}, config exists: {}", basePath, customPageConfigModel != null);

                    customPageConfig = customPageConfigTranslator.convertToEntity(customPageConfigModel);
                    if (customPageConfig == null) {
                        log.warn("select Backend failed - base Path not found: {} config", basePath);
                        return null;
                    }
                    if (env == ProxyConfig.ProxyEnv.prod) {
                        SimpleJvmHashCache.putHash(CustomPageConfig.class.getName(), basePath, customPageConfig, 10);
                    }
                }
            }
        }

        //下面逻辑中有对customPageConfig的操作，避免影响其他请求
        customPageConfig = (CustomPageConfig) JsonSerializeUtil.deepCopy(customPageConfig);
        log.debug("found config - ID: {}, project : {}", customPageConfig.getId(), customPageConfig.getProjectType());
        if (customPageConfig.getProxyConfigs() == null) {
            customPageConfig.setProxyConfigs(new ArrayList<>());
        }
        Integer devPort = null;
        if (customPageConfig.getProjectType() == ProjectType.ONLINE_DEPLOY) {
            // 删除误配的根目录
            customPageConfig.getProxyConfigs().removeIf(proxyConfig -> proxyConfig.getPath().equals("/"));
            if (env == ProxyConfig.ProxyEnv.dev) {
                CustomPageBuildModel customPageBuildModel = (CustomPageBuildModel) SimpleJvmHashCache.getHash(CustomPageBuildModel.class.getName(), customPageConfig.getId().toString());
                if (customPageBuildModel == null) {
                    synchronized (this) {
                        if (customPageBuildModel == null) {
                            customPageBuildModel = customPageBuildRepository.getByProjectId(customPageConfig.getId());
                            if (customPageBuildModel == null) {
                                log.warn("select Backend failed - project ID not found: {}", customPageConfig.getId());
                                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.customPageProxyProjectBuildNotFound);
                                // return null;
                            }

                            if (customPageBuildModel.getDevPort() == null) {
                                log.warn("select Backend failed - project dev service not started, projectId:{}", customPageConfig.getId());
                                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.customPageProxyDevServerNotStarted);
                                // return null;
                            }
                        }
                    }
                }
                devPort = customPageBuildModel.getDevPort();

                SandboxConfigValue sandboxConfigValue = loadSandboxConfigValue(customPageConfig);
                String devUrl = "";
                String devApiKey = null;
                if (enableDockerProxy) {
                    if (sandboxConfigValue != null && sandboxConfigValue.getVncPort() > 0) {
                        devUrl = sandboxConfigValue.getHostWithScheme() + ":" + sandboxConfigValue.getVncPort();
                        devApiKey = sandboxConfigValue.getApiKey();
                    } else {
                        if (customPageDockerProxyBaseUrl.startsWith("http://")
                                || customPageDockerProxyBaseUrl.startsWith("https://")) {
                            devUrl = customPageDockerProxyBaseUrl;
                        } else {
                            devUrl = "http://" + customPageDockerProxyBaseUrl;
                        }
                    }
                } else if (sandboxConfigValue != null) {
                    devUrl = buildDirectDevServerUrl(sandboxConfigValue.getHostWithScheme(), customPageBuildModel.getDevPort());
                    devApiKey = sandboxConfigValue.getApiKey();
                } else {
                    if (customPageDevServerHost.startsWith("http://")
                            || customPageDevServerHost.startsWith("https://")) {
                        devUrl = customPageDevServerHost + ":" + customPageBuildModel.getDevPort();
                    } else {
                        devUrl = "http://" + customPageDevServerHost + ":" + customPageBuildModel.getDevPort();
                    }
                }

                log.debug("generate dev environment config - dev Url: {}", devUrl);
                ProxyConfig devProxyConfig = ProxyConfig.builder().path("/")
                        .healthCheckPath("/health")
                        .env(ProxyConfig.ProxyEnv.dev)
                        .requireAuth(false)
                        .backends(List.of(new ProxyConfigBackend(devUrl, 1, devApiKey)))
                        .build();
                customPageConfig.getProxyConfigs().add(devProxyConfig);
            }
            if (env == ProxyConfig.ProxyEnv.prod) {
                String prodUrl = null;
                String prodApiKey = null;
                SandboxConfigValue sandboxConfigForProd = loadSandboxConfigValue(customPageConfig);
                if (sandboxConfigForProd != null) {
                    prodUrl = buildDirectDevServerUrl(sandboxConfigForProd.getHostWithScheme(), customPageNginxPort);
                    if (prodUrl != null) {
                        prodApiKey = sandboxConfigForProd.getApiKey();
                    }
                }
                if (prodUrl == null) {
                    prodUrl = customPageProdServerHost;
                }
                if (!prodUrl.endsWith("/")) {
                    prodUrl += "/";
                }
                prodUrl += customPageConfig.getId();
                log.debug("generate prod environment config - prod Url: {}", prodUrl);

                ProxyConfig prodProxyConfig = ProxyConfig.builder().path("/")
                        .healthCheckPath("/health")
                        .env(ProxyConfig.ProxyEnv.prod)
                        .requireAuth(false)
                        .backends(List.of(new ProxyConfigBackend(prodUrl, 1, prodApiKey)))
                        .build();
                customPageConfig.getProxyConfigs().add(prodProxyConfig);
            }
        }
        if (customPageConfig.getProjectType() == ProjectType.REVERSE_PROXY) {
            // 检查是否有反向代理配置
            if (CollectionUtils.isEmpty(customPageConfig.getProxyConfigs())) {
                return null;
            }
        }
        // 根据env过滤
        List<ProxyConfig> proxyConfigs = customPageConfig.getProxyConfigs().stream()
                .filter(proxyConfig1 -> proxyConfig1.getEnv().equals(env)).collect(Collectors.toList());
        log.debug("filteredproxyconfigcount: {}", proxyConfigs.size());

        if (proxyConfigs.isEmpty()) {
            log.warn("select Backend failed - env not found: {} proxy config", env);
            return null;
        }

        ProxyConfig proxyConfig = longestPrefixMatch(realUri, proxyConfigs);
        if (proxyConfig == null) {
            log.warn("select Backend failed - real Uri: {} cannot match any proxy config", realUri);
            return null;
        }
        log.debug("matchedproxyconfig - path: {}, backend: {}", proxyConfig.getPath(),
                proxyConfig.getBackends().get(0).getBackend());

        URL url;
        try {
            url = new URL(proxyConfig.getBackends().get(0).getBackend());
        } catch (MalformedURLException e) {
            log.error("invalid backend URL: {}", proxyConfig.getBackends().get(0).getBackend(), e);
            throw new RuntimeException(e);
        }
        BackendVo backendVo = new BackendVo();
        backendVo.setHost(url.getHost());
        backendVo.setPort(url.getPort() == -1 ? ("https".equals(url.getProtocol()) ? 443 : 80) : url.getPort());
        backendVo.setScheme(url.getProtocol());
        backendVo.setRequireAuth(customPageConfig.getNeedLogin() != null && customPageConfig.getNeedLogin() == 1);
        backendVo.setDevAgentId(customPageConfig.getDevAgentId());
        backendVo.setPublishType(customPageConfig.getPublishType());
        // 沙箱直连 backend 构造期已绑定鉴权 key；配置来源 backend 为 null，代理不下发该头
        backendVo.setApiKey(proxyConfig.getBackends().get(0).getApiKey());

        // 对于ONLINE_DEPLOY项目，完整的代理路径，因为前端开发服务器配置了base URL
        if (customPageConfig.getProjectType() == ProjectType.ONLINE_DEPLOY && proxyConfig.getPath().equals("/")) {

            // 构建当前页面的base URL
            String currentBase = "/page" + basePath;
            currentBase += "-" + agentId;
            currentBase += "/" + env.name();

            // 检查 realUri 是否匹配另一个页面的路径模式 (/page/xxx-xxx/xxx/)
            // 如果匹配，说明这是前端应用错误生成的嵌套路径，应该去掉这部分
            if (realUri.matches("^/page/\\w+(?:-\\d+)?/\\w+.*")) {
                // 直接使用 realUri
                backendVo.setUri(realUri);
                log.warn("select Backend [{}] real Uri contains page path pattern, - uri: {}",
                        env.name(), realUri);
            } else {
                // 正常情况拼接完整路径
                String fullPath = currentBase;

                if (!realUri.equals("/")) {
                    if (realUri.startsWith("/")) {
                        fullPath += realUri;
                    } else {
                        fullPath += "/" + realUri;
                    }
                } else {
                    // 确保末尾有斜杠
                    if (!fullPath.endsWith("/")) {
                        fullPath += "/";
                    }
                }
                if (enableDockerProxy && env == ProxyConfig.ProxyEnv.dev && devPort != null) {
                    int routePort = devPort;
                    fullPath = "/proxy/" + routePort + fullPath;
                }
                backendVo.setUri(fullPath);
                log.debug("select Backend [-{}] assemble full path - full Path: {}", env.name(), fullPath);
            }
        } else if (StringUtils.isNotBlank(url.getPath()) && !url.getPath().equals("/")) {
            log.debug("assemble URL - real Uri: {}, proxy Config.path: {}, url.get Path(): {}",
                    realUri, proxyConfig.getPath(), url.getPath());

            // 移除path前缀，只移除开头匹配的部分
            String subUri = realUri;
            if (realUri.startsWith(proxyConfig.getPath())) {
                subUri = realUri.substring(proxyConfig.getPath().length());
                // 如果path不是 "/" 结尾，且 realUri 在移除前缀后还有内容
                // 确保subUri以 "/" 开头（除非已经是空的）
                if (!proxyConfig.getPath().endsWith("/") && !subUri.isEmpty() && !subUri.startsWith("/")) {
                    subUri = "/" + subUri;
                }
            }
            log.debug("compute sub Uri - after replacement: {}", subUri);

            // 拼接 url.getPath() 和 subUri
            String urlPath = url.getPath();
            if (!urlPath.endsWith("/") && !subUri.isEmpty() && !subUri.startsWith("/")) {
                urlPath += "/";
            }
            backendVo.setUri(urlPath + subUri);
        } else {
            backendVo.setUri(realUri);
        }
        log.debug("select Backend succeeded - backend address: {}://{}:{}{}", backendVo.getScheme(), backendVo.getHost(),
                backendVo.getPort(), backendVo.getUri());
        return backendVo;
    }

    private SandboxConfigValue loadSandboxConfigValue(CustomPageConfig customPageConfig) {
        Long sandboxId = customPageConfig.getSandboxId();
        if (sandboxId == null) {
            return null;
        }
        try {
            RequestContext<?> requestContext = RequestContext.get();
            Long tenantId = requestContext == null ? null : requestContext.getTenantId();
            Long userId = requestContext == null ? null : requestContext.getUserId();
            SandboxConfigRpcDto sandboxConfig = sandboxConfigRpcService.selectAppDevelopmentSandbox(
                    tenantId,
                    userId,
                    customPageConfig.getSpaceId(),
                    customPageConfig.getId(),
                    sandboxId);
            if (sandboxConfig == null) {
                return null;
            }
            SandboxConfigValue configValue = sandboxConfig.getConfigValue();
            if (configValue == null || configValue.getHostWithScheme() == null || configValue.getHostWithScheme().isBlank()) {
                return null;
            }
            return configValue;
        } catch (Exception e) {
            log.warn("load sandbox config for dev url failed, projectId={}, fallback to yaml",
                    customPageConfig.getId(), e);
            return null;
        }
    }

    private static String buildDirectDevServerUrl(String hostWithScheme, int port) {
        if (hostWithScheme == null || hostWithScheme.isBlank()) {
            return null;
        }
        String host = hostWithScheme.trim();
        while (host.endsWith("/")) {
            host = host.substring(0, host.length() - 1);
        }
        return host + ":" + port;
    }

    public static ProxyConfig longestPrefixMatch(String realUri, List<ProxyConfig> proxyConfigs) {
        ProxyConfig longestMatchProxyConfig = null;
        int maxLength = -1;

        for (ProxyConfig proxyConfig : proxyConfigs) {
            if (realUri.startsWith(proxyConfig.getPath()) && proxyConfig.getPath().length() > maxLength) {
                longestMatchProxyConfig = proxyConfig;
                maxLength = proxyConfig.getPath().length();
            }
        }

        return longestMatchProxyConfig;
    }

    public CustomPageConfigModel queryCustomPageConfigByDomain(String domain) {
        if (domain == null) {
            return null;
        }
        domain = domain.split(":")[0];
        Object customPageConfigModel = SimpleJvmHashCache.getHash("custom_page_config_model", domain);
        if (customPageConfigModel != null) {
            return (CustomPageConfigModel) customPageConfigModel;
        }
        String finalDomain = domain;
        CustomPageDomainModel byDomain = TenantFunctions.callWithIgnoreCheck(() -> customPageDomainRepository.getByDomain(finalDomain));
        if (byDomain == null) {
            return null;
        }
        CustomPageConfigModel configModel = TenantFunctions.callWithIgnoreCheck(() -> customPageConfigRepository.getById(byDomain.getProjectId()));
        if (configModel == null) {
            return null;
        }
        // 内存缓存10秒，避免短时间重复查询
        SimpleJvmHashCache.putHash("custom_page_config_model", domain, configModel, 10);
        return configModel;
    }
}
