package com.xspaceagi.bill.sdk.dto;

import com.xspaceagi.bill.spec.enums.ResourceStatTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

@Data
@Schema(description = "资源统计记录")
public class ResourceStatDTO implements Serializable {

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "用户名")
    private String userName;

    @Schema(description = "昵称")
    private String nickName;

    @Schema(description = "手机号")
    private String phone;

    @Schema(description = "邮箱")
    private String email;

    @Schema(description = "类型")
    private ResourceStatTypeEnum type;

    @Schema(description = "目标类型")
    private String targetType;

    @Schema(description = "目标ID")
    private Long targetId;

    @Schema(description = "目标名称")
    private String targetName;

    @Schema(description = "日期")
    private String dt;

    @Schema(description = "调用次数")
    private Long callCount;

    @Schema(description = "调用失败次数")
    private Long callFailedCount;

    @Schema(description = "积分消耗")
    private BigDecimal creditAmount;

    @Schema(description = "费用金额")
    private BigDecimal feeAmount;

    @Schema(description = "缓存输入Token")
    private Long cacheInputTokens;

    @Schema(description = "输入Token")
    private Long inputTokens;

    @Schema(description = "输出Token")
    private Long outputTokens;

    @Schema(description = "扩展信息")
    private String extra;

    @Schema(description = "创建时间")
    private Date created;
}