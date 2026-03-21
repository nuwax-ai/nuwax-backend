package com.xspaceagi.agent.core.adapter.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotNull;
import java.io.Serializable;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "动态增加技能到工作空间请求DTO")
public class AddSkillsToWorkspaceDto implements Serializable {

    @Schema(description = "用户ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long userId;

    @NotNull
    @JsonProperty("cId")
    @Schema(description = "会话ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long cId;

    @Schema(description = "技能ID列表", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<SkillConfigDto> skillConfigs;
}
