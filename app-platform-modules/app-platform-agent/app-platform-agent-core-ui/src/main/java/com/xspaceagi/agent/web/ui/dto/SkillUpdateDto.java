package com.xspaceagi.agent.web.ui.dto;

import com.xspaceagi.agent.core.adapter.dto.SkillFileDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
@Schema(description = "技能更新请求DTO")
public class SkillUpdateDto implements Serializable {

    @Schema(description = "技能ID")
    private Long id;

    @Schema(description = "技能名称")
    private String name;

    @Schema(description = "技能描述")
    private String description;

    @Schema(description = "技能图标")
    private String icon;

    @Schema(description = "文件内容")
    private List<SkillFileDto> files;
}