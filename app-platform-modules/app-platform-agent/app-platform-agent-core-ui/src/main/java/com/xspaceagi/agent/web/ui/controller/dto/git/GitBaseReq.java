package com.xspaceagi.agent.web.ui.controller.dto.git;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Git 基础请求")
public class GitBaseReq {

    @Schema(description = "工作空间类型: taskAgent / pageApp")
    private String workspaceType;

    @Schema(description = "会话ID（taskAgent 模式）")
    private Long cId;

    @Schema(description = "项目ID（pageApp 模式）")
    private Long projectId;
}
