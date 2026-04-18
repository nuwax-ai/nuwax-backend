package com.xspaceagi.compose.domain.util.dml;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.stream.Collectors;

import com.xspaceagi.compose.domain.model.CustomFieldDefinitionModel;
import com.xspaceagi.compose.domain.model.CustomTableDefinitionModel;
import com.xspaceagi.compose.domain.util.BuildSqlUtil;
import com.xspaceagi.compose.domain.util.SnowflakeIdWorker;
import com.xspaceagi.compose.sdk.enums.DefaultTableFieldEnum;
import com.xspaceagi.compose.sdk.enums.TableFieldTypeEnum;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.ComposeException;

import lombok.extern.slf4j.Slf4j;

/**
 * 插入数据SQL语句工具类
 */
@Slf4j
public final class InsertTableDMLUtil {
    private InsertTableDMLUtil() {
    }

    /**
     * 构建插入数据的SQL语句 (MySQL)
     */
    public static String buildMysqlInsertSql(CustomTableDefinitionModel tableModel, Map<String, Object> rowData) {
        // MySQL：不生成id字段
        return buildInsertSqlInternal(tableModel, rowData, false, true);
    }

    /**
     * 构建插入数据的SQL语句 (Doris，主键id需主动生成)
     */
    public static String buildDorisInsertSql(CustomTableDefinitionModel tableModel, Map<String, Object> rowData) {
        // Doris：强制生成id字段
        return buildInsertSqlInternal(tableModel, rowData, true, false);
    }

    /**
     * 公共方法，生成 insert SQL
     * 
     * @param tableModel 表结构
     * @param rowData    行数据
     * @param forceId    是否强制生成主键id（Doris用）
     * @param skipId     是否跳过主键id（MySQL用）
     */
    private static String buildInsertSqlInternal(CustomTableDefinitionModel tableModel, Map<String, Object> rowData,
            boolean forceId, boolean skipId) {
        if (rowData == null || rowData.isEmpty()) {
            throw ComposeException.build(BizExceptionCodeEnum.composeInsertDataEmpty);
        }

        String idField = DefaultTableFieldEnum.ID.getFieldName();
        // Doris 需要主动生成主键id
        if (forceId) {
            String id = SnowflakeIdWorker.nextIdStr();
            rowData.put(idField, id);
        }
        // 固定插入创建时间和修改时间字段
        String createdField = DefaultTableFieldEnum.CREATED.getFieldName();
        String modifiedField = DefaultTableFieldEnum.MODIFIED.getFieldName();

        // 获取东八区当前系统时间
        ZoneId zoneId = ZoneId.of("Asia/Shanghai");
        String currentTime = OffsetDateTime.now(zoneId).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        // 覆写创建时间和修改时间
        rowData.put(createdField, currentTime);
        rowData.put(modifiedField, currentTime);

        // 构建字段类型Map
        Map<String, Integer> fieldTypeMap = null;
        Map<String, String> fieldDefaultMap = null;
        if (tableModel.getFieldList() != null) {
            fieldTypeMap = tableModel.getFieldList().stream()
                    .filter(f -> f != null && f.getFieldName() != null && f.getFieldType() != null)
                    .collect(Collectors.toMap(CustomFieldDefinitionModel::getFieldName,
                            CustomFieldDefinitionModel::getFieldType, (a, b) -> a));
            fieldDefaultMap = tableModel.getFieldList().stream()
                    .filter(f -> f != null && f.getFieldName() != null && f.getDefaultValue() != null)
                    .collect(Collectors.toMap(CustomFieldDefinitionModel::getFieldName,
                            CustomFieldDefinitionModel::getDefaultValue, (a, b) -> a));
        }

        StringBuilder columns = new StringBuilder();
        StringBuilder values = new StringBuilder();

        for (Map.Entry<String, Object> entry : rowData.entrySet()) {
            String fieldName = entry.getKey();
            // MySQL 跳过 id 字段
            if (skipId && idField.equalsIgnoreCase(fieldName)) {
                continue;
            }
            Object value = entry.getValue();
            columns.append("`").append(BuildSqlUtil.escapeSqlString(fieldName)).append("`, ");

            Integer fieldType = fieldTypeMap != null ? fieldTypeMap.get(fieldName) : null;
            String defaultVal = fieldDefaultMap != null ? fieldDefaultMap.get(fieldName) : null;
            boolean isValueEmpty = (value == null) || (value instanceof String && ((String) value).trim().isEmpty());

            // 布尔类型
            if (fieldType != null && TableFieldTypeEnum.BOOLEAN.getCode().equals(fieldType)) {
                String boolStr = isValueEmpty ? defaultVal : String.valueOf(value);
                if ("true".equalsIgnoreCase(boolStr) || "1".equals(boolStr)) {
                    values.append("1, ");
                } else if ("false".equalsIgnoreCase(boolStr) || "0".equals(boolStr)) {
                    values.append("0, ");
                } else if (isValueEmpty) {
                    // 空值时设置为NULL
                    values.append("NULL, ");
                } else {
                    // 非空值但无法识别的布尔值，抛出异常
                    log.error("Boolean conversion failed, fieldName: {}, value: {}", fieldName, value);
                    String errorMessage = String.format("布尔类型转换失败，字段名: %s, 字段值: %s, 支持的值: true/false/1/0",
                            fieldName, value);
                    throw ComposeException.build(BizExceptionCodeEnum.composeSqlExecuteFailed, errorMessage);
                }
                continue;
            }

            // 日期类型
            if (fieldType != null && TableFieldTypeEnum.DATE.getCode().equals(fieldType)) {
                if (!isValueEmpty && value instanceof String) {
                    String dateStr = (String) value;
                    try {
                        OffsetDateTime odt = OffsetDateTime.parse(dateStr, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                        LocalDateTime ldt = odt.atZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai"))
                                .toLocalDateTime();
                        String mysqlDateStr = ldt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                        values.append("'").append(mysqlDateStr).append("', ");
                        continue;
                    } catch (Exception e) {
                        log.error("Date conversion failed, fieldName: {}, value: {}", fieldName, value, e);
                        String errorMessage = String.format("日期类型转换失败，字段名: %s, 字段值: %s, 错误: %s",
                                fieldName, value, e.getMessage());
                        throw ComposeException.build(BizExceptionCodeEnum.composeSqlExecuteFailed, errorMessage);
                    }
                }
            }

            // 其它类型
            if (isValueEmpty) {
                if (defaultVal != null && !defaultVal.isEmpty()) {
                    if (fieldType != null && (TableFieldTypeEnum.INTEGER.getCode().equals(fieldType)
                            || TableFieldTypeEnum.NUMBER.getCode().equals(fieldType))) {
                        // 验证默认值是否为有效数字
                        try {
                            Double.parseDouble(defaultVal);
                            values.append(defaultVal).append(", ");
                        } catch (NumberFormatException e) {
                            log.error("Numeric default conversion failed, fieldName: {}, defaultValue: {}", fieldName, defaultVal, e);
                            String errorMessage = String.format("数值类型默认值转换失败，字段名: %s, 默认值: %s, 错误: %s",
                                    fieldName, defaultVal, e.getMessage());
                            throw ComposeException.build(BizExceptionCodeEnum.composeSqlExecuteFailed, errorMessage);
                        }
                    } else {
                        values.append("'").append(BuildSqlUtil.escapeSqlString(defaultVal)).append("', ");
                    }
                } else {
                    values.append("NULL, ");
                }
            } else if (value instanceof Number) {
                values.append(value).append(", ");
            } else {
                // 对于字符串类型的数值字段，验证是否为有效数字
                if (fieldType != null && (TableFieldTypeEnum.INTEGER.getCode().equals(fieldType)
                        || TableFieldTypeEnum.NUMBER.getCode().equals(fieldType))) {
                    try {
                        Double.parseDouble(value.toString());
                        values.append(value).append(", ");
                    } catch (NumberFormatException e) {
                        log.error("Numeric conversion failed, fieldName: {}, value: {}", fieldName, value, e);
                        String errorMessage = String.format("数值类型转换失败，字段名: %s, 字段值: %s, 错误: %s",
                                fieldName, value, e.getMessage());
                        throw ComposeException.build(BizExceptionCodeEnum.composeSqlExecuteFailed, errorMessage);
                    }
                } else {
                    values.append("'").append(BuildSqlUtil.escapeSqlString(value == null ? "" : value.toString()))
                            .append("', ");
                }
            }
        }

        // 去掉最后的逗号和空格
        columns.setLength(columns.length() - 2);
        values.setLength(values.length() - 2);

        var sql = "INSERT INTO `" + BuildSqlUtil.escapeSqlString(tableModel.getDorisDatabase()) + "`.`"
                + BuildSqlUtil.escapeSqlString(tableModel.getDorisTable()) + "` ("
                + columns + ") VALUES (" + values + ")";

        log.debug("Built INSERT INTO SQL for {}.{}: \n{}", tableModel.getDorisDatabase(), tableModel.getDorisTable(),
                sql);
        return sql;
    }
}