package com.xspaceagi.subscription.sdk.dto;

import com.xspaceagi.subscription.spec.enums.BizTypeEnum;
import com.xspaceagi.subscription.spec.enums.PlanPeriodEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

@AllArgsConstructor
@NoArgsConstructor
@Builder
@Data
@Schema(description = "订阅计划信息")
public class PlanDTO implements Serializable {

    @Schema(description = "计划ID")
    private Long id;

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "计划名称")
    private String name;

    @Schema(description = "计划描述")
    private String description;

    @Schema(description = "价格")
    private BigDecimal price;

    @Schema(description = "首次订阅价格")
    private BigDecimal firstPrice;

    @Schema(description = "周期：1-月，3-季度，12-年")
    private PlanPeriodEnum period;

    @Schema(description = "每月赠送积分")
    private BigDecimal creditAmount;

    @Schema(description = "可调用次数，-1表示不限制")
    private Integer callLimitCount;

    @Schema(description = "是否仅为功能订阅（true时资源消耗费用另计）")
    private Boolean functionOnly;

    //从全局配置读取，无需字段存储
    @Schema(description = "每日登录赠送积分")
    private BigDecimal dailyGiftCreditAmount;

    @Schema(description = "是否热门")
    private Boolean isHot;

    @Schema(description = "状态：0-下线，1-上线")
    private Integer status;

    @Schema(description = "业务类型：SYSTEM-系统，AGENT-智能体，SKILL-技能")
    private BizTypeEnum bizType;

    @Schema(description = "业务对象ID，非SYSTEM时必填")
    private String bizId;

    @Schema(description = "关联用户组ID（JSON数组）")
    private List<Long> groupIds;

    @Schema(description = "扩展字段（JSON）")
    private Map<String, Object> extra;

    @Schema(description = "排序，越小越靠前，前端支持拖拽")
    private Integer sort;

    @Schema(description = "创建时间")
    private Date created;

    @Schema(description = "修改时间")
    private Date modified;

    @Schema(description = "订阅计划的权限分组项")
    private List<PlanItemGroupDTO> itemGroups;
}
