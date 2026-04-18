package com.xspaceagi.system.domain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xspaceagi.system.domain.service.I18nDomainService;
import com.xspaceagi.system.infra.dao.entity.I18nConfig;
import com.xspaceagi.system.infra.dao.entity.I18nLang;
import com.xspaceagi.system.infra.dao.service.I18nLangService;
import com.xspaceagi.system.infra.dao.service.I18nService;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import jakarta.annotation.Resource;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class I18nDomainServiceImpl implements I18nDomainService {

    @Resource
    private I18nService i18nService;

    @Resource
    private I18nLangService i18nLangService;

    @Override
    public IPage<I18nConfig> queryI18nConfigPage(Long tenantId, String type, String side, String module, String dataId, String lang, String fieldKey, String fieldValue, Long pageNo, Long pageSize) {
        LambdaQueryWrapper<I18nConfig> queryWrapper = buildTenantSystemI18nWrapper(tenantId, type, side, module, dataId, lang, fieldKey, fieldValue);
        queryWrapper.orderByAsc(I18nConfig::getSide, I18nConfig::getFieldKey);
        return i18nService.page(new Page<>(pageNo, pageSize), queryWrapper);
    }

    @Override
    public List<I18nConfig> queryI18nConfigList(Long tenantId, String type, String side, String module, String dataId, String lang, String fieldKey, String fieldValue) {
        LambdaQueryWrapper<I18nConfig> queryWrapper = buildTenantSystemI18nWrapper(tenantId, type, side, module, dataId, lang, fieldKey, fieldValue);
        queryWrapper.orderByAsc(I18nConfig::getSide, I18nConfig::getFieldKey);
        return TenantFunctions.callWithIgnoreCheck(() -> i18nService.list(queryWrapper));
    }

    @Override
    public long countI18nConfigList(Long tenantId, String type, String side, String module, String dataId, String lang, String fieldKey, String fieldValue) {
        return i18nService.count(buildTenantSystemI18nWrapper(tenantId, type, side, module, dataId, lang, fieldKey, fieldValue));
    }

    private static LambdaQueryWrapper<I18nConfig> buildTenantSystemI18nWrapper(Long tenantId, String type, String side, String module, String dataId, String lang, String fieldKey, String fieldValue) {
        LambdaQueryWrapper<I18nConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(I18nConfig::getTenantId, tenantId);
        queryWrapper.eq(I18nConfig::getType, type);
        queryWrapper.eq(StringUtils.isNotBlank(side), I18nConfig::getSide, side);
        queryWrapper.eq(StringUtils.isNotBlank(module), I18nConfig::getModule, module);
        queryWrapper.eq(dataId != null, I18nConfig::getDataId, dataId);
        queryWrapper.eq(StringUtils.isNotBlank(lang), I18nConfig::getLang, lang);
        queryWrapper.like(StringUtils.isNotBlank(fieldKey), I18nConfig::getFieldKey, fieldKey);
        queryWrapper.like(StringUtils.isNotBlank(fieldValue), I18nConfig::getFieldValue, fieldValue);
        return queryWrapper;
    }

    @Override
    public I18nConfig queryByKey(String lang, String fieldKey) {
        LambdaQueryWrapper<I18nConfig> wrapper = Wrappers.<I18nConfig>lambdaQuery()
                .eq(I18nConfig::getLang, lang)
                .eq(I18nConfig::getFieldKey, fieldKey);
        return i18nService.getOne(wrapper);
    }

    @Override
    public List<I18nConfig> listByTenantLangAndFieldKeys(Long tenantId, String lang, Collection<String> fieldKeys) {
        if (CollectionUtils.isEmpty(fieldKeys)) {
            return List.of();
        }
        LambdaQueryWrapper<I18nConfig> wrapper = Wrappers.<I18nConfig>lambdaQuery()
                .eq(I18nConfig::getTenantId, tenantId)
                .eq(I18nConfig::getLang, lang)
                .in(I18nConfig::getFieldKey, fieldKeys);
        return i18nService.list(wrapper);
    }

    @Override
    public void addOrUpdate(I18nConfig i18n) {
        LambdaQueryWrapper<I18nConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(I18nConfig::getType, i18n.getType());
        queryWrapper.eq(I18nConfig::getSide, i18n.getSide());
        queryWrapper.eq(I18nConfig::getModule, i18n.getModule());
        queryWrapper.eq(I18nConfig::getDataId, i18n.getDataId() == null ? "-1" : i18n.getDataId());
        queryWrapper.eq(I18nConfig::getLang, i18n.getLang());
        queryWrapper.eq(I18nConfig::getFieldKey, i18n.getFieldKey());
        I18nConfig i18n0 = i18nService.getOne(queryWrapper);
        if (i18n0 != null) {
            i18n.setId(i18n0.getId());
            i18nService.updateById(i18n);
        } else {
            i18nService.save(i18n);
        }
    }

    @Override
    public void addOrUpdateBatch(List<I18nConfig> i18nConfigs) {
        if (CollectionUtils.isEmpty(i18nConfigs)) {
            return;
        }
        // 业务约定：同一租户 + 同一 lang + 同一 fieldKey 至多一条，按 (tenantId, lang) 分桶后一次 IN 查询即可
        Map<String, List<I18nConfig>> byLang = new HashMap<>();
        for (I18nConfig cfg : i18nConfigs) {
            byLang.computeIfAbsent(cfg.getLang(), k -> new ArrayList<>()).add(cfg);
        }

        // 按 lang 多次查询
        for (List<I18nConfig> group : byLang.values()) {
            I18nConfig sample = group.get(0);
            Long tenantId = sample.getTenantId();
            Set<String> fieldKeys = new LinkedHashSet<>();
            for (I18nConfig cfg : group) {
                fieldKeys.add(cfg.getFieldKey());
            }

            LambdaQueryWrapper<I18nConfig> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(I18nConfig::getLang, sample.getLang());
            queryWrapper.in(I18nConfig::getFieldKey, fieldKeys);
            List<I18nConfig> exists = i18nService.list(queryWrapper);
            Map<String, I18nConfig> existsMap = new HashMap<>();
            for (I18nConfig exist : exists) {
                existsMap.putIfAbsent(exist.getFieldKey(), exist);
            }

            for (I18nConfig cfg : group) {
                I18nConfig exist = existsMap.get(cfg.getFieldKey());
                if (exist != null) {
                    cfg.setId(exist.getId());
                }
            }
        }
        i18nService.saveOrUpdateBatch(i18nConfigs);
    }

    @Override
    public void batchDelete(List<I18nConfig> i18nConfigs) {
        if (CollectionUtils.isEmpty(i18nConfigs)) {
            return;
        }
        // 按 type + side 分组后使用 field_key IN 删除，不区分语言
        Map<String, List<I18nConfig>> grouped = new HashMap<>();
        for (I18nConfig cfg : i18nConfigs) {
            String groupKey = String.join("||",
                    cfg.getType(),
                    cfg.getSide());
            grouped.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(cfg);
        }

        grouped.values().forEach(group -> {
            I18nConfig sample = group.get(0);
            Set<String> fieldKeys = new LinkedHashSet<>();
            for (I18nConfig cfg : group) {
                fieldKeys.add(cfg.getFieldKey());
            }
            if (fieldKeys.isEmpty()) {
                return;
            }
            LambdaQueryWrapper<I18nConfig> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(I18nConfig::getType, sample.getType());
            queryWrapper.eq(I18nConfig::getSide, sample.getSide());
            queryWrapper.in(I18nConfig::getFieldKey, fieldKeys);
            i18nService.remove(queryWrapper);
        });
    }

    @Override
    public void deleteByLang(Long tenantId, String lang) {
        LambdaQueryWrapper<I18nConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(I18nConfig::getTenantId, tenantId);
        queryWrapper.eq(I18nConfig::getLang, lang);
        i18nService.remove(queryWrapper);
    }

    @Override
    public void initConfigsForNewLang(Long tenantId, String lang) {
        String templateLang = resolveTemplateLang(tenantId, lang);
        if (StringUtils.isBlank(templateLang)) {
            return;
        }
        LambdaQueryWrapper<I18nConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(I18nConfig::getTenantId, tenantId);
        queryWrapper.eq(I18nConfig::getLang, templateLang);
        List<I18nConfig> existing = i18nService.list(queryWrapper);
        if (CollectionUtils.isEmpty(existing)) {
            return;
        }
        Map<String, I18nConfig> uniqueByKey = new LinkedHashMap<>();
        for (I18nConfig item : existing) {
            String uniqueKey = String.join("||",
                    item.getType(),
                    item.getSide(),
                    item.getModule(),
                    item.getDataId() == null ? "-1" : item.getDataId(),
                    item.getFieldKey());
            uniqueByKey.putIfAbsent(uniqueKey, item);
        }
        List<I18nConfig> newLangConfigs = uniqueByKey.values().stream().map(item -> {
            I18nConfig config = new I18nConfig();
            config.setTenantId(tenantId);
            config.setType(item.getType());
            config.setModule(item.getModule());
            config.setSide(item.getSide());
            config.setDataId(item.getDataId() == null ? "-1" : item.getDataId());
            config.setLang(lang);
            config.setFieldKey(item.getFieldKey());
            config.setRemark(item.getRemark());
            return config;
        }).toList();
        addOrUpdateBatch(newLangConfigs);
    }

    private String resolveTemplateLang(Long tenantId, String newLang) {
        LambdaQueryWrapper<I18nLang> defaultQuery = new LambdaQueryWrapper<>();
        defaultQuery.eq(I18nLang::getTenantId, tenantId);
        defaultQuery.eq(I18nLang::getIsDefault, 1);
        defaultQuery.eq(I18nLang::getStatus, 1);
        defaultQuery.last("limit 1");
        I18nLang defaultLang = i18nLangService.getOne(defaultQuery);
        if (defaultLang != null && StringUtils.isNotBlank(defaultLang.getLang())
                && !defaultLang.getLang().equalsIgnoreCase(newLang)) {
            return defaultLang.getLang();
        }

        LambdaQueryWrapper<I18nConfig> fallbackQuery = new LambdaQueryWrapper<>();
        fallbackQuery.eq(I18nConfig::getTenantId, tenantId);
        fallbackQuery.ne(I18nConfig::getLang, newLang);
        fallbackQuery.select(I18nConfig::getLang);
        fallbackQuery.last("limit 1");
        I18nConfig fallbackConfig = i18nService.getOne(fallbackQuery);
        return fallbackConfig == null ? null : fallbackConfig.getLang();
    }

    @Override
    public void addOrUpdateInAllLangs(List<I18nLang> allLangs, I18nConfig i18nConfig) {
        // 在所有语言下创建或更新配置
        for (I18nLang lang : allLangs) {
            I18nConfig config = new I18nConfig();
            config.setType(i18nConfig.getType());
            config.setModule(i18nConfig.getModule());
            config.setDataId(i18nConfig.getDataId());
            config.setLang(lang.getLang());
            config.setFieldKey(i18nConfig.getFieldKey());
            config.setSide(i18nConfig.getSide());
            if (lang.getLang().equals(i18nConfig.getLang())) {
                config.setFieldValue(i18nConfig.getFieldValue());
                config.setRemark(i18nConfig.getRemark());
            }
            addOrUpdate(config);
        }
    }

    @Override
    public List<I18nLang> queryAllLangs() {
        return i18nLangService.list();
    }
}
