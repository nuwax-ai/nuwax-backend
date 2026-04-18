package com.xspaceagi.agent.core.adapter.dto;

import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
@Schema(description = "技能列表请求DTO")
public class SkillQueryDto implements Serializable {

    @NotNull(message = "spaceId is required")
    @Schema(description = "空间ID")
    private Long spaceId;

    @Schema(description = "技能名称")
    private String name;

    @Schema(description = "发布状态")
    private List<Published.PublishStatus> publishStatus;

}