package com.xspaceagi.subscription.sdk.dto;

import com.xspaceagi.subscription.spec.enums.BizTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

@Data
@Schema(description = "用户订阅查询请求")
public class SubscriptionQueryRequest implements Serializable {

    @Schema(description = "租户ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long tenantId;

    @Schema(description = "用户ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long userId;

    @Schema(description = "业务类型过滤")
    private BizTypeEnum bizType;

    @Schema(description = "业务ID过滤")
    private String bizId;

    @Schema(description = "状态过滤：0-生效中，1-已过期，2-已取消")
    private Integer status;

    @Schema(description = "是否展示计划详细描述列表")
    private boolean showPlanDescItems;
}
