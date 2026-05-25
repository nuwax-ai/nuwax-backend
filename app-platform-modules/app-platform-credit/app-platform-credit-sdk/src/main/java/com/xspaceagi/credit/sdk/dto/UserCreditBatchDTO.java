package com.xspaceagi.credit.sdk.dto;

import com.xspaceagi.credit.spec.enums.CreditTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;
import java.util.Map;

@Data
@Schema(description = "用户积分批次信息")
public class UserCreditBatchDTO {

    @Schema(description = "批次ID")
    private Long id;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "批次号")
    private String batchNo;

    @Schema(description = "积分类型")
    private CreditTypeEnum creditType;

    @Schema(description = "积分类型名称")
    private String creditTypeName;

    @Schema(description = "总积分")
    private BigDecimal totalAmount;

    @Schema(description = "已使用积分")
    private BigDecimal usedAmount;

    @Schema(description = "剩余积分")
    private BigDecimal remainAmount;

    @Schema(description = "过期时间")
    private Date expireTime;

    @Schema(description = "是否已过期")
    private Boolean expired;

    @Schema(description = "创建时间")
    private Date created;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "扩展信息")
    private Map<String, Object> extra;
}
