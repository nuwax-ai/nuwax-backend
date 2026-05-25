package com.xspaceagi.bill.sdk.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
@Schema(description = "订单分页结果")
public class OrderPageDTO implements Serializable {

    @Schema(description = "记录列表")
    private List<OrderDTO> records;

    @Schema(description = "总记录数")
    private Long total;

    @Schema(description = "页码")
    private Integer pageNum;

    @Schema(description = "每页大小")
    private Integer pageSize;
}
