package com.xspaceagi.system.application.service;

import com.xspaceagi.system.application.dto.permission.export.I18nConfigExportDto;

import java.util.List;

public interface I18nExportService {

    /**
     * 从默认租户（tenant_id=1）导出 i18n 配置。
     */
    I18nConfigExportDto exportConfig(String version, List<String> langs);
}
