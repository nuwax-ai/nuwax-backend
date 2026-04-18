package com.xspaceagi.agent.web.ui.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class SandboxTaskCreateResult {

    @Schema(description = "创建的新任务ID")
    private Long id;
}
