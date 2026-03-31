package com.xspaceagi.agent.core.application.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.xspaceagi.system.application.service.PermissionImportService;
import com.xspaceagi.system.infra.dao.entity.Tenant;
import com.xspaceagi.system.infra.dao.service.TenantService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.xspaceagi.agent.core.adapter.application.ModelApplicationService;
import com.xspaceagi.agent.core.adapter.dto.config.ModelConfigDto;
import com.xspaceagi.agent.core.adapter.repository.entity.ModelConfig;
import com.xspaceagi.agent.core.spec.enums.ModelApiProtocolEnum;
import com.xspaceagi.agent.core.spec.enums.ModelFunctionCallEnum;
import com.xspaceagi.agent.core.spec.enums.ModelTypeEnum;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service("TenantVersionUpdateService")
public class TenantVersionUpdateServiceImpl {

    @Resource
    private TenantService tenantService;

    @Value("${app.version:1.0.0}")
    private String newVersion;

    private Map<String, Consumer<Tenant>> tenantVersionUpgradeMap = new LinkedHashMap<>();

    @Resource
    private ModelApplicationService modelApplicationService;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private PermissionImportService permissionImportService;

    @PostConstruct
    public void init() {
        tenantVersionUpgradeMap.put("1.0.2", (tenant) -> {
            ModelConfigDto modelConfigDto = buildModelConfig(tenant.getId());
            modelApplicationService.addOrUpdate(modelConfigDto);
        });

        tenantVersionUpgradeMap.put("1.0.3", (tenant) -> {
            // 更新 eco_market_client_config 表
            String updateConfigSql = "UPDATE eco_market_client_config SET target_sub_type='ChatBot' " +
                    "WHERE target_type='Agent' AND target_sub_type IS NULL";
            int configUpdatedRows = jdbcTemplate.update(updateConfigSql);
            log.info("更新 eco_market_client_config 表，影响行数：{}，", configUpdatedRows);

            // 更新 eco_market_client_publish_config 表
            String updatePublishConfigSql = "UPDATE eco_market_client_publish_config SET target_sub_type='ChatBot' " +
                    "WHERE target_type='Agent' AND target_sub_type IS NULL";
            int publishConfigUpdatedRows = jdbcTemplate.update(updatePublishConfigSql);
            log.info("更新 eco_market_client_publish_config 表，影响行数：{}", publishConfigUpdatedRows);
        });

        tenantVersionUpgradeMap.put("1.0.4", (tenant) -> {
            String updateSql = "UPDATE custom_page_config SET cover_img=icon, cover_img_source_type='SYSTEM' WHERE cover_img IS NULL AND icon IS NOT NULL;";
            int updatedRows = jdbcTemplate.update(updateSql);
            log.info("更新 custom_page_config 表，影响行数：{}，", updatedRows);
        });

//        tenantVersionUpgradeMap.put("1.0.5", (tenant) -> {
//            //让所有的文档分段，重新全文检索，同步一次es，确保数据没有遗漏
//            String updateSql = "update knowledge_raw_segment set fulltext_sync_status=0;";
//            int updatedRows = jdbcTemplate.update(updateSql);
//            log.info("更新 knowledge_raw_segment 表，影响行数：{}，", updatedRows);
//        });

        tenantVersionUpgradeMap.put("1.0.6.1", (tenant) -> {
            log.info("初始化菜单权限，租户ID：{}", tenant.getId());
            // 权限相关表如果在执行此方法时还未创建完成，会抛出异常，升级管控会等待并重试，此处不需特殊处理
            permissionImportService.importToTenant(tenant, "1.0");
        });

        tenantVersionUpgradeMap.put("1.0.7.6", (tenant) -> {
            permissionImportService.importDiffToTenant(tenant, "1.1");
        });

        new Thread(() -> {
            //升级版本需要的定制化内容
            while (true) {
                try {
                    tenantService.list().forEach(tenant -> {
                        try {
                            RequestContext.setThreadTenantId(tenant.getId());
                            tenantVersionUpgradeMap.forEach((version, upgrade) -> {
                                if (compareVersion(tenant.getVersion(), version) < 0 && compareVersion(tenant.getVersion(), newVersion) < 0) {
                                    log.info("{} 升级版本开始，租户ID：{}", version, tenant.getId());
                                    upgrade.accept(tenant);
                                    log.info("{} 升级版本结束，租户ID：{}", version, tenant.getId());
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
                    log.error("升级版本数据更新失败，进入重试", e);
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
     * 语义化版本比较，支持形如 x.y.z 的数字点分版本号。
     * 返回值语义与 String.compareTo 一致：
     * <0 表示 v1 < v2，0 表示相等，>0 表示 v1 > v2。
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
            // 非数字版本片段，按 0 处理，避免影响整体升级逻辑
            log.warn("版本号片段不是数字，将按 0 处理: {}", part);
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
        modelConfigDto.setDescription("本模型由女娲平台提供的试用模型，请尽快更换使用自有模型。");
        modelConfigDto.setName("glm-4.7-anthropic");
        modelConfigDto.setModel("glm-4.7");
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
