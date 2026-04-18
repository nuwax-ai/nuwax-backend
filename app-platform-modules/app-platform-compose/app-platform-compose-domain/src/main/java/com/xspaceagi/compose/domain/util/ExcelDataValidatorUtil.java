package com.xspaceagi.compose.domain.util;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.xspaceagi.compose.domain.model.CustomFieldDefinitionModel;
import com.xspaceagi.compose.domain.model.CustomTableDefinitionModel;
import com.xspaceagi.compose.sdk.enums.DefaultTableFieldEnum;
import com.xspaceagi.compose.sdk.enums.TableFieldTypeEnum;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.ComposeException;

import lombok.extern.slf4j.Slf4j;

/**
 * Excel数据校验工具类
 */
@Slf4j
public final class ExcelDataValidatorUtil {

    private ExcelDataValidatorUtil() {
    }

    // 数据类型限制常量（不暴露具体的数据库技术细节）
    private static final int INT_MIN_VALUE = -2147483648;
    private static final int INT_MAX_VALUE = 2147483647;
    private static final int SHORT_TEXT_MAX_LENGTH = 255;
    private static final int LONG_TEXT_MAX_LENGTH = 16777215; // 16MB
    private static final int DECIMAL_MAX_PRECISION = 28;
    private static final int DECIMAL_MAX_SCALE = 6;

    /**
     * 批量验证Excel数据的合规性
     * 
     * @param tableModel 表定义模型
     * @param excelData  Excel数据列表
     * @throws ComposeException 当数据不符合要求时抛出异常
     */
    public static void validateExcelData(CustomTableDefinitionModel tableModel, List<Map<String, Object>> excelData) {
        if (excelData == null || excelData.isEmpty()) {
            return;
        }

        // 构建字段类型映射
        Map<String, CustomFieldDefinitionModel> fieldDefinitionMap = null;
        if (tableModel.getFieldList() != null) {
            fieldDefinitionMap = tableModel.getFieldList().stream()
                    .filter(f -> f != null && f.getFieldName() != null)
                    .collect(Collectors.toMap(CustomFieldDefinitionModel::getFieldName, f -> f, (a, b) -> a));
        }

        // 逐行验证数据
        for (int rowIndex = 0; rowIndex < excelData.size(); rowIndex++) {
            Map<String, Object> rowData = excelData.get(rowIndex);
            if (rowData == null || rowData.isEmpty()) {
                continue;
            }

            validateRowData(tableModel, rowData, fieldDefinitionMap, rowIndex + 1);
        }

        log.info("Excel validation done, {} rows checked", excelData.size());
    }

    /**
     * 验证单行数据
     * 
     * @param tableModel         表定义模型
     * @param rowData            行数据
     * @param fieldDefinitionMap 字段定义映射
     * @param rowNumber          行号（用于错误提示）
     */
    private static void validateRowData(CustomTableDefinitionModel tableModel, Map<String, Object> rowData,
            Map<String, CustomFieldDefinitionModel> fieldDefinitionMap, int rowNumber) {

        for (Map.Entry<String, Object> entry : rowData.entrySet()) {
            String fieldName = entry.getKey();
            Object value = entry.getValue();

            // 跳过系统字段的校验
            if (isSystemField(fieldName)) {
                continue;
            }

            // 获取字段定义信息
            CustomFieldDefinitionModel fieldDefinition = null;
            if (fieldDefinitionMap != null && fieldDefinitionMap.containsKey(fieldName)) {
                fieldDefinition = fieldDefinitionMap.get(fieldName);
            }

            validateFieldValue(fieldName, value, fieldDefinition, rowNumber);
        }
    }

    /**
     * 验证单个字段值
     * 
     * @param fieldName       字段名
     * @param value           字段值
     * @param fieldDefinition 字段定义信息
     * @param rowNumber       行号
     */
    private static void validateFieldValue(String fieldName, Object value, CustomFieldDefinitionModel fieldDefinition,
            int rowNumber) {
        // 空值检查
        boolean isValueEmpty = (value == null) || (value instanceof String && ((String) value).trim().isEmpty());

        if (isValueEmpty) {
            // 校验必填字段（仅校验已启用的字段）
            if (fieldDefinition != null 
                    && fieldDefinition.getEnabledFlag() != null && fieldDefinition.getEnabledFlag() == 1
                    && fieldDefinition.getNullableFlag() != null && fieldDefinition.getNullableFlag() == -1) {
                String errorMessage = String.format("第%d行字段'%s'为必填项，不能为空", rowNumber, fieldName);
                throw ComposeException.build(BizExceptionCodeEnum.composeSqlExecuteFailed, errorMessage);
            }
            return;
        }

        // 根据字段类型进行校验
        if (fieldDefinition == null) {
            return;
        }

        Integer fieldType = fieldDefinition.getFieldType();
        if (fieldType == null) {
            return;
        }

        try {
            if (TableFieldTypeEnum.BOOLEAN.getCode().equals(fieldType)) {
                validateBooleanField(fieldName, value, rowNumber);
            } else if (TableFieldTypeEnum.INTEGER.getCode().equals(fieldType)) {
                validateIntegerField(fieldName, value, rowNumber);
            } else if (TableFieldTypeEnum.NUMBER.getCode().equals(fieldType)) {
                validateNumberField(fieldName, value, rowNumber);
            } else if (TableFieldTypeEnum.STRING.getCode().equals(fieldType)) {
                validateStringField(fieldName, value, rowNumber, SHORT_TEXT_MAX_LENGTH);
            } else if (TableFieldTypeEnum.MEDIUMTEXT.getCode().equals(fieldType)) {
                validateStringField(fieldName, value, rowNumber, LONG_TEXT_MAX_LENGTH);
            } else if (TableFieldTypeEnum.DATE.getCode().equals(fieldType)) {
                validateDateField(fieldName, value, rowNumber);
            }
        } catch (Exception e) {
            log.error("Field validation failed, name: {}, value: {}, row: {}", fieldName, value, rowNumber, e);
            throw e;
        }
    }

    /**
     * 校验布尔类型字段
     */
    private static void validateBooleanField(String fieldName, Object value, int rowNumber) {
        String stringValue = value.toString().trim();

        if (!"true".equalsIgnoreCase(stringValue) && !"false".equalsIgnoreCase(stringValue)
                && !"1".equals(stringValue) && !"0".equals(stringValue)) {
            String errorMessage = String.format("第%d行字段'%s'应填写true/false或1/0",
                    rowNumber, fieldName);
            throw ComposeException.build(BizExceptionCodeEnum.composeSqlExecuteFailed, errorMessage);
        }
    }

    /**
     * 校验整数类型字段
     */
    private static void validateIntegerField(String fieldName, Object value, int rowNumber) {
        try {
            if (value instanceof Number) {
                // 如果是数值类型，检查范围
                long longValue = ((Number) value).longValue();
                if (longValue < INT_MIN_VALUE || longValue > INT_MAX_VALUE) {
                    String errorMessage = String.format("第%d行字段'%s'数值过大，请填写%d到%d之间的整数",
                            rowNumber, fieldName, INT_MIN_VALUE, INT_MAX_VALUE);
                    throw ComposeException.build(BizExceptionCodeEnum.composeSqlExecuteFailed, errorMessage);
                }
            } else {
                // 如果是字符串类型，尝试转换并检查范围
                String stringValue = value.toString().trim();
                long longValue = Long.parseLong(stringValue);
                if (longValue < INT_MIN_VALUE || longValue > INT_MAX_VALUE) {
                    String errorMessage = String.format("第%d行字段'%s'数值过大，请填写%d到%d之间的整数",
                            rowNumber, fieldName, INT_MIN_VALUE, INT_MAX_VALUE);
                    throw ComposeException.build(BizExceptionCodeEnum.composeSqlExecuteFailed, errorMessage);
                }
            }
        } catch (NumberFormatException e) {
            String errorMessage = String.format("第%d行字段'%s'请填写有效的整数",
                    rowNumber, fieldName);
            throw ComposeException.build(BizExceptionCodeEnum.composeSqlExecuteFailed, errorMessage);
        }
    }

    /**
     * 校验数值类型字段
     */
    private static void validateNumberField(String fieldName, Object value, int rowNumber) {
        try {
            BigDecimal decimal;
            if (value instanceof Number) {
                decimal = new BigDecimal(value.toString());
            } else {
                String stringValue = value.toString().trim();
                decimal = new BigDecimal(stringValue);
            }

            // 检查总精度和小数位数
            int precision = decimal.precision();
            int scale = decimal.scale();

            if (precision > DECIMAL_MAX_PRECISION) {
                String errorMessage = String.format("第%d行字段'%s'数字太长，请限制在%d位以内",
                        rowNumber, fieldName, DECIMAL_MAX_PRECISION);
                throw ComposeException.build(BizExceptionCodeEnum.composeSqlExecuteFailed, errorMessage);
            }

            if (scale > DECIMAL_MAX_SCALE) {
                String errorMessage = String.format("第%d行字段'%s'小数位过多，请限制在%d位以内",
                        rowNumber, fieldName, DECIMAL_MAX_SCALE);
                throw ComposeException.build(BizExceptionCodeEnum.composeSqlExecuteFailed, errorMessage);
            }

        } catch (NumberFormatException e) {
            String errorMessage = String.format("第%d行字段'%s'请填写有效的数字",
                    rowNumber, fieldName);
            throw ComposeException.build(BizExceptionCodeEnum.composeSqlExecuteFailed, errorMessage);
        }
    }

    /**
     * 校验字符串类型字段
     */
    private static void validateStringField(String fieldName, Object value, int rowNumber, int maxLength) {
        String stringValue = value.toString();

        if (stringValue.length() > maxLength) {
            String errorMessage = String.format("第%d行字段'%s'内容过长，请控制在%d个字符以内",
                    rowNumber, fieldName, maxLength);
            throw ComposeException.build(BizExceptionCodeEnum.composeSqlExecuteFailed, errorMessage);
        }
    }

    /**
     * 校验日期类型字段
     */
    private static void validateDateField(String fieldName, Object value, int rowNumber) {
        if (!(value instanceof String)) {
            String errorMessage = String.format("第%d行字段'%s'请填写正确的日期格式",
                    rowNumber, fieldName);
            throw ComposeException.build(BizExceptionCodeEnum.composeSqlExecuteFailed, errorMessage);
        }

        try {
            String dateStr = (String) value;
            OffsetDateTime.parse(dateStr, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (Exception e) {
            String errorMessage = String.format("第%d行字段'%s'日期格式错误，请使用标准格式",
                    rowNumber, fieldName);
            throw ComposeException.build(BizExceptionCodeEnum.composeSqlExecuteFailed, errorMessage);
        }
    }

    /**
     * 判断是否为系统字段
     */
    private static boolean isSystemField(String fieldName) {
        return DefaultTableFieldEnum.getEnumByFieldName(fieldName) != null;
    }
}