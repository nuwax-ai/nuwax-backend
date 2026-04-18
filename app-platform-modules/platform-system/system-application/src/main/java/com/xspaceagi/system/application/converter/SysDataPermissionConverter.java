package com.xspaceagi.system.application.converter;

import com.xspaceagi.system.application.dto.permission.SysDataPermissionBindDto;
import com.xspaceagi.system.infra.dao.entity.SysDataPermission;
import com.xspaceagi.system.sdk.service.dto.TokenLimit;
import com.xspaceagi.system.spec.jackson.JsonSerializeUtil;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 数据权限转换器
 */
public final class SysDataPermissionConverter {

    private SysDataPermissionConverter() {
    }

    /**
     * 实体转 DTO（用于查询接口返回）
     */
    public static SysDataPermissionBindDto toDto(SysDataPermission entity) {
        if (entity == null) {
            return null;
        }
        SysDataPermissionBindDto dto = new SysDataPermissionBindDto();
        BeanUtils.copyProperties(entity, dto);
        return dto;
    }

    public static SysDataPermission toEntity(SysDataPermissionBindDto dto) {
        if (dto == null) {
            return null;
        }
        SysDataPermission entity = new SysDataPermission();
        entity.setModelIds(dto.getModelIds());
        entity.setAgentIds(dto.getAgentIds());
        entity.setPageAgentIds(dto.getPageAgentIds());
        Map<String, String> openApiConfigMap = new LinkedHashMap<>();
        if (CollectionUtils.isNotEmpty(dto.getOpenApiConfigs())) {
            for (SysDataPermissionBindDto.OpenApiConfig config : dto.getOpenApiConfigs()) {
                if (config == null || StringUtils.isBlank(config.getKey())) {
                    continue;
                }
                Map<String, Integer> configValue = new LinkedHashMap<>();
                configValue.put("rpm", config.getRpm());
                configValue.put("rpd", config.getRpd());
                openApiConfigMap.put(config.getKey(), JsonSerializeUtil.toJSONString(configValue));
            }
        }
        entity.setOpenApiConfigMap(openApiConfigMap);
        entity.setKnowledgeIds(dto.getKnowledgeIds());

        // 数量类型不传默认 -1（不限制）
        entity.setTokenLimit(dto.getTokenLimit() != null ? dto.getTokenLimit() : new TokenLimit(-1L));
        entity.setMaxSpaceCount(dto.getMaxSpaceCount() != null ? dto.getMaxSpaceCount() : -1);
        entity.setMaxAgentCount(dto.getMaxAgentCount() != null ? dto.getMaxAgentCount() : -1);
        entity.setMaxPageAppCount(dto.getMaxPageAppCount() != null ? dto.getMaxPageAppCount() : -1);
        entity.setMaxKnowledgeCount(dto.getMaxKnowledgeCount() != null ? dto.getMaxKnowledgeCount() : -1);
        entity.setKnowledgeStorageLimitGb(dto.getKnowledgeStorageLimitGb() != null ? dto.getKnowledgeStorageLimitGb() : BigDecimal.valueOf(-1L));
        entity.setMaxDataTableCount(dto.getMaxDataTableCount() != null ? dto.getMaxDataTableCount() : -1);
        entity.setMaxScheduledTaskCount(dto.getMaxScheduledTaskCount() != null ? dto.getMaxScheduledTaskCount() : -1);
        entity.setAgentFileStorageDays(dto.getAgentFileStorageDays() != null ? dto.getAgentFileStorageDays() : -1);
        entity.setAgentDailyPromptLimit(dto.getAgentDailyPromptLimit() != null ? dto.getAgentDailyPromptLimit() : -1);
        entity.setPageDailyPromptLimit(dto.getPageDailyPromptLimit() != null ? dto.getPageDailyPromptLimit() : -1);
        //entity.setAllowApiExternalCall(dto.getAllowApiExternalCall());
        // 智能体电脑内存不传默认 4，交换分区不传默认 8，CPU核心数不传默认 2
        entity.setAgentComputerCpuCores(dto.getAgentComputerCpuCores() != null ? dto.getAgentComputerCpuCores() : 2);
        entity.setAgentComputerMemoryGb(dto.getAgentComputerMemoryGb() != null ? dto.getAgentComputerMemoryGb() : 4);
        entity.setAgentComputerSwapGb(null);
        return entity;
    }

}
