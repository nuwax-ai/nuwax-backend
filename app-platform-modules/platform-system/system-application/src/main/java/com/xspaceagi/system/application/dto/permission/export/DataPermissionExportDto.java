package com.xspaceagi.system.application.dto.permission.export;

import com.xspaceagi.system.sdk.service.dto.TokenLimit;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 数据权限导出DTO（targetCode表示角色或用户组code）
 */
@Data
public class DataPermissionExportDto {

    private Integer targetType;
    private String targetCode;
    private TokenLimit tokenLimit;
    private Integer maxSpaceCount;
    private Integer maxAgentCount;
    private Integer maxPageAppCount;
    private Integer maxKnowledgeCount;
    private BigDecimal knowledgeStorageLimitGb;
    private Integer maxDataTableCount;
    private Integer maxScheduledTaskCount;
    private Integer agentComputerCpuCores;
    private Integer agentComputerMemoryGb;
    private Integer agentComputerSwapGb;
    private BigDecimal agentComputerStorageLimitGb;
    private BigDecimal pageAppStorageLimitGb;
    private Integer agentFileStorageDays;
    private Integer agentDailyPromptLimit;
    private Integer pageDailyPromptLimit;
}
