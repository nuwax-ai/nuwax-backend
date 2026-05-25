package com.xspaceagi.bill.sdk.dto;

import com.xspaceagi.bill.spec.enums.LimitModeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

@Data
@Schema(description = "提现配置")
public class WithdrawConfigDTO implements Serializable {

    @Schema(description = "配置ID")
    private Long id;

    @Schema(description = "最低提现金额")
    private BigDecimal minAmount;

    @Schema(description = "每月提现次数限制")
    private Integer monthlyLimit;

    @Schema(description = "每日提现次数限制")
    private Integer dailyLimit;

    @Schema(description = "限制模式")
    private LimitModeEnum limitMode;

    @Schema(description = "创建时间")
    private Date created;

    @Schema(description = "修改时间")
    private Date modified;
}
