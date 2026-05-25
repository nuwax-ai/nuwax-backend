package com.xspaceagi.bill.sdk.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;

@Data
@Schema(description = "提现处理请求")
public class WithdrawProcessRequest implements Serializable {

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "申请ID")
    private Long applicationId;

    @Schema(description = "操作类型：APPROVE-通过，REJECT-驳回，COMPLETE_PAYMENT-打款完成")
    private String action;

    @Schema(description = "驳回原因（REJECT时必填）")
    private String rejectReason;

    @Schema(description = "打款补充信息（COMPLETE_PAYMENT时填写）")
    private Map<String, Object> paymentExtra;
}
