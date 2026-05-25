package com.xspaceagi.credit.sdk.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

@Data
@Schema(description = "积分套餐信息")
public class CreditPackageDTO {

    @Schema(description = "套餐ID")
    private Long id;

    @Schema(description = "套餐名称")
    private String packageName;

    @Schema(description = "积分数量")
    private BigDecimal creditAmount;

    @Schema(description = "价格")
    private BigDecimal price;

    @Schema(description = "排序")
    private Integer sort;

    @Schema(description = "状态：0-禁用，1-启用")
    private Integer status;

    @Schema(description = "创建时间")
    private Date created;

    @Schema(description = "有效期（月）")
    private Integer period;

    @Schema(description = "更新时间")
    private Date modified;

    @Schema(description = "备注")
    private String remark;
}
