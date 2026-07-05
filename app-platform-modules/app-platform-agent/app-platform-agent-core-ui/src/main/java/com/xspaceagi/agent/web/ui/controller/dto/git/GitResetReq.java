package com.xspaceagi.agent.web.ui.controller.dto.git;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "Git reset 请求")
public class GitResetReq extends GitBaseReq {

    @Schema(description = "重置目标（commit hash / tag / branch）")
    private String target;

    @Schema(description = "重置模式: soft / mixed / hard（默认 mixed）")
    private String mode;
}
