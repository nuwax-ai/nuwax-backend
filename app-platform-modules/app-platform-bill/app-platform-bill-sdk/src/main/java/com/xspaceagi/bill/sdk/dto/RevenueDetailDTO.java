package com.xspaceagi.bill.sdk.dto;

import com.xspaceagi.bill.spec.enums.RevenueTargetTypeEnum;
import com.xspaceagi.bill.spec.enums.RevenueTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import java.util.Map;

@Data
@Schema(description = "收益明细")
public class RevenueDetailDTO implements Serializable {

    @Schema(description = "明细ID")
    private Long id;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "日期")
    private String dt;

    @Schema(description = "金额")
    private BigDecimal amount;

    @Schema(description = "类型")
    private RevenueTypeEnum type;

    @Schema(description = "类型关联ID")
    private Long typeId;

    @Schema(description = "关联订单ID")
    private Long orderId;

    @Schema(description = "目标类型")
    private RevenueTargetTypeEnum targetType;

    @Schema(description = "目标ID")
    private Long targetId;

    @Schema(description = "业务单号（幂等性保证）")
    private String bizNo;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "扩展字段")
    private Map<String, Object> extra;

    @Schema(description = "创建时间")
    private Date created;
}
