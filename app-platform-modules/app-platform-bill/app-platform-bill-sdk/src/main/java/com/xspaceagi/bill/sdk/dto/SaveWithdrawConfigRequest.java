package com.xspaceagi.bill.sdk.dto;

import com.xspaceagi.bill.spec.enums.LimitModeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
@Schema(description = "保存提现配置请求")
public class SaveWithdrawConfigRequest implements Serializable {

    @Schema(description = "租户ID", hidden = true)
    private Long tenantId;

    @Schema(description = "最低提现金额")
    private BigDecimal minAmount;

    @Schema(description = "每月提现次数限制")
    private Integer monthlyLimit;

    @Schema(description = "每日提现次数限制")
    private Integer dailyLimit;

    @Schema(description = "限制模式")
    private LimitModeEnum limitMode;
}
