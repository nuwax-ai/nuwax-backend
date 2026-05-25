package com.xspaceagi.bill.sdk.dto;

import com.xspaceagi.bill.spec.enums.WithdrawStatusEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Data
@Schema(description = "提现申请")
public class WithdrawApplicationDTO implements Serializable {

    @Schema(description = "申请ID")
    private Long id;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "用户名")
    private String userName;

    @Schema(description = "用户昵称")
    private String phone;

    @Schema(description = "用户邮箱")
    private String email;

    @Schema(description = "提现金额")
    private BigDecimal amount;

    @Schema(description = "平台服务费")
    private BigDecimal fee;

    @Schema(description = "到账金额")
    private BigDecimal actualAmount;

    @Schema(description = "状态")
    private WithdrawStatusEnum status;

    @Schema(description = "驳回原因")
    private String rejectReason;

    @Schema(description = "打款补充信息")
    private Map<String, Object> paymentExtra;

    @Schema(description = "关联的每日收益")
    private List<DailyRevenueDTO> revenues;

    @Schema(description = "创建时间")
    private Date created;

    @Schema(description = "修改时间")
    private Date modified;
}
