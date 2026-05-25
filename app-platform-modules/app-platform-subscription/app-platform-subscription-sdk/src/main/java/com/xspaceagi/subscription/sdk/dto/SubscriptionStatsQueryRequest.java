package com.xspaceagi.subscription.sdk.dto;

import com.xspaceagi.subscription.spec.enums.BizTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

@Data
@Schema(description = "订阅统计查询请求")
public class SubscriptionStatsQueryRequest implements Serializable {

    @Schema(description = "业务类型", requiredMode = Schema.RequiredMode.REQUIRED)
    private BizTypeEnum bizType;

    @Schema(description = "业务对象ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String bizId;

    @Schema(description = "页码，从1开始")
    private Integer pageNum = 1;

    @Schema(description = "每页数量")
    private Integer pageSize = 20;
}
