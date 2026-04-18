package com.xspaceagi.system.application.dto.permission.export;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 与 resources/i18n/i18n-config-*.json 中单条结构一致（fieldKey、fieldValue、dataId 等 camelCase）。
 */
@Data
@JsonInclude(JsonInclude.Include.ALWAYS)
public class I18nConfigRowExportDto {

    private String type;

    private String side;

    private String module;

    private String dataId;

    private String lang;

    private String fieldKey;

    private String fieldValue;

    private String remark;
}
