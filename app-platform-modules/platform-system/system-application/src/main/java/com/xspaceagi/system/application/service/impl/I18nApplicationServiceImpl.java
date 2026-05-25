package com.xspaceagi.system.application.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xspaceagi.system.application.constant.I18nLangTagConstraints;
import com.xspaceagi.system.application.dto.I18nConfigDto;
import com.xspaceagi.system.application.dto.I18nConfigQueryDto;
import com.xspaceagi.system.application.dto.I18nConfigQueryExportDto;
import com.xspaceagi.system.application.dto.I18nLangDto;
import com.xspaceagi.system.application.dto.permission.export.I18nConfigRowExportDto;
import com.xspaceagi.system.application.service.I18nApplicationService;
import com.xspaceagi.system.application.service.I18nLangApplicationService;
import com.xspaceagi.system.application.service.I18nLlmTranslator;
import com.xspaceagi.system.domain.service.I18nDomainService;
import com.xspaceagi.system.infra.dao.entity.I18nConfig;
import com.xspaceagi.system.infra.dao.entity.I18nLang;
import com.xspaceagi.system.spec.enums.I18nSideEnum;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class I18nApplicationServiceImpl implements I18nApplicationService {
    private static final int TRANSLATE_BATCH_SIZE = 50;
    private static final int BATCH_ADD_OR_UPDATE_MAX_SIZE = 10000;
    private static final int BATCH_UPSERT_CHUNK_SIZE = 500;
    /** 按条件导出 JSON 时允许的最大行数，超出则拒绝导出，避免 OOM 与超大响应 */
    private static final int MAX_EXPORT_I18N_CONFIG_ROWS = 200_000;

    @Resource
    private I18nDomainService i18nDomainService;

    @Resource
    private I18nLangApplicationService i18nLangApplicationService;

    @Autowired(required = false)
    private I18nLlmTranslator i18nLlmTranslator;

    @Override
    public List<I18nConfigDto> queryI18nConfigList(Long tenantId, String type, String side, String module, String dataId, String lang) {
        List<I18nConfig> i18nConfigs = i18nDomainService.queryI18nConfigList(tenantId, type, side, module, dataId, lang, null, null);
        return i18nConfigs.stream().map(i18nConfig -> {
            I18nConfigDto i18nConfigDto = new I18nConfigDto();
            BeanUtils.copyProperties(i18nConfig, i18nConfigDto);
            i18nConfigDto.setKey(i18nConfig.getFieldKey());
            i18nConfigDto.setValue(i18nConfig.getFieldValue());
            return i18nConfigDto;
        }).collect(Collectors.toList());
    }

    @Override
    public IPage<I18nConfigDto> queryI18nConfigPage(Long tenantId, I18nConfigQueryDto query) {
        if (query == null) {
            query = new I18nConfigQueryDto();
        }
        long pageNo = query.getPageNo() != null ? query.getPageNo() : 1L;
        long pageSize = query.getPageSize() != null ? query.getPageSize() : 20L;
        IPage<I18nConfig> page = i18nDomainService.queryI18nConfigPage(
                tenantId, "System", query.getSide(), query.getModule(), null, query.getLang(), query.getKey(), query.getValue(), pageNo, pageSize);
        IPage<I18nConfigDto> resultPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        List<I18nConfigDto> records = page.getRecords().stream().map(i18nConfig -> {
            I18nConfigDto i18nConfigDto = new I18nConfigDto();
            BeanUtils.copyProperties(i18nConfig, i18nConfigDto);
            i18nConfigDto.setKey(i18nConfig.getFieldKey());
            i18nConfigDto.setValue(i18nConfig.getFieldValue());
            return i18nConfigDto;
        }).toList();
        resultPage.setRecords(records);
        return resultPage;
    }

    @Override
    public I18nConfigQueryExportDto exportI18nConfig(Long tenantId, I18nConfigQueryDto query) {
        if (query == null) {
            query = new I18nConfigQueryDto();
        }
        String langParam = query.getLang();
        if (StringUtils.isNotBlank(langParam)) {
            langParam = I18nLangTagConstraints.tryNormalizeToStoredForm(langParam.trim()).orElse(langParam.trim());
        }
        long total = i18nDomainService.countI18nConfigList(
                tenantId, "System", query.getSide(), query.getModule(), null, langParam, query.getKey(), query.getValue());
        if (total > MAX_EXPORT_I18N_CONFIG_ROWS) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemI18nExportRowCountExceeded,
                    total, MAX_EXPORT_I18N_CONFIG_ROWS);
        }
        List<I18nConfig> list = i18nDomainService.queryI18nConfigList(
                tenantId, "System", query.getSide(), query.getModule(), null, langParam, query.getKey(), query.getValue());
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
        I18nConfigQueryExportDto dto = new I18nConfigQueryExportDto();
        dto.setConfigs(rows);
        return dto;
    }

    @Override
    public Map<String, String> querySystemLangMap(Long tenantId, String side, String lang) {
        String defaultLang = getDefaultLang(tenantId);
        String targetLang = StringUtils.isBlank(lang) ? defaultLang
                : I18nLangTagConstraints.tryNormalizeToStoredForm(lang).orElse(lang.trim());
        List<I18nConfigDto> i18nConfigs = queryI18nConfigList(tenantId, "System", side, null, null, targetLang);
        if (CollectionUtils.isEmpty(i18nConfigs)) {
            i18nConfigs = queryI18nConfigList(tenantId, "System", side, null, null, defaultLang);
        }
        Map<String, String> langMap = i18nConfigs.stream()
                .collect(Collectors.toMap(I18nConfigDto::getKey, v -> v.getValue() == null ? "" : v.getValue(), (v1, v2) -> v1));
        if (!I18nLangTagConstraints.sameLanguageTag(targetLang, defaultLang)) {
            List<String> blankKeys = langMap.entrySet().stream()
                    .filter(e -> StringUtils.isBlank(e.getValue()))
                    .map(Map.Entry::getKey)
                    .toList();
            if (blankKeys.isEmpty()) {
                return langMap;
            }
            List<I18nConfigDto> defaultConfigs = queryI18nConfigList(tenantId, "System", side, null, null, defaultLang);
            Map<String, String> defaultLangMap = defaultConfigs.stream()
                    .collect(Collectors.toMap(I18nConfigDto::getKey, v -> v.getValue() == null ? "" : v.getValue(), (v1, v2) -> v1));
            for (String key : blankKeys) {
                String fallback = defaultLangMap.get(key);
                if (StringUtils.isNotBlank(fallback)) {
                    langMap.put(key, fallback);
                }
            }
        }
        return langMap;
    }

    private String getDefaultLang(Long tenantId) {
        I18nLangDto aDefault = i18nLangApplicationService.getDefault(tenantId);
        if (aDefault != null) {
            return aDefault.getLang();
        } else {
            return "en-us";
        }
    }

    @Override
    public void addOrUpdateI18nConfig(I18nConfigDto i18nConfigDto) {
        List<I18nLang> allLangs = i18nDomainService.queryAllLangs();
        addOrUpdateI18nConfig(allLangs, i18nConfigDto);
    }

    private void addOrUpdateI18nConfig(List<I18nLang> allLangs, I18nConfigDto i18nConfigDto) {
        Assert.notNull(i18nConfigDto.getKey(), "Parameter 'key' cannot be left blank.");
        Assert.hasText(i18nConfigDto.getValue(), "Parameter 'value' cannot be left blank.");
        Assert.hasText(i18nConfigDto.getLang(), "Parameter 'lang' cannot be left blank.");
        String inputLang = requireCanonicalLang(i18nConfigDto.getLang());
        i18nConfigDto.setLang(inputLang);
        i18nConfigDto.setType("System");
        String[] split = i18nConfigDto.getKey().split("\\.");
        Assert.isTrue(split.length >= 2, "Parameter 'key' format must be: $side.$module.xxx");
        Assert.isTrue(I18nSideEnum.isValid(split[0]), "Parameter 'key' side is invalid.");
        i18nConfigDto.setSide(split[0]);
        i18nConfigDto.setModule(split[1]);
        I18nConfig i18nConfig = new I18nConfig();
        BeanUtils.copyProperties(i18nConfigDto, i18nConfig);
        i18nConfig.setFieldKey(i18nConfigDto.getKey());
        i18nConfig.setFieldValue(i18nConfigDto.getValue());
        // 在所有语言下新增或更新配置
        i18nDomainService.addOrUpdateInAllLangs(allLangs, i18nConfig);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchAddOrUpdateI18nConfig(List<I18nConfigDto> I18nConfigDtos) {
        Assert.notEmpty(I18nConfigDtos, "The configuration list cannot be empty.");
        Assert.isTrue(I18nConfigDtos.size() <= BATCH_ADD_OR_UPDATE_MAX_SIZE, "Supports up to 10,000 configurations per batch.");
        // 查询所有语言
        List<I18nLang> allLangs = i18nDomainService.queryAllLangs();
        List<I18nConfig> allConfigs = new ArrayList<>();
        for (I18nConfigDto dto : I18nConfigDtos) {
            Assert.notNull(dto.getKey(), "Parameter 'key' cannot be left blank.");
            Assert.hasText(dto.getValue(), "Parameter 'value' cannot be left blank.");
            Assert.hasText(dto.getLang(), "Parameter 'lang' cannot be left blank.");
            String inputLang = requireCanonicalLang(dto.getLang());
            dto.setLang(inputLang);
            dto.setType("System");
            String[] split = dto.getKey().split("\\.");
            Assert.isTrue(split.length >= 2, "Parameter 'key' format must be: $side.$module.xxx");
            Assert.isTrue(I18nSideEnum.isValid(split[0]), "Parameter 'key' side is invalid.");
            dto.setSide(split[0]);
            dto.setModule(split[1]);

            for (I18nLang lang : allLangs) {
                I18nConfig config = new I18nConfig();
                BeanUtils.copyProperties(dto, config);
                config.setLang(lang.getLang());
                config.setFieldKey(dto.getKey());
                if (I18nLangTagConstraints.sameLanguageTag(lang.getLang(), inputLang)) {
                    config.setFieldValue(dto.getValue());
                    config.setRemark(dto.getRemark());
                }
                allConfigs.add(config);
            }
        }

        for (int i = 0; i < allConfigs.size(); i += BATCH_UPSERT_CHUNK_SIZE) {
            int end = Math.min(i + BATCH_UPSERT_CHUNK_SIZE, allConfigs.size());
            i18nDomainService.addOrUpdateBatch(allConfigs.subList(i, end));
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchDeleteI18nConfig(List<I18nConfigDto> i18nConfigDtos) {
        if (CollectionUtils.isEmpty(i18nConfigDtos)) {
            return;
        }
        List<I18nConfig> deleteList = i18nConfigDtos.stream().map(dto -> {
            Assert.hasText(dto.getKey(), "Parameter 'key' cannot be left blank.");
            Assert.hasText(dto.getSide(), "Parameter 'side' cannot be left blank.");
            I18nConfig i18nConfig = new I18nConfig();
            i18nConfig.setType(StringUtils.isNotBlank(dto.getType()) ? dto.getType() : "System");
            i18nConfig.setSide(dto.getSide());
            i18nConfig.setFieldKey(dto.getKey());
            return i18nConfig;
        }).toList();
        i18nDomainService.batchDelete(deleteList);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void translateForKey(Long tenantId, I18nConfigDto i18nConfigDto, String sourceLang, String targetLang) {
        if (i18nLlmTranslator == null) {
            throw new IllegalArgumentException("The LLM translator is not configured.");
        }
        Assert.notNull(i18nConfigDto.getKey(), "Parameter 'key' cannot be left blank.");
        Assert.hasText(sourceLang, "The source language cannot be empty.");
        Assert.hasText(targetLang, "The target language cannot be empty.");
        String sourceCanon = requireCanonicalLang(sourceLang);
        String targetCanonParam = requireCanonicalLang(targetLang);
        Assert.isTrue(!I18nLangTagConstraints.sameLanguageTag(sourceCanon, targetCanonParam),
                "The source language and target language cannot be same.");

        I18nConfig sourceI18nConfig = i18nDomainService.queryByKey(sourceCanon, i18nConfigDto.getKey());
        if (sourceI18nConfig == null) {
            throw new IllegalArgumentException("The source language configuration does not exist.");
        }
        String sourceValue = sourceI18nConfig.getFieldValue();

        String targetTag = validateLang(targetLang);

        String fieldValue = i18nLlmTranslator.translate(sourceValue, sourceCanon, targetTag);
        if (StringUtils.isBlank(fieldValue)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemI18nTranslationFailedTargetLang, targetTag);
        }
        I18nConfig config = new I18nConfig();
        config.setTenantId(tenantId);
        config.setType(sourceI18nConfig.getType());
        config.setSide(sourceI18nConfig.getSide());
        config.setModule(sourceI18nConfig.getModule());
        config.setDataId(sourceI18nConfig.getDataId());
        config.setLang(targetTag);
        config.setFieldKey(i18nConfigDto.getKey());
        config.setFieldValue(fieldValue);
        config.setRemark(sourceI18nConfig.getRemark());
        i18nDomainService.addOrUpdate(config);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void translateForKeysBatch(Long tenantId, List<I18nConfigDto> sourceItems, String sourceLang, String targetLang) {
        if (i18nLlmTranslator == null) {
            throw new IllegalArgumentException("The LLM translator is not configured.");
        }
        Assert.hasText(sourceLang, "The source language cannot be empty.");
        Assert.hasText(targetLang, "The target language cannot be empty.");
        String sourceCanon = requireCanonicalLang(sourceLang);
        String targetCanonParam = requireCanonicalLang(targetLang);
        Assert.isTrue(!I18nLangTagConstraints.sameLanguageTag(sourceCanon, targetCanonParam),
                "The source language and target language cannot be the same.");

        Map<String, I18nConfigDto> sourceMap = new LinkedHashMap<>();
        for (I18nConfigDto item : sourceItems) {
            if (StringUtils.isBlank(item.getKey()) || StringUtils.isBlank(item.getValue())) {
                continue;
            }
            sourceMap.put(item.getKey(), item);
        }
        if (sourceMap.isEmpty()) {
            return;
        }

        String targetTag = validateLang(targetLang);
        List<Map.Entry<String, I18nConfigDto>> entries = sourceMap.entrySet().stream().toList();
        List<I18nConfig> allUpsertsForTargetLang = new ArrayList<>();
        for (int i = 0; i < entries.size(); i += TRANSLATE_BATCH_SIZE) {
            int end = Math.min(i + TRANSLATE_BATCH_SIZE, entries.size());
            List<Map.Entry<String, I18nConfigDto>> chunk = entries.subList(i, end);
            Map<String, String> payload = chunk.stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getValue(), (a, b) -> a, LinkedHashMap::new));
            Map<String, String> translated = i18nLlmTranslator.translateBatch(payload, sourceCanon, targetTag);
            if (translated == null || translated.size() != payload.size()) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemI18nBatchTranslationSizeMismatch, targetTag, i);
            }

            for (Map.Entry<String, I18nConfigDto> entry : chunk) {
                String key = entry.getKey();
                String translatedValue = translated.get(key);
                if (StringUtils.isBlank(translatedValue)) {
                    throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemI18nBatchTranslationResultEmpty, key, targetTag);
                }
                I18nConfigDto src = entry.getValue();
                I18nConfig config = new I18nConfig();
                config.setTenantId(tenantId);
                config.setType(StringUtils.isNotBlank(src.getType()) ? src.getType() : "System");
                config.setSide(src.getSide());
                config.setModule(src.getModule());
                config.setDataId(src.getDataId());
                config.setLang(targetTag);
                config.setFieldKey(src.getKey());
                config.setFieldValue(translatedValue);
                config.setRemark(src.getRemark());
                allUpsertsForTargetLang.add(config);
            }
        }
        if (!allUpsertsForTargetLang.isEmpty()) {
            i18nDomainService.addOrUpdateBatch(allUpsertsForTargetLang);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void translateForKeys(Long tenantId, List<String> keys, String sourceLang, String targetLang) {
        Assert.notEmpty(keys, "The key list cannot be empty.");
        String sourceCanon = requireCanonicalLang(sourceLang);
        List<I18nConfigDto> sourceItems = new ArrayList<>();
        for (String key : keys) {
            if (StringUtils.isBlank(key)) {
                continue;
            }
            String fieldKey = key.trim();
            I18nConfig sourceConfig = i18nDomainService.queryByKey(sourceCanon, fieldKey);
            if (sourceConfig == null) {
                throw new IllegalArgumentException(
                        "The source language configuration does not exist for key: " + fieldKey);
            }
            if (StringUtils.isBlank(sourceConfig.getFieldValue())) {
                throw new IllegalArgumentException("The source value is empty for key: " + fieldKey);
            }
            sourceItems.add(toI18nConfigDto(sourceConfig));
        }
        if (sourceItems.isEmpty()) {
            return;
        }
        translateForKeysBatch(tenantId, sourceItems, sourceLang, targetLang);
    }

    private I18nConfigDto toI18nConfigDto(I18nConfig i18nConfig) {
        I18nConfigDto dto = new I18nConfigDto();
        BeanUtils.copyProperties(i18nConfig, dto);
        dto.setKey(i18nConfig.getFieldKey());
        dto.setValue(i18nConfig.getFieldValue());
        return dto;
    }

    private String validateLang(String lang) {
        List<I18nLang> allLangs = i18nDomainService.queryAllLangs();
        if (CollectionUtils.isEmpty(allLangs)) {
            throw new IllegalArgumentException("The language list is empty.");
        }
        String wanted = requireCanonicalLang(lang);
        return allLangs.stream()
                .filter(l -> l.getStatus() == null || l.getStatus() == 1)
                .filter(l -> StringUtils.isNotBlank(l.getLang()))
                .filter(l -> I18nLangTagConstraints.sameLanguageTag(l.getLang(), wanted))
                .findFirst()
                .map(l -> I18nLangTagConstraints.tryNormalizeToStoredForm(l.getLang()).orElse(l.getLang()))
                .filter(I18nLangTagConstraints::isWellFormedLanguageTag)
                .orElseThrow(() -> new IllegalArgumentException("The target language has not been added to the language list."));
    }

    private static String requireCanonicalLang(String raw) {
        return I18nLangTagConstraints.tryNormalizeToStoredForm(raw)
                .orElseThrow(() -> new IllegalArgumentException(I18nLangTagConstraints.LANG_TAG_MESSAGE_EN));
    }

}
