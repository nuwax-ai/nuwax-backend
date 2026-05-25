package com.xspaceagi.bill.sdk.dto;

import com.xspaceagi.bill.spec.enums.TargetTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import java.util.Map;

@Data
@Schema(description = "订单明细")
public class OrderItemDTO implements Serializable {

    @Schema(description = "明细ID")
    private Long id;

    @Schema(description = "订单ID")
    private Long orderId;

    @Schema(description = "目标类型")
    private TargetTypeEnum targetType;

    @Schema(description = "目标名称")
    private String targetName;

    @Schema(description = "目标ID")
    private Long targetId;

    @Schema(description = "单价")
    private BigDecimal price;

    @Schema(description = "数量")
    private Integer count;

    @Schema(description = "快照信息")
    private Map<String, Object> snapshot;

    @Schema(description = "创建时间")
    private Date created;
}
