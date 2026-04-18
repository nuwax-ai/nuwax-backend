package com.xspaceagi.system.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * 语言排序更新请求 DTO
 */
@Data
@Schema(description = "语言排序更新请求")
public class I18nLangSortDto implements Serializable {

    @NotNull(message = "Sort parameters are required")
    @Schema(description = "排序参数，key: 语言 ID, value: 排序值", requiredMode = Schema.RequiredMode.REQUIRED)
    private Map<Long, Integer> sortMap;
}
