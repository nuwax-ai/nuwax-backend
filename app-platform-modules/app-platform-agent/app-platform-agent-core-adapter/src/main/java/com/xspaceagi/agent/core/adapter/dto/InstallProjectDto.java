package com.xspaceagi.agent.core.adapter.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "安装项目依赖请求DTO")
public class InstallProjectDto implements Serializable {

    @Schema(description = "用户ID", hidden = true)
    private Long userId;

    @Schema(description = "会话ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @JsonProperty("cId")
    private Long cId;

    @Schema(description = "编程语言", requiredMode = Schema.RequiredMode.REQUIRED)
    private InitProjectTemplateDto.ProgrammingLanguage programmingLanguage;

}
