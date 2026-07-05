package com.xspaceagi.agent.web.ui.controller.dto.git;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "Git commit 请求")
public class GitCommitReq extends GitBaseReq {

    @Schema(description = "提交信息")
    private String message;

    @Schema(description = "提交文件列表")
    private List<String> files;
}
