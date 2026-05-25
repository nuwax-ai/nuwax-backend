package com.xspaceagi.bill.sdk.dto;

import com.xspaceagi.bill.spec.enums.RevenueTargetTypeEnum;
import com.xspaceagi.bill.spec.enums.RevenueTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

@Data
@Schema(description = "收益查询请求")
public class RevenueQueryRequest implements Serializable {

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "日期")
    private String dt;

    @Schema(description = "收益类型")
    private RevenueTypeEnum type;

    @Schema(description = "目标类型")
    private RevenueTargetTypeEnum targetType;

    @Schema(description = "目标ID")
    private Long targetId;

    @Schema(description = "页码")
    private Integer pageNum;

    @Schema(description = "每页大小")
    private Integer pageSize;
}
