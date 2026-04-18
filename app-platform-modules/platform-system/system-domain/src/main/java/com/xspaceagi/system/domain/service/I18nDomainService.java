package com.xspaceagi.system.domain.service;


import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xspaceagi.system.infra.dao.entity.I18nConfig;
import com.xspaceagi.system.infra.dao.entity.I18nLang;

import java.util.Collection;
import java.util.List;

public interface I18nDomainService {

    IPage<I18nConfig> queryI18nConfigPage(Long tenantId, String type, String side, String module, String dataId, String lang, String fieldKey, String fieldValue, Long pageNo, Long pageSize);

    List<I18nConfig> queryI18nConfigList(Long tenantId, String type, String side, String module, String dataId, String lang, String fieldKey, String fieldValue);

    long countI18nConfigList(Long tenantId, String type, String side, String module, String dataId, String lang, String fieldKey, String fieldValue);

    I18nConfig queryByKey(String lang, String fieldKey);

    /**
     * 按租户 + 语言 + 一批 fieldKey 查询配置（用于批量判断目标语言是否已有值）
     */
    List<I18nConfig> listByTenantLangAndFieldKeys(Long tenantId, String lang, Collection<String> fieldKeys);

    void addOrUpdate(I18nConfig i18nConfig);

    void addOrUpdateBatch(List<I18nConfig> i18nConfigs);

    void batchDelete(List<I18nConfig> i18nConfigs);

    void deleteByLang(Long tenantId, String lang);

    void initConfigsForNewLang(Long tenantId, String lang);

    /**
     * 在所有语言下新增或更新配置
     */
    void addOrUpdateInAllLangs(List<I18nLang> allLangs, I18nConfig i18nConfig);

    /**
     * 查询所有语言
     */
    List<I18nLang> queryAllLangs();
}
