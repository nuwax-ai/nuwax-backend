package com.xspaceagi.system.sdk.service.dto;

import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

@Schema(description = "用户数据权限 DTO")
@Data
public class UserDataPermissionDto implements Serializable {

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "token 限制")
    private TokenLimit tokenLimit;

    @Schema(description = "可创建工作空间数量，-1 表示不限制")
    private Integer maxSpaceCount;

    @Schema(description = "可创建智能体数量，-1 表示不限制")
    private Integer maxAgentCount;

    @Schema(description = "可创建网页应用数量，-1 表示不限制")
    private Integer maxPageAppCount;

    @Schema(description = "可创建知识库数量，-1 表示不限制")
    private Integer maxKnowledgeCount;

    @Schema(description = "知识库存储空间上限(GB，保留三位小数)，-1 表示不限制")
    private BigDecimal knowledgeStorageLimitGb;

    @Schema(description = "可创建数据表数量，-1 表示不限制")
    private Integer maxDataTableCount;

    @Schema(description = "可创建定时任务数量，-1 表示不限制")
    private Integer maxScheduledTaskCount;

    @Schema(description = "是否允许 API 外部调用，1-允许，0-不允许")
    private Integer allowApiExternalCall;

    @Schema(description = "智能体电脑 CPU 核心数")
    private Integer agentComputerCpuCores;

    @Schema(description = "智能体电脑内存(GB)")
    private Integer agentComputerMemoryGb;

    @Schema(description = "智能体电脑交换分区(GB)")
    private Integer agentComputerSwapGb;

    @Schema(description = "通用智能体执行结果文件存储天数，-1 表示不限制")
    private Integer agentFileStorageDays;

    @Schema(description = "通用智能体每天对话次数，-1 表示不限制")
    private Integer agentDailyPromptLimit;

    @Schema(description = "网页应用每天对话次数，-1 表示不限制")
    private Integer pageDailyPromptLimit;

    @Schema(description = "模型ID列表，-1 表示不限制（允许全部模型）")
    private List<Long> modelIds;

    @Schema(description = "有权限访问的智能体ID列表")
    private List<Long> agentIds;

    @Schema(description = "有权限访问的网页应用ID列表")
    private List<Long> pageAgentIds;

    @Schema(description = "有权限访问的开放 API 及频率配置（合并自角色与用户组）")
    private List<OpenApiConfig> openApiConfigs;

    @Schema(description = "有权限访问的知识库ID列表")
    private List<Long> knowledgeIds;

    @Data
    @Schema(description = "开放 API 权限配置（用户侧合并结果）")
    public static class OpenApiConfig implements Serializable {

        @Schema(description = "开放 API key")
        private String key;

        @Schema(description = "每分钟请求次数，-1 表示不限制")
        private Integer rpm;

        @Schema(description = "每天请求次数，-1 表示不限制")
        private Integer rpd;
    }

    public void checkMaxSpaceCount(Integer userSpaceCount) {
        if (noLimit(maxSpaceCount)) {
            return;
        }
        if (userSpaceCount >= maxSpaceCount) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                    "你的工作空间数量已经达到上限，当前工作空间上限数为" + maxSpaceCount);
        }
    }

    public void checkMaxAgentCount(Integer userAgentCount) {
        if (noLimit(maxAgentCount)) {
            return;
        }
        if (userAgentCount >= maxAgentCount) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                    "你的智能体数量已经达到上限，当前智能体上限数为" + maxAgentCount);
        }
    }

    public void checkMaxPageAppCount(Integer userPageAppCount) {
        if (noLimit(maxPageAppCount)) {
            return;
        }
        if (userPageAppCount >= maxPageAppCount) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                    "你的网页应用数量已经达到上限，当前网页应用上限数为" + maxPageAppCount);
        }
    }

    public void checkMaxKnowledgeCount(Integer userKnowledgeCount) {
        if (noLimit(maxKnowledgeCount)) {
            return;
        }
        if (userKnowledgeCount >= maxKnowledgeCount) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                    "你的知识库数量已经达到上限，当前知识库上限数为" + maxKnowledgeCount);
        }
    }

    public void checkMaxDataTableCount(Integer userDataTableCount) {
        if (noLimit(maxDataTableCount)) {
            return;
        }
        if (userDataTableCount >= maxDataTableCount) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                    "你的数据表数量已经达到上限，当前数据表上限数为" + maxDataTableCount);
        }
    }

    public void checkMaxScheduledTaskCount(Integer userScheduledTaskCount) {
        if (noLimit(maxScheduledTaskCount)) {
            return;
        }
        if (userScheduledTaskCount >= maxScheduledTaskCount) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                    "你的定时任务数量已经达到上限，当前定时任务上限数为" + maxScheduledTaskCount);
        }
    }

    public void checkMaxKnowledgeStorageLimitGb(BigDecimal userKnowledgeStorageLimitGb) {
        if (noLimit(maxKnowledgeCount)) {
            return;
        }
        if (userKnowledgeStorageLimitGb.compareTo(knowledgeStorageLimitGb) > 0) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                    "你的知识库存储空间已经达到上限，当前知识库存储空间上限为" + knowledgeStorageLimitGb + "GB");
        }
    }

    private boolean noLimit(Integer maxSpaceCount) {
        return maxSpaceCount == null || maxSpaceCount == -1;
    }
}
