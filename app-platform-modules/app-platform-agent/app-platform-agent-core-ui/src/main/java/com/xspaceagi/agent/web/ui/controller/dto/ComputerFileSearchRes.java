package com.xspaceagi.agent.web.ui.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "搜索文件响应")
public class ComputerFileSearchRes {

    @Schema(description = "命中项列表")
    private Object files;

    @Schema(description = "true 表示截断扫描，比如触达 limit / maxVisit / 超时")
    private Object truncated;

    @Schema(description = "本次搜索实际扫描的文件/目录节点数")
    private Object visited;
}
