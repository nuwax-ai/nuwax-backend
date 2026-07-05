package com.xspaceagi.agent.core.adapter.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Hook外挂脚本DTO")
public class HookScriptDto implements Serializable {

    // 比如：传的相对路径：hooks/my-script.sh，实际存放路径是 ${PROJECT_DIR}/.claude/hooks/my-script.sh
    @Schema(description = "脚本相对路径，如 hooks/my-script.sh")
    private String path;

    @Schema(description = "脚本文件内容")
    private String content;
}
