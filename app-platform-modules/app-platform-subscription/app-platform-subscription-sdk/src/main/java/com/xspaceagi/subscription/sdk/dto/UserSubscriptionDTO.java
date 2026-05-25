package com.xspaceagi.subscription.sdk.dto;

import com.xspaceagi.subscription.spec.enums.BizTypeEnum;
import com.xspaceagi.subscription.spec.enums.PlanPeriodEnum;
import com.xspaceagi.subscription.spec.enums.SubscriptionStatusEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;
import java.util.Map;

@Data
@Schema(description = "用户订阅信息")
public class UserSubscriptionDTO implements Serializable {

    @Schema(description = "订阅记录ID")
    private Long id;

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "计划ID")
    private Long planId;

    @Schema(description = "计划名称")
    private String planName;

    private BizTypeEnum bizType;

    private String bizId;

    @Schema(description = "业务对象名称")
    private String bizName;

    @Schema(description = "图标")
    private String icon;

    @Schema(description = "周期：1-月，3-季度，12-年")
    private PlanPeriodEnum period;

    @Schema(description = "开始时间")
    private Date startTime;

    @Schema(description = "结束时间，为空时为买断，永不过期")
    private Date endTime;

    @Schema(description = "状态：0-生效中，1-已过期，2-已取消")
    private SubscriptionStatusEnum status;

    @Schema(description = "计划订阅时的快照")
    private PlanDTO plan;

    @Schema(description = "已使用调用次数")
    private Integer callUsedCount;

    @Schema(description = "下次重置时间（每月重置）")
    private Date nextResetTime;

    @Schema(description = "扩展字段，记录订阅快照等")
    private Map<String, Object> extra;

    @Schema(description = "创建时间")
    private Date created;

    @Schema(description = "修改时间")
    private Date modified;

    @Schema(description = "订阅者信息")
    private Subscriber subscriber;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Subscriber {
        @Schema(description = "订阅者ID")
        private Long id;
        @Schema(description = "订阅者名称")
        private String name;
        @Schema(description = "订阅者头像")
        private String avatar;
    }
}
