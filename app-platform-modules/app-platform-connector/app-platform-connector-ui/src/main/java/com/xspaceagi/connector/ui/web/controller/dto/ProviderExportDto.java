package com.xspaceagi.connector.ui.web.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 管理侧导出请求：services 为空/缺省时导出全部官方。
 */
@Data
@Schema(description = "管理侧批量导出请求")
public class ProviderExportDto {

    @Schema(description = "要导出的 service 列表；空/缺省 = 全部官方")
    private List<String> services;
}
