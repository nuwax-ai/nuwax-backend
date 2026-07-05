package com.xspaceagi.agent.core.adapter.dto;

import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class ProjectCreateDTO {

    @Schema(description = "项目所属空间ID，不传则默认放在个人空间,可选")
    private Long spaceId;

    @Schema(description = "目标项目类型", requiredMode = Schema.RequiredMode.REQUIRED)
    private Published.TargetType targetType;

    @Schema(description = "目标项目名称,可选")
    private String name;

    @Schema(description = "目标项目创建者ID,可选", hidden = true)
    private Long creatorId;

    @Schema(description = "编程语言选择")
    private InitProjectTemplateDto.ProgrammingLanguage programmingLanguage;
}
