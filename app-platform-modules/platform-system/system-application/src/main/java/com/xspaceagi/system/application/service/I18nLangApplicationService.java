package com.xspaceagi.system.application.service;

import com.xspaceagi.system.application.dto.I18nLangAddDto;
import com.xspaceagi.system.application.dto.I18nLangDto;
import com.xspaceagi.system.application.dto.I18nLangUpdateDto;

import java.util.List;

/**
 * 语言应用服务
 */
public interface I18nLangApplicationService {

    /**
     * 新增语言
     *
     * @param addDto 语言信息
     * @return 语言 ID
     */
    Long add(I18nLangAddDto addDto);

    /**
     * 删除语言
     *
     * @param id 语言 ID
     */
    void delete(Long id);

    /**
     * 更新语言
     *
     * @param updateDto 语言信息
     */
    void update(I18nLangUpdateDto updateDto);

    /**
     * 设置为默认语言
     *
     * @param id 语言 ID
     */
    void setDefault(Long id);

    /**
     * 查询全部语言
     *
     * @return 语言列表
     */
    List<I18nLangDto> queryAll();

    /**
     * 批量更新排序
     *
     * @param sortList key: 语言 ID, value: 排序值
     */
    void updateSort(List<I18nLangDto> sortList);

    /**
     * 根据 ID 查询语言
     *
     * @param id 语言 ID
     * @return 语言信息
     */
    I18nLangDto queryById(Long id);

    /**
     * 获取默认语言
     *
     * @return 默认语言信息
     */
    I18nLangDto getDefault(Long tenantId);
}
