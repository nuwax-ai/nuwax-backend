package com.xspaceagi.system.application.dto.permission.export;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * i18n 配置版本比对结果
 */
@Data
public class I18nConfigDiffSplitDto {

    private List<I18nConfigRowExportDto> addRows = new ArrayList<>();

    private List<I18nConfigRowExportDto> updateRows = new ArrayList<>();
}
