package com.xspaceagi.bill.sdk.dto;

import com.xspaceagi.bill.spec.enums.RevenueTargetTypeEnum;
import com.xspaceagi.bill.spec.enums.RevenueTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Map;

@Data
@Schema(description = "增加收益请求")
public class AddRevenueRequest implements Serializable {

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "业务单号（幂等性保证，相同bizNo不会重复记录）")
    private String bizNo;

    @Schema(description = "金额")
    private BigDecimal amount;

    @Schema(description = "收益类型")
    private RevenueTypeEnum type;

    @Schema(description = "类型关联ID（Plan时为订阅计划ID）")
    private Long typeId;

    @Schema(description = "关联订单ID")
    private Long orderId;

    @Schema(description = "目标类型")
    private RevenueTargetTypeEnum targetType;

    @Schema(description = "目标ID")
    private Long targetId;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "扩展字段")
    private Map<String, Object> extra;
}
