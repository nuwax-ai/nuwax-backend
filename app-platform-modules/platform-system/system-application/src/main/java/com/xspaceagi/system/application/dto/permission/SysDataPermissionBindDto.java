package com.xspaceagi.system.application.dto.permission;

import com.xspaceagi.system.sdk.service.dto.TokenLimit;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

@Data
@Schema(description = "数据权限绑定")
public class SysDataPermissionBindDto implements Serializable {

    @Schema(description = "可用的模型ID列表")
    private List<Long> modelIds;

    @Schema(description = "可访问的智能体id列表，null或空表示不限制")
    private List<Long> agentIds;

    @Schema(description = "可访问的网页应用智能体id列表，null或空表示不限制")
    private List<Long> pageAgentIds;

    @Schema(description = "可访问的开放api及访问频率配置")
    private List<OpenApiConfig> openApiConfigs;

    @Schema(description = "有权限访问的知识库id列表")
    private List<Long> knowledgeIds;

    @Schema(description = "token限制")
    private TokenLimit tokenLimit;

    @Schema(description = "可创建工作空间数量，-1表示不限制")
    private Integer maxSpaceCount;

    @Schema(description = "可创建智能体数量，-1表示不限制")
    private Integer maxAgentCount;

    @Schema(description = "可创建网页应用数量，-1表示不限制")
    private Integer maxPageAppCount;

    @Schema(description = "可创建知识库数量，-1表示不限制")
    private Integer maxKnowledgeCount;

    @Schema(description = "知识库存储空间上限(GB，保留三位小数)，-1表示不限制")
    private BigDecimal knowledgeStorageLimitGb;

    @Schema(description = "可创建数据表数量，-1表示不限制")
    private Integer maxDataTableCount;

    @Schema(description = "可创建定时任务数量，-1表示不限制")
    private Integer maxScheduledTaskCount;

    @Schema(description = "智能体电脑CPU核心数，null表示使用默认值2")
    private Integer agentComputerCpuCores;

    @Schema(description = "智能体电脑内存(GB)，null表示使用默认值4")
    private Integer agentComputerMemoryGb;

    @Schema(description = "智能体电脑交换分区(GB)，null表示使用默认值8")
    private Integer agentComputerSwapGb;

    @Schema(description = "智能体电脑存储上限(GB)，-1表示不限制")
    private BigDecimal agentComputerStorageLimitGb;

    @Schema(description = "网页应用存储上限(GB)，-1表示不限制")
    private BigDecimal pageAppStorageLimitGb;

    @Schema(description = "通用智能体执行结果文件存储天数，-1表示不限制")
    private Integer agentFileStorageDays;

    @Schema(description = "通用智能体每天对话次数，-1表示不限制")
    private Integer agentDailyPromptLimit;

    @Schema(description = "网页应用开发每天对话次数，-1表示不限制")
    private Integer pageDailyPromptLimit;


//    @Schema(description = "是否允许API外部调用，1-允许，0-不允许")
//    private Integer allowApiExternalCall;

    @Data
    @Schema(description = "开放API权限配置")
    public static class OpenApiConfig implements Serializable {

        @Schema(description = "开放api key")
        private String key;

        @Schema(description = "接口调用频率限制，每分钟调用次数")
        private Integer rpm;

        @Schema(description = "接口调用频率限制，每天调用次数")
        private Integer rpd;
    }
}
