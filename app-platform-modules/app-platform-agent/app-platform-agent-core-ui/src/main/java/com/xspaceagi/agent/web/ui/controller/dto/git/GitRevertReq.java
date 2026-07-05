package com.xspaceagi.agent.web.ui.controller.dto.git;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "Git revert 请求（新建 commit 使文件树回退到 target 版本，保留完整历史）")
public class GitRevertReq extends GitBaseReq {

    @Schema(description = "回退目标（commit hash / tag / branch）", required = true)
    private String target;

    @Schema(description = "自定义 commit message，为空时默认 'Revert to <short-hash>'")
    private String message;
}
