package com.xspaceagi.agent.web.ui.dto;

import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
public class ComponentQueryDto {

    @Schema(description = "空间ID")
    private Long spaceId;

    @Schema(description = "分组ID")
    private Long groupId;

    @Schema(description = "组件类型")
    private List<Published.TargetType> types;
}
