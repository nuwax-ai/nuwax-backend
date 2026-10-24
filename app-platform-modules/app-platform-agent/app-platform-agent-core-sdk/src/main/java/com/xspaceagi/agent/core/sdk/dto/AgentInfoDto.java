package com.xspaceagi.agent.core.sdk.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AgentInfoDto implements Serializable {
    @Schema(description = "智能体ID")
    private Long id; // 智能体ID

    private Long tenantId; // 商户ID

    @Schema(description = "空间ID")
    private Long spaceId; // 空间ID

    @Schema(description = "创建者ID")
    private Long creatorId; // 创建者ID

    @Schema(description = "Agent名称")
    private String name; // Agent名称

    @Schema(description = "Agent描述")
    private String description; // Agent描述

    @Schema(description = "图标地址")
    private String icon; // 图标地址

    @Schema(description = "子类型,ChatBot->ChatBot、PageApp->PageApp, TaskAgent -> General、Custom、Flow、Group")
    private String subType;

    @Schema(description = "最后编辑时间")
    private Date modified; // 更新时间

    @Schema(description = "创建时间")
    private Date created; // 创建时间

    @Schema(description = "变量")
    private List<ArgDto> variables;
}
