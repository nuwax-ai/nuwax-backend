package com.xspaceagi.agent.web.ui.controller.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import javax.validation.constraints.NotNull;
import java.util.List;

@Data
@Schema(description = "文件元数据批量查询请求")
public class ComputerFileMetaReq {

    @NotNull
    @JsonProperty("cId")
    @Schema(description = "会话ID")
    private Long cId;

    @NotNull
    @Schema(description = "相对工作区根的路径数组（通常为 file-list 返回的 name）；单次最多 100 条")
    private List<String> filePaths;
}
