package com.xspaceagi.agent.core.adapter.dto;

import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

@Data
public class ResourceGroupDto implements Serializable {

    private Long id;

    private Long spaceId;

    private String name;

    private String description;

    private String icon;

    private Integer toolCount;

    private String type;

    @Schema(description = "目标对象（智能体、工作流、插件）类型,分组中第一个工具的类型")
    private Published.TargetType targetType;

    @Schema(description = "目标对象（工作流、插件）ID,分组中第一个工具的ID")
    private Long targetId;

    private Date created;

    private Date modified;

    @Schema(description = "是否需要付费")
    private boolean paymentRequired;

    @Schema(description = "发布者信息")
    private PublishUserDto publishUser;

    @Schema(description = "工具列表，选择使用")
    private List<PublishedDto> tools;
}
