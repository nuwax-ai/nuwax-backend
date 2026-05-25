package com.xspaceagi.subscription.sdk.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
@Schema(description = "订阅统计")
public class SubscriptionStatsDTO implements Serializable {

    @Schema(description = "总订阅数")
    private Long totalCount;

    @Schema(description = "今日新订阅数")
    private Long todayCount;

    @Schema(description = "本月新订阅数")
    private Long monthCount;

    @Schema(description = "订阅用户列表")
    private List<UserSubscriptionDTO> subscribers;

    @Schema(description = "总记录数（分页）")
    private Long total;

    @Schema(description = "当前页码")
    private Integer pageNum;

    @Schema(description = "每页数量")
    private Integer pageSize;
}
