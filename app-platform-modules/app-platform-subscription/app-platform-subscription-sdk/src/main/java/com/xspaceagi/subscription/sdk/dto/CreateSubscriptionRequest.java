package com.xspaceagi.subscription.sdk.dto;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.subscription.spec.enums.BizTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

@NoArgsConstructor
@AllArgsConstructor
@Builder
@Data
@Schema(description = "创建订阅请求")
public class CreateSubscriptionRequest implements Serializable {

    @Schema(description = "租户ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long tenantId;

    @Schema(description = "用户ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long userId;

    @Schema(description = "计划ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long planId;

    @Schema(description = "业务类型")
    private BizTypeEnum bizType;

    @Schema(description = "扩展信息，记录订阅快照等")
    private Map<String, Object> extra;

    public String getExtraJsonString() {
        return extra == null ? null : JSON.toJSONString(extra);
    }
}
