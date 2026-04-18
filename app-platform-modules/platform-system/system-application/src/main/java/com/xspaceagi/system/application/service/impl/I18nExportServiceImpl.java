package com.xspaceagi.system.application.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xspaceagi.system.application.constant.I18nLangTagConstraints;
import com.xspaceagi.system.application.dto.permission.export.I18nConfigExportDto;
import com.xspaceagi.system.application.dto.permission.export.I18nConfigRowExportDto;
import com.xspaceagi.system.application.service.I18nExportService;
import com.xspaceagi.system.infra.dao.entity.I18nConfig;
import com.xspaceagi.system.infra.dao.service.I18nService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 从默认租户（tenant_id=1）导出 i18n 配置；语言范围由调用方传入。
 */
@Slf4j
@Service
public class I18nExportServiceImpl implements I18nExportService {

    private static final long DEFAULT_EXPORT_TENANT_ID = 1L;

    @Resource
    private I18nService i18nService;

    @Override
    public I18nConfigExportDto exportConfig(String version, List<String> langs) {
        I18nConfigExportDto dto = new I18nConfigExportDto();
        dto.setVersion(version);
        if (StringUtils.isBlank(version)) {
            return dto;
        }

        List<String> langTags = normalizeLangParams(langs);
        if (CollectionUtils.isEmpty(langTags)) {
            log.warn("i18n 配置导出：lang 列表为空或均无法规范为合法语言标签，version={}", version);
            dto.setConfigs(new ArrayList<>());
            return dto;
        }

        List<I18nConfig> list = i18nService.list(Wrappers.<I18nConfig>lambdaQuery()
                .eq(I18nConfig::getTenantId, DEFAULT_EXPORT_TENANT_ID)
                .in(I18nConfig::getLang, langTags));
        list.sort(Comparator
                .comparing(I18nConfig::getLang, Comparator.nullsFirst(String::compareTo))
                .thenComparing(I18nConfig::getSide, Comparator.nullsFirst(String::compareTo))
                .thenComparing(I18nConfig::getFieldKey, Comparator.nullsFirst(String::compareTo)));

        List<I18nConfigRowExportDto> rows = new ArrayList<>();
        for (I18nConfig e : list) {
            I18nConfigRowExportDto r = new I18nConfigRowExportDto();
            r.setType(e.getType());
            r.setSide(e.getSide());
            r.setModule(e.getModule());
            r.setDataId(e.getDataId());
            r.setLang(e.getLang());
            r.setFieldKey(e.getFieldKey());
            r.setFieldValue(e.getFieldValue());
            r.setRemark(e.getRemark());
            rows.add(r);
        }
        dto.setConfigs(rows);
        return dto;
    }

    private static List<String> normalizeLangParams(List<String> raw) {
        if (CollectionUtils.isEmpty(raw)) {
            return List.of();
        }
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        for (String s : raw) {
            if (StringUtils.isBlank(s)) {
                continue;
            }
            I18nLangTagConstraints.tryNormalizeToStoredForm(s.trim()).ifPresent(ordered::add);
        }
        return new ArrayList<>(ordered);
    }
}
