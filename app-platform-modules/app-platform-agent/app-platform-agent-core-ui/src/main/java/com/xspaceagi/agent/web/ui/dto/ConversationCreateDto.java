package com.xspaceagi.agent.web.ui.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;

@Data
public class ConversationCreateDto implements Serializable {

    @Schema(description = "智能体ID")
    private Long agentId;

    @Schema(description = "用户填写的会话变量内容")
    private Map<String, Object> variables;

    @Schema(description = "开发模式")
    private boolean devMode;

    @Schema(description = "沙箱ID（用户个人电脑或云端沙箱）。传 workspacePath 时必传")
    private Long sandboxId;

    @Schema(description = "会话工作目录，仅当 sandboxId 为用户个人电脑（USER 类型沙箱）时生效")
    private String workspacePath;

    @Schema(description = "项目ID（统一项目登记 id，传后会话关联该项目并设为开发会话）")
    private Long projectId;

    @Schema(description = "项目类型（NormalProject/UserApp），projectId 非空时必传，与 projectId 配合定位项目")
    private String projectType;
}
