package com.xspaceagi.agent.core.application.service;

import com.xspaceagi.agent.core.adapter.application.ModelApplicationService;
import com.xspaceagi.agent.core.adapter.dto.config.ModelConfigDto;
import com.xspaceagi.agent.core.adapter.repository.entity.ModelConfig;
import com.xspaceagi.agent.core.spec.enums.ModelApiProtocolEnum;
import com.xspaceagi.agent.core.spec.enums.ModelFunctionCallEnum;
import com.xspaceagi.agent.core.spec.enums.ModelTypeEnum;
import com.xspaceagi.system.application.service.I18nImportService;
import com.xspaceagi.system.application.service.PermissionImportService;
import com.xspaceagi.system.infra.dao.entity.Tenant;
import com.xspaceagi.system.infra.dao.service.TenantService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.utils.RedisUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Slf4j
@Service("TenantVersionUpdateService")
public class TenantVersionUpdateServiceImpl {

    @Resource
    private TenantService tenantService;

    @Value("${app.version:1.0.0}")
    private String newVersion;

    private final Map<String, Consumer<Tenant>> tenantVersionUpgradeMap = new LinkedHashMap<>();

    @Resource
    private ModelApplicationService modelApplicationService;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private PermissionImportService permissionImportService;

    @Resource
    private I18nImportService i18nImportService;

    @Resource
    private RedisUtil redisUtil;

    @PostConstruct
    public void init() {
        tenantVersionUpgradeMap.put("1.0.2", (tenant) -> {
            ModelConfigDto modelConfigDto = buildModelConfig(tenant.getId());
            modelApplicationService.addOrUpdate(modelConfigDto);
        });

        tenantVersionUpgradeMap.put("1.0.3", (tenant) -> {
            // Update eco_market_client_config table
            String updateConfigSql = "UPDATE eco_market_client_config SET target_sub_type='ChatBot' " +
                    "WHERE target_type='Agent' AND target_sub_type IS NULL";
            int configUpdatedRows = jdbcTemplate.update(updateConfigSql);
            log.info("Updated eco_market_client_config table, affected rows: {},", configUpdatedRows);

            // Update eco_market_client_publish_config table
            String updatePublishConfigSql = "UPDATE eco_market_client_publish_config SET target_sub_type='ChatBot' " +
                    "WHERE target_type='Agent' AND target_sub_type IS NULL";
            int publishConfigUpdatedRows = jdbcTemplate.update(updatePublishConfigSql);
            log.info("Updated eco_market_client_publish_config table, affected rows: {}", publishConfigUpdatedRows);
        });

        tenantVersionUpgradeMap.put("1.0.4", (tenant) -> {
            String updateSql = "UPDATE custom_page_config SET cover_img=icon, cover_img_source_type='SYSTEM' WHERE cover_img IS NULL AND icon IS NOT NULL;";
            int updatedRows = jdbcTemplate.update(updateSql);
            log.info("Updated custom_page_config table, affected rows: {},", updatedRows);
        });

        tenantVersionUpgradeMap.put("1.0.6.1", (tenant) -> {
            log.info("Initialize menu permissions, tenant ID: {}", tenant.getId());
            // If permission-related tables are not yet created when executing this method, an exception will be thrown. The upgrade control will wait and retry, no special handling needed here
            permissionImportService.importToTenant(tenant, "1.0");
        });

        tenantVersionUpgradeMap.put("1.0.7.6", (tenant) -> {
            permissionImportService.importDiffToTenant(tenant, "1.1");
        });

        tenantVersionUpgradeMap.put("1.0.7.7", (tenant) -> {
            // permission update
            permissionImportService.importDiffToTenant(tenant, "1.2");
        });

        tenantVersionUpgradeMap.put("1.0.8.2", (tenant) -> {
            // permission
            permissionImportService.importDiffToTenant(tenant, "1.3");
            // i18n
            i18nImportService.importLangToTenant(tenant, "0.0");
            i18nImportService.importConfigToTenant(tenant, "0.0");
            i18nImportService.updateConfigToTenant(tenant, "1.0");
            i18nImportService.updateConfigToTenant(tenant, "1.1");
        });

        tenantVersionUpgradeMap.put("1.0.8.3", (tenant) -> {
            // permission
            permissionImportService.importDiffToTenant(tenant, "1.4");
            // i18n
            i18nImportService.addConfigToTenant(tenant, "1.2");
            i18nImportService.updateConfigToTenant(tenant, "1.2");
        });

        new Thread(() -> {
            // Customized content required for version upgrade
            while (true) {
                try {
                    tenantService.list().forEach(tenant -> {
                        try {
                            RequestContext.setThreadTenantId(tenant.getId());
                            redisUtil.expire("tenant_config:" + tenant.getId(), 0);
                            tenantVersionUpgradeMap.forEach((version, upgrade) -> {
                                if (compareVersion(tenant.getVersion(), version) < 0 && compareVersion(tenant.getVersion(), newVersion) < 0) {
                                    log.info("{} Version upgrade started, tenant ID: {}", version, tenant.getId());
                                    upgrade.accept(tenant);
                                    log.info("{} Version upgrade completed, tenant ID: {}", version, tenant.getId());
                                }
                            });
                            tenant.setVersion(newVersion);
                            tenantService.updateById(tenant);
                        } finally {
                            RequestContext.remove();
                        }
                    });
                    break;
                } catch (Exception e) {
                    log.error("Version upgrade data update failed, entering retry", e);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e1) {
                        break;
                        // ignore
                    }
                }
            }
        }).start();
    }

    /**
     * Semantic version comparison, supports numeric dot-separated version numbers like x.y.z.
     * Return value semantics are consistent with String.compareTo:
     * <0 means v1 < v2, 0 means equal, >0 means v1 > v2.
     */
    private int compareVersion(String v1, String v2) {
        if (v1 == null && v2 == null) {
            return 0;
        }
        if (v1 == null) {
            return -1;
        }
        if (v2 == null) {
            return 1;
        }

        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        int maxLen = Math.max(parts1.length, parts2.length);

        for (int i = 0; i < maxLen; i++) {
            int p1 = i < parts1.length ? parseVersionPart(parts1[i]) : 0;
            int p2 = i < parts2.length ? parseVersionPart(parts2[i]) : 0;
            if (p1 != p2) {
                return p1 - p2;
            }
        }

        return 0;
    }

    private int parseVersionPart(String part) {
        try {
            return Integer.parseInt(part);
        } catch (NumberFormatException e) {
            // Non-numeric version fragment, treat as 0 to avoid affecting overall upgrade logic
            log.warn("Version fragment is not a number, will be treated as 0: {}", part);
            return 0;
        }
    }

    public ModelConfigDto buildModelConfig(Long tenantId) {
        ModelConfigDto modelConfigDto = new ModelConfigDto();
        modelConfigDto.setSpaceId(-1L);
        modelConfigDto.setIsReasonModel(YesOrNoEnum.N.getKey());
        modelConfigDto.setApiProtocol(ModelApiProtocolEnum.Anthropic);
        modelConfigDto.setScope(ModelConfig.ModelScopeEnum.Tenant);
        modelConfigDto.setCreatorId(-1L);
        modelConfigDto.setTenantId(tenantId);
        modelConfigDto.setDescription("This model is a trial model provided by the Nuwax. Please replace it with your own model as soon as possible.");
        modelConfigDto.setName("glm-5.1-anthropic");
        modelConfigDto.setModel("glm-5.1");
        modelConfigDto.setType(ModelTypeEnum.Chat);
        modelConfigDto.setFunctionCall(ModelFunctionCallEnum.StreamCallSupported);
        modelConfigDto.setStrategy(ModelConfig.ModelStrategyEnum.RoundRobin);
        modelConfigDto.setMaxTokens(32000);
        modelConfigDto.setTopP(0.7);
        modelConfigDto.setTemperature(1.0);
        modelConfigDto.setNetworkType(ModelConfig.NetworkType.Internet);
        modelConfigDto.setDimension(1536);
        ModelConfigDto.ApiInfo apiInfo = new ModelConfigDto.ApiInfo();
        apiInfo.setUrl("https://anthropic-code-api.nuwax.com/api/anthropic/session-SESSION_ID");
        apiInfo.setKey("TENANT_SECRET");
        apiInfo.setWeight(1);
        modelConfigDto.setApiInfoList(List.of(apiInfo));
        return modelConfigDto;
    }
}
