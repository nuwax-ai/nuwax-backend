package com.xspaceagi.compose.domain.util.excel;

import java.io.InputStream;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import com.xspaceagi.compose.domain.model.CustomFieldDefinitionModel;
import com.xspaceagi.compose.domain.model.CustomTableDefinitionModel;

import cn.idev.excel.FastExcel;
import cn.idev.excel.context.AnalysisContext;
import cn.idev.excel.event.AnalysisEventListener;
import lombok.extern.slf4j.Slf4j;

/**
 * excel工具类,使用的 fastExcel 库
 * <p>
 * 文档: https://idev.cn/fastexcel/zh-CN/docs/advance_api
 * </p>
 */
@Slf4j
public class ExcelUtils {

    // 匹配 "中文名称(english_name)" 格式的正则表达式
    private static final Pattern HEADER_PATTERN = Pattern.compile("([^(]+)\\(([^)]+)\\)");

    /**
     * 动态读取excel表头和数据（无Java Bean绑定，兼容无数据时获取表头）
     * 
     * @param inputStream excel输入流
     * @return ReadExcelDataVo（header为表头，data为数据）
     */
    public static ReadExcelDataVo readExcel(InputStream inputStream, CustomTableDefinitionModel tableDefinitionModel) {
        final List<Map<String, Object>> dataList = new ArrayList<>();
        final List<String> originalHeader = new ArrayList<>();
        final List<CustomFieldDefinitionModel> matchedHeaderFields = new ArrayList<>();
        final List<String> newFieldsToCreate = new ArrayList<>();
        final Map<Integer, String> headerIndexToFinalKeyMap = new LinkedHashMap<>();

        // 获取表定义字段列表
        final List<CustomFieldDefinitionModel> fieldList = tableDefinitionModel.getFieldList();

        // 创建字段名 -> 字段定义 的映射
        final Map<String, CustomFieldDefinitionModel> fieldNameMap = fieldList.stream()
                .collect(Collectors.toMap(CustomFieldDefinitionModel::getFieldName, Function.identity(), (a, b) -> a));

        // 创建字段描述 -> 字段定义 的映射
        final Map<String, CustomFieldDefinitionModel> fieldDescriptionMap = fieldList.stream()
                .filter(f -> StringUtils.isNotBlank(f.getFieldDescription()))
                .collect(Collectors.toMap(CustomFieldDefinitionModel::getFieldDescription, Function.identity(),
                        (a, b) -> a));

        FastExcel.read(inputStream, new AnalysisEventListener<Map<Integer, String>>() {
            @Override
            public void invoke(Map<Integer, String> data, AnalysisContext context) {
                // 数据行
                Map<String, Object> row = new LinkedHashMap<>();
                headerIndexToFinalKeyMap.forEach((index, key) -> {
                    row.put(key, data.get(index));
                });
                dataList.add(row);
            }

            @Override
            public void invokeHeadMap(Map<Integer, String> headMap, AnalysisContext context) {
                //去掉 两边的空格
                var trimmedValues = headMap.values().stream()
                        .filter(StringUtils::isNotBlank)
                        .map(ExcelUtils::cleanWhitespace)
                        .toList();
                originalHeader.addAll(trimmedValues);

                for (int i = 0; i < headMap.size(); i++) {
                    String rawHeader = Optional.ofNullable(headMap.get(i))
                            .map(ExcelUtils::cleanWhitespace)
                            .orElse(null);
                    if (StringUtils.isBlank(rawHeader)) {
                        log.warn("Excel header empty at index {}, skip column", i);
                        continue;
                    }
                    rawHeader = ExcelUtils.cleanWhitespace(rawHeader);

                    Matcher matcher = HEADER_PATTERN.matcher(rawHeader);
                    String parsedDesc = null;
                    String parsedName = null;

                    if (matcher.matches()) {
                        // 匹配 "中文(英文)" 格式
                        parsedDesc = ExcelUtils.cleanWhitespace(matcher.group(1));
                        parsedName = ExcelUtils.cleanWhitespace(matcher.group(2));
                    }

                    CustomFieldDefinitionModel matchedField = null;

                    // 策略1: 根据解析出的英文名匹配 fieldName
                    if (parsedName != null) {
                        matchedField = fieldNameMap.get(parsedName);
                    }

                    // 策略2: 如果策略1失败，则根据解析出的中文名匹配 fieldDescription
                    if (matchedField == null && parsedDesc != null) {
                        matchedField = fieldDescriptionMap.get(parsedDesc);
                    }

                    // 策略3: 如果策略1和2都失败（或非 "中文(英文)" 格式），则使用原始表头尝试匹配
                    if (matchedField == null) {
                        // 3.1 尝试匹配英文名 fieldName
                        matchedField = fieldNameMap.get(rawHeader);
                        // 3.2 尝试匹配中文名 fieldDescription
                        if (matchedField == null) {
                            matchedField = fieldDescriptionMap.get(rawHeader);
                        }
                    }

                    if (matchedField != null) {
                        // 找到了匹配的字段
                        matchedHeaderFields.add(matchedField);
                        headerIndexToFinalKeyMap.put(i, matchedField.getFieldName());
                    } else {
                        // 未找到匹配字段，将其视为一个要创建的新字段
                        log.info("No match for header '{}', create new field.", rawHeader);
                        newFieldsToCreate.add(rawHeader);
                        // 使用原始表头作为临时key，以便在数据行中引用
                        headerIndexToFinalKeyMap.put(i, rawHeader);
                    }
                }
            }

            @Override
            public void doAfterAllAnalysed(AnalysisContext context) {
                log.info("Excel read done. Matched {} fields, will create {} new.", matchedHeaderFields.size(),
                        newFieldsToCreate.size());
            }
        }).headRowNumber(1).sheet().doRead();

        ReadExcelDataVo result = new ReadExcelDataVo();
        result.setOriginalHeader(originalHeader);
        result.setHeader(matchedHeaderFields);
        result.setData(dataList);
        result.setNewFieldsToCreate(newFieldsToCreate);
        return result;
    }

    /**
     * 根据新字段的映射关系，重映射Excel数据中的key
     */
    public static void remapExcelDataKeys(List<Map<String, Object>> excelData, Map<String, String> newFieldMapping) {
        if (CollectionUtils.isEmpty(excelData) || CollectionUtils.isEmpty(newFieldMapping)) {
            return;
        }
        for (Map<String, Object> row : excelData) {
            Map<String, Object> updatedRow = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                String oldKey = entry.getKey();
                Object value = entry.getValue();
                String newKey = newFieldMapping.getOrDefault(oldKey, oldKey);
                updatedRow.put(newKey, value);
            }
            row.clear();
            row.putAll(updatedRow);
        }
    }


    /**
     * 去掉空格,以及特殊的空格,比如:  <0xa0>（即 Unicode U+00A0，No-Break Space）的字符
     * 优化版本：保留换行符等格式字符，只处理空格类字符
     * @param str
     * @return
     */
    public static String cleanWhitespace(String str) {
        if (str == null) {
            return "";
        }
        // 替换特殊空格字符
        String cleaned = str.replace("\u00A0", " ");
        // 只合并连续的空格字符（不包括换行符），保留换行符等格式
        cleaned = cleaned.replaceAll("[ \\f\\v]+", " ").trim();
        return cleaned;
    }

}
