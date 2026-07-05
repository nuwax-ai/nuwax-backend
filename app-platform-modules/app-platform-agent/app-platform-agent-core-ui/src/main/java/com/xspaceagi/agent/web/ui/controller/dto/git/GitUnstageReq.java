package com.xspaceagi.agent.web.ui.controller.dto.git;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "Git unstage 请求")
public class GitUnstageReq extends GitBaseReq {

    @Schema(description = "取消暂存的文件列表")
    private List<String> files;
}
