package com.xspaceagi.agent.core.adapter.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class PageSqlResultDTO {

    @Schema(description = "接口ID")
    private Long apiId;

    @Schema(description = "接口Schema，可用于调试")
    private String apiSchema;
}
