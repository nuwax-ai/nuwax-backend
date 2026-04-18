package com.xspaceagi.system.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

@Data
public class SpaceUpdateDto implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "空间ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Space ID is required")
    private Long id;

    @Schema(description = "空间名称")
    private String name;

    @Schema(description = "空间类型")
    private String description;

    @Schema(description = "空间图标")
    private String icon;

    @Schema(description = "空间是否接收来自外部的发布")
    private Integer receivePublish;

    @Schema(description = "空间是否开启开发功能")
    private Integer allowDevelop;
}
