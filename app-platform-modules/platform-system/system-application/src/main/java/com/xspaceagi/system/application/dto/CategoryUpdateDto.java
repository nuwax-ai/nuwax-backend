package com.xspaceagi.system.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 分类更新请求DTO
 */
@Data
@Schema(description = "分类更新请求")
public class CategoryUpdateDto {

    @NotNull(message = "ID is required")
    @Schema(description = "分类ID", required = true)
    private Long id;

    @Schema(description = "分类名称")
    private String name;

    @Schema(description = "分类编码")
    private String code;

    @Schema(description = "分类类型：Agent、PageApp、Component")
    private String type;

    @Schema(description = "分类描述")
    private String description;
}
