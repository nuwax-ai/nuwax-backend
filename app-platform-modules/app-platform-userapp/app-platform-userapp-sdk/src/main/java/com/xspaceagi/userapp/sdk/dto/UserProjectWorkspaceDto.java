package com.xspaceagi.userapp.sdk.dto;

import java.io.Serializable;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 用户×项目 工作目录绑定 */
@Data
@Schema(description = "用户项目工作目录绑定")
public class UserProjectWorkspaceDto implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "项目类型")
    private String projectType;

    @Schema(description = "项目ID（常规项目为主键；全栈应用为 appId）")
    private Long projectId;

    @Schema(description = "用户级文件工作目录")
    private String fileWorkspacePath;

    @Schema(description = "用户级 agent 执行路径")
    private String agentWorkspacePath;

    @Schema(description = "沙箱ID（目录所在机器）")
    private Long sandboxId;
}
