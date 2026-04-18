package com.xspaceagi.sandbox.api;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xspaceagi.sandbox.api.vo.SandboxServerConfig;
import com.xspaceagi.sandbox.application.service.SandboxConfigApplicationService;
import com.xspaceagi.sandbox.infra.dao.entity.SandboxConfig;
import com.xspaceagi.sandbox.infra.dao.service.SandboxConfigService;
import com.xspaceagi.sandbox.infra.network.ReverseServerContainer;
import com.xspaceagi.sandbox.sdk.server.ISandboxConfigRpcService;
import com.xspaceagi.sandbox.sdk.service.dto.SandboxConfigRpcDto;
import com.xspaceagi.sandbox.sdk.service.dto.SandboxConfigValue;
import com.xspaceagi.sandbox.sdk.service.dto.SandboxGlobalConfigDto;
import com.xspaceagi.sandbox.sdk.service.dto.SandboxServerInfo;
import com.xspaceagi.sandbox.spec.enums.SandboxScopeEnum;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.service.TenantConfigApplicationService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import com.xspaceagi.system.spec.utils.RedisUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 沙盒配置 RPC 服务实现
 */
@Slf4j
@Service("iSandboxConfigRpcService")
public class SandboxConfigRpcServiceImpl implements ISandboxConfigRpcService {

    @Resource
    private SandboxConfigService sandboxConfigService;

    @Resource
    private SandboxConfigApplicationService sandboxConfigApplicationService;

    @Resource
    private ReverseServerContainer reverseServerContainer;

    @Resource
    private TenantConfigApplicationService tenantConfigApplicationService;

    @Resource
    private RedisUtil redisUtil;


    @PostConstruct
    public void init() {
        TenantFunctions.runWithIgnoreCheck(() -> {
            try {
                List<SandboxConfigRpcDto> sandboxConfigRpcDtos = queryGlobalConfigs(1L);
                if (!sandboxConfigRpcDtos.isEmpty()) {
                    return;
                }
                //初始化租户id为1的全局配置，私有化部署的id为1L
                TenantConfigDto tenantConfig = tenantConfigApplicationService.getTenantConfig(1L);
                //全局没有配置时读取系统设置
                String sandboxServerConfigStr = tenantConfig.getSandboxConfig();
                SandboxServerConfig sandboxServerConfig = JSON.parseObject(sandboxServerConfigStr, SandboxServerConfig.class);
                if (sandboxServerConfig != null && sandboxServerConfig.getSandboxServers() != null) {
                    com.xspaceagi.sandbox.application.dto.SandboxGlobalConfigDto sandboxGlobalConfigDto = new com.xspaceagi.sandbox.application.dto.SandboxGlobalConfigDto();
                    sandboxGlobalConfigDto.setPerUserCpuCores(sandboxServerConfig.getPerUserCpuCores() != 0 ? String.valueOf(sandboxServerConfig.getPerUserCpuCores()) : "2");
                    sandboxGlobalConfigDto.setPerUserMemoryGB(sandboxServerConfig.getPerUserMemoryGB() != 0 ? String.valueOf(sandboxServerConfig.getPerUserMemoryGB()) : "4");
                    sandboxConfigApplicationService.updateGlobalConfig(1L, sandboxGlobalConfigDto);
                    sandboxServerConfig.getSandboxServers().forEach(sandboxServer -> {
                        try {
                            SandboxConfig sandboxConfig = new SandboxConfig();
                            sandboxConfig.setId(Long.valueOf(sandboxServer.getServerId()));
                            sandboxConfig.setScope(SandboxScopeEnum.GLOBAL);
                            sandboxConfig.setTenantId(tenantConfig.getTenantId());
                            sandboxConfig.setName(sandboxServer.getServerName());
                            sandboxConfig.setDescription(sandboxServer.getServerName());
                            sandboxConfig.setConfigKey(UUID.randomUUID().toString().replace("-", ""));
                            SandboxConfigValue sandboxConfigValue = new SandboxConfigValue();
                            URL serverUrl = new URL(sandboxServer.getServerAgentUrl());
                            sandboxConfigValue.setHostWithScheme(serverUrl.getProtocol() + "://" + serverUrl.getHost());
                            sandboxConfigValue.setAgentPort(serverUrl.getPort());
                            serverUrl = new URL(sandboxServer.getServerFileUrl());
                            sandboxConfigValue.setFileServerPort(serverUrl.getPort());
                            serverUrl = new URL(sandboxServer.getServerVncUrl());
                            sandboxConfigValue.setVncPort(serverUrl.getPort());
                            sandboxConfigValue.setApiKey(sandboxServer.getServerApiKey());
                            sandboxConfigValue.setMaxUsers(sandboxServer.getMaxUsers());
                            sandboxConfig.setConfigValue(JSON.toJSONString(sandboxConfigValue));
                            sandboxConfig.setIsActive(true);
                            sandboxConfigService.save(sandboxConfig);
                        } catch (MalformedURLException e) {
                            log.error("初始化沙箱配置失败 {}", sandboxServer, e);
                        }
                    });

                }
            } catch (Exception e) {
                log.error("初始化全局沙箱配置失败", e);
            }
        });
    }

    @Override
    public List<SandboxConfigRpcDto> queryUserConfigs(Long userId) {
        LambdaQueryWrapper<SandboxConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SandboxConfig::getScope, SandboxScopeEnum.USER)
                .eq(SandboxConfig::getUserId, userId)
                .eq(SandboxConfig::getIsActive, true)
                .orderByAsc(SandboxConfig::getId);

        List<SandboxConfig> configs = sandboxConfigService.list(queryWrapper);
        return convertToRpcDtoList(configs);
    }

    @Override
    public List<SandboxConfigRpcDto> queryGlobalConfigs(Long tenantId) {
        LambdaQueryWrapper<SandboxConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SandboxConfig::getScope, SandboxScopeEnum.GLOBAL)
                .eq(SandboxConfig::getIsActive, true)
                .eq(SandboxConfig::getTenantId, tenantId)
                .orderByAsc(SandboxConfig::getId);

        List<SandboxConfig> configs = TenantFunctions.callWithIgnoreCheck(() -> sandboxConfigService.list(queryWrapper));
        return convertToRpcDtoList(configs);
    }

    @Override
    public SandboxConfigRpcDto queryUserConfigByKey(String configKey) {
        SandboxConfig config = sandboxConfigService.queryUserConfigByKey(configKey);
        return convertToRpcDto(config);
    }

    @Override
    public SandboxConfigRpcDto queryGlobalConfigByKey(String configKey) {
        SandboxConfig config = sandboxConfigService.queryGlobalConfigByKey(configKey);
        return convertToRpcDto(config);
    }

    @Override
    public SandboxConfigRpcDto queryById(Long id) {
        SandboxConfig config = TenantFunctions.callWithIgnoreCheck(() -> sandboxConfigService.getById(id));
        return convertToRpcDto(config);
    }

    /**
     * 转换为 RPC DTO 列表
     */
    private List<SandboxConfigRpcDto> convertToRpcDtoList(List<SandboxConfig> configs) {
        if (CollectionUtils.isEmpty(configs)) {
            return List.of();
        }
        return configs.stream()
                .map(this::convertToRpcDto)
                .collect(Collectors.toList());
    }

    /**
     * 转换为 RPC DTO
     */
    private SandboxConfigRpcDto convertToRpcDto(SandboxConfig entity) {
        if (entity == null) {
            return null;
        }
        SandboxConfigRpcDto dto = new SandboxConfigRpcDto();
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
                dto.setSandboxServerInfo(JSON.parseObject(entity.getServerInfo(), SandboxServerInfo.class));
            } catch (Exception e) {
                log.error("Failed to parse config value", e);
            }
        }

        if (entity.getScope() == SandboxScopeEnum.USER) {
            dto.setOnline(reverseServerContainer.getUserSandboxAliveTime(entity.getConfigKey()) != null);
        }
        return dto;
    }

    @Override
    public SandboxGlobalConfigDto getGlobalConfig(Long tenantId) {
        com.xspaceagi.sandbox.application.dto.SandboxGlobalConfigDto globalConfig = sandboxConfigApplicationService.getGlobalConfig(tenantId);
        if (globalConfig != null) {
            SandboxGlobalConfigDto dto = new SandboxGlobalConfigDto();
            BeanUtils.copyProperties(globalConfig, dto);
            return dto;
        }
        return null;
    }

    @Override
    public Long queryUserSelectedSandboxId(Long userId, Long agentId) {
        if (agentId == null || userId == null) {
            return null;
        }
        Object val = redisUtil.hashGet("user-sandbox-selected:" + RequestContext.get().getUserId(), agentId.toString());
        if (val != null) {
            try {
                return Long.parseLong(val.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }
}
