package com.xspaceagi.agent.web.ui.controller.dto.git;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "Git branch 请求（创建/切换/删除共用）")
public class GitBranchReq extends GitBaseReq {

    @Schema(description = "分支名称")
    private String branchName;

    @Schema(description = "起始点（创建分支时使用）")
    private String startPoint;

    @Schema(description = "是否强制删除（删除分支时使用）")
    private Boolean force;
}
