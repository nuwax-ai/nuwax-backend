package com.xspaceagi.bill.sdk.dto;

import com.xspaceagi.bill.spec.enums.WithdrawStatusEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

@Data
@Schema(description = "提现查询请求")
public class WithdrawQueryRequest implements Serializable {

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "状态")
    private WithdrawStatusEnum status;

    @Schema(description = "搜索关键词")
    private String keyword;

    @Schema(description = "页码")
    private Integer pageNum;

    @Schema(description = "每页大小")
    private Integer pageSize;
}
