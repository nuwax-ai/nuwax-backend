package com.xspaceagi.custompage.domain.proxypath;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xspaceagi.custompage.domain.model.CustomPageConfigModel;
import com.xspaceagi.custompage.domain.service.ICustomPageConfigDomainService;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.utils.RedisUtil;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * dev/prod代理路径生成服务
 */
@Slf4j
@Service
public class CustomPageProxyPathServiceImpl implements ICustomPageProxyPathService {

    @Resource
    private ICustomPageConfigDomainService customPageConfigDomainService;
    @Resource
    private RedisUtil redisUtil;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // 缓存key前缀
    private static final String CONFIG_CACHE_KEY_PREFIX = "custom_page:config:";

    // 缓存过期时间：60分钟
    private static final long CACHE_EXPIRE_SECONDS = 60 * 60;

    @Override
    public String getDevProxyPath(Long projectId) {
        CustomPageConfigModel configModel = getConfigModelWithCache(projectId);
        if (configModel != null) {
            return getDevProxyPath(configModel);
        } else {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.customPageProxyPathLoadFailed);
        }
    }

    @Override
    public String getDevProxyPath(CustomPageConfigModel configModel) {
        if (configModel == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "configuration object");
        }

        String proxyPath = "/page" + configModel.getBasePath();
        proxyPath += "-" + getAgentId(configModel);
        proxyPath += "/dev/";
        return proxyPath;
    }

    @Override
    public String getProdProxyPath(Long projectId) {
        CustomPageConfigModel configModel = getConfigModelWithCache(projectId);
        if (configModel != null) {
            return getProdProxyPath(configModel);
        } else {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.customPageProxyPathLoadFailed);
        }
    }

    @Override
    public String getProdProxyPath(CustomPageConfigModel configModel) {
        if (configModel == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "configuration object");
        }

        String proxyPath = "/page" + configModel.getBasePath();
        proxyPath += "-" + getAgentId(configModel);
        proxyPath += "/prod/";
        return proxyPath;
    }

    @Override
    public void removeConfigCache(Long projectId) {
        String cacheKey = CONFIG_CACHE_KEY_PREFIX + projectId;
        redisUtil.expire(cacheKey, 0);
        log.info("[Proxy Path] config cache cleared, project Id={}", projectId);
    }

    private Long getAgentId(CustomPageConfigModel configModel) {
        Long devAgentId = configModel.getDevAgentId();
        if (devAgentId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.customPageProxyPathNoAgent);
        }
        return devAgentId;

        // 从 RequestContext 获取租户配置
        // TenantConfigDto tenantConfig = (TenantConfigDto)
        // RequestContext.get().getTenantConfig();
        // if (tenantConfig == null || tenantConfig.getDefaultAgentId() == null) {
        // throw new BizException("0001", "租户未配置默认智能体,请先配置");
        // }
        // return tenantConfig.getDefaultAgentId();
    }

    private CustomPageConfigModel getConfigModelWithCache(Long projectId) {
        String cacheKey = CONFIG_CACHE_KEY_PREFIX + projectId;

        CustomPageConfigModel configModel = null;

        try {
            // 先从缓存获取
            Object cachedValue = redisUtil.get(cacheKey);
            if (cachedValue != null) {
                log.debug("[Proxy Path] from cachegetconfig, project Id={}", projectId);
                CustomPageConfigModel cacheModel = objectMapper.readValue(cachedValue.toString(),
                        CustomPageConfigModel.class);
                if (cacheModel.getBasePath() != null && cacheModel.getDevAgentId() != null) {
                    return cacheModel;
                }
                // 缓存错误,删除缓存
                removeConfigCache(projectId);
            }

            log.debug("[Proxy Path] cache miss, from DBqueryconfig, project Id={}", projectId);
            configModel = customPageConfigDomainService.getById(projectId);

            if (configModel != null) {
                String configJson = objectMapper.writeValueAsString(configModel);
                redisUtil.set(cacheKey, configJson, CACHE_EXPIRE_SECONDS);
                log.debug("[Proxy Path] configcached, project Id={}", projectId);
            }

            return configModel;

        } catch (Exception e) {
            log.error("[Proxy Path] serialize/deserializeconfigfailed, project Id={}", projectId, e);
            // 缓存异常时，直接查询数据库
            return configModel != null ? configModel : customPageConfigDomainService.getById(projectId);
        }
    }
}
