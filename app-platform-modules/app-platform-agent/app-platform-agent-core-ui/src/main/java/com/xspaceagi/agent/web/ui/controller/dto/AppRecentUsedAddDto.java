package com.xspaceagi.agent.web.ui.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "记录应用最近使用请求")
public class AppRecentUsedAddDto {

    @Schema(description = "项目ID")
    private Long projectId;

    @Schema(description = "项目类型：UserApp=全栈应用、ThirdApp=三方应用")
    private String projectType;
}
