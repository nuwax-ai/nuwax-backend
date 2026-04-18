package com.xspaceagi.system.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

@Data
public class SpaceAddDto implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "空间名称", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Space name is required")
    private String name;

    @Schema(description = "空间类型")
    private String description;

    @Schema(description = "空间图标")
    private String icon;
}
