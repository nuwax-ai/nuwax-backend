package com.xspaceagi.compose.domain.util.dml;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Collectors;

import com.xspaceagi.compose.domain.model.CustomTableDefinitionModel;
import com.xspaceagi.compose.domain.model.CustomFieldDefinitionModel;
import com.xspaceagi.compose.domain.util.BuildSqlUtil;
import com.xspaceagi.compose.sdk.enums.TableFieldTypeEnum;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.ComposeException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class UpdateTableDMLUtil {
    private UpdateTableDMLUtil() {}

    /**
     * 构建更新数据的SQL语句,根据id来修改行数
     */
    public static String buildUpdateSql(CustomTableDefinitionModel tableModel, Map<String, Object> rowData, Long id) {
        if (id == null) {
            throw ComposeException.build(BizExceptionCodeEnum.fieldRequiredButEmpty, "行ID");
        }
        if (rowData == null || rowData.isEmpty()) {
            throw ComposeException.build(BizExceptionCodeEnum.composeUpdateDataEmpty);
        }

        // 构建字段类型Map
        Map<String, Integer> fieldTypeMap = null;
        if (tableModel.getFieldList() != null) {
            fieldTypeMap = tableModel.getFieldList().stream()
                    .filter(f -> f != null && f.getFieldName() != null)
                    .collect(Collectors.toMap(CustomFieldDefinitionModel::getFieldName,
                            CustomFieldDefinitionModel::getFieldType));
        }

        StringBuilder setClause = new StringBuilder();
        for (Map.Entry<String, Object> entry : rowData.entrySet()) {
            String fieldName = entry.getKey();
            Object value = entry.getValue();
            Integer fieldType = fieldTypeMap != null ? fieldTypeMap.get(fieldName) : null;
            boolean isValueEmpty = (value == null) || (value instanceof String && ((String) value).trim().isEmpty());

            setClause.append("`").append(BuildSqlUtil.escapeSqlString(fieldName)).append("` = ");

            // 优先处理布尔类型
            if (fieldType != null && TableFieldTypeEnum.BOOLEAN.getCode().equals(fieldType)) {
                String boolStr = isValueEmpty ? null : String.valueOf(value);
                if ("true".equalsIgnoreCase(boolStr) || "1".equals(boolStr)) {
                    setClause.append("1");
                } else if ("false".equalsIgnoreCase(boolStr) || "0".equals(boolStr)) {
                    setClause.append("0");
                } else if (isValueEmpty) {
                    // 空值时设置为NULL
                    setClause.append("NULL");
                } else {
                    // 非空值但无法识别的布尔值，抛出异常
                    log.error("Boolean conversion failed, fieldName: {}, value: {}", fieldName, value);
                    String errorMessage = String.format("布尔类型转换失败，字段名: %s, 字段值: %s, 支持的值: true/false/1/0", 
                                                       fieldName, value);
                    throw ComposeException.build(BizExceptionCodeEnum.composeSqlExecuteFailed, errorMessage);
                }
            } else if (fieldType != null && TableFieldTypeEnum.DATE.getCode().equals(fieldType)) {
                // 新增：处理日期类型，自动转换 ISO 8601 到 MySQL DATETIME（东八区）
                if (!isValueEmpty && value instanceof String) {
                    String dateStr = (String) value;
                    try {
                        OffsetDateTime odt = OffsetDateTime.parse(dateStr, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                        LocalDateTime ldt = odt.atZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai")).toLocalDateTime();
                        String mysqlDateStr = ldt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                        setClause.append("'").append(mysqlDateStr).append("'");
                    } catch (Exception e) {
                        log.error("Date conversion failed, fieldName: {}, value: {}", fieldName, value, e);
                        String errorMessage = String.format("日期类型转换失败，字段名: %s, 字段值: %s, 错误: %s", 
                                                           fieldName, value, e.getMessage());
                        throw ComposeException.build(BizExceptionCodeEnum.composeSqlExecuteFailed, errorMessage);
                    }
                } else if (isValueEmpty) {
                    setClause.append("NULL");
                } else {
                    setClause.append("'").append(BuildSqlUtil.escapeSqlString(value == null ? "" : value.toString())).append("'");
                }
            } else if (isValueEmpty) {
                setClause.append("NULL");
            } else if (value instanceof Number) {
                setClause.append(value);
            } else {
                // 对于字符串类型的数值字段，验证是否为有效数字
                if (fieldType != null && (TableFieldTypeEnum.INTEGER.getCode().equals(fieldType)
                        || TableFieldTypeEnum.NUMBER.getCode().equals(fieldType))) {
                    try {
                        Double.parseDouble(value.toString());
                        setClause.append(value);
                    } catch (NumberFormatException e) {
                        log.error("Numeric conversion failed, fieldName: {}, value: {}", fieldName, value, e);
                        String errorMessage = String.format("数值类型转换失败，字段名: %s, 字段值: %s, 错误: %s", 
                                                           fieldName, value, e.getMessage());
                        throw ComposeException.build(BizExceptionCodeEnum.composeSqlExecuteFailed, errorMessage);
                    }
            } else {
                setClause.append("'").append(BuildSqlUtil.escapeSqlString(value == null ? "" : value.toString())).append("'");
                }
            }
            setClause.append(", ");
        }
        // 去掉最后的逗号和空格
        if (setClause.length() > 2) {
            setClause.setLength(setClause.length() - 2);
        }

        return "UPDATE `" + BuildSqlUtil.escapeSqlString(tableModel.getDorisDatabase()) + "`.`"
                + BuildSqlUtil.escapeSqlString(tableModel.getDorisTable()) + "` SET "
                + setClause + " WHERE id = " + id;
    }
} 