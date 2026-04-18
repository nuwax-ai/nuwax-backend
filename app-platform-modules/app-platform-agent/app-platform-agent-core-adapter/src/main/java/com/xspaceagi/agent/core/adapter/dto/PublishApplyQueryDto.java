package com.xspaceagi.agent.core.adapter.dto;

import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

@Data
public class PublishApplyQueryDto implements Serializable {

    @Schema(description = "发布类型")
    @NotNull(message = "targetType is required; use Agent for agents and apps, or omit")
    private Published.TargetType targetType;

    @Schema(description = "发布子类型类型：PageApp 和 Agent")
    private Published.TargetSubType targetSubType;

    @Schema(description = "发布状态")
    private Published.PublishStatus publishStatus;

    @Schema(description = "关键字搜索")
    private String kw;
}
