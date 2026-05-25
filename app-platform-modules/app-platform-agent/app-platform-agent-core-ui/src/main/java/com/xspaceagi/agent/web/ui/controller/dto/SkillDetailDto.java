package com.xspaceagi.agent.web.ui.controller.dto;

import com.xspaceagi.agent.core.adapter.dto.PublishUserDto;
import com.xspaceagi.agent.core.adapter.dto.StatisticsDto;
import com.xspaceagi.agent.core.adapter.dto.SkillFileDto;
import com.xspaceagi.agent.core.spec.enums.UsageScenarioEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

@Data
public class SkillDetailDto implements Serializable {

    @Schema(description = "技能ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;

    @Schema(description = "技能名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @Schema(description = "图标")
    private String icon;

    @Schema(description = "版本信息")
    private String remark;

    @Schema(description = "技能描述")
    private String description;

    @Schema(description = "技能文件列表")
    private List<SkillFileDto> files;

    @Schema(description = "扩展字段")
    private Object ext;

    @Schema(description = "适用场景列表，如 [TaskAgent, PageApp]")
    private List<UsageScenarioEnum> usageScenarios;

    @Schema(description = "是否收藏")
    private boolean isCollect;

    @Schema(description = "发布者信息")
    private PublishUserDto publishUser;

    @Schema(description = "统计信息")
    private StatisticsDto statistics;

    @Schema(description = "是否允许复制, 1 允许")
    private Integer allowCopy;

    private Date created;

    @Schema(description = "技能分类")
    private String category;

    @Schema(description = "技能付费状态")
    private boolean paymentRequired;

    @Schema(description = "技能订阅状态")
    private boolean subscribed;
}
