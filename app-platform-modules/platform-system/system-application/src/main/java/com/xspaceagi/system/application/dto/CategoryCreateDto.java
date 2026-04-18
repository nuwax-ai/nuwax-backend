package com.xspaceagi.system.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 分类创建请求DTO
 */
@Data
@Schema(description = "分类创建请求")
public class CategoryCreateDto {

    @NotBlank(message = "Category name is required")
    @Schema(description = "分类名称", required = true)
    private String name;

    @NotBlank(message = "Category code is required")
    @Schema(description = "分类编码", required = true)
    private String code;

    @NotBlank(message = "Category type is required")
    @Schema(description = "分类类型：Agent、PageApp、Component", required = true)
    private String type;

    @Schema(description = "分类描述")
    private String description;
}
