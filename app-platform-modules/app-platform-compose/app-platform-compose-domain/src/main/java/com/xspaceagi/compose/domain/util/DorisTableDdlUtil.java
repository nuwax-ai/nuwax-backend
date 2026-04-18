package com.xspaceagi.compose.domain.util;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.compose.domain.config.DorisProperties;
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
import org.springframework.beans.factory.annotation.Autowired;
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
 * Doris DDL语句生成工具类
 */
@Slf4j
@Component
public class DorisTableDdlUtil {

    /**
     * 静态变量，用于存储 DorisProperties
     */
    private static DorisProperties dorisProperties;

    @Autowired
    public void setDorisProperties(DorisProperties dorisProperties) {
        DorisTableDdlUtil.dorisProperties = dorisProperties;
        log.info("[DorisProperties] Doris config at startup: {}", JSON.toJSONString(dorisProperties));
    }

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

        // 固定 id 作为唯一键
        List<String> uniqueKeyFields = new java.util.ArrayList<>();
        uniqueKeyFields.add("id");

        int bucketNum = getBucketNum();

        StringBuilder sql = new StringBuilder();

        // 1. CREATE TABLE 语句头
        sql.append("CREATE TABLE IF NOT EXISTS `")
                .append(tableModel.getDorisDatabase()).append("`.`")
                .append(tableModel.getDorisTable()).append("` (\n");

        // 2. 添加 id 主键字段
        sql.append("    `id` BIGINT NOT NULL COMMENT '主键ID',\n");

        // 3. 字段定义
        String columnDefinitions = fields.stream()
                // 过滤掉id字段
                .filter(f -> !f.getFieldName().equals("id"))
                .map(DorisTableDdlUtil::buildColumnDefinitionSql)
                .collect(Collectors.joining(",\n"));
        sql.append(columnDefinitions).append("\n");

        // 4. 表属性
        sql.append(") \n")
                .append("ENGINE=OLAP\n")
                .append("UNIQUE KEY(`id`)\n")
                .append("COMMENT '").append(BuildSqlUtil.escapeSqlString(tableModel.getTableDescription()))
                .append("'\n")
                .append("DISTRIBUTED BY HASH(`id`) BUCKETS ").append(bucketNum).append("\n")
                .append("PROPERTIES (\n")
                .append("    \"replication_num\" = \"").append(getReplicationNum()).append("\",")
                .append("\n    \"enable_unique_key_merge_on_write\" = \"true\"\n")
                .append(")");

        log.debug("Built CREATE TABLE SQL for {}.{}: \n{}", tableModel.getDorisDatabase(), tableModel.getDorisTable(),
                sql.toString());
        return sql.toString();
    }

    /**
     * 获取重复键字段名
     * 如果配置文件中指定了字段名，则使用配置的字段名
     * 否则使用第一个字段作为重复键
     */
    private static String getDuplicateKey(List<CustomFieldDefinitionModel> fields) {
        if (dorisProperties != null
                && StringUtils.hasText(dorisProperties.getDuplicateKey())) {
            return dorisProperties.getDuplicateKey();
        }
        return fields.get(0).getFieldName(); // 默认使用第一个字段
    }

    /**
     * 获取分布键字段名
     * 如果配置文件中指定了字段名，则使用配置的字段名
     * 否则使用第一个字段作为分布键
     */
    private static String getDistributedKey(List<CustomFieldDefinitionModel> fields) {
        if (dorisProperties != null
                && StringUtils.hasText(dorisProperties.getDistributedKey())) {
            return dorisProperties.getDistributedKey();
        }
        return fields.get(0).getFieldName(); // 默认使用第一个字段
    }

    /**
     * 获取分桶数
     */
    private static int getBucketNum() {
        if (dorisProperties != null
                && dorisProperties.getBucketNum() != null) {
            return dorisProperties.getBucketNum();
        }
        return 10; // 默认值
    }

    /**
     * 获取副本数
     * 如果配置类未初始化，则返回默认值3
     */
    private static int getReplicationNum() {
        if (dorisProperties != null
                && dorisProperties.getReplicationNum() != null) {
            return dorisProperties.getReplicationNum();
        }
        return 3; // 默认值
    }

    /**
     * 构建单个字段的 SQL 定义部分
     * 
     * @param field 字段定义模型
     * @return 单个字段的 SQL 定义字符串
     */
    private static String buildColumnDefinitionSql(CustomFieldDefinitionModel field) {
        if (field == null || !StringUtils.hasText(field.getFieldName())) {
            log.warn("Skipping invalid field definition");
            return ""; // Or throw exception, depending on business logic
        }

        StringBuilder fieldSql = new StringBuilder();
        // Field name, quoted
        fieldSql.append("    `").append(field.getFieldName()).append("` ");

        // 特殊处理 modified 字段
        if ("modified".equalsIgnoreCase(field.getFieldName())) {
            fieldSql.append("DATETIME DEFAULT CURRENT_TIMESTAMP");
        } else {
            // Field type
            fieldSql.append(convertToDorisTypeDefinition(field.getFieldType()));

            // Mark if NOT NULL
            boolean isNotNull = field.getNullableFlag() != null && field.getNullableFlag() == -1;
            // 读取控制字段: DorisConfigContants.FIXED_FIELD_NULLABLE ,建表的时候,不限制字段
            if (DorisConfigContants.FIXED_FIELD_NULLABLE) {
                isNotNull = false;
            }
            // Nullability constraint
            if (isNotNull) {
                fieldSql.append(" NOT NULL");
            } else {
                fieldSql.append(" NULL"); // Explicitly add NULL
            }

            // Default value handling
            if (field.getDefaultValue() != null) { // Check if a default value was specified in the model
                String formattedDefault = formatDefaultValue(field.getDefaultValue(), field.getFieldType());
                // Only add DEFAULT clause if the default value is valid (formattedDefault !=
                // null)
                // AND (the column is nullable OR the default value is not "NULL")
                if (formattedDefault != null && (!isNotNull || !"NULL".equals(formattedDefault))) {
                    fieldSql.append(" DEFAULT ").append(formattedDefault);
                } else if (formattedDefault != null && isNotNull && "NULL".equals(formattedDefault)) {
                    // Log a warning if the column is NOT NULL but the default resolves to NULL
                    log.warn("Field '{}' is NOT NULL but default resolves to NULL; no DEFAULT clause will be added.", field.getFieldName());
                }
            }
        }
        // Field comment
        if (StringUtils.hasText(field.getFieldDescription())) {
            fieldSql.append(" COMMENT '").append(BuildSqlUtil.escapeSqlString(field.getFieldDescription())).append("'");
        }
        return fieldSql.toString();
    }

    /**
     * 将内部字段类型代码转换为Doris数据类型定义字符串
     * 
     * @param fieldTypeCode 内部字段类型代码 (来自 TableFieldTypeEnum 的 code)
     * @return Doris 数据类型定义字符串 (例如 "VARCHAR(255)", "INT")
     */
    private static String convertToDorisTypeDefinition(Integer fieldTypeCode) {
        if (fieldTypeCode == null) {
            log.warn("Field type code is null, returning default definition: {}", TableFieldTypeEnum.STRING.getDorisDefinition());
            return TableFieldTypeEnum.STRING.getDorisDefinition();
        }

        for (TableFieldTypeEnum typeEnum : TableFieldTypeEnum.values()) {
            if (typeEnum.getCode().equals(fieldTypeCode)) {
                return typeEnum.getDorisDefinition();
            }
        }

        log.warn("No TableFieldTypeEnum for code {}, default def: {}",
                fieldTypeCode, TableFieldTypeEnum.STRING.getDorisDefinition());
        return TableFieldTypeEnum.STRING.getDorisDefinition();
    }

    /**
     * 格式化 SQL 的默认值
     * 
     * @param defaultValue  默认值字符串
     * @param fieldTypeCode 字段类型代码
     * @return 格式化后的默认值字符串 (例如 'abc', 123, 'NULL')
     */
    private static String formatDefaultValue(String defaultValue, Integer fieldTypeCode) {
        // 空或 NULL 字符串直接返回 SQL NULL
        if (!StringUtils.hasText(defaultValue) || "NULL".equalsIgnoreCase(defaultValue.trim())) {
            return "NULL";
        }

        TableFieldTypeEnum typeEnum = TableFieldTypeEnum.STRING; // 默认按字符串处理
        if (fieldTypeCode != null) {
            for (TableFieldTypeEnum t : TableFieldTypeEnum.values()) {
                if (t.getCode().equals(fieldTypeCode)) {
                    typeEnum = t;
                    break;
                }
            }
        }
        if (typeEnum == TableFieldTypeEnum.STRING && fieldTypeCode != null
                && !TableFieldTypeEnum.STRING.getCode().equals(fieldTypeCode)) {
            log.warn("No enum for field type code {}, default as STRING", fieldTypeCode);
        }

        switch (typeEnum) {
            case INTEGER:
            case NUMBER:
                // 只允许数字，非法则转字符串
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
                log.warn("DATETIME field: default '{}' is not CURRENT_TIMESTAMP; no DEFAULT clause.", defaultValue);
                return null;
            case MEDIUMTEXT:
            case STRING:
                // 长文本和字符串类型，始终加引号
                return "'" + BuildSqlUtil.escapeSqlString(defaultValue) + "'";
            default:
                // 其它类型默认加引号
                return "'" + BuildSqlUtil.escapeSqlString(defaultValue) + "'";
        }
    }

    // --- ALTER TABLE 相关方法 ---

    /**
     * 比较新旧字段列表，生成 ALTER TABLE 语句列表。
     * 目前支持：
     * 1. ADD COLUMN: 添加新字段。
     * 2. MODIFY COLUMN COMMENT: 修改现有字段的注释。
     * 注意：不支持删除字段、修改字段类型、修改可空性、修改默认值等。
     *
     * @param database  数据库名
     * @param table     表名
     * @param oldFields 旧的字段定义列表 (当前数据库中的结构)
     * @param newFields 新的字段定义列表 (用户期望的结构)
     * @return List<String> 包含 ALTER TABLE SQL 语句的列表，如果没有变更则为空列表。
     */
    public static List<String> buildAlterTableSqls(String database, String table,
            List<CustomFieldDefinitionModel> oldFields,
            List<CustomFieldDefinitionModel> newFields) {

        List<String> alterStatements = new ArrayList<>();
        if (!StringUtils.hasText(database) || !StringUtils.hasText(table)) {
            log.error("Database name or table name cannot be empty");
            return alterStatements; // 返回空列表
        }
        if (newFields == null) {
            newFields = new ArrayList<>(); // 避免空指针
        }
        if (oldFields == null) {
            oldFields = new ArrayList<>(); // 避免空指针
        }

        // 使用字段名作为 Key 创建 Map，方便查找
        Map<String, CustomFieldDefinitionModel> oldFieldMap = oldFields.stream()
                .filter(f -> f != null && StringUtils.hasText(f.getFieldName()))
                .collect(Collectors.toMap(CustomFieldDefinitionModel::getFieldName, Function.identity(),
                        (f1, f2) -> f1)); // 重复时取第一个

        Map<String, CustomFieldDefinitionModel> newFieldMap = newFields.stream()
                .filter(f -> f != null && StringUtils.hasText(f.getFieldName()))
                .collect(Collectors.toMap(CustomFieldDefinitionModel::getFieldName, Function.identity(),
                        (f1, f2) -> f1)); // 重复时取第一个

        // --- 检查需要添加的字段 ---
        for (Map.Entry<String, CustomFieldDefinitionModel> entry : newFieldMap.entrySet()) {
            String fieldName = entry.getKey();
            CustomFieldDefinitionModel newField = entry.getValue();

            if (!oldFieldMap.containsKey(fieldName)) {
                // 新增字段
                String addColumnSql = buildAddColumnSql(newField);
                if (StringUtils.hasText(addColumnSql)) {
                    alterStatements.add(String.format("ALTER TABLE `%s`.`%s` ADD COLUMN %s",
                            database, table, addColumnSql));
                }
            }
        }

        // --- 检查需要修改注释的字段 ---
        for (Map.Entry<String, CustomFieldDefinitionModel> entry : newFieldMap.entrySet()) {
            String fieldName = entry.getKey();
            CustomFieldDefinitionModel newField = entry.getValue();

            if (oldFieldMap.containsKey(fieldName)) {
                CustomFieldDefinitionModel oldField = oldFieldMap.get(fieldName);
                // 比较注释是否变化 (注意 null 处理)
                String oldComment = oldField.getFieldDescription() == null ? "" : oldField.getFieldDescription();
                String newComment = newField.getFieldDescription() == null ? "" : newField.getFieldDescription();

                if (!Objects.equals(oldComment, newComment)) {
                    // 修改注释 (需要字段的完整定义，除了注释部分是新的)
                    // 注意: Doris 的 MODIFY COLUMN 语法通常需要提供完整的列定义
                    String modifyCommentSql = buildModifyColumnCommentSql(newField);
                    if (StringUtils.hasText(modifyCommentSql)) {
                        alterStatements.add(String.format("ALTER TABLE `%s`.`%s` MODIFY COLUMN %s",
                                database, table, modifyCommentSql));
                    }
                }

                // TODO: 在此添加对类型、可空性、默认值变化的比较和处理逻辑
            }
        }

        // --- 检查需要删除的字段 ---
        for (Map.Entry<String, CustomFieldDefinitionModel> entry : oldFieldMap.entrySet()) {
            String fieldName = entry.getKey();
            if (!newFieldMap.containsKey(fieldName)) {
                // 旧字段在新字段列表中不存在，需要生成 DROP COLUMN 语句
                // 注意：调用者需要根据业务规则（例如表是否有数据）来决定是否执行此语句
                log.warn("Column marked for drop: {}; ensure no data before DROP!", fieldName);
                alterStatements.add(String.format("ALTER TABLE `%s`.`%s` DROP COLUMN `%s`",
                        database, table, fieldName));
            }
        }

        if (!alterStatements.isEmpty()) {
            log.info("Generated {} ALTER statements for {}.{}: {}", database, table, alterStatements.size(), alterStatements);
        }

        return alterStatements;
    }

    /**
     * 构建 ADD COLUMN 子句 (不包含 ALTER TABLE ... ADD COLUMN)
     * 例如: `column_name` VARCHAR(255) NULL DEFAULT 'abc' COMMENT 'comment'
     */
    private static String buildAddColumnSql(CustomFieldDefinitionModel field) {
        if (field == null || !StringUtils.hasText(field.getFieldName())) {
            log.warn("Cannot ADD COLUMN for invalid field");
            return null;
        }
        StringBuilder sql = new StringBuilder();
        sql.append("`").append(field.getFieldName()).append("` ");
        sql.append(convertToDorisTypeDefinition(field.getFieldType()));

        if (field.getNullableFlag() != null && field.getNullableFlag() == -1) {
            sql.append(" NOT NULL");
        } else {
            sql.append(" NULL"); // 显式添加 NULL
        }

        if (field.getDefaultValue() != null) {
            sql.append(" DEFAULT ").append(formatDefaultValue(field.getDefaultValue(), field.getFieldType()));
        }

        if (StringUtils.hasText(field.getFieldDescription())) {
            sql.append(" COMMENT '").append(BuildSqlUtil.escapeSqlString(field.getFieldDescription())).append("'");
        }
        return sql.toString();
    }

    /**
     * 构建 MODIFY COLUMN 子句，主要用于修改注释 (不包含 ALTER TABLE ... MODIFY COLUMN)
     * 注意：Doris 通常要求提供完整的列定义。
     * 例如: `column_name` VARCHAR(255) NULL DEFAULT 'abc' COMMENT 'new_comment'
     */
    private static String buildModifyColumnCommentSql(CustomFieldDefinitionModel field) {
        // 实现与 buildAddColumnSql 几乎相同，因为需要提供完整的列定义
        return buildAddColumnSql(field);
    }

    /**
     * 构建创建数据库的SQL语句
     */
    public static String buildCreateDatabaseSql(String database) {
        return String.format("CREATE DATABASE IF NOT EXISTS `%s`", BuildSqlUtil.escapeSqlString(database));
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
        return "SELECT count(*) FROM information_schema.tables WHERE table_schema = '"
                + BuildSqlUtil.escapeSqlString(database) +
                "' AND table_name = '" + BuildSqlUtil.escapeSqlString(table) + "' LIMIT 1";
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
        // doris生成插入语句,需要主动设置id主键,没有自动生成,使用雪花算法生成id
        return InsertTableDMLUtil.buildDorisInsertSql(tableModel, rowData);
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

    /**
     * UNIQUE INDEX 的sql创建
     * 
     * @param database
     * @param table
     * @param fieldName
     * @return
     */
    public static String buildCreateUniqueIndexSql(String database, String table, String fieldName) {

        String indexName = "uk_" + fieldName;
        String sql = "ALTER TABLE `" + database + "`.`" + table +
                "` ADD INDEX " + indexName + "(`" + fieldName + "`) COMMENT '唯一索引'";
        return sql;
    }

    /**
     * 将字段类型转换为Doris数据类型
     * 
     * @param fieldType 字段类型
     * @return Doris数据类型
     */
    public static String convertToFieldType(Integer fieldType) {
        return convertToDorisTypeDefinition(fieldType);
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
                sqls.add(String.format("ALTER TABLE %s ADD UNIQUE INDEX %s (`%s`) COMMENT '唯一索引'",
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
