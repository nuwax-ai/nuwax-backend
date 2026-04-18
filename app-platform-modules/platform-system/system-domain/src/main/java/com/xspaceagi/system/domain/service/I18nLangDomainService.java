package com.xspaceagi.system.domain.service;

import com.xspaceagi.system.infra.dao.entity.I18nLang;

import java.util.List;
import java.util.Map;

/**
 * 语言表领域服务
 */
public interface I18nLangDomainService {

    /**
     * 新增语言
     *
     * @param i18nLang 语言信息
     */
    void add(I18nLang i18nLang);

    /**
     * 删除语言
     *
     * @param id 语言 ID
     */
    void delete(Long id);

    /**
     * 更新语言（可修改名字、状态、默认状态、排序，不能修改 lang 字段）
     *
     * @param i18nLang 语言信息
     */
    void update(I18nLang i18nLang);

    /**
     * 设置为默认语言（同时将其他语言设为非默认）
     *
     * @param id 语言 ID
     */
    void setDefault(Long id);

    /**
     * 查询全部语言
     *
     * @return 语言列表
     */
    List<I18nLang> queryAll();

    /**
     * 批量更新排序
     *
     * @param sortMap key: 语言 ID, value: 排序值
     */
    void updateSort(Map<Long, Integer> sortMap);

    /**
     * 根据 ID 查询语言
     *
     * @param id 语言 ID
     * @return 语言信息
     */
    I18nLang queryById(Long id);

    /**
     * 获取默认语言
     *
     * @return 默认语言信息
     */
    I18nLang getDefault(Long tenantId);
}
