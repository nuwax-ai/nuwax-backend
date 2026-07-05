package com.xspaceagi.agent.web.ui.controller.dto.git;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "Git log 请求")
public class GitLogReq extends GitBaseReq {

    @Schema(description = "页码，从1开始")
    private Integer page;

    @Schema(description = "每页条数")
    private Integer pageSize;

    @Schema(description = "分支名称")
    private String branch;

    @Schema(description = "文件相对路径，指定时只返回该文件的提交历史")
    private String filePath;
}
