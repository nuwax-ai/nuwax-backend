package com.xspaceagi.system.application.service;


import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xspaceagi.system.application.dto.I18nConfigDto;
import com.xspaceagi.system.application.dto.I18nConfigQueryDto;
import com.xspaceagi.system.application.dto.I18nConfigQueryExportDto;

import java.util.List;
import java.util.Map;

public interface I18nApplicationService {

    List<I18nConfigDto> queryI18nConfigList(Long tenantId, String type, String side, String module, String dataId, String lang);

    IPage<I18nConfigDto> queryI18nConfigPage(Long tenantId, I18nConfigQueryDto query);

    I18nConfigQueryExportDto exportI18nConfig(Long tenantId, I18nConfigQueryDto query);

    Map<String, String> querySystemLangMap(Long tenantId, String side, String lang);

    void addOrUpdateI18nConfig(I18nConfigDto I18nConfigDto);

    void batchAddOrUpdateI18nConfig(List<I18nConfigDto> I18nConfigDtos);

    void batchDeleteI18nConfig(List<I18nConfigDto> i18nConfigDtos);

    void translateForKey(Long tenantId, I18nConfigDto i18nConfigDto, String sourceLang, String targetLang);

    void translateForKeysBatch(Long tenantId, List<I18nConfigDto> sourceItems, String sourceLang, String targetLang);
}
