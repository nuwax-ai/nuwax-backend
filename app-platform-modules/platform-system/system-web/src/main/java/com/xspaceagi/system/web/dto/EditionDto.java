package com.xspaceagi.system.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 当前产品版本，供前端识别部署形态（AIOS / AI 服务平台）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EditionDto {

    @Schema(description = "版本标识，小写，用于前端比较", example = "aios")
    private String edition;

    @Schema(description = "版本展示名", example = "AIOS")
    private String name;
}
