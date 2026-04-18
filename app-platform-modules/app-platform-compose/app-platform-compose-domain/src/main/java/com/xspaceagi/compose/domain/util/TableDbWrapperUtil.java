package com.xspaceagi.compose.domain.util;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.xspaceagi.compose.DatabaseTypeEnum;
import com.xspaceagi.compose.domain.model.CustomFieldDefinitionModel;
import com.xspaceagi.compose.domain.model.CustomTableDefinitionModel;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.ComposeException;

import lombok.extern.slf4j.Slf4j;

/**
 * tableDDlUtil的包装类,区分用户配置的 mysql,还是doris,使用对应的工具类
 * <p>
 * DorisTableDdlUtil , MysqlTableDdlUtil,根据配置,选择对应的工具类调用
 * </p>
 */
@Slf4j
@Component
public class TableDbWrapperUtil {

    private static final String DB_TYPE_MYSQL = DatabaseTypeEnum.MYSQL.getType();
    private static final String DB_TYPE_DORIS = DatabaseTypeEnum.DORIS.getType();

    @Value("${spring.datasource.sql-generator.type:mysql}")
    private String dbType;

    @Value("${spring.datasource.dynamic.datasource.doris.url}")
    private String dorisUrl;

    /**
     * 获取Doris数据库名称
     * 从数据源URL中解析数据库名称
     * 
     * @return 数据库名称
     */
    public String getDorisDatabase() {
        if (!StringUtils.hasText(dorisUrl)) {
            throw ComposeException.build(BizExceptionCodeEnum.composeDorisUrlNotConfigured);
        }
        try {
            // 示例URL:
            // jdbc:mysql://192.168.1.12:3306/agent_custom_table_test?serverTimezone=Asia/Shanghai...
            // 1. 先用?分割获取前半部分
            String urlWithoutParams = dorisUrl.split("\\?")[0];
            // 2. 再用/分割获取最后一部分即数据库名
            String[] parts = urlWithoutParams.split("/");
            String databaseName = parts[parts.length - 1];

            if (!StringUtils.hasText(databaseName)) {
                log.error("Failed to parse DB name from URL[{}], URL: {}", dorisUrl);
                throw ComposeException.build(BizExceptionCodeEnum.composeDorisDbNameParseFailed, "无法从URL中解析出数据库名称");
            }

            log.debug("Parsed DB name from URL[{}]: {}", dorisUrl, databaseName);
            return databaseName;
        } catch (Exception e) {
            log.error("Parse Doris DB name failed, URL: {}", dorisUrl, e);
            throw ComposeException.build(BizExceptionCodeEnum.composeDorisDbNameParseFailed, e.getMessage());
        }
    }

    /**
     * 构建创建数据库的SQL语句
     */
    public String buildCreateDatabaseSql(String database) {
        if (DB_TYPE_DORIS.equalsIgnoreCase(dbType)) {
            return DorisTableDdlUtil.buildCreateDatabaseSql(database);
        }
        return MysqlTableDdlUtil.buildCreateDatabaseSql(database);
    }

    /**
     * 构建删除数据库的SQL语句
     */
    public String buildDropDatabaseSql(String database) {
        if (DB_TYPE_DORIS.equalsIgnoreCase(dbType)) {
            return DorisTableDdlUtil.buildDropDatabaseSql(database);
        }
        return MysqlTableDdlUtil.buildDropDatabaseSql(database);
    }

    /**
     * 构建创建表的SQL语句
     */
    public String buildCreateTableSql(CustomTableDefinitionModel tableModel, List<CustomFieldDefinitionModel> fields) {
        validateDbType();
        if (DB_TYPE_DORIS.equalsIgnoreCase(dbType)) {
            return DorisTableDdlUtil.buildCreateTableSql(tableModel, fields);
        }
        return MysqlTableDdlUtil.buildCreateTableSql(tableModel, fields);
    }

    /**
     * 构建删除表的SQL语句
     */
    public String buildDropTableSql(String database, String table) {
        validateDbType();
        if (DB_TYPE_DORIS.equalsIgnoreCase(dbType)) {
            return DorisTableDdlUtil.buildDropTableSql(database, table);
        }
        return MysqlTableDdlUtil.buildDropTableSql(database, table);
    }

    /**
     * 生成修改表的SQL语句列表
     */
    public List<String> buildAlterTableSqls(String database, String table,
            List<CustomFieldDefinitionModel> oldFields,
            List<CustomFieldDefinitionModel> newFields) {
        validateDbType();
        if (DB_TYPE_DORIS.equalsIgnoreCase(dbType)) {
            return DorisTableDdlUtil.buildAlterTableSqls(database, table, oldFields, newFields);
        }
        return MysqlTableDdlUtil.buildAlterTableSqls(database, table, oldFields, newFields);
    }

    /**
     * 获取表的建表DDL语句
     *
     * @param database 数据库名
     * @param table    表名
     * @return 建表DDL语句
     */
    public String getShowCreateTableDdl(String database, String table) {
        validateDbType();
        if (DB_TYPE_DORIS.equalsIgnoreCase(dbType)) {
            return DorisTableDdlUtil.getCreateTableDdl(database, table);
        }
        return MysqlTableDdlUtil.getCreateTableDdl(database, table);
    }

    /**
     * 获取检查表是否存在的SQL语句
     *
     * @param database 数据库名
     * @param table    表名
     * @return 检查表是否存在的SQL语句
     */
    public String getTableExistsSql(String database, String table) {
        validateDbType();
        String sql;
        if (DB_TYPE_DORIS.equalsIgnoreCase(dbType)) {
            sql = DorisTableDdlUtil.getTableExistsSql(database, table);
        } else {
            sql = MysqlTableDdlUtil.getTableExistsSql(database, table);
        }
        log.debug("Check SQL exists, database: {}, table: {}, sql={}", database, table, sql);
        return sql;
    }

    /**
     * 获取查询表总记录数的SQL语句
     *
     * @param database 数据库名
     * @param table    表名
     * @return 查询表总记录数的SQL语句
     */
    public String getTableCountSql(String database, String table) {
        validateDbType();
        if (DB_TYPE_DORIS.equalsIgnoreCase(dbType)) {
            log.debug("Count SQL for Doris, database: {}, table: {}", database, table);
            return DorisTableDdlUtil.getTableCountSql(database, table);
        }
        log.debug("Count SQL for MySQL, database: {}, table: {}", database, table);
        return MysqlTableDdlUtil.getTableCountSql(database, table);
    }

    /**
     * 验证数据库类型配置是否有效
     */
    private void validateDbType() {
        if (!StringUtils.hasText(dbType)) {
            log.error("DB type not configured, spring.datasource.sql-generator.type, dbType: {}", dbType);
            throw ComposeException.build(BizExceptionCodeEnum.composeDbTypeNotConfigured);
        }
        if (!DB_TYPE_MYSQL.equalsIgnoreCase(dbType) && !DB_TYPE_DORIS.equalsIgnoreCase(dbType)) {
            log.error("Unsupported DB type: {}, only mysql or doris", dbType);
            throw ComposeException.build(BizExceptionCodeEnum.composeUnsupportedDbType, dbType);
        }
    }

    /**
     * 获取当前使用的数据库类型
     */
    public String getCurrentDbType() {
        return dbType;
    }

    /**
     * 构建插入数据的SQL语句
     * 
     * @param tableModel 表定义
     * @param rowData    行数据
     * @return 插入数据的SQL语句
     */
    public String buildInsertSql(CustomTableDefinitionModel tableModel, Map<String, Object> rowData) {
        validateDbType();
        if (DB_TYPE_DORIS.equalsIgnoreCase(dbType)) {
            return DorisTableDdlUtil.buildInsertSql(tableModel, rowData);
        }
        return MysqlTableDdlUtil.buildInsertSql(tableModel, rowData);
    }

    /**
     * 构建更新数据的SQL语句,根据id来修改行数
     * 
     * @param tableModel 表定义
     * @param rowData    行数据
     * @param id         行id
     * @return 更新数据的SQL语句
     */
    public String buildUpdateSql(CustomTableDefinitionModel tableModel, Map<String, Object> rowData, Long id) {
        validateDbType();
        if (DB_TYPE_DORIS.equalsIgnoreCase(dbType)) {
            return DorisTableDdlUtil.buildUpdateSql(tableModel, rowData, id);
        }
        return MysqlTableDdlUtil.buildUpdateSql(tableModel, rowData, id);
    }

    /**
     * 构建删除数据的SQL语句,根据id删除
     * 
     * @param tableModel 表定义
     * @param id         行id
     * @return 删除数据的SQL语句
     */
    public String buildDeleteSql(CustomTableDefinitionModel tableModel, Long id) {
        validateDbType();
        if (DB_TYPE_DORIS.equalsIgnoreCase(dbType)) {
            return DorisTableDdlUtil.buildDeleteSql(tableModel, id);
        }
        return MysqlTableDdlUtil.buildDeleteSql(tableModel, id);
    }

    /**
     * 构建创建唯一索引的SQL语句
     * 
     * @param database  数据库名
     * @param table     表名
     * @param fieldName 字段名
     * @return 创建唯一索引的SQL语句
     */
    public String buildCreateUniqueIndexSql(String database, String table, String fieldName) {
        validateDbType();
        if (DB_TYPE_DORIS.equalsIgnoreCase(dbType)) {
            return DorisTableDdlUtil.buildCreateUniqueIndexSql(database, table, fieldName);
        }
        return MysqlTableDdlUtil.buildCreateUniqueIndexSql(database, table, fieldName);
    }

    /**
     * 将字段类型转换为Doris数据类型
     * 
     * @param fieldType 字段类型
     * @return Doris数据类型
     */
    public String convertToFieldType(Integer fieldType) {
        // 判断数据库是mysql,还是 doris
        if (DB_TYPE_DORIS.equalsIgnoreCase(dbType)) {
            return DorisTableDdlUtil.convertToFieldType(fieldType);
        }
        return MysqlTableDdlUtil.convertToFieldType(fieldType);
    }

    /**
     * 生成 ALTER TABLE SQL语句
     * 
     * @param tableModel
     * @param fieldsToAdd
     * @param fieldsToUpdate
     * @param existingFieldMap
     * @return
     */
    public List<String> generateDorisAlterSqls(CustomTableDefinitionModel tableModel,
            List<CustomFieldDefinitionModel> fieldsToAdd,
            List<CustomFieldDefinitionModel> fieldsToUpdate,
            Map<String, CustomFieldDefinitionModel> existingFieldMap) {
        if (DB_TYPE_DORIS.equalsIgnoreCase(dbType)) {
            return DorisTableDdlUtil.generateDorisAlterSqls(tableModel, fieldsToAdd, fieldsToUpdate, existingFieldMap);
        }
        return MysqlTableDdlUtil.generateDorisAlterSqls(tableModel, fieldsToAdd, fieldsToUpdate, existingFieldMap);
    }

    /**
     * 获取数据库类型
     * 
     * @return
     */
    public String getDbType() {
        return dbType;
    }
}
