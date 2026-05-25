package com.xspaceagi.system.application.service;

import com.xspaceagi.system.application.dto.permission.export.I18nConfigDiffSplitDto;

/**
 * i18n 配置差异比对服务
 */
public interface I18nConfigDiffService {

    /**
     * 比对两个版本的 i18n 配置 JSON（fromVersion → toVersion），分别输出新增行与变更行。
     */
    I18nConfigDiffSplitDto generateDiff(String fromVersion, String toVersion);
}
