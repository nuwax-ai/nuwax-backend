package com.xspaceagi.bill.sdk.dto;

import com.xspaceagi.bill.spec.enums.BizTypeEnum;
import com.xspaceagi.bill.spec.enums.OrderStatusEnum;
import com.xspaceagi.bill.spec.enums.PayStatusEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "订单信息")
public class OrderDTO implements Serializable {

    @Schema(description = "订单ID")
    private Long id;

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "订单描述")
    private String description;

    @Schema(description = "业务类型")
    private BizTypeEnum bizType;

    @Schema(description = "订单状态")
    private OrderStatusEnum orderStatus;

    @Schema(description = "支付状态")
    private PayStatusEnum payStatus;

    @Schema(description = "订单金额")
    private BigDecimal amount;

    @Schema(description = "订单明细")
    private List<OrderItemDTO> items;

    @Schema(description = "扩展字段")
    private Map<String, Object> extra;

    @Schema(description = "创建时间")
    private Date created;

    @Schema(description = "修改时间")
    private Date modified;
}
