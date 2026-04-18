package com.xspaceagi.sandbox.application.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xspaceagi.agent.core.sdk.IAgentRpcService;
import com.xspaceagi.agent.core.sdk.dto.ReqResult;
import com.xspaceagi.sandbox.application.dto.SandboxConfigDto;
import com.xspaceagi.sandbox.application.dto.SandboxConfigQueryDto;
import com.xspaceagi.sandbox.application.dto.SandboxGlobalConfigDto;
import com.xspaceagi.sandbox.application.service.SandboxConfigApplicationService;
import com.xspaceagi.sandbox.infra.config.ReverseServerProperties;
import com.xspaceagi.sandbox.infra.dao.entity.SandboxConfig;
import com.xspaceagi.sandbox.infra.dao.service.SandboxConfigService;
import com.xspaceagi.sandbox.infra.dao.vo.SandboxConfigValue;
import com.xspaceagi.sandbox.infra.dao.vo.SandboxServerInfo;
import com.xspaceagi.sandbox.infra.network.ReverseServerContainer;
import com.xspaceagi.sandbox.spec.enums.SandboxScopeEnum;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.utils.RedisUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 沙盒配置应用服务实现
 */
@Slf4j
@Service
public class SandboxConfigApplicationServiceImpl implements SandboxConfigApplicationService {

    @Resource
    private SandboxConfigService sandboxConfigService;

    @Resource
    private RedisUtil redisUtil;

    @Resource
    private ReverseServerProperties reverseServerProperties;

    @Resource
    private ReverseServerContainer reverseServerContainer;

    @Resource
    private IAgentRpcService iAgentRpcService;

    static {
        // disable keep alive，暂不使用连接池
        System.setProperty("jdk.httpclient.keepalive.timeout", "0");
    }

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @Override
    public Page<SandboxConfigDto> pageQuery(SandboxConfigQueryDto queryDto) {
        LambdaQueryWrapper<SandboxConfig> queryWrapper = buildQueryWrapper(queryDto);

        Page<SandboxConfig> page = sandboxConfigService.page(
                new Page<>(queryDto.getPageNum(), queryDto.getPageSize()),
                queryWrapper
        );

        return convertDtoPage(page);
    }

    @Override
    public SandboxConfigDto getById(Long id) {
        SandboxConfig entity = sandboxConfigService.getById(id);
        if (entity == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.configNotFound);
        }

        return convertToDto(entity);
    }

    @Override
    public SandboxConfigDto getByKey(String key) {
        Assert.hasText(key, "配置key不能为空");
        LambdaQueryWrapper<SandboxConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SandboxConfig::getConfigKey, key);
        SandboxConfig sandboxConfig = sandboxConfigService.getOne(queryWrapper);
        if (sandboxConfig == null) {
            return null;
        }
        SandboxConfigDto dto = convertToDto(sandboxConfig);
        String host = reverseServerProperties.getOuter().getHost();
        if (StringUtils.isBlank(host)) {
            TenantConfigDto tenantConfig = (TenantConfigDto) RequestContext.get().getTenantConfig();
            try {
                host = new URL(tenantConfig.getSiteUrl()).getHost();
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
        }
        if (StringUtils.isBlank(host)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.sandboxClientAddressUnavailable);
        }
        //host为多个英文逗号隔开的地址，帮我轮询选择
        String[] hosts = host.split(",");
        if (hosts.length == 1) {
            dto.setServerHost(hosts[0]);
        } else {
            Long increment = redisUtil.increment("sandbox:config:host:round:index", 1);
            dto.setServerHost(hosts[increment.intValue() % hosts.length]);
        }
        dto.setServerPort(reverseServerProperties.getOuter().getPort());
        return dto;
    }

    @Override
    public List<SandboxConfigDto> listUserConfigsByType(Long userId) {
        List<SandboxConfig> configs = sandboxConfigService.queryUserConfigsByType(userId);
        return configs.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    @Override
    public List<SandboxConfigDto> listGlobalConfigsByType() {
        List<SandboxConfig> configs = sandboxConfigService.queryGlobalConfigs(null);
        List<SandboxConfigDto> collect = configs.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
        collect.forEach(dto -> {
            try {
                int i = totalUsingCount(dto);
                dto.setUsingCount(i);
                dto.setOnline(true);
            } catch (Exception e) {
                log.warn("获取配置使用数量失败 {}", dto, e);
            }
        });
        return collect;
    }

    @Override
    public boolean hasGlobalConfigsForSelect() {
        return !sandboxConfigService.queryGlobalConfigs(true).isEmpty();
    }

    /**
     * 获取配置使用数量（顺带用于测试连通性）
     */
    private int totalUsingCount(SandboxConfigDto sandboxConfigDto) throws Exception {
        String url = sandboxConfigDto.getConfigValue().getHostWithScheme() + ":" + sandboxConfigDto.getConfigValue().getAgentPort() + "/computer/pod/count";
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url))
                .header("x-api-key", sandboxConfigDto.getConfigValue().getApiKey() == null ? "" : sandboxConfigDto.getConfigValue().getApiKey())
                .timeout(java.time.Duration.ofSeconds(2))
                .GET().build();
        String serverStatusStr = httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body();
        JSONObject jsonObject = JSON.parseObject(serverStatusStr);
        JSONObject data = jsonObject.getJSONObject("data");
        return data.getInteger("total_count");
    }

    @DSTransactional(rollbackFor = Exception.class)
    @Override
    public void create(SandboxConfigDto dto, boolean userAdd) {
        validateConfigKey(dto);

        SandboxConfig entity = convertToEntity(dto);
        entity.setCreated(new Date());
        entity.setModified(new Date());

        // 设置默认值
        if (entity.getIsActive() == null) {
            entity.setIsActive(true);
        }

        // 个人配置自动设置用户ID
        if (entity.getScope() == SandboxScopeEnum.USER && entity.getUserId() == null) {
            entity.setUserId(RequestContext.get().getUserId());
        }
        sandboxConfigService.save(entity);

        // 创建用户沙盒代理
        if (entity.getScope() == SandboxScopeEnum.USER) {
            ReqResult<Long> userSandboxAgent = iAgentRpcService.createUserSandboxAgent(entity.getUserId(), entity.getId(), userAdd ? entity.getName() : null);
            if (!userSandboxAgent.isSuccess()) {
                log.error("创建用户沙盒代理失败：{}", userSandboxAgent.getMessage());
                throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.sandboxUserProxyCreateFailed);
            }
            entity.setAgentId(userSandboxAgent.getData());
            if (userAdd) {
                entity.setName(entity.getName());
            } else {
                entity.setName(entity.getName() + entity.getId());
            }
            sandboxConfigService.updateById(entity);
        }
    }

    @DSTransactional(rollbackFor = Exception.class)
    @Override
    public void update(SandboxConfigDto dto) {
        if (dto.getId() == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "配置ID");
        }

        SandboxConfig existingEntity = sandboxConfigService.getById(dto.getId());
        if (existingEntity == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.configNotFound);
        }

        // 如果修改了 config_key，需要验证唯一性
        if (StringUtils.isNotBlank(dto.getConfigKey()) && !dto.getConfigKey().equals(existingEntity.getConfigKey())) {
            validateConfigKey(dto);
        }

        SandboxConfig entity = convertToEntity(dto);
        entity.setId(dto.getId());
        entity.setModified(new Date());

        sandboxConfigService.updateById(entity);

        if (StringUtils.isNotBlank(entity.getName())) {
            //更新智能体名称
            iAgentRpcService.updateUserSandboxAgentName(existingEntity.getAgentId(), existingEntity.getName(), entity.getName());
        }
    }

    @DSTransactional(rollbackFor = Exception.class)
    @Override
    public void delete(Long id) {
        SandboxConfig entity = sandboxConfigService.getById(id);
        if (entity == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.configNotFound);
        }

        if (entity.getScope() == SandboxScopeEnum.USER) {
            iAgentRpcService.deleteUserSandboxAgent(entity.getAgentId(), entity.getId());
        }

        sandboxConfigService.removeById(id);
    }

    @DSTransactional(rollbackFor = Exception.class)
    @Override
    public void toggle(Long id) {
        SandboxConfig entity = sandboxConfigService.getById(id);
        if (entity == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.configNotFound);
        }

        entity.setIsActive(!entity.getIsActive());
        entity.setModified(new Date());

        sandboxConfigService.updateById(entity);
        if (entity.getScope() == SandboxScopeEnum.USER && !entity.getIsActive()) {
            reverseServerContainer.offlineClient(entity.getConfigKey());
        }
    }


    @Override
    public SandboxGlobalConfigDto getGlobalConfig(Long tenantId) {
        Object o = redisUtil.get("sandbox:global:config:" + tenantId);
        if (o != null) {
            try {
                return JSON.parseObject(o.toString(), SandboxGlobalConfigDto.class);
            } catch (Exception e) {
                // 忽略
                log.warn("反序列化沙盒全局配置失败", e);
            }
        }
        SandboxGlobalConfigDto dto = new SandboxGlobalConfigDto();
        dto.setPerUserMemoryGB("4");
        dto.setPerUserCpuCores("2");
        return dto;
    }

    @Override
    public void updateGlobalConfig(Long tenantId, SandboxGlobalConfigDto dto) {
        try {
            redisUtil.set("sandbox:global:config:" + tenantId, JSON.toJSONString(dto));
        } catch (Exception e) {
            // 忽略
            log.warn("序列化沙盒全局配置失败", e);
        }
    }

    @Override
    public void testConnection(Long sandboxId) {
        SandboxConfigDto byId = getById(sandboxId);
        if (byId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.configNotFound);
        }
        try {
            totalUsingCount(byId);
        } catch (Exception e) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.sandboxDeployFailedWithHelp,
                    (e.getMessage() != null ? e.getMessage() : "") + "\n点击查看 <a target=\"_blank\" href=\"https://nuwax.com/agent-computer-deploy.html#%E5%85%AD%E3%80%81%E6%95%85%E9%9A%9C%E6%8E%92%E6%9F%A5\">常见问题</a>");
        }
    }

    /**
     * 构建查询条件
     */
    private LambdaQueryWrapper<SandboxConfig> buildQueryWrapper(SandboxConfigQueryDto queryDto) {
        LambdaQueryWrapper<SandboxConfig> queryWrapper = new LambdaQueryWrapper<>();

        if (queryDto.getScope() != null) {
            queryWrapper.eq(SandboxConfig::getScope, queryDto.getScope());
        }

        if (queryDto.getUserId() != null) {
            queryWrapper.eq(SandboxConfig::getUserId, queryDto.getUserId());
        }

        if (StringUtils.isNotBlank(queryDto.getName())) {
            queryWrapper.like(SandboxConfig::getName, queryDto.getName());
        }

        if (queryDto.getIsActive() != null) {
            queryWrapper.eq(SandboxConfig::getIsActive, queryDto.getIsActive());
        }

        queryWrapper.orderByAsc(SandboxConfig::getId);

        return queryWrapper;
    }

    /**
     * 验证配置键唯一性
     */
    private void validateConfigKey(SandboxConfigDto dto) {
        if (StringUtils.isBlank(dto.getConfigKey())) {
            return;
        }

        LambdaQueryWrapper<SandboxConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SandboxConfig::getScope, dto.getScope())
                .eq(SandboxConfig::getConfigKey, dto.getConfigKey());

        if (dto.getScope() == SandboxScopeEnum.USER) {
            Long userId = dto.getUserId() != null ? dto.getUserId() : RequestContext.get().getUserId();
            queryWrapper.eq(SandboxConfig::getUserId, userId);
        }

        if (dto.getId() != null) {
            queryWrapper.ne(SandboxConfig::getId, dto.getId());
        }

        long count = sandboxConfigService.count(queryWrapper);
        if (count > 0) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.sandboxConfigKeyDuplicate);
        }
    }

    /**
     * 转换为 DTO
     */
    private SandboxConfigDto convertToDto(SandboxConfig entity) {
        SandboxConfigDto dto = new SandboxConfigDto();
        BeanUtils.copyProperties(entity, dto);
        // 将 JSON 字符串转换为对象
        if (StringUtils.isNotBlank(entity.getConfigValue())) {
            try {
                dto.setConfigValue(JSON.parseObject(entity.getConfigValue(), SandboxConfigValue.class));
            } catch (Exception e) {
                log.error("Failed to parse config value", e);
            }
        }
        if (StringUtils.isNotBlank(entity.getServerInfo())) {
            try {
                dto.setServerInfo(JSON.parseObject(entity.getServerInfo(), SandboxServerInfo.class));
            } catch (Exception e) {
                log.error("解析服务端信息失败", e);
            }
        }

        if (entity.getScope() == SandboxScopeEnum.USER) {
            dto.setOnline(reverseServerContainer.getUserSandboxAliveTime(entity.getConfigKey()) != null);
        }
        return dto;
    }

    /**
     * 转换为实体
     */
    private SandboxConfig convertToEntity(SandboxConfigDto dto) {
        SandboxConfig entity = new SandboxConfig();
        BeanUtils.copyProperties(dto, entity);
        // 将对象转换为 JSON 字符串
        if (dto.getConfigValue() != null) {
            try {
                entity.setConfigValue(JSON.toJSONString(dto.getConfigValue()));
            } catch (Exception e) {
                log.error("序列化配置值失败", e);
            }
        }
        return entity;
    }

    /**
     * 转换分页结果
     */
    private Page<SandboxConfigDto> convertDtoPage(Page<SandboxConfig> page) {
        Page<SandboxConfigDto> dtoPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        List<SandboxConfigDto> dtoList = page.getRecords().stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
        dtoPage.setRecords(dtoList);
        return dtoPage;
    }
}
