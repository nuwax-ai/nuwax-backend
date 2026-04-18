package com.xspaceagi.system.application.dto;

import com.xspaceagi.system.application.dto.permission.export.I18nConfigRowExportDto;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 按查询条件全量导出的 JSON 结构（与列表查询条件一致，忽略分页字段）。
 */
@Data
@Schema(description = "多语言配置按条件导出")
public class I18nConfigQueryExportDto {

    @Schema(description = "与 i18n-config-*.json 中单条结构一致（camelCase）")
    private List<I18nConfigRowExportDto> configs = new ArrayList<>();
}
