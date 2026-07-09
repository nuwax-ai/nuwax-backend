package com.xspaceagi.system.infra.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xspaceagi.system.infra.dao.typehandler.TokenLimitTypeHandler;
import com.xspaceagi.system.spec.enums.PermissionTargetTypeEnum;
import com.xspaceagi.system.sdk.service.dto.TokenLimit;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 数据权限
 */
@Data
@TableName(value = "sys_data_permission", autoResultMap = true)
public class SysDataPermission {
    
    /**
     * 角色ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 目标类型
     * @see PermissionTargetTypeEnum
     */
    private Integer targetType;

    /**
     * 目标ID
     */
    private Long targetId;


    /**
     * 模型ID列表（不落库，仅用于绑定入参透传）
     */
    @TableField(exist = false)
    private List<Long> modelIds;

    /**
     * 可访问的智能体id列表（不落库，仅用于绑定入参透传）
     */
    @TableField(exist = false)
    private List<Long> agentIds;

    /**
     * 可访问的应用页面id列表（不落库，仅用于绑定入参透传）
     */
    @TableField(exist = false)
    private List<Long> pageAgentIds;

    /**
     * 开放API配置（不落库，仅用于绑定入参透传）
     */
    @TableField(exist = false)
    private Map<String, String> openApiConfigMap;

    /**
     * 可访问的知识库id列表（不落库，仅用于绑定入参透传）
     */
    @TableField(exist = false)
    private List<Long> knowledgeIds;

    // ========== 配额限制配置 ==========

    /**
     * token限制
     */
    @TableField(value = "token_limit", typeHandler = TokenLimitTypeHandler.class)
    private TokenLimit tokenLimit;

    /**
     * 可创建工作空间数量，-1 表示不限制
     */
    private Integer maxSpaceCount;

    /**
     * 可创建智能体数量，-1 表示不限制
     */
    private Integer maxAgentCount;

    /**
     * 可创建网页应用数量，-1 表示不限制
     */
    private Integer maxPageAppCount;

    /**
     * 可创建知识库数量，-1 表示不限制
     */
    private Integer maxKnowledgeCount;

    /**
     * 知识库存储空间上限(GB，保留三位小数)，-1 表示不限制
     */
    private BigDecimal knowledgeStorageLimitGb;

    /**
     * 可创建数据表数量，-1 表示不限制
     */
    private Integer maxDataTableCount;

    /**
     * 可创建定时任务数量，-1 表示不限制
     */
    private Integer maxScheduledTaskCount;

//    /**
//     * 是否允许API外部调用，1-允许，0-不允许，null 表示不允许
//     */
//    private Integer allowApiExternalCall;

    /**
     * 智能体电脑CPU核心数
     */
    private Integer agentComputerCpuCores;

    /**
     * 智能体电脑内存(GB)
     */
    private Integer agentComputerMemoryGb;

    /**
     * 智能体电脑交换分区(GB)
     */
    private Integer agentComputerSwapGb;

    /**
     * 智能体电脑存储上限(GB，保留三位小数)，-1 表示不限制
     */
    private BigDecimal agentComputerStorageLimitGb;

    /**
     * 网页应用存储上限(GB，保留三位小数)，-1 表示不限制
     */
    private BigDecimal pageAppStorageLimitGb;

    /**
     * 通用智能体执行结果文件存储天数(仅云端电脑受限)，-1 表示不限制
     */
    private Integer agentFileStorageDays;

    /**
     * 通用智能体每天对话次数(含编排调试，问答智能体不限)，-1 表示不限制
     */
    private Integer agentDailyPromptLimit;

    /**
     * 网页应用每天对话次数，-1 表示不限制
     */
    private Integer pageDailyPromptLimit;

    // ========== 审计字段 ==========

    /**
     * 租户ID
     */
    @TableField(value = "_tenant_id")
    private Long tenantId;
    
    /**
     * 创建人ID
     */
    private Long creatorId;
    
    /**
     * 创建人
     */
    private String creator;
    
    /**
     * 创建时间
     */
    private Date created;
    
    /**
     * 修改人ID
     */
    private Long modifierId;
    
    /**
     * 修改人
     */
    private String modifier;
    
    /**
     * 修改时间
     */
    private Date modified;
    
    /**
     * 是否有效；1：有效，-1：无效
     */
    private Integer yn;
}

