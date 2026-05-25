package com.xspaceagi.agent.web.ui.controller.dto;

import com.xspaceagi.agent.core.adapter.dto.PublishUserDto;
import com.xspaceagi.agent.core.adapter.dto.StatisticsDto;
import com.xspaceagi.agent.core.adapter.dto.config.Arg;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

@Data
public class PluginDetailDto implements Serializable {

    @Schema(description = "插件ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;

    @Schema(description = "插件名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @Schema(description = "图标")
    private String icon;

    @Schema(description = "版本信息")
    private String remark;

    @Schema(description = "插件描述")
    private String description;

    @Schema(description = "插件输入参数", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<Arg> inputArgs;

    @Schema(description = "插件输出参数", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<Arg> outputArgs;

    @Schema(description = "插件示例输出")
    private String sampleOutput;

    @Schema(description = "是否收藏")
    private boolean isCollect;

    @Schema(description = "发布者信息")
    private PublishUserDto publishUser;

    @Schema(description = "统计信息")
    private StatisticsDto statistics;

    @Schema(description = "是否允许复制, 1 允许")
    private Integer allowCopy;

    private Date created;

    @Schema(description = "插件分类")
    private String category;

    @Schema(description = "是否需要付费")
    private boolean paymentRequired;

    @Schema(description = "价格")
    private BigDecimal price;
}