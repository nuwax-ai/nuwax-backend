package com.xspaceagi.system.sdk.service.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 分类DTO
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "分类信息")
public class CategoryDto {

    @Schema(description = "主键ID")
    private Long id;

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "分类名称")
    private String name;

    @Schema(description = "分类描述")
    private String description;

    @Schema(description = "分类图标地址")
    private String icon;

    @Schema(description = "分类编码")
    private String code;

    @Schema(description = "分类类型：Agent、PageApp、Component、ChatBox、Connector、Skill")
    private String type;

    @Schema(description = "创建时间")
    private Date created;

    @Schema(description = "修改时间")
    private Date modified;
}