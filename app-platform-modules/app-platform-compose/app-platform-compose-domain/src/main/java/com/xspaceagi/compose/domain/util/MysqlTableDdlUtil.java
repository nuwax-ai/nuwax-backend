package com.xspaceagi.compose.domain.util;

import com.xspaceagi.compose.domain.model.CustomFieldDefinitionModel;
import com.xspaceagi.compose.domain.model.CustomTableDefinitionModel;
import com.xspaceagi.compose.domain.util.dml.DeleteTableDmlUtil;
import com.xspaceagi.compose.domain.util.dml.InsertTableDMLUtil;
import com.xspaceagi.compose.domain.util.dml.UpdateTableDMLUtil;
import com.xspaceagi.compose.sdk.enums.DefaultTableFieldEnum;
import com.xspaceagi.compose.sdk.enums.TableFieldTypeEnum;
import com.xspaceagi.compose.spec.constants.DorisConfigContants;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.ComposeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * MySQL DDL语句生成工具类
 */
@Slf4j
@Component
public class MysqlTableDdlUtil {

    /**
     * 构建创建表的SQL语句
     * 
     * @param tableModel 表定义模型
     * @param fields     字段定义列表
     * @return CREATE TABLE SQL 语句
     */
    public static String buildCreateTableSql(CustomTableDefinitionModel tableModel,
            List<CustomFieldDefinitionModel> fields) {
        if (tableModel == null || !StringUtils.hasText(tableModel.getDorisDatabase())
                || !StringUtils.hasText(tableModel.getDorisTable())) {
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "表定义、数据库名或表名不能为空");
        }
        if (CollectionUtils.isEmpty(fields)) {
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "创建表时至少需要一个字段");
        }

        StringBuilder sql = new StringBuilder();

        // 1. CREATE TABLE 语句头
        sql.append("CREATE TABLE IF NOT EXISTS `")
                .append(tableModel.getDorisDatabase()).append("`.`")
                .append(tableModel.getDorisTable()).append("` (\n");

        // 2. 添加 id 主键字段
        sql.append("    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',\n");

        // 3. 字段定义
        String columnDefinitions = fields.stream()
                // 过滤掉id字段
                .filter(f -> !f.getFieldName().equals("id"))
                .map(MysqlTableDdlUtil::buildColumnDefinitionSql)
                .collect(Collectors.joining(",\n"));
        sql.append(columnDefinitions).append(",\n");

        // 4. 添加主键约束
        sql.append("    PRIMARY KEY (`id`)\n");

        // 5. 表属性
        sql.append(") ENGINE=InnoDB\n")
                .append("DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci\n")
                .append("COMMENT='").append(BuildSqlUtil.escapeSqlString(tableModel.getTableDescription())).append("'");

        log.debug("Built CREATE TABLE SQL for {}.{}: \n{}", tableModel.getDorisDatabase(), tableModel.getDorisTable(),
                sql.toString());
        return sql.toString();
    }

    /**
     * 构建单个字段的 SQL 定义部分
     */
    private static String buildColumnDefinitionSql(CustomFieldDefinitionModel field) {
        if (field == null || !StringUtils.hasText(field.getFieldName())) {
            log.warn("Skipping invalid field definition");
            return "";
        }

        StringBuilder fieldSql = new StringBuilder();
        // 字段名
        fieldSql.append("    `").append(field.getFieldName()).append("` ");

        // 特殊处理 modified 字段
        if ("modified".equalsIgnoreCase(field.getFieldName())) {
            fieldSql.append("DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP");
        } else {
            // 字段类型
            fieldSql.append(convertToMysqlTypeDefinition(field.getFieldType()));

            // 标记是否为 NOT NULL,读取控制字段: DorisConfigContants.FIXED_FIELD_NULLABLE
            boolean isNotNull = field.getNullableFlag() != null && field.getNullableFlag() == -1;
            if (DorisConfigContants.FIXED_FIELD_NULLABLE) {
                isNotNull = false;
            }

            // 非空约束
            if (isNotNull) {
                fieldSql.append(" NOT NULL");
            } else {
                fieldSql.append(" NULL");
            }
            // 默认值处理
            if (!TableFieldTypeEnum.MEDIUMTEXT.getCode().equals(field.getFieldType())) {
                if (field.getDefaultValue() != null) {
                    String formattedDefault = formatDefaultValue(field.getDefaultValue(), field.getFieldType());
                    if (formattedDefault != null && (!isNotNull || !"NULL".equals(formattedDefault))) {
                        fieldSql.append(" DEFAULT ").append(formattedDefault);
                    } else if (formattedDefault != null && isNotNull && "NULL".equals(formattedDefault)) {
                        log.warn("Field '{}' is NOT NULL but default resolves to NULL; no DEFAULT clause will be added.", field.getFieldName());
                    }
                }
            }
        }
        // 字段注释
        if (StringUtils.hasText(field.getFieldDescription())) {
            fieldSql.append(" COMMENT '").append(BuildSqlUtil.escapeSqlString(field.getFieldDescription())).append("'");
        }
        return fieldSql.toString();
    }

    /**
     * 将内部字段类型代码转换为MySQL数据类型定义字符串
     */
    private static String convertToMysqlTypeDefinition(Integer fieldTypeCode) {
        if (fieldTypeCode == null) {
            log.warn("Field type code is null, returning default definition: {}", TableFieldTypeEnum.STRING.getMysqlDefinition());
            return TableFieldTypeEnum.STRING.getMysqlDefinition();
        }

        // MySQL的类型定义与Doris略有不同
        for (TableFieldTypeEnum typeEnum : TableFieldTypeEnum.values()) {
            if (typeEnum.getCode().equals(fieldTypeCode)) {
                switch (typeEnum) {
                    case STRING:
                        return typeEnum.getMysqlDefinition();
                    case INTEGER:
                        return typeEnum.getMysqlDefinition();
                    case NUMBER:
                        return typeEnum.getMysqlDefinition();
                    case BOOLEAN:
                        return typeEnum.getMysqlDefinition();
                    case DATE:
                        return typeEnum.getMysqlDefinition();
                    case MEDIUMTEXT:
                        return typeEnum.getMysqlDefinition();
                    default:
                        return typeEnum.getMysqlDefinition();
                }
            }
        }
        return TableFieldTypeEnum.STRING.getMysqlDefinition();
    }

    /**
     * 格式化 SQL 的默认值
     */
    private static String formatDefaultValue(String defaultValue, Integer fieldTypeCode) {
        if (!StringUtils.hasText(defaultValue) || "NULL".equalsIgnoreCase(defaultValue.trim())) {
            return "NULL";
        }

        TableFieldTypeEnum typeEnum = null;
        for (TableFieldTypeEnum t : TableFieldTypeEnum.values()) {
            if (t.getCode().equals(fieldTypeCode)) {
                typeEnum = t;
                break;
            }
        }
        if (typeEnum == null) {
            log.warn("Unknown field type code: {}, treat as string", fieldTypeCode);
            typeEnum = TableFieldTypeEnum.STRING;
        }

        switch (typeEnum) {
            case INTEGER:
            case NUMBER:
                try {
                    Double.parseDouble(defaultValue);
                    return defaultValue;
                } catch (NumberFormatException e) {
                    log.warn("Field is numeric but default '{}' is invalid, treating as string", defaultValue);
                    return "'" + BuildSqlUtil.escapeSqlString(defaultValue) + "'";
                }
            case BOOLEAN:
                if ("true".equalsIgnoreCase(defaultValue) || "1".equals(defaultValue))
                    return "1";
                if ("false".equalsIgnoreCase(defaultValue) || "0".equals(defaultValue))
                    return "0";
                log.warn("Field is boolean but default '{}' is invalid, using '0'", defaultValue);
                return "0";
            case DATE:
                if ("CURRENT_TIMESTAMP".equalsIgnoreCase(defaultValue.trim())) {
                    return "CURRENT_TIMESTAMP";
                }
                log.warn("Date field: default '{}' invalid; no DEFAULT clause.", defaultValue);
                return null;
            case MEDIUMTEXT:
                return "'" + BuildSqlUtil.escapeSqlString(defaultValue) + "'";
            case STRING:
            default:
                return "'" + BuildSqlUtil.escapeSqlString(defaultValue) + "'";
        }
    }

    /**
     * 生成修改表的SQL语句列表
     */
    public static List<String> buildAlterTableSqls(String database, String table,
            List<CustomFieldDefinitionModel> oldFields,
            List<CustomFieldDefinitionModel> newFields) {
        List<String> alterStatements = new ArrayList<>();
        if (!StringUtils.hasText(database) || !StringUtils.hasText(table)) {
            log.error("Database name or table name cannot be empty");
            return alterStatements;
        }

        if (newFields == null)
            newFields = new ArrayList<>();
        if (oldFields == null)
            oldFields = new ArrayList<>();

        Map<String, CustomFieldDefinitionModel> oldFieldMap = oldFields.stream()
                .filter(f -> f != null && StringUtils.hasText(f.getFieldName()))
                .collect(Collectors.toMap(CustomFieldDefinitionModel::getFieldName, Function.identity()));

        Map<String, CustomFieldDefinitionModel> newFieldMap = newFields.stream()
                .filter(f -> f != null && StringUtils.hasText(f.getFieldName()))
                .collect(Collectors.toMap(CustomFieldDefinitionModel::getFieldName, Function.identity()));

        // 处理新增字段
        for (CustomFieldDefinitionModel newField : newFields) {
            if (!oldFieldMap.containsKey(newField.getFieldName())) {
                String addColumnSql = buildAddColumnSql(newField);
                if (StringUtils.hasText(addColumnSql)) {
                    alterStatements.add(String.format("ALTER TABLE `%s`.`%s` ADD COLUMN %s",
                            database, table, addColumnSql));
                }
            }
        }

        // 处理修改字段
        for (Map.Entry<String, CustomFieldDefinitionModel> entry : newFieldMap.entrySet()) {
            String fieldName = entry.getKey();
            CustomFieldDefinitionModel newField = entry.getValue();
            CustomFieldDefinitionModel oldField = oldFieldMap.get(fieldName);

            if (oldField != null && needsModification(oldField, newField)) {
                String modifyColumnSql = buildModifyColumnSql(newField);
                if (StringUtils.hasText(modifyColumnSql)) {
                    alterStatements.add(String.format("ALTER TABLE `%s`.`%s` MODIFY COLUMN %s",
                            database, table, modifyColumnSql));
                }
            }
        }

        return alterStatements;
    }

    /**
     * 判断字段是否需要修改
     */
    private static boolean needsModification(CustomFieldDefinitionModel oldField, CustomFieldDefinitionModel newField) {
        return !Objects.equals(oldField.getFieldType(), newField.getFieldType()) ||
                !Objects.equals(oldField.getNullableFlag(), newField.getNullableFlag()) ||
                !Objects.equals(oldField.getDefaultValue(), newField.getDefaultValue()) ||
                !Objects.equals(oldField.getFieldDescription(), newField.getFieldDescription());
    }

    /**
     * 构建添加列的SQL
     */
    private static String buildAddColumnSql(CustomFieldDefinitionModel field) {
        return buildColumnDefinitionSql(field);
    }

    /**
     * 构建修改列的SQL
     */
    private static String buildModifyColumnSql(CustomFieldDefinitionModel field) {
        return buildColumnDefinitionSql(field);
    }

    /**
     * 构建创建数据库的SQL语句
     */
    public static String buildCreateDatabaseSql(String database) {
        return String.format(
                "CREATE DATABASE IF NOT EXISTS `%s` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci",
                BuildSqlUtil.escapeSqlString(database));
    }

    /**
     * 构建删除数据库的SQL语句
     */
    public static String buildDropDatabaseSql(String database) {
        return String.format("DROP DATABASE IF EXISTS `%s`", BuildSqlUtil.escapeSqlString(database));
    }

    /**
     * 构建删除表的SQL语句
     */
    public static String buildDropTableSql(String database, String table) {
        return String.format("DROP TABLE IF EXISTS `%s`.`%s`",
                BuildSqlUtil.escapeSqlString(database), BuildSqlUtil.escapeSqlString(table));
    }

    /**
     * 获取表的建表DDL语句
     *
     * @param database 数据库名
     * @param table    表名
     * @return 建表DDL语句
     */
    public static String getCreateTableDdl(String database, String table) {
        if (!StringUtils.hasText(database) || !StringUtils.hasText(table)) {
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "数据库名或表名不能为空");
        }
        return "SHOW CREATE TABLE `" + database + "`.`" + table + "`";
    }

    /**
     * 获取检查表是否存在的SQL语句
     *
     * @param database 数据库名
     * @param table    表名
     * @return 检查表是否存在的SQL语句
     */
    public static String getTableExistsSql(String database, String table) {
        if (!StringUtils.hasText(database) || !StringUtils.hasText(table)) {
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "数据库名或表名不能为空");
        }
        return "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '"
                + BuildSqlUtil.escapeSqlString(database) +
                "' AND table_name = '" + BuildSqlUtil.escapeSqlString(table) + "'";
    }

    /**
     * 获取查询表总记录数的SQL语句
     *
     * @param database 数据库名
     * @param table    表名
     * @return 查询表总记录数的SQL语句
     */
    public static String getTableCountSql(String database, String table) {
        if (!StringUtils.hasText(database) || !StringUtils.hasText(table)) {
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "数据库名或表名不能为空");
        }
        return "SELECT COUNT(*) FROM `" + BuildSqlUtil.escapeSqlString(database) + "`.`"
                + BuildSqlUtil.escapeSqlString(table) + "`";
    }

    /**
     * 构建插入数据的SQL语句 (使用 INSERT INTO ... SET ... 语法)
     */
    public static String buildInsertSql(CustomTableDefinitionModel tableModel, Map<String, Object> rowData) {
        return InsertTableDMLUtil.buildMysqlInsertSql(tableModel, rowData);
    }

    /**
     * 构建更新数据的SQL语句,根据id来修改行数
     */
    public static String buildUpdateSql(CustomTableDefinitionModel tableModel, Map<String, Object> rowData, Long id) {
        return UpdateTableDMLUtil.buildUpdateSql(tableModel, rowData, id);
    }

    /**
     * 构建删除数据的SQL语句,根据id删除
     */
    public static String buildDeleteSql(CustomTableDefinitionModel tableModel, Long id) {
        return DeleteTableDmlUtil.buildDeleteSql(tableModel, id);
    }

    public static String buildCreateUniqueIndexSql(String database, String table, String fieldName) {
        String indexName = "uk_" + fieldName;
        return String.format("ALTER TABLE `%s`.`%s` ADD UNIQUE INDEX %s(`%s`);", database, table, indexName, fieldName);
    }

    /**
     * 将字段类型转换为Mysql数据类型
     * 
     * @param fieldType 字段类型
     * @return Mysql数据类型
     */
    public static String convertToFieldType(Integer fieldType) {
        return convertToMysqlTypeDefinition(fieldType);
    }

    /**
     * 生成Doris ALTER TABLE SQL语句
     */
    public static List<String> generateDorisAlterSqls(CustomTableDefinitionModel tableModel,
            List<CustomFieldDefinitionModel> fieldsToAdd,
            List<CustomFieldDefinitionModel> fieldsToUpdate,
            Map<String, CustomFieldDefinitionModel> existingFieldMap) {
        List<String> sqls = new ArrayList<>();
        String tableName = "`" + tableModel.getDorisDatabase() + "`.`" + tableModel.getDorisTable() + "`";

        // 添加新字段
        for (CustomFieldDefinitionModel field : fieldsToAdd) {
            // 字段是否允许为空,读取变量 DorisConfigContants.FIXED_FIELD_NULLABLE
            boolean isNotNull = DorisConfigContants.FIXED_FIELD_NULLABLE ? false
                    : field.getNullableFlag() != null && field.getNullableFlag() == -1;
            boolean hasDefault = field.getDefaultValue() != null && StringUtils.hasText(field.getDefaultValue());
            String defaultClause = "";
            if (!TableFieldTypeEnum.MEDIUMTEXT.getCode().equals(field.getFieldType()) && hasDefault) {
                String formattedDefault = formatDefaultValue(field.getDefaultValue(), field.getFieldType());
                if (formattedDefault != null) {
                    defaultClause = "DEFAULT " + formattedDefault;
                }
            }
            String dorisType = convertToFieldType(field.getFieldType());
            sqls.add(String.format("ALTER TABLE %s ADD COLUMN %s %s %s %s COMMENT '%s'",
                    tableName,
                    "`" + field.getFieldName() + "`",
                    dorisType,
                    isNotNull ? "NOT NULL" : "NULL",
                    defaultClause,
                    field.getFieldDescription() != null ? field.getFieldDescription() : ""));
            // 如果新字段需要唯一索引
            if (field.getUniqueFlag() != null && field.getUniqueFlag() == 1) {
                String indexName = "uk_" + field.getFieldName();
                sqls.add(String.format("ALTER TABLE %s ADD UNIQUE INDEX %s (`%s`)  COMMENT '唯一索引'",
                        tableName, indexName, field.getFieldName()));
            }
        }

        // 修改现有字段 (主要处理类型变更，约束变更在 validateFieldConstraintChanges 中已阻止)
        for (CustomFieldDefinitionModel field : fieldsToUpdate) {
            CustomFieldDefinitionModel existingField = existingFieldMap.get(field.getFieldName());
            // 且不是系统字段,通过名称来获取,如果 defaultTableFieldEnum 有值,表示是系统字段
            DefaultTableFieldEnum defaultTableFieldEnum = DefaultTableFieldEnum
                    .getEnumByFieldName(field.getFieldName());
            // 另外看系统的描述,默认值,字段类型,是否必填,有修改,则需要生成 ALTER TABLE 语句
            // 1. 默认值
            // 2. 字段类型
            // 3. 是否必填
            // 4. 字段描述
            // 5. 字段名
            // 6. 字段类型

            var fieldChange = existingField != null && Objects.isNull(defaultTableFieldEnum)
                    && (!Objects.equals(existingField.getDefaultValue(), field.getDefaultValue())
                    || !Objects.equals(existingField.getFieldDescription(), field.getFieldDescription()));

            if (existingField != null && Objects.isNull(defaultTableFieldEnum) && fieldChange) {
                log.warn("Field [{}] type changed from {} to {}, generating MODIFY COLUMN; confirm Doris supports this without data loss",
                        field.getFieldName(), existingField.getFieldType(), field.getFieldType());
                boolean isNotNull = DorisConfigContants.FIXED_FIELD_NULLABLE ? false
                        : field.getNullableFlag() != null && field.getNullableFlag() == -1;
                boolean hasDefault = field.getDefaultValue() != null && StringUtils.hasText(field.getDefaultValue());
                String defaultClause = "";
                if (!TableFieldTypeEnum.MEDIUMTEXT.getCode().equals(field.getFieldType()) && hasDefault) {
                    String formattedDefault = formatDefaultValue(field.getDefaultValue(), field.getFieldType());
                    if (formattedDefault != null) {
                        defaultClause = "DEFAULT " + formattedDefault;
                    }
                }
                String dorisType = convertToFieldType(field.getFieldType());
                sqls.add(String.format("ALTER TABLE %s MODIFY COLUMN %s %s %s %s COMMENT '%s'",
                        tableName,
                        "`" + field.getFieldName() + "`",
                        dorisType,
                        isNotNull ? "NOT NULL" : "NULL",
                        defaultClause,
                        field.getFieldDescription() != null ? field.getFieldDescription() : ""));
            }
            // Note: 修改字段的其他属性如 COMMENT 也可以在这里处理
        }

        return sqls;
    }
}