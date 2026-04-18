package com.xspaceagi.compose.domain.service.impl;

import com.alibaba.fastjson2.JSON;
import com.baomidou.dynamic.datasource.annotation.DS;
import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.baomidou.dynamic.datasource.tx.DsPropagation;
import com.google.common.collect.Lists;
import com.xspaceagi.compose.domain.dto.ColumnDefinitionResult;
import com.xspaceagi.compose.domain.dto.CustomAddBusinessRowDataVo;
import com.xspaceagi.compose.domain.dto.CustomDeleteBusinessRowDataVo;
import com.xspaceagi.compose.domain.dto.CustomUpdateBusinessRowDataVo;
import com.xspaceagi.compose.domain.model.CustomDorisDataRequest;
import com.xspaceagi.compose.domain.model.CustomFieldDefinitionModel;
import com.xspaceagi.compose.domain.model.CustomTableDefinitionModel;
import com.xspaceagi.compose.domain.repository.ICustomDorisTableRepository;
import com.xspaceagi.compose.domain.service.CustomDorisTableDomainService;
import com.xspaceagi.compose.domain.service.CustomTableDefinitionDomainService;
import com.xspaceagi.compose.domain.util.SqlParserUtil;
import com.xspaceagi.compose.domain.util.TableDbWrapperUtil;
import com.xspaceagi.compose.domain.util.ExcelDataValidatorUtil;
import com.xspaceagi.compose.sdk.DorisDataPage;
import com.xspaceagi.compose.sdk.enums.DefaultTableFieldEnum;
import com.xspaceagi.compose.sdk.enums.TableFieldTypeEnum;
import com.xspaceagi.compose.sdk.request.DorisTableDataRequest;
import com.xspaceagi.compose.sdk.vo.data.ExecuteRawResultVo;
import com.xspaceagi.compose.sdk.vo.data.FrontColumnDefineVo;
import com.xspaceagi.compose.sdk.vo.doris.DorisTableDefinitionVo;
import com.xspaceagi.compose.spec.constants.DorisConfigContants;
import com.xspaceagi.compose.spec.utils.ComposeExceptionUtils;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.ComposeException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@DS("doris")
@Slf4j
@Service
public class CustomDorisTableDomainServiceImpl implements CustomDorisTableDomainService {

    @Resource
    private ICustomDorisTableRepository customDorisTableRepository;

    @Resource
    private TableDbWrapperUtil tableDbWrapperUtil;

    @Lazy
    @Resource
    private CustomDorisTableDomainService self;

    @Lazy
    @Resource
    private CustomTableDefinitionDomainService customTableDefinitionDomainService;

    @Override
    @DSTransactional(rollbackFor = Exception.class, propagation = DsPropagation.NOT_SUPPORTED)
    public boolean hasData(String database, String table) {
        // 先判断业务表是否存在,不存在,则任务不存在数据,不然直接执行查询数据有无的sql会报表不存在
        var tableExistFlag = customDorisTableRepository.tableExists(database, table);
        if (!tableExistFlag) {
            return false;
        }
        return customDorisTableRepository.hasData(database, table);
    }

    @Override
    @DSTransactional(rollbackFor = Exception.class, propagation = DsPropagation.NOT_SUPPORTED)
    public void createTable(CustomTableDefinitionModel tableModel) {
        List<CustomFieldDefinitionModel> fields = tableModel.getFieldList();
        String createTableSql = tableDbWrapperUtil.buildCreateTableSql(tableModel, fields);
        log.info("Create business table, sql={}", createTableSql);
        try {
            // 创建表
            customDorisTableRepository.executeCreateTable(createTableSql);
            // 创建唯一索引
            this.createUniqueIndexes(tableModel.getDorisDatabase(), tableModel.getDorisTable(), fields);

            // 新增：为 uid、agent_id、created 字段自动建普通索引
            String db = tableModel.getDorisDatabase();
            String tb = tableModel.getDorisTable();
            List<String> indexFields = List.of(DefaultTableFieldEnum.UID.getFieldName(),
                    DefaultTableFieldEnum.AGENT_ID.getFieldName(),
                    DefaultTableFieldEnum.CREATED.getFieldName());
            for (String field : indexFields) {
                String idxName = "idx_" + field;
                String sql = String.format("CREATE INDEX %s ON `%s`.`%s` (`%s`)", idxName, db, tb, field);
                try {
                    customDorisTableRepository.executeRawAdminSql(sql);
                } catch (Exception e) {
                    log.warn("Create index {} failed (exists or missing column): {}", idxName, e.getMessage());
                    throw ComposeException.build(BizExceptionCodeEnum.composeCreateIndexFailed);
                }
            }
        } catch (Exception e) {
            log.error("Create Doris table error", e);
            throw ComposeException.build(BizExceptionCodeEnum.composeCreateTableFailed);
        }
    }

    @Override
    public void dropTable(String database, String table) {
        // 1. 检查表是否存在
        var tableExistFlag = customDorisTableRepository.tableExists(database, table);

        if (!tableExistFlag) {
            log.warn("Table not found: {}", database + "." + table);
            return;
        }

        String dropTableSql = tableDbWrapperUtil.buildDropTableSql(database, table);
        log.info("Delete business data, sql={}", dropTableSql);
        customDorisTableRepository.executeRawAdminSql(dropTableSql);
    }

    @Override
    @DSTransactional(rollbackFor = Exception.class, propagation = DsPropagation.NOT_SUPPORTED)
    public void rebuildTable(CustomTableDefinitionModel tableModel) {
        // 1. 删除原表
        this.self.dropTable(tableModel.getDorisDatabase(), tableModel.getDorisTable());
        // 2. 创建新表
        this.self.createTable(tableModel);
    }

    @Override
    public boolean isFieldValueUnique(String database, String table, String fieldName, String fieldValue) {
        return customDorisTableRepository.isFieldValueUnique(database, table, fieldName, fieldValue);
    }

    @Override
    public void deleteDorisTableRowDataById(String database, String table, Long id) {
        if (id == null) {
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "删除数据时ID不能为空");
        }

        int affectedRows = customDorisTableRepository.deleteTableDataById(database, table, id);
        if (affectedRows == 0) {
            log.warn("Row not found for delete, database: {}, table: {}, id: {}", database, table, id);
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "未找到要删除的数据");
        }

        log.info("Doris row deleted, database: {}, table: {}, id: {}", database, table, id);
    }

    @Override
    @DSTransactional(rollbackFor = Exception.class, propagation = DsPropagation.NOT_SUPPORTED)
    public void alterTable(String database, String table, List<String> alterSqlStatements) {
        log.info("Doris schema change start, database: {}, table: {}", database, table);
        if (CollectionUtils.isEmpty(alterSqlStatements)) {
            log.warn("No Doris schema change SQL to run");
            return;
        }

        try {
            for (String sql : alterSqlStatements) {
                customDorisTableRepository.executeAlterTable(database, table, sql);
            }
            log.info("Doris schema change done, database: {}, table: {}", database, table);
        } catch (Exception e) {
            log.error("Doris schema change error", e);
            var errorMessage = ComposeExceptionUtils.getRootErrorMessage(e);
            throw ComposeException.build(BizExceptionCodeEnum.composeSqlExecuteFailed, errorMessage);
        }
    }

    @Override
    public DorisDataPage<List<Object>> queryPageDorisTableData(DorisTableDataRequest request,
            ColumnDefinitionResult columnDefResult) {
        log.debug("Paged Doris query start, request: {}", request);
        if (request == null || request.getTableId() == null) {
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "请求参数或表ID不能为空");
        }

        // 1. 获取表定义和字段定义
        CustomTableDefinitionModel tableModel = columnDefResult.getTableModel();
        List<CustomFieldDefinitionModel> fields = columnDefResult.getFields();

        // 2. 构建查询SQL和参数
        String finalSql;
        List<Object> params = new ArrayList<>();
        if (StringUtils.hasText(request.getSql())) {
            // 如果提供了SQL，使用提供的SQL
            finalSql = request.getSql();
            // 处理SQL参数
            if (request.getArgs() != null) {
                params.addAll(request.getArgs().values());
            }
        } else {
            // 如果没有提供SQL，构建默认的查询SQL
            StringBuilder sql = new StringBuilder("SELECT * FROM `")
                    .append(tableModel.getDorisDatabase())
                    .append("`.`")
                    .append(tableModel.getDorisTable())
                    .append("`");

            // 添加扩展参数作为WHERE条件
            if (request.getExtArgs() != null && !request.getExtArgs().isEmpty()) {
                sql.append(" WHERE ");
                boolean first = true;
                for (Map.Entry<String, Object> entry : request.getExtArgs().entrySet()) {
                    if (!first) {
                        sql.append(" AND ");
                    }
                    sql.append("`").append(entry.getKey()).append("` = ?");
                    params.add(entry.getValue());
                    first = false;
                }
            }
            finalSql = sql.toString();
        }

        // 3. 执行查询
        try {
            ExecuteRawResultVo executeRawResultVo = customDorisTableRepository.executeRawQuery(finalSql,
                    params.toArray());
            List<Map<String, Object>> rawData = executeRawResultVo.getData();

            // 4. 转换数据格式
            List<List<Object>> data = transformToRowDataVo(rawData, fields);

            // 5. 构建返回结果
            DorisDataPage<List<Object>> result = new DorisDataPage<>(1, rawData.size(), rawData.size());
            result.setRecords(data);
            result.setColumnDefines(columnDefResult.getColumnDefines());

            log.debug("Paged Doris query done, data size: {}", data.size());
            return result;

        } catch (ComposeException ce) {
            log.error("DML SQL execution error, SQL: {}", finalSql, ce);
            throw ce;
        } catch (Exception e) {
            log.error("Doris query error, sql: {}, params: {}", finalSql, params, e);
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "执行查询失败: " + e.getMessage());
        }
    }

    private List<List<Object>> transformToRowDataVo(List<Map<String, Object>> rawData,
            List<CustomFieldDefinitionModel> fields) {
        if (CollectionUtils.isEmpty(rawData)) {
            return Collections.emptyList();
        }

        return rawData.stream()
                .map(row -> {
                    List<Object> rowData = new ArrayList<>();
                    for (CustomFieldDefinitionModel field : fields) {
                        String fieldName = field.getFieldName();
                        Object value = row.get(fieldName);
                        rowData.add(value != null ? value : "");
                    }
                    return rowData;
                })
                .collect(Collectors.toList());
    }

    @Override
    public DorisDataPage<Map<String, Object>> queryPageTableData(String database, String table,
            Map<String, Object> conditions, String orderBy, int pageNo, int pageSize) {
        // 构建基础SQL
        StringBuilder sql = new StringBuilder("SELECT * FROM `")
                .append(database)
                .append("`.`")
                .append(table)
                .append("`");

        List<Object> params = new ArrayList<>();

        // 添加查询条件
        if (conditions != null && !conditions.isEmpty()) {
            sql.append(" WHERE ");
            boolean first = true;
            for (Map.Entry<String, Object> entry : conditions.entrySet()) {
                if (!first) {
                    sql.append(" AND ");
                }
                sql.append("`").append(entry.getKey()).append("` = ?");
                params.add(entry.getValue());
                first = false;
            }
        }

        // 添加排序
        if (StringUtils.hasText(orderBy)) {
            sql.append(" ORDER BY ").append(orderBy);
        }

        // 添加分页
        sql.append(" LIMIT ?, ?");
        params.add((pageNo - 1) * pageSize);
        params.add(pageSize);

        // 执行查询
        var executedSql = sql.toString();
        ExecuteRawResultVo result = customDorisTableRepository.executeRawQuery(executedSql,
                params.toArray());
        List<Map<String, Object>> records = result.getData();

        // 构建分页结果
        DorisDataPage<Map<String, Object>> page = new DorisDataPage<>(pageNo, pageSize);
        page.setRecords(records);

        return page;
    }

    @Override
    public List<Map<String, Object>> queryAllTableData(String database, String table,
            Map<String, Object> conditions, String orderBy, Integer limit) {

        log.debug("Doris query all start, database: {}, table: {}, limit: {}, conditions: {}",
                database, table, limit, conditions);

        // 直接调用 Repository 层的方法，由其处理默认 limit
        List<Map<String, Object>> result = customDorisTableRepository.queryAllData(database, table, conditions, orderBy,
                limit);

        log.debug("Doris query all done, data size: {}", result.size());

        return result;
    }

    /**
     * 为唯一字段创建索引
     */
    @Override
    public void createUniqueIndexes(String database, String table, List<CustomFieldDefinitionModel> fields) {
        fields.stream()
                .filter(field -> field.getUniqueFlag() != null && field.getUniqueFlag() == 1)
                .forEach(field -> customDorisTableRepository.createUniqueIndex(database, table, field.getFieldName()));
    }

    @Override
    public ExecuteRawResultVo executeRawQuery(String database, String sql) {
        log.info("Native SQL query start, db: {}, SQL: {}", database, sql);
        try {
            ExecuteRawResultVo result = customDorisTableRepository.executeRawQuery(sql);
            log.info("Native SQL done, {} rows", result.getRowNum());
            return result;
        } catch (ComposeException ce) {
            log.error("DML SQL execution error", ce);
            throw ce;
        } catch (Exception e) {
            log.error("Native SQL failed, db: {}, SQL: {}", database, sql, e);
            var errorMessage = ComposeExceptionUtils.getRootErrorMessage(e);
            throw ComposeException.build(BizExceptionCodeEnum.composeSqlExecuteFailed, errorMessage);
        }
    }

    @Override
    public boolean tableExists(String database, String table) {
        log.info("Check table exists, database: {}, table: {}", database, table);
        try {
            return customDorisTableRepository.tableExists(database, table);
        } catch (Exception e) {
            log.error("Check table exists error, database: {}, table: {}", database, table, e);
            var errorMessage = ComposeExceptionUtils.getRootErrorMessage(e);
            throw ComposeException.build(BizExceptionCodeEnum.composeCheckTableExistsFailed, errorMessage);
        }
    }

    @Override
    public void truncateTable(String database, String table) {
        log.info("Truncate table start, database: {}, table: {}", database, table);
        try {
            customDorisTableRepository.truncateTable(database, table);
            log.info("Truncate done, database: {}, table: {}", database, table);
        } catch (Exception e) {
            log.error("Truncate error, database: {}, table: {}", database, table, e);
            var errorMessage = ComposeExceptionUtils.getRootErrorMessage(e);
            throw ComposeException.build(BizExceptionCodeEnum.composeTruncateTableFailed, errorMessage);
        }
    }

    @Override
    public DorisTableDefinitionVo getTableDefinition(String database, String table) {
        log.info("Get table definition start, database: {}, table: {}", database, table);
        try {
            // 检查表是否存在
            if (!tableExists(database, table)) {
                log.error("Table not found, database: {}, table: {}", database, table);
                throw ComposeException.build(BizExceptionCodeEnum.tableNotFound);
            }

            // 获取表定义信息
            DorisTableDefinitionVo definition = customDorisTableRepository.getTableDefinition(database, table);
            log.info("Get table definition done, database: {}, table: {}", database, table);
            return definition;
        } catch (Exception e) {
            log.error("Get table definition error, database: {}, table: {}", database, table, e);
            var errorMessage = ComposeExceptionUtils.getRootErrorMessage(e);
            throw ComposeException.build(BizExceptionCodeEnum.composeGetTableDefinitionFailed, errorMessage);
        }
    }

    @Override
    @DSTransactional(rollbackFor = Exception.class)
    public void updateTableStructure(Long tableId, List<CustomFieldDefinitionModel> newFields,
            CustomTableDefinitionModel tableModel) {
        log.info("Update table schema start, tableId: {}", tableId);
        if (tableId == null) {
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "更新表结构时 tableId 不能为空");
        }
        if (newFields == null) {
            log.warn("newFields null, no schema change, tableId: {}", tableId);
            return;
        }

        // 1. 获取当前表定义
        String database = tableModel.getDorisDatabase();
        String table = tableModel.getDorisTable();
        if (!StringUtils.hasText(database) || !StringUtils.hasText(table)) {
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "表定义缺少数据库名或表名, tableId: " + tableId);
        }

        // 2. 获取当前字段列表
        List<CustomFieldDefinitionModel> oldFields = tableModel.getFieldList();

        // 3. 调用工具类生成 ALTER SQL 语句
        List<String> potentialAlterSqls = tableDbWrapperUtil.buildAlterTableSqls(database, table, oldFields, newFields);

        // 4. 检查表是否有数据，并根据规则过滤 SQL 语句
        List<String> finalAlterSqls = new ArrayList<>();
        boolean tableHasData = this.self.hasData(database, table);
        log.info("Check table {}.{} has data: {}", database, table, tableHasData);

        if (!CollectionUtils.isEmpty(potentialAlterSqls)) {
            for (String sql : potentialAlterSqls) {
                boolean isDropColumn = sql.toUpperCase().contains(" DROP COLUMN ");

                if (isDropColumn && tableHasData) {
                    log.warn("Drop column blocked: table {}.{} has data, columns: [{}]", database, table, sql);
                    throw ComposeException.build(
                            BizExceptionCodeEnum.resourceDataNotFound,
                            String.format("表 %s.%s 中存在数据，不允许删除列。如需删除列，请先清空表数据", database, table));
                } else {
                    finalAlterSqls.add(sql);
                }
            }
        }

        // 5. 执行最终确认的 ALTER SQL 语句
        if (!CollectionUtils.isEmpty(finalAlterSqls)) {
            log.info("Apply {} confirmed schema changes for {}.{}", finalAlterSqls.size(), database, table);
            this.self.alterTable(database, table, finalAlterSqls);
            log.info("Schema change applied for {}.{}", database, table);
        } else {
            if (!CollectionUtils.isEmpty(potentialAlterSqls)) {
                log.warn("All schema changes blocked (table has data / drop column) for {}.{}", database, table);
            } else {
                log.info("No schema change needed for {}.{}", database, table);
            }
        }

        log.warn("TODO: update custom_field_definition fields, tableId: {}", tableId);
    }

    @Override
    public DorisDataPage<Map<String, Object>> queryPageDorisTableDataForWeb(CustomDorisDataRequest request,
            CustomTableDefinitionModel tableModel) {
        log.debug("Web paged Doris query start, request: {}", request);
        if (request == null || request.getTableId() == null) {
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "请求参数或表ID不能为空");
        }

        // 1. 获取表定义和字段定义
        List<CustomFieldDefinitionModel> fields = tableModel.getFieldList();
        if (CollectionUtils.isEmpty(fields)) {
            log.error("Table definition has no fields, tableId: {}", request.getTableId());
            throw ComposeException.build(BizExceptionCodeEnum.composeTableDefMissingFields);
        }

        // 2. 构建SQL和参数
        StringBuilder sql = new StringBuilder("SELECT * FROM `")
                .append(tableModel.getDorisDatabase())
                .append("`.`")
                .append(tableModel.getDorisTable())
                .append("`");

        // 3. 计算总数的SQL
        StringBuilder countSql = new StringBuilder("SELECT COUNT(*) FROM `")
                .append(tableModel.getDorisDatabase())
                .append("`.`")
                .append(tableModel.getDorisTable())
                .append("`");

        // 4. 添加分页参数
        int pageNo = request.getPageNo() != null && request.getPageNo() > 0 ? request.getPageNo().intValue() : 1;
        int pageSize = request.getPageSize() != null && request.getPageSize() > 0 ? request.getPageSize().intValue()
                : 10;
        int offset = (pageNo - 1) * pageSize;

        // 5. 添加排序（按id正序）
        sql.append(" ORDER BY `id` ASC");

        // 6. 添加分页
        sql.append(" LIMIT ?, ?");
        List<Object> params = Arrays.asList(offset, pageSize);

        try {
            // 执行查询
            // 查询总数
            var executedCountSql = countSql.toString();
            log.debug("Run count SQL: {}", executedCountSql);
            Long total = customDorisTableRepository.countRawQuery(executedCountSql);

            // 查询数据
            var executedSql = sql.toString();
            log.debug("Run data SQL: {}", executedSql);
            ExecuteRawResultVo executeRawResultVo = customDorisTableRepository.executeRawQuery(executedSql,
                    params.toArray());
            List<Map<String, Object>> rawData = executeRawResultVo.getData();
            // 长数值字段处理：对可能超过JavaScript安全整数范围的数值进行字符串转换，避免前端精度丢失
            // JavaScript安全整数范围: -(2^53-1) 到 (2^53-1)，即 -9007199254740991 到 9007199254740991
            
            // 创建字段类型映射，用于判断哪些字段需要特殊处理
            Map<String, Integer> fieldTypeMap = new HashMap<>();
            for (CustomFieldDefinitionModel field : fields) {
                fieldTypeMap.put(field.getFieldName(), field.getFieldType());
            }
            
            // 对数据进行预处理：转换长数值为字符串
            for (Map<String, Object> row : rawData) {
                if (row == null) {
                    continue; // 防御性检查：跳过空行
                }
                
                for (Map.Entry<String, Object> entry : row.entrySet()) {
                    if (entry == null) {
                        continue; // 防御性检查：跳过空条目
                    }
                    
                    String fieldName = entry.getKey();
                    Object fieldValue = entry.getValue();
                    
                    // 只处理数值类型的字段
                    if (fieldValue instanceof Number) {
                        Integer fieldType = fieldTypeMap.get(fieldName);
                        
                        // 如果字段类型未找到，记录警告但不影响处理
                        if (fieldType == null) {
                            log.debug("Field [{}] not in type map, skip web numeric conversion", fieldName);
                            continue;
                        }
                        
                        // 判断是否需要转换为字符串
                        if (shouldConvertToStringForWeb(fieldValue, fieldType)) {
                            row.put(fieldName, String.valueOf(fieldValue));
                            log.debug("Field [{}] value [{}] stringified to avoid precision loss", fieldName, fieldValue);
                        }
                    }
                }
            }

            // === 处理布尔类型字段 ===
            if (fields != null && !fields.isEmpty() && rawData != null && !rawData.isEmpty()) {
                // 重用之前创建的fieldTypeMap
                for (Map<String, Object> row : rawData) {
                    for (Map.Entry<String, Object> entry : row.entrySet()) {
                        String fieldName = entry.getKey();
                        Object value = entry.getValue();
                        Integer fieldType = fieldTypeMap.get(fieldName);
                        if (fieldType != null && com.xspaceagi.compose.sdk.enums.TableFieldTypeEnum.BOOLEAN.getCode()
                                .equals(fieldType)) {
                            if (value == null) {
                                // 保持为空
                                continue;
                            } else if (value instanceof Number number) {
                                row.put(fieldName, number.intValue() == 1);
                            } else if ("1".equals(String.valueOf(value))) {
                                row.put(fieldName, true);
                            } else if ("0".equals(String.valueOf(value))) {
                                row.put(fieldName, false);
                            }
                        }
                    }
                }
            }
            // === 布尔类型处理结束 ===

            // 构建返回结果
            DorisDataPage<Map<String, Object>> result = new DorisDataPage<>(pageNo, pageSize, total);
            result.setRecords(rawData);

            // 构建前端用的抬头字段定义
            List<FrontColumnDefineVo> columnDefines = fields.stream()
                    .map(CustomFieldDefinitionModel::convertToExcelColumnDefineVo)
                    .collect(Collectors.toList());

            result.setColumnDefines(columnDefines);
            var dataSize = Optional.ofNullable(rawData).map(List::size).orElse(0);

            log.debug("Web paged Doris query done, total: {}, data size: {}", total, dataSize);
            return result;

        } catch (ComposeException ce) {
            log.error("DML SQL execution error", ce);
            throw ce;
        } catch (Exception e) {
            log.error("Web paged Doris query error, sql: {}, params: {}", sql, params, e);
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "执行查询失败: " + e.getMessage());
        }
    }

    @Override
    public String generateExecuteSql(DorisTableDataRequest request, CustomTableDefinitionModel tableModel) {
        if (request == null || request.getTableId() == null) {
            throw new IllegalArgumentException("Request parameters or table ID cannot be empty");
        }

        String sql = request.getSql();
        if (!StringUtils.hasText(sql)) {
            throw new IllegalArgumentException("SQL statement cannot be empty");
        }

        try {
            // 2. 替换参数
            if (request.getArgs() != null && !request.getArgs().isEmpty()) {
                for (Map.Entry<String, Object> entry : request.getArgs().entrySet()) {
                    String rawKey = "${{" + entry.getKey() + "}}";
                    String normalKey = "{{" + entry.getKey() + "}}";
                    Object value = entry.getValue();
                    // 1. 先处理原始字符串
                    if (sql.contains(rawKey)) {
                        sql = sql.replace(rawKey, value == null ? "" : value.toString());
                    }
                    // 2. 再处理普通参数
                    if (sql.contains(normalKey)) {
                        String paramValue = formatValue(value);
                        sql = sql.replace(normalKey, paramValue);
                    }
                }
            }

            // 3. 使用SqlParserUtil处理SQL
            // 3.1 验证SQL
            SqlParserUtil.validateSql(sql);

            // 3.2 替换表名和添加额外条件
            String finalSql = SqlParserUtil.modifySql(
                    sql, // 原始SQL
                    tableModel.getDorisTable(), // 实际表名
                    request.getExtArgs() // 额外的限制条件
            );

            log.debug("Final SQL: {}", finalSql);
            return finalSql;

        } catch (JSQLParserException e) {
            log.error("SQL parse failed: sql={}, error={}", sql, e.getMessage(), e);
            throw ComposeException.build(BizExceptionCodeEnum.composeSqlParseFailed, e.getMessage());
        } catch (Exception e) {
            log.error("Build SQL failed: sql={}, error={}", sql, e.getMessage(), e);
            var errorMessage = ComposeExceptionUtils.getRootErrorMessage(e);
            throw ComposeException.build(BizExceptionCodeEnum.composeSqlExecuteFailed, errorMessage);
        }
    }

    /**
     * 格式化SQL值，防止SQL注入
     * 优化版本：只移除真正危险的特殊字符，保留换行符等格式字符
     */
    private String formatValue(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number) {
            return value.toString();
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) ? "1" : "0";
        }
        // 如果是List类型，需要展开成逗号分隔的字符串
        if (value instanceof List) {
            return ((List<?>) value).stream()
                    .map(this::formatValue)
                    .collect(Collectors.joining(","));
        }
        // 对字符串类型进行特殊处理，防止SQL注入
        String strValue = value.toString();

        // 1. 替换单引号（防止SQL注入的核心操作）
        strValue = strValue.replace("'", "''");

        // 2. 只移除真正危险的特殊字符，保留换行符等格式字符
        // 保留：\n \r \t （换行符、回车符、制表符）
        // 移除：\ " （反斜杠、双引号）- 这些可能被用于SQL注入
        strValue = strValue.replace("\\", "\\\\").replace("\"", "\\\"");

        return "'" + strValue + "'";
    }

    @Override
    public void addBusinessData(CustomAddBusinessRowDataVo request, CustomTableDefinitionModel tableModel,
            UserContext userContext) {
        // 根据表定义: tableModel ,生成insert sql
        String insertSql = tableDbWrapperUtil.buildInsertSql(tableModel, request.getRowData());

        // 执行insert sql
        customDorisTableRepository.executeRawAdminSql(insertSql);
    }

    @Override
    public void updateBusinessData(CustomUpdateBusinessRowDataVo request, CustomTableDefinitionModel tableModel,
            UserContext userContext) {
        // 根据表定义: tableModel ,生成update sql
        String updateSql = tableDbWrapperUtil.buildUpdateSql(tableModel, request.getRowData(), request.getRowId());
        // 执行update sql
        customDorisTableRepository.executeRawAdminSql(updateSql);
    }

    @Override
    public void deleteBusinessData(CustomDeleteBusinessRowDataVo request, CustomTableDefinitionModel tableModel,
            UserContext userContext) {
        // 根据表定义: tableModel ,生成delete sql
        String deleteSql = tableDbWrapperUtil.buildDeleteSql(tableModel, request.getRowId());
        // 执行delete sql
        customDorisTableRepository.executeRawAdminSql(deleteSql);
    }

//    @Cacheable(value = "tableTotalCache", key = "#database + '.' + #table")
    @Override
    public Long getTableTotal(String database, String table) {
        return customDorisTableRepository.getTableTotal(database, table);
    }

    @Override
    public boolean queryDorisTableRowDataById(String database, String table, Long id) {
        return customDorisTableRepository.queryDorisTableRowDataById(database, table, id);
    }

    @Override
    public void clearBusinessData(Long tableId, CustomTableDefinitionModel tableModel, UserContext userContext) {

        // 1. 检查表是否存在
        if (!tableExists(tableModel.getDorisDatabase(), tableModel.getDorisTable())) {
            log.warn("Table missing, cannot truncate, tableId: {}, database: {}, table: {}", tableId, tableModel.getDorisDatabase(),
                    tableModel.getDorisTable());
            throw ComposeException.build(BizExceptionCodeEnum.tableNotFound);
        }

        // 2. 清空业务数据
        customDorisTableRepository.clearBusinessData(tableModel.getDorisDatabase(), tableModel.getDorisTable());
    }

    @Override
    public boolean existTableData(String database, String table) {
        return customDorisTableRepository.existTableData(database, table);
    }

    @Override
    public void batchInsertData(CustomTableDefinitionModel tableDefinitionModel, List<Map<String, Object>> excelData,
            UserContext userContext) {
        var tableId = tableDefinitionModel.getId();
        var database = tableDefinitionModel.getDorisDatabase();
        var table = tableDefinitionModel.getDorisTable();

        if (!tableExists(database, table)) {
            log.warn("Table missing, cannot import, tableId: {}, database: {}, table: {}", tableId, database, table);
            throw ComposeException.build(BizExceptionCodeEnum.tableNotFound);
        }

        Long tableTotal = this.self.getTableTotal(database, table);
        int dataSize = excelData.size();

        if (tableTotal + dataSize > DorisConfigContants.IMPORT_EXCEL_DATA_MAX_ROWS) {
            log.warn("Import would exceed row limit, tableId: {}, currentTotal: {}, importSize: {}",
                    tableId, tableTotal, dataSize);
            throw ComposeException.build(BizExceptionCodeEnum.composeTableRowCountExceeded, DorisConfigContants.IMPORT_EXCEL_DATA_MAX_ROWS);
        }
        if (dataSize > DorisConfigContants.IMPORT_EXCEL_DATA_MAX_ROWS_CHECK) {
            log.warn("Excel rows exceed import limit, tableId: {}, size: {}", tableId, dataSize);
            throw ComposeException.build(BizExceptionCodeEnum.composeExcelRowCountExceeded, DorisConfigContants.IMPORT_EXCEL_DATA_MAX_ROWS_CHECK);
        }

        if (dataSize == 0) {
            log.warn("Excel empty, tableId: {}", tableId);
            return;
        }

        // Excel数据校验：在插入前进行类型和格式验证
        log.info("Excel validation start, tableId: {}, totalRows: {}", tableId, dataSize);
        try {
            ExcelDataValidatorUtil.validateExcelData(tableDefinitionModel, excelData);
            log.info("Excel validation passed, tableId: {}", tableId);
        } catch (ComposeException e) {
            log.error("Excel validation failed, tableId: {}, error: {}", tableId, e.getMessage());
            throw e;
        }

        // 分批插入数据
        List<List<Map<String, Object>>> batches = Lists.partition(excelData,
                DorisConfigContants.IMPORT_EXCEL_DATA_BATCH_SIZE);
        int batchCount = batches.size();

        for (int i = 0; i < batchCount; i++) {
            List<Map<String, Object>> batchData = batches.get(i);
            List<String> insertSqls = new ArrayList<>();

            for (Map<String, Object> originRowData : batchData) {
                if(originRowData.isEmpty()){
                    log.debug("Empty row, skip");
                    continue;
                }
                String insertSql = tableDbWrapperUtil.buildInsertSql(tableDefinitionModel, originRowData);
                insertSqls.add(insertSql);
            }

            if (log.isDebugEnabled()) {
                log.debug("Batch insert SQL: {}", JSON.toJSONString(insertSqls));
            }
            customDorisTableRepository.executeRawAdminSqls(insertSqls);

            int startIndex = i * DorisConfigContants.IMPORT_EXCEL_DATA_BATCH_SIZE + 1;
            int endIndex = Math.min((i + 1) * DorisConfigContants.IMPORT_EXCEL_DATA_BATCH_SIZE, dataSize);
            log.info("Imported rows {} to {}, total {}", startIndex, endIndex, endIndex - startIndex + 1);
        }
    }

    /**
     * 判断数值是否需要转换为字符串以避免前端JavaScript精度丢失
     * JavaScript安全整数范围: -(2^53-1) 到 (2^53-1)，即 -9007199254740991 到 9007199254740991
     * 
     * @param fieldValue 字段值
     * @param fieldType  字段类型
     * @return 是否需要转换为字符串
     */
    private boolean shouldConvertToStringForWeb(Object fieldValue, Integer fieldType) {
        // 基础检查：字段值必须是非null的数值类型
        if (fieldValue == null || !(fieldValue instanceof Number)) {
            return false;
        }
        
        // 字段类型检查：如果字段类型为null，则不进行转换
        if (fieldType == null) {
            log.debug("Field type null, skip web numeric conversion");
            return false;
        }
        
        // 配置检查：如果未启用Web端智能转换，则不进行处理
        if (!DorisConfigContants.ENABLE_WEB_SMART_NUMBER_CONVERSION) {
            return false;
        }
        
                Number number = (Number) fieldValue;
        
        try {
            // 主键类型根据配置决定是否转换为字符串
            if (TableFieldTypeEnum.PRIMARY_KEY.getCode().equals(fieldType)) {
                return DorisConfigContants.FORCE_CONVERT_PRIMARY_KEY;
            }
            
            // INTEGER类型：超过JavaScript安全整数范围时转换为字符串
            if (TableFieldTypeEnum.INTEGER.getCode().equals(fieldType)) {
                long longValue = number.longValue();
                return longValue > DorisConfigContants.JS_MAX_SAFE_INTEGER || longValue < DorisConfigContants.JS_MIN_SAFE_INTEGER;
            }
            
            // NUMBER类型：处理大数值和高精度数值
            if (TableFieldTypeEnum.NUMBER.getCode().equals(fieldType)) {
                return isUnsafeForJavaScript(fieldValue, number);
            }
            
            // 对于所有其他数值类型，也进行基本的安全检查
            return isUnsafeForJavaScript(fieldValue, number);
            
        } catch (Exception e) {
            // 如果在处理过程中发生任何异常，记录错误但不影响整体流程
            log.error("Web numeric conversion error, fieldValue: {}, fieldType: {}", fieldValue, fieldType, e);
            return false;
        }
    }

    /**
     * 检查数值是否对JavaScript来说是不安全的（会丢失精度）
     * 
     * @param fieldValue 原始字段值
     * @param number     数值对象
     * @return 是否不安全需要转换为字符串
     */
    private boolean isUnsafeForJavaScript(Object fieldValue, Number number) {
        try {
            // 方案1：检查绝对值是否超过JavaScript安全阈值
            double doubleValue = number.doubleValue();
            if (Math.abs(doubleValue) >= DorisConfigContants.JS_SAFE_NUMBER_THRESHOLD) {
                return true;
            }
            
            // 方案2：检查是否超过JavaScript安全整数范围
            if (fieldValue instanceof Long || fieldValue instanceof Integer) {
                long longValue = number.longValue();
                if (longValue > DorisConfigContants.JS_MAX_SAFE_INTEGER || longValue < DorisConfigContants.JS_MIN_SAFE_INTEGER) {
                    return true;
                }
            }
            
            // 方案3：对于BigDecimal，检查精度和范围
            if (fieldValue instanceof BigDecimal) {
                BigDecimal bd = (BigDecimal) fieldValue;
                
                // 检查绝对值是否超过安全阈值
                if (bd.abs().compareTo(BigDecimal.valueOf(DorisConfigContants.JS_SAFE_NUMBER_THRESHOLD)) >= 0) {
                    return true;
                }
                
                // 检查有效数字位数
                String plainString = bd.toPlainString().replace("-", "").replace(".", "");
                // 移除前导零
                plainString = plainString.replaceFirst("^0+", "");
                if (plainString.length() > DorisConfigContants.JS_MAX_SAFE_DIGITS) {
                    return true;
                }
            }
            
            // 方案4：检查字符串表示的有效数字位数
            String numberStr = String.valueOf(fieldValue);
            if (numberStr.contains(".") || numberStr.contains("e") || numberStr.contains("E")) {
                // 有小数或科学计数法，需要检查有效数字
                String cleanStr = numberStr.replace("-", "").replace(".", "").replace("e", "").replace("E", "");
                // 移除前导零
                cleanStr = cleanStr.replaceFirst("^0+", "");
                if (cleanStr.length() > DorisConfigContants.JS_MAX_SAFE_DIGITS) {
                    return true;
                }
            }
            
            return false;
        } catch (Exception e) {
            log.error("JS number safety check error, fieldValue: {}", fieldValue, e);
            // 发生异常时，为了安全起见，建议转换为字符串
            return true;
        }
    }
}
