package com.xspaceagi.connector.ui.web.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 官方目录排序请求：services 为全量官方有序 service 数组（数组索引即顺序，1 起）。
 */
@Data
@Schema(description = "官方目录排序请求")
public class ProviderOrderDto {

    @Schema(description = "官方全量有序 service 数组（索引即排序，越小越靠前）")
    private List<String> services;
}
