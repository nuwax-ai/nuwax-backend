package com.xspaceagi.system.application.dto.permission.export;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class I18nConfigExportDto {

    /**
     * 版本号，与app.version对应，如 1.0.5
     */
    private String version;

    private List<I18nConfigRowExportDto> configs = new ArrayList<>();

}
