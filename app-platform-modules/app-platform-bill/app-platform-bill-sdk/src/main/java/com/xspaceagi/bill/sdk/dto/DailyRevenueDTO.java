package com.xspaceagi.bill.sdk.dto;

import com.xspaceagi.bill.spec.enums.RevenueStatusEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

@Data
@Schema(description = "每日收益")
public class DailyRevenueDTO implements Serializable {

    @Schema(description = "收益ID")
    private Long id;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "用户昵称")
    private String nickName;

    @Schema(description = "用户名称")
    private String userName;

    @Schema(description = "手机号码")
    private String phone;

    @Schema(description = "邮箱")
    private String email;

    @Schema(description = "日期")
    private String dt;

    @Schema(description = "收益金额")
    private BigDecimal amount;

    @Schema(description = "结算状态")
    private RevenueStatusEnum status;

    @Schema(description = "创建时间")
    private Date created;
}
