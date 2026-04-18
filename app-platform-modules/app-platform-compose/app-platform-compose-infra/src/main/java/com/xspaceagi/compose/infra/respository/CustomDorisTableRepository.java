package com.xspaceagi.compose.infra.respository;

import com.baomidou.dynamic.datasource.annotation.DS;
import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.baomidou.dynamic.datasource.tx.DsPropagation;
import com.xspaceagi.compose.domain.repository.ICustomDorisTableRepository;
import com.xspaceagi.compose.domain.util.SqlParserUtil;
import com.xspaceagi.compose.domain.util.SqlParserUtil.SqlType;
import com.xspaceagi.compose.domain.util.TableDbWrapperUtil;
import com.xspaceagi.compose.sdk.enums.TableFieldTypeEnum;
import com.xspaceagi.compose.sdk.vo.data.ExecuteRawResultVo;
import com.xspaceagi.compose.sdk.vo.doris.DorisTableDefinitionVo;
import com.xspaceagi.compose.sdk.vo.doris.DorisTableFieldVo;
import com.xspaceagi.compose.sdk.vo.doris.DorisTableIndexVo;
import com.xspaceagi.compose.spec.constants.DorisConfigContants;
import com.xspaceagi.compose.spec.utils.ComposeExceptionUtils;
import com.xspaceagi.compose.spec.utils.DDLSqlParseUtil;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.ComposeException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.*;

@Slf4j
@Repository
@DS("doris")
public class CustomDorisTableRepository implements ICustomDorisTableRepository {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private TableDbWrapperUtil tableDbWrapperUtil;

    @Lazy
    @Resource
    private CustomDorisTableRepository self;

    @DS("doris")
    @Override
    public boolean hasData(String database, String table) {
        String sql = "SELECT COUNT(*) FROM `" + database + "`.`" + table + "` LIMIT 1";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
        return count != null && count > 0;

    }

    @DSTransactional(propagation = DsPropagation.NOT_SUPPORTED)
    @Override
    public boolean tableExists(String database, String table) {

        String sql = tableDbWrapperUtil.getTableExistsSql(database, table);
        List<Map<String, Object>> result = jdbcTemplate.queryForList(sql);
        return !result.isEmpty();

    }

    @Override
    public void executeCreateTable(String createTableSql) {
        jdbcTemplate.execute(createTableSql);

    }

    @Override
    public void createUniqueIndex(String database, String table, String fieldName) {
        String sql = tableDbWrapperUtil.buildCreateUniqueIndexSql(database, table, fieldName);
        log.info("Create unique index, sql={}", sql);
        jdbcTemplate.execute(sql);

    }

    @Override
    public void dropTable(String database, String table) {
        String sql = "DROP TABLE IF EXISTS `" + database + "`.`" + table + "`";
        jdbcTemplate.execute(sql);

    }

    @Override
    public void truncateTable(String database, String table) {
        String sql = "TRUNCATE TABLE `" + database + "`.`" + table + "`";
        jdbcTemplate.execute(sql);

    }

    @Override
    public boolean isFieldValueUnique(String database, String table, String fieldName, String fieldValue) {
        String sql = "SELECT COUNT(*) FROM `" + database + "`.`" + table + "` WHERE `" + fieldName + "` = ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, fieldValue);
        return count != null && count.equals(0);

    }

    @Override
    public void executeAlterTable(String database, String table, String alterSql) {
        try {
            log.debug("Execute Doris ALTER SQL: {}", alterSql);
            jdbcTemplate.execute(alterSql);
        } catch (Exception e) {
            log.error("Doris schema change error, database: {}, table: {}, sql: {}", database, table, alterSql, e);
            var errorMessage = ComposeExceptionUtils.getRootErrorMessage(e);
            throw ComposeException.build(BizExceptionCodeEnum.composeSqlExecuteFailed, errorMessage);
        }
    }

    @Override
    public int deleteTableDataById(String database, String table, Long id) {
        try {
            String sql = "DELETE FROM `" + database + "`.`" + table + "` WHERE id = ?";
            return jdbcTemplate.update(sql, id);
        } catch (Exception e) {
            log.error("Doris delete row error, database: {}, table: {}, id: {}", database, table, id, e);
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "删除数据失败");
        }
    }

    @Override
    public DorisTableDefinitionVo getTableDefinition(String database, String table) {
        try {
            // 1. 获取表的建表 DDL
            String createTableDdl = getCreateTableDdl(database, table);
            log.info("Table DDL: database={}, table={}, ddl={}", database, table, createTableDdl);

            // 2. 获取表的字段信息
            String descTableSql = "DESC `" + database + "`.`" + table + "`";
            List<Map<String, Object>> fieldsResult = jdbcTemplate.queryForList(descTableSql);

            // 3. 获取表的索引信息
            String showIndexSql = "SHOW INDEX FROM `" + database + "`.`" + table + "`";
            List<Map<String, Object>> indexesResult = jdbcTemplate.queryForList(showIndexSql);

            // 4. 构建返回对象
            DorisTableDefinitionVo definition = new DorisTableDefinitionVo();
            definition.setDatabase(database);
            definition.setTable(table);
            definition.setCreateTableDdl(createTableDdl);

            // 调用新的工具类方法进行解析
            DDLSqlParseUtil.parseCreateTableDdl(createTableDdl, definition);

            // 构建字段列表
            List<DorisTableFieldVo> fields = new ArrayList<>();
            for (Map<String, Object> field : fieldsResult) {

                var fieldType = (String) field.get("Type");
                // 根据Doris/MySQL 类型获取枚举值
                var fieldTypeEnum = TableFieldTypeEnum.tryGetByDorisType(fieldType);
                var fieldTypeInteger = Optional.ofNullable(fieldTypeEnum).map(TableFieldTypeEnum::getCode).orElse(null);
                DorisTableFieldVo fieldVo = DorisTableFieldVo.builder()
                        .fieldName((String) field.get("Field"))
                        .fieldType(fieldTypeInteger)
                        .fieldTypeDesc(fieldType)
                        .nullable("YES".equals(field.get("Null")))
                        .defaultValue((String) field.get("Default"))
                        .comment((String) field.get("Comment"))
                        .build();
                fields.add(fieldVo);
            }
            definition.setFields(fields);

            // 构建索引列表
            Map<String, DorisTableIndexVo> indexMap = new HashMap<>();
            for (Map<String, Object> index : indexesResult) {
                String indexName = (String) index.get("Key_name");
                String column = (String) index.get("Column_name");
                String indexType = (String) index.get("Index_type");
                String indexComment = (String) index.get("Index_comment");

                DorisTableIndexVo indexVo = indexMap.computeIfAbsent(indexName, k -> {
                    DorisTableIndexVo vo = new DorisTableIndexVo();
                    vo.setIndexName(indexName);
                    vo.setIndexType(indexType);
                    vo.setComment(indexComment);
                    vo.setIndexColumns(column);
                    return vo;
                });

                // 如果已存在，则追加列名
                if (!column.equals(indexVo.getIndexColumns())) {
                    indexVo.setIndexColumns(indexVo.getIndexColumns() + "," + column);
                }
            }
            definition.setIndexes(new ArrayList<>(indexMap.values()));

            return definition;
        } catch (Exception e) {
            log.error("Get Doris table definition error, database: {}, table: {}", database, table, e);
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "获取表定义失败");
        }
    }

    @Override
    public int insertRow(String database, String table, Map<String, Object> data) {
        try {
            if (data == null || data.isEmpty()) {
                throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "插入数据不能为空");
            }

            StringBuilder sql = new StringBuilder("INSERT INTO `").append(database).append("`.`")
                    .append(table).append("` (");
            StringBuilder values = new StringBuilder(") VALUES (");
            List<Object> params = new ArrayList<>();

            for (Map.Entry<String, Object> entry : data.entrySet()) {
                sql.append("`").append(entry.getKey()).append("`,");
                values.append("?,");
                params.add(entry.getValue());
            }

            // 移除最后的逗号
            sql.setLength(sql.length() - 1);
            values.setLength(values.length() - 1);
            sql.append(values).append(")");

            return jdbcTemplate.update(sql.toString(), params.toArray());
        } catch (Exception e) {
            log.error("Doris insert error, database: {}, table: {}, data: {}", database, table, data, e);
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "插入数据失败");
        }
    }

    @Override
    public int batchInsert(String database, String table, List<Map<String, Object>> dataList) {
        try {
            if (CollectionUtils.isEmpty(dataList)) {
                return 0;
            }

            // 获取所有字段名
            Set<String> allFields = new HashSet<>();
            for (Map<String, Object> data : dataList) {
                allFields.addAll(data.keySet());
            }

            StringBuilder sql = new StringBuilder("INSERT INTO `").append(database).append("`.`")
                    .append(table).append("` (");

            // 构建字段列表
            for (String field : allFields) {
                sql.append("`").append(field).append("`,");
            }
            sql.setLength(sql.length() - 1);
            sql.append(") VALUES ");

            // 构建值占位符
            String valuePlaceholder = "(" + String.join(",", Collections.nCopies(allFields.size(), "?")) + ")";
            List<String> valuePlaceholders = Collections.nCopies(dataList.size(), valuePlaceholder);
            sql.append(String.join(",", valuePlaceholders));

            // 准备参数
            List<Object> params = new ArrayList<>();
            for (Map<String, Object> data : dataList) {
                for (String field : allFields) {
                    params.add(data.get(field));
                }
            }

            return jdbcTemplate.update(sql.toString(), params.toArray());
        } catch (Exception e) {
            log.error("Doris batch insert error, database: {}, table: {}, dataSize: {}",
                    database, table, dataList.size(), e);
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "批量插入数据失败");
        }
    }

    @Override
    public int updateRow(String database, String table, Long id, Map<String, Object> data) {
        try {
            if (data == null || data.isEmpty()) {
                throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "更新数据不能为空");
            }

            StringBuilder sql = new StringBuilder("UPDATE `").append(database).append("`.`")
                    .append(table).append("` SET ");
            List<Object> params = new ArrayList<>();

            for (Map.Entry<String, Object> entry : data.entrySet()) {
                sql.append("`").append(entry.getKey()).append("` = ?,");
                params.add(entry.getValue());
            }

            sql.setLength(sql.length() - 1);
            sql.append(" WHERE id = ?");
            params.add(id);

            return jdbcTemplate.update(sql.toString(), params.toArray());
        } catch (Exception e) {
            log.error("Doris update error, database: {}, table: {}, id: {}, data: {}",
                    database, table, id, data, e);
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "更新数据失败");
        }
    }

    @Override
    public int batchUpdate(String database, String table, List<Map<String, Object>> dataList) {
        try {
            if (CollectionUtils.isEmpty(dataList)) {
                return 0;
            }

            // 使用 CASE WHEN 语法进行批量更新
            StringBuilder sql = new StringBuilder("UPDATE `").append(database).append("`.`")
                    .append(table).append("` SET ");

            // 获取所有需要更新的字段（排除id）
            Set<String> updateFields = new HashSet<>();
            List<Object> params = new ArrayList<>();
            for (Map<String, Object> data : dataList) {
                updateFields.addAll(data.keySet());
            }
            updateFields.remove("id");

            // 构建每个字段的 CASE WHEN 语句
            boolean first = true;
            for (String field : updateFields) {
                if (!first) {
                    sql.append(", ");
                }
                first = false;

                sql.append("`").append(field).append("` = CASE id ");
                for (Map<String, Object> data : dataList) {
                    if (data.containsKey(field)) {
                        sql.append("WHEN ? THEN ? ");
                        params.add(data.get("id"));
                        params.add(data.get(field));
                    }
                }
                sql.append("ELSE `").append(field).append("` END");
            }

            // 添加 WHERE 条件
            sql.append(" WHERE id IN (");
            sql.append(String.join(",", Collections.nCopies(dataList.size(), "?")));
            sql.append(")");

            // 添加 id 参数
            for (Map<String, Object> data : dataList) {
                params.add(data.get("id"));
            }

            return jdbcTemplate.update(sql.toString(), params.toArray());
        } catch (Exception e) {
            log.error("Doris batch update error, database: {}, table: {}, dataSize: {}",
                    database, table, dataList.size(), e);
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "批量更新数据失败");
        }
    }

    @Override
    public List<Map<String, Object>> queryByConditions(String database, String table,
            Map<String, Object> conditions, String orderBy, Integer offset, Integer limit) {
        try {
            StringBuilder sql = new StringBuilder("SELECT * FROM `").append(database).append("`.`")
                    .append(table).append("`");
            List<Object> params = new ArrayList<>();

            // 添加查询条件
            if (conditions != null && !conditions.isEmpty()) {
                sql.append(" WHERE ");
                boolean first = true;
                for (Map.Entry<String, Object> entry : conditions.entrySet()) {
                    if (!first) {
                        sql.append(" AND ");
                    }
                    first = false;
                    sql.append("`").append(entry.getKey()).append("` = ?");
                    params.add(entry.getValue());
                }
            }

            // 添加排序
            if (StringUtils.hasText(orderBy)) {
                sql.append(" ORDER BY ").append(orderBy);
            }

            // 添加分页
            if (offset != null && limit != null) {
                sql.append(" LIMIT ?, ?");
                params.add(offset);
                params.add(limit);
            }

            return jdbcTemplate.queryForList(sql.toString(), params.toArray());
        } catch (Exception e) {
            log.error("Doris query error, database: {}, table: {}, conditions: {}",
                    database, table, conditions, e);
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "查询数据失败");
        }
    }

    @Override
    public long countByConditions(String database, String table, Map<String, Object> conditions) {
        try {
            StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM `").append(database)
                    .append("`.`").append(table).append("`");
            List<Object> params = new ArrayList<>();

            // 添加查询条件
            if (conditions != null && !conditions.isEmpty()) {
                sql.append(" WHERE ");
                boolean first = true;
                for (Map.Entry<String, Object> entry : conditions.entrySet()) {
                    if (!first) {
                        sql.append(" AND ");
                    }
                    first = false;
                    sql.append("`").append(entry.getKey()).append("` = ?");
                    params.add(entry.getValue());
                }
            }

            return jdbcTemplate.queryForObject(sql.toString(), Long.class, params.toArray());
        } catch (Exception e) {
            log.error("Doris count error, database: {}, table: {}, conditions: {}",
                    database, table, conditions, e);
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "统计数据失败");
        }
    }

    @Override
    public int deleteByConditions(String database, String table, Map<String, Object> conditions) {
        try {
            if (conditions == null || conditions.isEmpty()) {
                throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "删除条件不能为空");
            }

            StringBuilder sql = new StringBuilder("DELETE FROM `").append(database).append("`.`")
                    .append(table).append("` WHERE ");
            List<Object> params = new ArrayList<>();

            boolean first = true;
            for (Map.Entry<String, Object> entry : conditions.entrySet()) {
                if (!first) {
                    sql.append(" AND ");
                }
                first = false;
                sql.append("`").append(entry.getKey()).append("` = ?");
                params.add(entry.getValue());
            }

            return jdbcTemplate.update(sql.toString(), params.toArray());
        } catch (Exception e) {
            log.error("Doris delete error, database: {}, table: {}, conditions: {}",
                    database, table, conditions, e);
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "删除数据失败");
        }
    }

    @Override
    public int batchDelete(String database, String table, List<Long> ids) {
        try {
            if (CollectionUtils.isEmpty(ids)) {
                return 0;
            }

            String sql = "DELETE FROM `" + database + "`.`" + table + "` WHERE id IN (" +
                    String.join(",", Collections.nCopies(ids.size(), "?")) + ")";

            return jdbcTemplate.update(sql, ids.toArray());
        } catch (Exception e) {
            log.error("Doris batch delete error, database: {}, table: {}, ids: {}",
                    database, table, ids, e);
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "批量删除数据失败");
        }
    }

    @Override
    public List<Map<String, Object>> queryAllData(String database, String table,
            Map<String, Object> conditions, String orderBy, Integer limit) {
        try {
            // 如果 limit 无效，设置默认最大值 DorisConfigContants.EXPORT_EXCEL_DATA_MAX_ROWS
            int effectiveLimit = (limit != null && limit > 0) ? limit : DorisConfigContants.EXPORT_EXCEL_DATA_MAX_ROWS;

            // 调用现有带分页的方法，offset 设为 0
            return queryByConditions(database, table, conditions, orderBy, 0, effectiveLimit);
        } catch (Exception e) {
            // 异常处理和日志记录已在 queryByConditions 中完成，这里可以不再重复记录
            // 但仍然需要向上抛出或包装异常
            log.error("Doris query all error (limit={}), database: {}, table: {}, conditions: {}",
                    limit, database, table, conditions, e);
            throw ComposeException.build(BizExceptionCodeEnum.composeQueryAllDataFailed);
        }
    }

    @Override
    public ExecuteRawResultVo executeRawQuery(String sql, Object... params) {
        try {
            if (!StringUtils.hasText(sql)) {
                throw ComposeException.build(BizExceptionCodeEnum.composeSqlEmpty);
            }

            SqlType sqlType;
            try {
                sqlType = SqlParserUtil.getSqlType(sql);
            } catch (JSQLParserException e) {
                log.error("SQL parse failed: {}", sql, e);
                var errorMessage = ComposeExceptionUtils.getRootErrorMessage(e);
                throw ComposeException.build(BizExceptionCodeEnum.composeSqlExecuteFailed, errorMessage);
            }

            switch (sqlType) {
                case SELECT: {
                    log.debug("Raw Doris query SQL: {}, params: {}", sql, params);
                    var result = jdbcTemplate.queryForList(sql, params);
                    return ExecuteRawResultVo.builder()
                            .data(result)
                            .rowNum((long) result.size())
                            .build();
                }
                case UPDATE: {
                    log.debug("Raw Doris update SQL: {}, params: {}", sql, params);
                    int affectedRows = jdbcTemplate.update(sql, params);
                    return ExecuteRawResultVo.builder()
                            .rowNum((long) affectedRows)
                            .build();
                }
                case INSERT: {
                    log.debug("Raw Doris insert SQL: {}, params: {}", sql, params);
                    KeyHolder keyHolder = new GeneratedKeyHolder();
                    try {
                        int affectedRows = jdbcTemplate.update(connection -> {
                            var ps = connection.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS);
                            for (int i = 0; i < params.length; i++) {
                                ps.setObject(i + 1, params[i]);
                            }
                            return ps;
                        }, keyHolder);

                        // 判断是否为批量插入（通过检查keyHolder中的key数量）
                        List<Map<String, Object>> keyList = keyHolder.getKeyList();
                        if (keyList != null && keyList.size() > 1) {
                            // 批量插入情况，获取所有生成的主键ID
                            log.info("Batch insert, affected: {}, generated keys: {}", affectedRows, keyList.size());

                            // 提取所有生成的主键ID
                            List<Long> generatedIds = new ArrayList<>();
                            for (Map<String, Object> keyMap : keyList) {
                                // 通常主键列名为"GENERATED_KEY"或数据库特定的名称
                                Object keyObj = keyMap.get("GENERATED_KEY");
                                if (keyObj != null) {
                                    if (keyObj instanceof Number) {
                                        generatedIds.add(((Number) keyObj).longValue());
                                    } else {
                                        try {
                                            generatedIds.add(Long.parseLong(keyObj.toString()));
                                        } catch (NumberFormatException e) {
                                            log.warn("Cannot parse generated PK: {}", keyObj);
                                        }
                                    }
                                }
                            }

                            // 返回第一个主键ID作为rowId，并在data中返回所有主键ID
                            Long firstId = generatedIds.isEmpty() ? null : generatedIds.get(0);
                            Map<String, Object> resultData = new HashMap<>();
                            resultData.put("rowIds", generatedIds);

                            return ExecuteRawResultVo.builder()
                                    .rowNum((long) affectedRows)
                                    .rowId(firstId)
                                    .data(Collections.singletonList(resultData))
                                    .build();
                        } else {
                            // 单条插入情况，获取生成的主键
                            Number key = keyHolder.getKey();
                            if (key == null) {
                                log.warn("Auto-increment PK not returned after insert, SQL: {}", sql);
                            }
                            var rowId = key != null ? key.longValue() : null;
                            return ExecuteRawResultVo.builder()
                                    .rowNum((long) affectedRows)
                                    .rowId(rowId)
                                    .build();
                        }
                    } catch (DuplicateKeyException e) {
                        // 因为唯一key重复,不抛异常,避免阻塞工作流任务;返回主键id是-1;
                        log.error("Unique key conflict on insert, SQL: {}", sql, e);
                        return ExecuteRawResultVo.builder()
                                .data(List.of())
                                .rowNum(0L)
                                .rowId(-1L)
                                .build();
                    }
                }
                case DELETE: {
                    log.debug("Raw Doris delete SQL: {}, params: {}", sql, params);
                    int affectedRows = jdbcTemplate.update(sql, params);
                    return ExecuteRawResultVo.builder()
                            .rowNum((long) affectedRows)
                            .build();
                }
                case DDL:
                default:
                    log.error("DDL raw SQL not allowed: {}", sql);
                    throw ComposeException.build(BizExceptionCodeEnum.composeSqlOnlyDmlAllowed);
            }
        } catch (BadSqlGrammarException e) {
            log.error("DML SQL execution error, SQL: {}", sql, e);
            var errorMessage = ComposeExceptionUtils.getUserFriendlyErrorMessage(e);
            throw ComposeException.build(BizExceptionCodeEnum.composeSqlExecuteFailed, errorMessage);
        } catch (ComposeException ce) {
            log.error("DML SQL execution error, SQL: {}", sql, ce);
            throw ce;
        } catch (Exception e) {
            log.error("Raw Doris query SQL execution error, SQL: {}", sql, e);
            var errorMessage = ComposeExceptionUtils.getUserFriendlyErrorMessage(e);
            throw ComposeException.build(BizExceptionCodeEnum.composeSqlExecuteFailed, errorMessage);
        }
    }

    @DSTransactional(propagation = DsPropagation.NOT_SUPPORTED)
    @Override
    public String getCreateTableDdl(String database, String table) {

        // 获取表的建表DDL语句
        // 检查表是否存在
        var existFlag = tableExists(database, table);
        if (!existFlag) {
            log.error("Table not found, database: {}, table: {}", database, table);
            throw ComposeException.build(BizExceptionCodeEnum.composeTableDefinitionNotFound);
        }

        // 1. 获取表的基本信息
        String showCreateTableSql = tableDbWrapperUtil.getShowCreateTableDdl(database, table);
        Map<String, Object> createTableResult = jdbcTemplate.queryForMap(showCreateTableSql);
        // 尝试不同的列名格式（MySQL使用空格，Doris使用下划线）
        String createTableDdl = (String) createTableResult.get("Create Table"); // MySQL格式
        if (createTableDdl == null) {
            createTableDdl = (String) createTableResult.get("Create_table"); // Doris格式
        }
        if (createTableDdl == null) {
            throw ComposeException.build(BizExceptionCodeEnum.composeCannotGetCreateTableDdl);
        }
        return createTableDdl;
    }

    @Override
    public int executeRawAdminSql(String sql) {
        try {
            if (!StringUtils.hasText(sql)) {
                throw ComposeException.build(BizExceptionCodeEnum.composeSqlEmpty); // SQL不能为空
            }
            // 不再检查是否为 SELECT
            log.warn("About to run raw admin/DML/DDL SQL (high risk): {}", sql);
            int affectedRows = jdbcTemplate.update(sql);
            log.info("Raw admin/DML/DDL SQL OK: {}, affected: {}", sql, affectedRows);
            return affectedRows;
        } catch (BadSqlGrammarException e) {
            log.error("Raw admin/DML/DDL SQL error, SQL: {}", sql, e);
            var errorMessage = ComposeExceptionUtils.getUserFriendlyErrorMessage(e);
            throw ComposeException.build(BizExceptionCodeEnum.composeSqlExecuteFailed, errorMessage);
        } catch (ComposeException ce) {
            // 直接抛出业务异常
            log.error("Raw admin/DML/DDL SQL execution error, SQL: {}", sql, ce);
            throw ce;
        } catch (Exception e) {
            log.error("Raw admin/DML/DDL SQL execution error, SQL: {}", sql, e);
            // 使用用户友好的错误信息，将数据库技术错误转换为易于理解的提示
            var errorMessage = ComposeExceptionUtils.getUserFriendlyErrorMessage(e);
            throw ComposeException.build(BizExceptionCodeEnum.composeGenericMessage, errorMessage);
        }
    }

    @Override
    public Long getTableTotal(String database, String table) {
        String sql = "SELECT COUNT(*) FROM " + database + "." + table;
        return jdbcTemplate.queryForObject(sql, Long.class);
    }

    @Override
    public boolean queryDorisTableRowDataById(String database, String table, Long id) {
        String sql = "SELECT COUNT(*) FROM " + database + "." + table + " WHERE id = " + id;
        return jdbcTemplate.queryForObject(sql, Long.class) > 0;
    }

    @Override
    public void clearBusinessData(String database, String table) {
        String sql = "TRUNCATE TABLE " + database + "." + table;
        jdbcTemplate.update(sql);
    }

    @Override
    public boolean existTableData(String database, String table) {
        // 检查表是否存在
        var existFlag = tableExists(database, table);
        if (!existFlag) {
            log.error("Table not found, database: {}, table: {}", database, table);
            return false;
        }

        String sql = "SELECT * FROM " + database + "." + table + " LIMIT 1";
        List<Map<String, Object>> result = jdbcTemplate.queryForList(sql);
        return !result.isEmpty();
    }

    @Override
    public void executeRawDDL(String sql) {
        try {
            // 只允许 DDL 类型
            SqlType sqlType = SqlParserUtil.getSqlType(sql);
            if (sqlType != SqlType.DDL) {
                log.error("DDL only, sql={}", sql);
                throw ComposeException.build(BizExceptionCodeEnum.composeSqlOnlyDdl, sql);
            }
            jdbcTemplate.execute(sql);
        } catch (ComposeException e) {
            throw e;
        } catch (JSQLParserException e) {
            log.error("DDL execution error, sql={}", sql, e);
            var errorMessage = ComposeExceptionUtils.getRootErrorMessage(e);
            throw ComposeException.build(BizExceptionCodeEnum.composeSqlParseFailed, errorMessage);
        }
    }

    @Override
    public Long countRawQuery(String sql) {
        try {
            return jdbcTemplate.queryForObject(sql, Long.class);
        } catch (Exception e) {
            log.error("Raw Doris query SQL execution error, SQL: {}", sql, e);
            var errorMessage = ComposeExceptionUtils.getRootErrorMessage(e);
            throw ComposeException.build(BizExceptionCodeEnum.composeSqlExecuteFailed, errorMessage);
        }
    }

    /**
     * 获取根错误信息
     * 
     * @param e 异常
     * @return 根错误信息
     */
    private String getRootErrorMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }

    @Override
    public void executeRawAdminSqls(List<String> sqls) {
        // 直接调用FAIL_FAST策略，保持原有行为
        this.self.executeFailFast(sqls);
    }

    @Override
    public void executeRawAdminSqlsWithStrategy(List<String> sqls,
            ICustomDorisTableRepository.BatchExecuteStrategy strategy) {
        if (CollectionUtils.isEmpty(sqls)) {
            return;
        }

        log.info("Batch run {} SQL statements, strategy: {}", sqls.size(), strategy);

        switch (strategy) {
            case FAIL_FAST:
                this.self.executeFailFast(sqls);
                break;
            case CONTINUE_ON_ERROR:
                this.self.executeContinueOnError(sqls);
                break;
            case ATOMIC_BATCH:
                this.self.executeAtomicBatch(sqls);
                break;
            default:
                throw new IllegalArgumentException("Unsupported execution strategy: " + strategy);
        }
    }

    /**
     * 策略1：遇到错误立即失败（原有行为）
     */
    @DSTransactional(rollbackFor = Exception.class)
    public void executeFailFast(List<String> sqls) {
        // 将多条SQL语句合并成一条，使用分号分隔
        String batchSql = String.join(";", sqls);
        batchSql = batchSql + ";";
        this.self.executeRawAdminSql(batchSql);
        log.info("Batch SQL done (FAIL_FAST)");
    }

    /**
     * 策略2：遇到错误继续执行其他SQL（无事务保护）
     */
    @DSTransactional(propagation = DsPropagation.NOT_SUPPORTED) // 不使用事务
    public void executeContinueOnError(List<String> sqls) {
        int successCount = 0;
        List<String> errorSqls = new ArrayList<>();
        List<String> errorMessages = new ArrayList<>();

        for (int i = 0; i < sqls.size(); i++) {
            String sql = sqls.get(i);
            try {
                jdbcTemplate.update(sql);
                successCount++;
                log.debug("SQL[{}] OK: {}", i + 1, sql);
            } catch (Exception e) {
                String errorMsg = String.format("SQL[%d]执行失败: %s, 错误: %s",
                        i + 1, sql, e.getMessage());
                log.error(errorMsg, e);
                errorSqls.add(sql);
                errorMessages.add(errorMsg);
            }
        }

        log.info("Batch SQL done (CONTINUE_ON_ERROR): ok {}, failed {}",
                successCount, errorSqls.size());

        // 如果有失败的SQL，抛出汇总异常
        if (!errorSqls.isEmpty()) {
            String summaryError = String.format("批量执行SQL部分失败: 成功%d条，失败%d条。失败详情: %s",
                    successCount, errorSqls.size(),
                    String.join("; ", errorMessages));
            throw ComposeException.build(BizExceptionCodeEnum.composeSqlExecuteFailed, summaryError);
        }
    }

    /**
     * 策略3：原子性批处理，逐个执行但保持事务完整性
     */
    @DSTransactional(rollbackFor = Exception.class)
    public void executeAtomicBatch(List<String> sqls) {
        try {
            for (int i = 0; i < sqls.size(); i++) {
                String sql = sqls.get(i);
                try {
                    int affectedRows = jdbcTemplate.update(sql);
                    log.debug("SQL[{}] OK, affected {}: {}", i + 1, affectedRows, sql);
                } catch (Exception e) {
                    String errorMsg = String.format("SQL[%d]执行失败: %s", i + 1, sql);
                    log.error(errorMsg, e);
                    // 在事务中抛出异常，会导致整个事务回滚
                    throw ComposeException.build(BizExceptionCodeEnum.composeSqlExecuteFailed,
                            errorMsg + "，错误: " + e.getMessage());
                }
            }
            log.info("Batch SQL done (ATOMIC_BATCH): {} statements", sqls.size());
        } catch (Exception e) {
            log.error("Batch SQL failed, rolling back all", e);
            throw e;
        }
    }

}