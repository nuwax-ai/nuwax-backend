package com.xspaceagi.system.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Date;

@Data
public class ApiKeyCreateDto {

    @Schema(description = "名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;
    @Schema(description = "过期时间")
    private Date expire;
}
