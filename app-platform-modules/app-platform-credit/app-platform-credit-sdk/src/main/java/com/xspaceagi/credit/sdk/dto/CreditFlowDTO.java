package com.xspaceagi.credit.sdk.dto;

import com.xspaceagi.credit.spec.enums.CreditTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

@Data
@Schema(description = "积分流水信息")
public class CreditFlowDTO {

    @Schema(description = "流水ID")
    private Long id;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "批次号")
    private String batchNo;

    @Schema(description = "积分类型")
    private CreditTypeEnum creditType;

    @Schema(description = "积分类型名称")
    private String creditTypeName;

    @Schema(description = "操作类型：1-增加，2-扣减")
    private Integer operationType;

    @Schema(description = "操作类型名称")
    private String operationTypeName;

    @Schema(description = "积分数量")
    private BigDecimal amount;

    @Schema(description = "操作前积分")
    private BigDecimal beforeAmount;

    @Schema(description = "操作后积分")
    private BigDecimal afterAmount;

    @Schema(description = "业务单号")
    private String bizNo;

    @Schema(description = "创建时间")
    private Date created;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "用户信息")
    private CreditUser user;
}
