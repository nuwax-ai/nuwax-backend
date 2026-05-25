package com.xspaceagi.bill.sdk.dto;

import com.xspaceagi.bill.spec.enums.BizTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Data
@Schema(description = "创建订单请求")
public class CreateOrderRequest implements Serializable {

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "订单描述")
    private String description;

    @Schema(description = "业务类型")
    private BizTypeEnum bizType;

    @Schema(description = "订单项列表")
    private List<CreateOrderItem> items;

    @Schema(description = "扩展字段")
    private Map<String, Object> extra;

    @Data
    @Schema(description = "订单项")
    public static class CreateOrderItem implements Serializable {

        @Schema(description = "目标类型")
        private String targetType;

        @Schema(description = "目标名称")
        private String targetName;

        @Schema(description = "目标ID")
        private Long targetId;

        @Schema(description = "单价")
        private java.math.BigDecimal price;

        @Schema(description = "数量")
        private Integer count;

        @Schema(description = "快照信息")
        private Map<String, Object> snapshot;
    }
}
