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

    /**
     * 将指定版本的新增差异配置导入到目标租户（仅插入，已存在则跳过）
     */
    void addConfigToTenant(Tenant tenant, String version);

    /**
     * 将指定版本的变更差异配置更新到目标租户（仅更新，不存在则跳过）
     */
    void updateConfigToTenant(Tenant tenant, String version);

}
