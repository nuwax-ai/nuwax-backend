package com.xspaceagi.userapp.web.dto;

import java.io.Serializable;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "查询当前用户在指定项目列表中的开发会话请求")
public class UserProjectConversationsReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "项目列表（去重后逐项查询当前用户的会话）", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<ProjectSelector> projects;

    @Data
    @Schema(description = "项目定位：项目类型 + 项目ID")
    public static class ProjectSelector implements Serializable {

        private static final long serialVersionUID = 1L;

        @Schema(description = "项目ID",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private Long projectId;

        @Schema(description = "项目类型（如 UserApp/NormalProject）",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private String projectType;
    }
}
