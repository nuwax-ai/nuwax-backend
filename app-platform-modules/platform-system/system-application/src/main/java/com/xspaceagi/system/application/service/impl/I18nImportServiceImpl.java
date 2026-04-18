package com.xspaceagi.system.application.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.xspaceagi.system.application.constant.I18nLangTagConstraints;
import com.xspaceagi.system.application.service.I18nImportService;
import com.xspaceagi.system.infra.dao.entity.I18nConfig;
import com.xspaceagi.system.infra.dao.entity.I18nLang;
import com.xspaceagi.system.infra.dao.entity.Tenant;
import com.xspaceagi.system.infra.dao.service.I18nLangService;
import com.xspaceagi.system.infra.dao.service.I18nService;
import com.xspaceagi.system.spec.jackson.JsonSerializeUtil;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
@Service
public class I18nImportServiceImpl implements I18nImportService {

    @Resource
    private I18nLangService i18nLangService;
    @Resource
    private I18nService i18nService;

    @Override
    public void importConfigToTenant(Tenant tenant, String version) {
        if (tenant == null || tenant.getId() == null) {
            log.warn("配置项导入失败，租户信息无效，version={}", version);
            return;
        }
        if (StringUtils.isBlank(version)) {
            log.warn("配置项导入失败，版本号为空，tenantId={}", tenant.getId());
            return;
        }
        List<Map<String, Object>> items = loadConfigFromClasspath(version);
        if (CollectionUtils.isEmpty(items)) {
            log.warn("配置项导入失败，未读取到可用数据，tenantId={}, version={}", tenant.getId(), version);
            return;
        }

        List<I18nConfig> parsedConfigs = new ArrayList<>();
        for (Map<String, Object> item : items) {
            String rawLang = firstNonBlankString(item, "lang");
            String fieldKey = firstNonBlankString(item, "fieldKey", "field_key");
            if (StringUtils.isAnyBlank(rawLang, fieldKey)) {
                continue;
            }
            String lang = requireCanonicalLang(rawLang);
            String type = firstNonBlankString(item, "type");
            String side = firstNonBlankString(item, "side");
            String module = firstNonBlankString(item, "module");
            String dataId = normalizeDataId(firstNonBlankString(item, "dataId", "data_id"));
            I18nConfig entity = new I18nConfig();
            entity.setTenantId(tenant.getId());
            entity.setType(type);
            entity.setSide(side);
            entity.setModule(module);
            entity.setDataId(dataId);
            entity.setLang(lang);
            entity.setFieldKey(fieldKey);
            entity.setFieldValue(firstNonBlankString(item, "fieldValue", "field_value"));
            entity.setRemark(firstNonBlankString(item, "remark"));
            parsedConfigs.add(entity);
        }
        if (CollectionUtils.isEmpty(parsedConfigs)) {
            if (CollectionUtils.isNotEmpty(items)) {
                Map<String, Object> sample = items.get(0);
                log.warn("配置项导入失败，解析后无有效配置项，tenantId={}, version={}, rawCount={}, firstEntryKeys={}",
                        tenant.getId(), version, items.size(), sample == null ? null : sample.keySet());
            } else {
                log.warn("配置项导入失败，解析后无有效配置项，tenantId={}, version={}", tenant.getId(), version);
            }
            return;
        }

        // 同一文件内与 uk_lang_key 一致去重（列顺序见 mysqlUkKey），避免批次内重复 INSERT
        Map<String, I18nConfig> dedupedByUk = new LinkedHashMap<>();
        for (I18nConfig cfg : parsedConfigs) {
            dedupedByUk.put(mysqlUkKey(cfg), cfg);
        }
        List<I18nConfig> uniqueConfigs = new ArrayList<>(dedupedByUk.values());

        // 导入目标 tenantId 与当前会话租户可能不一致，必须忽略多租户拦截，否则查不到目标租户已有行、全部为 INSERT 易撞 uk_lang_key
        TenantFunctions.runWithIgnoreCheck(() -> {
            List<I18nConfig> existingAll = i18nService.list(Wrappers.<I18nConfig>lambdaQuery()
                    .eq(I18nConfig::getTenantId, tenant.getId()));
            Map<String, I18nConfig> existingByUk = new HashMap<>();
            for (I18nConfig existing : existingAll) {
                existingByUk.putIfAbsent(mysqlUkKey(existing), existing);
            }

            List<I18nConfig> toSave = new ArrayList<>();
            int skippedExisting = 0;
            for (I18nConfig cfg : uniqueConfigs) {
                if (existingByUk.containsKey(mysqlUkKey(cfg))) {
                    skippedExisting++;
                    continue;
                }
                toSave.add(cfg);
            }
            if (CollectionUtils.isNotEmpty(toSave)) {
                i18nService.saveBatch(toSave);
            }
            log.info("配置项导入完成，tenantId={}, version={}, inserted={}, skippedExisting={}",
                    tenant.getId(), version, toSave.size(), skippedExisting);
        });
    }

    @Override
    public void importLangToTenant(Tenant tenant, String version) {
        if (tenant == null || tenant.getId() == null) {
            log.warn("语言包导入失败，租户信息无效，version={}", version);
            return;
        }
        if (StringUtils.isBlank(version)) {
            log.warn("语言包导入失败，版本号为空，tenantId={}", tenant.getId());
            return;
        }
        List<Map<String, Object>> items = loadLangFromClasspath(version);
        if (CollectionUtils.isEmpty(items)) {
            log.warn("语言包导入失败，未读取到可用数据，tenantId={}, version={}", tenant.getId(), version);
            return;
        }

        List<I18nLang> parsedLangs = new ArrayList<>();
        for (Map<String, Object> item : items) {
            String rawLang = firstNonBlankString(item, "lang");
            if (StringUtils.isBlank(rawLang)) {
                continue;
            }
            String lang = requireCanonicalLang(rawLang);
            I18nLang entity = new I18nLang();
            entity.setTenantId(tenant.getId());
            entity.setName(firstNonBlankString(item, "name"));
            entity.setLang(lang);
            entity.setStatus(toIntegerValue(firstPresent(item, "status")));
            entity.setIsDefault(toIntegerValue(firstPresent(item, "is_default", "isDefault")));
            entity.setSort(toIntegerValue(firstPresent(item, "sort")));
            parsedLangs.add(entity);
        }
        if (CollectionUtils.isEmpty(parsedLangs)) {
            log.warn("语言包导入失败，解析后无有效语言项，tenantId={}, version={}", tenant.getId(), version);
            return;
        }

        // 同一文件内同一 lang 只保留最后一条，避免重复插入
        Map<String, I18nLang> dedupedByCanonicalLang = new LinkedHashMap<>();
        for (I18nLang row : parsedLangs) {
            dedupedByCanonicalLang.put(row.getLang(), row);
        }
        final List<I18nLang> langsToImport = new ArrayList<>(dedupedByCanonicalLang.values());

        TenantFunctions.runWithIgnoreCheck(() -> {
            List<I18nLang> existingAll = i18nLangService.list(Wrappers.<I18nLang>lambdaQuery()
                    .eq(I18nLang::getTenantId, tenant.getId()));
            Map<String, I18nLang> existingByCanonicalLang = new HashMap<>();
            for (I18nLang existing : existingAll) {
                if (StringUtils.isBlank(existing.getLang())) {
                    continue;
                }
                String key = I18nLangTagConstraints.tryNormalizeToStoredForm(existing.getLang())
                        .orElse(StringUtils.trimToEmpty(existing.getLang()));
                existingByCanonicalLang.putIfAbsent(key, existing);
            }

            List<I18nLang> toSave = new ArrayList<>();
            int skippedExisting = 0;
            for (I18nLang lang : langsToImport) {
                I18nLang hit = existingByCanonicalLang.get(lang.getLang());
                if (hit == null) {
                    hit = existingAll.stream()
                            .filter(e -> StringUtils.isNotBlank(e.getLang())
                                    && I18nLangTagConstraints.sameLanguageTag(e.getLang(), lang.getLang()))
                            .findFirst()
                            .orElse(null);
                }
                if (hit != null) {
                    skippedExisting++;
                    continue;
                }
                toSave.add(lang);
            }
            if (CollectionUtils.isNotEmpty(toSave)) {
                i18nLangService.saveBatch(toSave);
            }
            log.info("语言包导入完成，tenantId={}, version={}, inserted={}, skippedExisting={}",
                    tenant.getId(), version, toSave.size(), skippedExisting);
        });
    }

    private List<Map<String, Object>> loadLangFromClasspath(String version) {
        String path = "i18n/i18n-lang-" + version + ".json";
        try {
            ClassPathResource resource = new ClassPathResource(path);
            if (!resource.exists()) {
                return List.of();
            }
            String json = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return unwrapJsonToMapList(json, path, "langs", "languages");
        } catch (IOException e) {
            log.warn("读取语言包文件失败，path={}", path, e);
            return List.of();
        }
    }

    private List<Map<String, Object>> loadConfigFromClasspath(String version) {
        String path = "i18n/i18n-config-" + version + ".json";
        try {
            ClassPathResource resource = new ClassPathResource(path);
            if (!resource.exists()) {
                return List.of();
            }
            String json = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return unwrapJsonToMapList(json, path, "configs", "items");
        } catch (IOException e) {
            log.warn("读取配置项文件失败，path={}", path, e);
            return List.of();
        }
    }

    /**
     * 支持根为 JSON 数组，或形如 {@code {"configs":[...]}} / {@code {"version":"x","configs":[...]}} 的包装对象；
     * 同时兼容部分工具导出的 camelCase 字段名（在条目解析时用 {@link #firstNonBlankString} 等读取）。
     * <p>
     * 必须使用 {@link JsonSerializeUtil#parseObject}（无多态类型信息），不能使用 {@link JsonSerializeUtil#parseObjectGeneric}，
     * 否则标准 JSON 数组会在 Jackson 多态反序列化阶段失败（Unexpected token START_OBJECT）。
     */
    private List<Map<String, Object>> unwrapJsonToMapList(String json, String pathForLog, String... wrapperArrayKeys) {
        if (StringUtils.isBlank(json)) {
            return List.of();
        }
        try {
            String trimmed = json.trim();
            Object root;
            if (trimmed.startsWith("[")) {
                root = JsonSerializeUtil.parseObject(json, new TypeReference<List<Map<String, Object>>>() {
                });
            } else if (trimmed.startsWith("{")) {
                root = JsonSerializeUtil.parseObject(json, new TypeReference<Map<String, Object>>() {
                });
            } else {
                log.warn("JSON 根节点非数组或对象，path={}", pathForLog);
                return List.of();
            }
            List<Map<String, Object>> list = unwrapRootToMapList(root, wrapperArrayKeys);
            if (CollectionUtils.isEmpty(list)) {
                log.warn("JSON 解析结果为空或非预期结构，path={}", pathForLog);
            }
            return list;
        } catch (RuntimeException e) {
            log.warn("解析 JSON 失败，path={}", pathForLog, e);
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> unwrapRootToMapList(Object root, String... wrapperArrayKeys) {
        if (root == null) {
            return List.of();
        }
        if (root instanceof List<?> rawList) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : rawList) {
                if (o instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
            return out;
        }
        if (root instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = (Map<String, Object>) rawMap;
            for (String key : wrapperArrayKeys) {
                Object nested = map.get(key);
                if (nested instanceof List<?>) {
                    return unwrapRootToMapList(nested);
                }
            }
        }
        return List.of();
    }

    /**
     * 依次尝试多个 key，取第一个非空字符串（trim 后）；兼容 JSON 里字面量 "null"。
     */
    private static String firstNonBlankString(Map<String, Object> item, String... keys) {
        for (String key : keys) {
            if (item == null || key == null || !item.containsKey(key)) {
                continue;
            }
            Object v = item.get(key);
            if (v == null) {
                continue;
            }
            String s = String.valueOf(v).trim();
            if (StringUtils.isBlank(s) || "null".equalsIgnoreCase(s)) {
                continue;
            }
            return s;
        }
        return null;
    }

    private static Object firstPresent(Map<String, Object> item, String... keys) {
        for (String key : keys) {
            if (item != null && key != null && item.containsKey(key) && item.get(key) != null) {
                return item.get(key);
            }
        }
        return null;
    }

    private String toStringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer toIntegerValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = String.valueOf(value);
        if (StringUtils.isBlank(text)) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String normalizeDataId(String dataId) {
        return StringUtils.defaultIfBlank(dataId, "-1");
    }

    /**
     * 与表 {@code uk_lang_key} 使用列一致（见报错串如 {@code 2-en-US-System-Claw-Claw.About.website-About--1}）：
     * {@code (_tenant_id, lang, type, side, field_key, module, data_id)}。
     * 使用 {@code |} 连接避免与 field_key 中的 {@code -} 混淆。
     */
    private String mysqlUkKey(I18nConfig c) {
        long tid = c.getTenantId();
        String lang = normalizeLangForMatch(c.getLang());
        String type = StringUtils.defaultString(c.getType());
        String side = StringUtils.defaultString(c.getSide());
        String fieldKey = StringUtils.defaultString(c.getFieldKey());
        String module = StringUtils.defaultString(c.getModule());
        return tid + "|" + lang + "|" + type + "|" + side + "|" + module + "|" + fieldKey;
    }

    /**
     * 入库语言标签用 JDK 规范形式。
     */
    private static String requireCanonicalLang(String raw) {
        return I18nLangTagConstraints.tryNormalizeToStoredForm(raw)
                .orElseThrow(() -> new IllegalArgumentException(I18nLangTagConstraints.LANG_TAG_MESSAGE_EN));
    }

    /**
     * 与导入侧规范形式对齐，用于匹配库中可能存在的非规范写法；无法解析时退回 trim 后的原文，避免整批导入被单条脏数据拖死。
     */
    private static String normalizeLangForMatch(String raw) {
        return I18nLangTagConstraints.tryNormalizeToStoredForm(raw).orElse(StringUtils.trimToEmpty(raw));
    }
}
