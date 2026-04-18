package com.xspaceagi.system.application.service;

import com.xspaceagi.system.infra.dao.entity.Tenant;

/**
 * 权限数据导入服务
 */
public interface I18nImportService {

    /**
     * 将指定版本的配置项导入到目标租户
     */
    void importConfigToTenant(Tenant tenant, String version);

    /**
     * 将指定版本的语言包导入到目标租户
     */
    void importLangToTenant(Tenant tenant, String version);

}
