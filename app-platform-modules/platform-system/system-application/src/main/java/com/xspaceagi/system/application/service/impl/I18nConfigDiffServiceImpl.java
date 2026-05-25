package com.xspaceagi.system.application.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xspaceagi.system.application.constant.I18nLangTagConstraints;
import com.xspaceagi.system.application.dto.permission.export.I18nConfigDiffSplitDto;
import com.xspaceagi.system.application.dto.permission.export.I18nConfigRowExportDto;
import com.xspaceagi.system.application.service.I18nConfigDiffService;
import com.xspaceagi.system.spec.constants.I18nSyncConstants;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.jackson.JsonSerializeUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class I18nConfigDiffServiceImpl implements I18nConfigDiffService {

    private static final TypeReference<List<I18nConfigRowExportDto>> ROW_LIST_TYPE = new TypeReference<>() {
    };

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    @Override
    public I18nConfigDiffSplitDto generateDiff(String fromVersion, String toVersion) {
        List<I18nConfigRowExportDto> fromRows = loadFromClasspath(fromVersion);
        if (fromRows == null) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(),
                    "未找到版本 " + fromVersion + " 的 i18n 配置：" + I18nSyncConstants.buildI18nConfigClasspathPath(fromVersion));
        }
        List<I18nConfigRowExportDto> toRows = loadFromClasspath(toVersion);
        if (toRows == null) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(),
                    "未找到版本 " + toVersion + " 的 i18n 配置：" + I18nSyncConstants.buildI18nConfigClasspathPath(toVersion));
        }

        Map<String, I18nConfigRowExportDto> fromMap = indexByUk(fromRows);
        I18nConfigDiffSplitDto split = new I18nConfigDiffSplitDto();
        for (I18nConfigRowExportDto toRow : toRows) {
            if (StringUtils.isAnyBlank(toRow.getLang(), toRow.getFieldKey())) {
                continue;
            }
            String uk = configUk(toRow);
            I18nConfigRowExportDto fromRow = fromMap.get(uk);
            if (fromRow == null) {
                split.getAddRows().add(toRow);
            } else if (!rowEquals(fromRow, toRow)) {
                split.getUpdateRows().add(toRow);
            }
        }
        return split;
    }

    private Map<String, I18nConfigRowExportDto> indexByUk(List<I18nConfigRowExportDto> rows) {
        if (CollectionUtils.isEmpty(rows)) {
            return Collections.emptyMap();
        }
        return rows.stream()
                .filter(r -> StringUtils.isNoneBlank(r.getLang(), r.getFieldKey()))
                .collect(Collectors.toMap(this::configUk, r -> r, (a, b) -> b, LinkedHashMap::new));
    }

    /**
     * 与 {@link I18nImportServiceImpl} 入库 uk 维度一致（不含 tenantId）。
     */
    private String configUk(I18nConfigRowExportDto row) {
        String lang = I18nLangTagConstraints.tryNormalizeToStoredForm(row.getLang())
                .orElse(StringUtils.trimToEmpty(row.getLang()));
        return lang + "|"
                + StringUtils.defaultString(row.getType()) + "|"
                + StringUtils.defaultString(row.getSide()) + "|"
                + StringUtils.defaultString(row.getModule()) + "|"
                + StringUtils.defaultString(row.getFieldKey());
    }

    private boolean rowEquals(I18nConfigRowExportDto a, I18nConfigRowExportDto b) {
        return Objects.equals(normalizeRow(a), normalizeRow(b));
    }

    private Map<String, Object> normalizeRow(I18nConfigRowExportDto row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", StringUtils.defaultString(row.getType()));
        map.put("side", StringUtils.defaultString(row.getSide()));
        map.put("module", StringUtils.defaultString(row.getModule()));
        map.put("dataId", normalizeDataId(row.getDataId()));
        map.put("lang", I18nLangTagConstraints.tryNormalizeToStoredForm(row.getLang())
                .orElse(StringUtils.trimToEmpty(row.getLang())));
        map.put("fieldKey", StringUtils.defaultString(row.getFieldKey()));
        map.put("fieldValue", row.getFieldValue());
        map.put("remark", row.getRemark());
        return map;
    }

    private String normalizeDataId(String dataId) {
        return StringUtils.defaultIfBlank(dataId, "-1");
    }

    private List<I18nConfigRowExportDto> loadFromClasspath(String version) {
        String path = I18nSyncConstants.buildI18nConfigClasspathPath(version);
        try {
            ClassPathResource resource = new ClassPathResource(path);
            if (!resource.exists()) {
                return null;
            }
            String json = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return unwrapToRowList(json);
        } catch (IOException e) {
            log.warn("读取 i18n 配置 JSON 失败：{}", path, e);
            return null;
        }
    }

    private List<I18nConfigRowExportDto> unwrapToRowList(String json) {
        if (StringUtils.isBlank(json)) {
            return List.of();
        }
        String trimmed = json.trim();
        if (trimmed.startsWith("[")) {
            return JsonSerializeUtil.parseObject(json, ROW_LIST_TYPE);
        }
        if (trimmed.startsWith("{")) {
            Map<String, Object> root = JsonSerializeUtil.parseObject(json, MAP_TYPE);
            for (String key : List.of("configs", "items")) {
                Object nested = root.get(key);
                if (nested instanceof List<?> list) {
                    String nestedJson = JsonSerializeUtil.toJSONString(list);
                    return JsonSerializeUtil.parseObject(nestedJson, ROW_LIST_TYPE);
                }
            }
        }
        return List.of();
    }
}
