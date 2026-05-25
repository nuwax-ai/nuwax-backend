package com.xspaceagi.system.application.service.impl;

import com.alibaba.fastjson2.JSON;
import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.xspaceagi.eco.market.sdk.service.IEcoMarketSecretRpcService;
import com.xspaceagi.pay.spec.gateway.PayGatewayOutboundCacheEvictSupport;
import com.xspaceagi.pay.spec.gateway.PayGatewayOutboundCacheEvictor;
import com.xspaceagi.system.application.dto.*;
import com.xspaceagi.system.application.service.TenantConfigApplicationService;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.infra.dao.entity.Tenant;
import com.xspaceagi.system.infra.dao.entity.TenantConfig;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.infra.dao.service.TenantConfigService;
import com.xspaceagi.system.infra.dao.service.TenantService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.utils.RedisUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TenantConfigApplicationServiceImpl implements TenantConfigApplicationService {

    @Resource
    private TenantConfigService tenantConfigService;

    @Resource
    private TenantService tenantService;

    @Resource
    private RedisUtil redisUtil;

    @Resource
    private UserApplicationService userApplicationService;

    @Resource
    private IEcoMarketSecretRpcService ecoMarketSecretRpcService;

    @Autowired(required = false)
    private PayGatewayOutboundCacheEvictor payGatewayOutboundCacheEvictor;

    @Value("${app.version:1.0.0}")
    private String newVersion;

    @Value("${license:}")
    private String license;

    @Override
    public List<TenantConfigItemDto> getTenantConfigList() {
        String lockKey = "lock-for-check-tenant-config:" + RequestContext.get().getTenantId();
        try {
            redisUtil.lock(lockKey, 10000);
            return tenantConfigService.getTenantConfigList().stream().map(tenantConfig -> {
                TenantConfigItemDto tenantConfigItemDto = new TenantConfigItemDto();
                tenantConfigItemDto.setTenantId(tenantConfig.getTenantId());
                tenantConfigItemDto.setName(tenantConfig.getName());
                tenantConfigItemDto.setValue(tenantConfig.getValue());
                tenantConfigItemDto.setDescription(tenantConfig.getDescription());
                tenantConfigItemDto.setCategory(tenantConfig.getCategory());
                tenantConfigItemDto.setInputType(tenantConfig.getInputType());
                tenantConfigItemDto.setDataType(tenantConfig.getDataType());
                tenantConfigItemDto.setNotice(tenantConfig.getNotice());
                tenantConfigItemDto.setPlaceholder(tenantConfig.getPlaceholder());
                tenantConfigItemDto.setMinHeight(tenantConfig.getMinHeight());
                tenantConfigItemDto.setRequired(tenantConfig.isRequired());
                tenantConfigItemDto.setSort(tenantConfig.getSort());
                return tenantConfigItemDto;
            }).collect(Collectors.toList());
        } finally {
            redisUtil.unlock(lockKey);
        }
    }

    @Override
    public List<TenantDto> getTenantList() {
        List<TenantConfigItemDto> tenantConfigList;
        try {
            RequestContext.addTenantIgnoreEntity(TenantConfig.class);
            tenantConfigList = getTenantConfigList();
        } finally {
            RequestContext.removeTenantIgnoreEntity(TenantConfig.class);
        }
        //按租户ID分组
        Map<Long, List<TenantConfigItemDto>> tenantConfigMap = tenantConfigList.stream().collect(Collectors.groupingBy(TenantConfigItemDto::getTenantId));
        return tenantService.list().stream().map(tenant -> {
            TenantDto tenantDto = new TenantDto();
            tenantDto.setId(tenant.getId());
            tenantDto.setName(tenant.getName());
            tenantDto.setDomain(tenant.getDomain());
            tenantDto.setDescription(tenant.getDescription());
            tenantDto.setStatus(tenant.getStatus());
            tenantDto.setCreated(tenant.getCreated());
            tenantDto.setTenantConfigs(tenantConfigMap.get(tenant.getId()));
            return tenantDto;
        }).collect(Collectors.toList());
    }

    @Override
    public TenantDto queryTenantById(Long tenantId) {
        Tenant tenant = tenantService.getById(tenantId);
        if (tenant != null) {
            TenantDto tenantDto = new TenantDto();
            BeanUtils.copyProperties(tenant, tenantDto);
            return tenantDto;
        }
        return null;
    }

    @Override
    public void updateConfig(TenantConfigDto tenantConfigDto) {
        Map<String, Object> updateConfig = new HashMap<>();
        JSON.parseObject(JSON.toJSONString(tenantConfigDto)).forEach((k, v) -> {
            if (v != null) {
                updateConfig.put(k, v);
            }
        });
        tenantConfigService.updateConfig(updateConfig);
        if (CollectionUtils.isNotEmpty(tenantConfigDto.getDomainNames())) {
            tenantConfigDto.getDomainNames().forEach(domainName -> {
                Long tenantId = queryTenantIdByDomainName(domainName);
                if (tenantId != null && tenantId.longValue() != tenantConfigDto.getTenantId().longValue()) {
                    throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemTenantDomainAlreadyInUse, domainName);
                }
            });
            redisUtil.expire("tenant_domain", 0);
        }
        redisUtil.expire("tenant_config:" + tenantConfigDto.getTenantId(), 0);
        if (updateConfig.containsKey("paymentGateway")) {
            PayGatewayOutboundCacheEvictSupport.evictIfPresent(
                    payGatewayOutboundCacheEvictor, tenantConfigDto.getTenantId());
        }
    }

    @Override
    public TenantConfigDto getTenantConfig(Long tenantId) {
        Object configStr = redisUtil.get("tenant_config:" + tenantId);
        if (configStr == null) {
            Map<String, Object> collect;
            boolean nullCtx = RequestContext.get() == null;
            try {
                if (nullCtx) {
                    RequestContext.setThreadTenantId(tenantId);
                }
                collect = getTenantConfigList().stream().collect(Collectors.toMap(TenantConfigItemDto::getName, TenantConfigItemDto::getValue));
            } finally {
                if (nullCtx) {
                    RequestContext.remove();
                }
            }
            configStr = JSON.toJSONString(collect);
            redisUtil.set("tenant_config:" + tenantId, configStr.toString());
        }
        TenantConfigDto tenantConfigDto = JSON.parseObject(configStr.toString(), TenantConfigDto.class);
        tenantConfigDto.setTenantId(tenantId);
        if (StringUtils.isNotBlank(license)) {
            tenantConfigDto.setCommercialEdition(true);
        }
        return tenantConfigDto;
    }

    @Override
    public Long queryTenantIdByDomainName(String domainName) {
        Object tenantId = redisUtil.hashGet("tenant_domain", domainName);
        if (tenantId != null) {
            return Long.parseLong(tenantId.toString());
        }
        Map<String, String> domainMap = tenantService.list().stream().collect(Collectors.toMap(Tenant::getDomain, tenant -> tenant.getId().toString()));
        domainMap.putAll(tenantConfigService.getAllTenantDomainConfigMap());
        //domainMap转Map<String, Object>
        Map<String, Object> domainMapObject = new HashMap<>(domainMap);
        redisUtil.hashPutAll("tenant_domain", domainMapObject);
        return domainMapObject.get(domainName) == null ? null : Long.parseLong(domainMapObject.get(domainName).toString());
    }

    @DSTransactional(rollbackFor = Exception.class)
    @Override
    public void addTenant(TenantAddDto tenantAddDto) {
        Tenant tenant = new Tenant();
        BeanUtils.copyProperties(tenantAddDto, tenant);
        tenant.setVersion(newVersion);
        tenantService.save(tenant);
        redisUtil.expire("tenant_domain", 0);
        UserDto userDto = new UserDto();
        userDto.setTenantId(tenant.getId());
        userDto.setUserName(tenantAddDto.getUserName());
        userDto.setPassword(tenantAddDto.getPassword());
        userDto.setRole(User.Role.Admin);
        userDto.setStatus(User.Status.Enabled);
        userDto.setNickName(tenantAddDto.getNickName());
        userDto.setEmail(tenantAddDto.getEmail());
        userDto.setPhone(tenantAddDto.getPhone());
        RequestContext.setThreadTenantId(tenant.getId());
        userApplicationService.add(userDto);

        // 注册生态市场客户端，注册成功后初始化租户信息
        ecoMarketSecretRpcService.registerClient(tenant.getId(), tenant.getName(), tenant.getDescription());
        log.info("注册生态市场客户端成功: tenantId={}", tenant.getId());
    }

    @Override
    public void updateTenant(Tenant tenant) {
        tenantService.updateById(tenant);
        redisUtil.expire("tenant_domain", 0);
    }
}
