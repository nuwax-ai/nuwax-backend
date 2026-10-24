package com.xspaceagi.agent.web.ui.controller.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "生成文件请求")
public class ComputerFileGenerateReq {

    @JsonProperty("cId")
    @Schema(description = "会话ID")
    private Long cId;

    @Schema(description = "文件名（含后缀，可为相对路径，如 report.md 或 docs/report.md）")
    private String fileName;

    @Schema(description = "文本内容")
    private String content;
}
