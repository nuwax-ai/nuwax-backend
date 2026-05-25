package com.xspaceagi.bill.sdk.dto;

import com.xspaceagi.bill.spec.enums.BizTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

@Data
@Schema(description = "订单查询请求")
public class OrderQueryRequest implements Serializable {

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "订单ID")
    private Long orderId;

    @Schema(description = "业务类型")
    private BizTypeEnum bizType;

    @Schema(description = "订单状态")
    private String orderStatus;

    @Schema(description = "支付状态")
    private String payStatus;

    @Schema(description = "搜索关键词（用户名）")
    private String keyword;

    @Schema(description = "开始时间")
    private String startTime;

    @Schema(description = "结束时间")
    private String endTime;

    @Schema(description = "页码")
    private Integer pageNum;

    @Schema(description = "每页大小")
    private Integer pageSize;
}
