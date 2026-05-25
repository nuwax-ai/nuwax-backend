package com.xspaceagi.subscription.sdk.dto;

import com.xspaceagi.subscription.spec.enums.BizTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@AllArgsConstructor
@NoArgsConstructor
@Builder
@Data
@Schema(description = "订阅计划查询请求")
public class PlanQueryRequest implements Serializable {

    @Schema(description = "租户ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long tenantId;

    @Schema(description = "业务类型过滤")
    private BizTypeEnum bizType;

    @Schema(description = "业务对象ID过滤")
    private String bizId;

    @Schema(description = "状态过滤：0-下线，1-上线")
    private Integer status;

    @Schema(description = "关键词搜索（名称）")
    private String keyword;
}
