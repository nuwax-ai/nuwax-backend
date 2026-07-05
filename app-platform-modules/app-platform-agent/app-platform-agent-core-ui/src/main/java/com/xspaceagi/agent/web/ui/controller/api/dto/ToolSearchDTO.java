package com.xspaceagi.agent.web.ui.controller.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class ToolSearchDTO {

    @Schema(description = "开发空间ID，使用 DEV_SPACE_ID 环境变量", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long devSpaceId;

    @Schema(description = "搜索数据类型，包括 tool、skill", requiredMode = Schema.RequiredMode.REQUIRED)
    private String type;

    @Schema(description = "页码")
    private Integer page;

    @Schema(description = "每页数量")
    private Integer pageSize;

    @Schema(description = "关键字搜索")
    private String kw;
}
