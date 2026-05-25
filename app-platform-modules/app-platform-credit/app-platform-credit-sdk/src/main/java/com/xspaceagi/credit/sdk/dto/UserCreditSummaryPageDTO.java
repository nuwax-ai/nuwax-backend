package com.xspaceagi.credit.sdk.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
@Schema(description = "用户积分汇总分页结果")
public class UserCreditSummaryPageDTO implements Serializable {

    @Schema(description = "记录列表")
    private List<UserCreditSummary> records;

    @Schema(description = "总记录数")
    private Long total;

    @Schema(description = "页码")
    private Integer pageNum;

    @Schema(description = "每页大小")
    private Integer pageSize;
}
