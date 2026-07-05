package com.xspaceagi.agent.web.ui.controller.dto.git;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "Git 文件内容请求")
public class GitFileContentReq extends GitBaseReq {

    @Schema(description = "git 引用（commit hash / 分支名 / HEAD / HEAD~1 / worktree / staged）")
    private String ref;

    @Schema(description = "文件相对路径")
    private String filePath;
}
