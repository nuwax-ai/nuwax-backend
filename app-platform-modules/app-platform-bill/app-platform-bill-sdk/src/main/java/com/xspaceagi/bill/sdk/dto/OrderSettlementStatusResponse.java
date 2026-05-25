package com.xspaceagi.bill.sdk.dto;

import com.xspaceagi.bill.spec.enums.OrderStatusEnum;
import com.xspaceagi.bill.spec.enums.PayStatusEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 支付中间结算页轮询用的订单结算状态。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "订单结算状态")
public class OrderSettlementStatusResponse implements Serializable {

    @Schema(description = "订单 ID")
    private Long orderId;

    @Schema(description = "支付状态")
    private PayStatusEnum payStatus;

    @Schema(description = "订单状态")
    private OrderStatusEnum orderStatus;

    @Schema(description = "是否已完成业务结算（支付成功且积分/订阅等已处理）")
    private boolean settled;

    @Schema(description = "是否已到达失败/关单等终态（不可再等待成功）")
    private boolean terminalFailed;

    @Schema(description = "给结算页展示的简短说明")
    private String message;
}
