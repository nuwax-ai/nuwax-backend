package com.xspaceagi.agent.web.ui.controller.dto.git;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "Git checkout 请求（将目标版本文件检出到工作区和暂存区，HEAD 不动）")
public class GitCheckoutReq extends GitBaseReq {

    @Schema(description = "检出目标（commit hash / tag / branch）")
    private String target;
}
