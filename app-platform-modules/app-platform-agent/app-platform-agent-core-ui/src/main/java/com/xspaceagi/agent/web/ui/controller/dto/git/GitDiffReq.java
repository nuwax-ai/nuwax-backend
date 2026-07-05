package com.xspaceagi.agent.web.ui.controller.dto.git;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "Git diff 请求")
public class GitDiffReq extends GitBaseReq {

    @Schema(description = "起始提交/引用")
    private String from;

    @Schema(description = "目标提交/引用")
    private String to;

    @Schema(description = "对比来源: worktree(工作区vs HEAD), staged(暂存区vs HEAD), commit(两个commit之间)")
    private String source;

    @Schema(description = "文件路径列表")
    private List<String> paths;
}
