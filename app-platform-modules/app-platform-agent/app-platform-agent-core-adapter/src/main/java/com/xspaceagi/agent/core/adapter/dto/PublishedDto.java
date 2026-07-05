package com.xspaceagi.agent.core.adapter.dto;

import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.spec.enums.PluginTypeEnum;
import com.xspaceagi.agent.core.spec.enums.UsageScenarioEnum;
import com.xspaceagi.custompage.sdk.dto.SourceTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

@Data
public class PublishedDto implements Serializable {

    @Schema(description = "发布ID")
    private Long id;

    private Long tenantId;

    private Long spaceId;

    private Long groupId;

    @Schema(description = "目标对象（智能体、工作流、插件）ID")
    private Published.TargetType targetType;

    @Schema(description = "目标对象子类型", hidden = true)
    private Published.TargetSubType targetSubType;

    @Schema(description = "目标对象（智能体、工作流、插件）ID")
    private Long targetId;

    @Schema(description = "发布名称")
    private String name;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "图标")
    private String icon;

    @Schema(description = "扩展字段")
    private Object ext;

    @Schema(description = "适用场景列表，如 [TaskAgent, PageApp]")
    private List<UsageScenarioEnum> usageScenarios;

    private String remark;

    @Schema(description = "配置", hidden = true)
    private String config;

    @Schema(description = "智能体发布修改时间")
    private Date modified;

    @Schema(description = "智能体发布创建时间")
    private Date created;

    @Schema(description = "统计信息")
    private StatisticsDto statistics;

    @Schema(description = "发布者信息")
    private PublishUserDto publishUser;

    @Schema(description = "当前登录用户是否收藏")
    private boolean isCollect;

    @Schema(description = "发布范围", hidden = true)
    private Published.PublishScope scope;

    @Schema(description = "分类名称")
    private String category;

    @Schema(description = "是否允许复制, 1 允许")
    private Integer allowCopy;

    @Schema(description = "是否只允许模板使用, 1 允许", hidden = true)
    private Integer onlyTemplate;

    @Schema(description = "访问控制, 0 不走权限管控；1 走权限管控")
    private Integer accessControl;

    private PluginTypeEnum pluginType;

    @Schema(description = "已发布的空间ID", hidden = true)
    private List<Long> publishedSpaceIds;

    @Schema(description = "智能体类型")
    private String agentType;

    @Schema(description = "封面图")
    private String coverImg;

    @Schema(description = "封面图片来源")
    private SourceTypeEnum coverImgSourceType;

    @Schema(description = "是否需要付费")
    private boolean paymentRequired;

    @Schema(description = "价格")
    private BigDecimal price;

    @Schema(description = "价格类型, ONE_TIME 次；SECOND 秒；MILLION_TOKEN 百万Token")
    private String pricingType;

    @Schema(description = "是否已订阅，对智能体和技能有效")
    private boolean subscribed;

    @Schema(description = "是否超出调用限制")
    private boolean overCallLimit;
}
