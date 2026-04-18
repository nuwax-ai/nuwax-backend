package com.xspaceagi.compose.domain.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.truncate.Truncate;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.LateralSubSelect;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import org.apache.commons.lang3.StringUtils;

import com.xspaceagi.compose.spec.constants.DorisConfigContants;
import com.xspaceagi.compose.spec.utils.ComposeExceptionUtils;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.ComposeException;

import net.sf.jsqlparser.statement.select.Limit;

@Slf4j
public class SqlParserUtil {

    public enum SqlType {
        SELECT, // SELECT 查询语句
        INSERT, // INSERT 插入语句
        UPDATE, // UPDATE 更新语句
        DELETE, // DELETE 删除语句
        DDL // CREATE, ALTER, DROP, TRUNCATE 等DDL语句
    }

    /**
     * 验证SQL语法和安全性
     */
    public static void validateSql(String sql) throws JSQLParserException {
        validateSql(sql, false);
    }

    /**
     * 验证SQL语句
     * 
     * @param sql      SQL语句
     * @param allowDDL 是否允许DDL语句
     * @throws JSQLParserException      SQL解析失败
     * @throws IllegalArgumentException SQL验证失败
     */
    public static void validateSql(String sql, boolean allowDDL) throws JSQLParserException {
        if (StringUtils.isBlank(sql)) {
            throw ComposeException.build(BizExceptionCodeEnum.composeSqlEmpty);
        }

        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            if (!allowDDL) {
                // 只禁止 DDL 语句，允许 select、update、delete、insert
                if (statement instanceof CreateTable
                        || statement instanceof Alter
                        || statement instanceof Drop
                        || statement instanceof Truncate) {
                    throw ComposeException.build(BizExceptionCodeEnum.composeSqlOnlyDdl, sql);
                }
            }

        } catch (JSQLParserException e) {
            log.error("SQL parse failed: sql={}", sql, e);
            throw e;
        }
    }

    /**
     * 解析SQL并返回SQL类型
     */
    public static SqlType getSqlType(String sql) throws JSQLParserException {
        Statement statement = CCJSqlParserUtil.parse(sql);

        if (statement instanceof Select) {
            return SqlType.SELECT;
        } else if (statement instanceof Insert) {
            return SqlType.INSERT;
        } else if (statement instanceof Update) {
            return SqlType.UPDATE;
        } else if (statement instanceof Delete) {
            return SqlType.DELETE;
        } else if (statement instanceof CreateTable ||
                statement instanceof Alter ||
                statement instanceof Drop ||
                statement instanceof Truncate) {
            return SqlType.DDL;
        }

        throw ComposeException.build(BizExceptionCodeEnum.composeUnsupportedSqlType, statement.getClass().getSimpleName());
    }

    /**
     * 修改SQL，替换表名并添加限制条件（默认不允许执行DDL语句）
     */
    public static String modifySql(String originalSql, String newTableName,
            Map<String, Object> extArgs) throws JSQLParserException {
        return modifySql(originalSql, newTableName, extArgs, false);
    }

    /**
     * 修改SQL，替换表名并添加限制条件
     * 
     * @param originalSql  原始SQL
     * @param newTableName 新表名
     * @param extArgs      额外的限制条件
     * @param allowDDL     是否允许执行DDL语句
     * @return 修改后的SQL
     * @throws JSQLParserException      SQL解析异常
     * @throws IllegalArgumentException SQL验证失败或不支持的SQL类型
     */
    public static String modifySql(String originalSql, String newTableName,
            Map<String, Object> extArgs, boolean allowDDL) throws JSQLParserException {
        try {
            // 验证SQL
            validateSql(originalSql, allowDDL);

            Statement statement = CCJSqlParserUtil.parse(originalSql);

            // 根据SQL类型添加限制条件
            SqlType sqlType = getSqlType(originalSql);
            switch (sqlType) {
                case SELECT:
                    Select select = (Select) statement;
                    PlainSelect plainSelect = select.getPlainSelect();
                    if (plainSelect != null) {
                        // 替换所有表名
                        replaceTableName(plainSelect.getFromItem(), newTableName);
                        if (plainSelect.getJoins() != null) {
                            for (Join join : plainSelect.getJoins()) {
                                replaceTableName(join.getRightItem(), newTableName);
                            }
                        }
                        // 添加限制条件
                        addSelectRestrictions(plainSelect, extArgs);
                        // 检查是否有 LIMIT，如果没有则加上 LIMIT 1000
                        if (plainSelect.getLimit() == null) {
                            var queryLimit = DorisConfigContants.DEFAULT_QUERY_LIMIT;
                            var limit = new Limit().withRowCount(new LongValue(queryLimit));
                            plainSelect.setLimit(limit);
                        }
                    }
                    break;
                case UPDATE:
                    if (statement instanceof Update update) {
                        // 替换表名
                        if (update.getTable() != null) {
                            update.getTable().setName(newTableName);
                        }
                        addUpdateRestrictions(update, extArgs);
                    }
                    break;
                case DELETE:
                    if (statement instanceof Delete delete) {
                        // 替换表名
                        if (delete.getTable() != null) {
                            delete.getTable().setName(newTableName);
                        }
                        addDeleteRestrictions(delete, extArgs);
                    }
                    break;
                case INSERT:
                    if (statement instanceof Insert insert) {
                        // 替换表名
                        if (insert.getTable() != null) {
                            insert.getTable().setName(newTableName);
                        }
                        // INSERT 不需要添加限制条件
                    }
                    break;
                case DDL:
                    // DDL 语句不需要处理
                    break;
                default:
                    throw ComposeException.build(BizExceptionCodeEnum.composeUnsupportedSqlType, sqlType.name());
            }

            return statement.toString();
        } catch (JSQLParserException e) {
            log.error("SQL parse failed: {}", originalSql, e);
            throw e;
        } catch (Exception e) {
            log.error("SQL processing error: {}, cause: {}", originalSql, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 为 SELECT 语句添加限制条件
     */
    private static void addSelectRestrictions(PlainSelect plainSelect, Map<String, Object> extArgs) {
        Expression where = plainSelect.getWhere();
        Expression newWhere = buildRestrictions(where, extArgs);
        plainSelect.setWhere(newWhere);
    }

    /**
     * 为 UPDATE 语句添加限制条件
     */
    private static void addUpdateRestrictions(Update update, Map<String, Object> extArgs) {
        Expression where = update.getWhere();
        Expression newWhere = buildRestrictions(where, extArgs);
        update.setWhere(newWhere);
    }

    /**
     * 为 DELETE 语句添加限制条件
     */
    private static void addDeleteRestrictions(Delete delete, Map<String, Object> extArgs) {
        Expression where = delete.getWhere();
        Expression newWhere = buildRestrictions(where, extArgs);
        delete.setWhere(newWhere);
    }

    /**
     * 构建限制条件表达式
     */
    private static Expression buildRestrictions(Expression originalWhere, Map<String, Object> extArgs) {
        if (extArgs == null || extArgs.isEmpty()) {
            return originalWhere;
        }

        List<Expression> conditions = new ArrayList<>();

        // 遍历所有限制参数
        for (Map.Entry<String, Object> entry : extArgs.entrySet()) {
            String columnName = entry.getKey();
            Object value = entry.getValue();

            if (value != null) {
                Expression condition = createEqualsExpression(columnName, value);
                if (condition != null) {
                    conditions.add(condition);
                }
            }
        }

        // 合并条件
        Expression newWhere = null;
        for (Expression condition : conditions) {
            if (newWhere == null) {
                newWhere = condition;
            } else {
                newWhere = new AndExpression(newWhere, condition);
            }
        }

        // 如果原始SQL有where条件，则与新的条件合并
        if (originalWhere != null && newWhere != null) {
            return new AndExpression(originalWhere, newWhere);
        }

        return Objects.requireNonNullElse(newWhere, originalWhere);
    }

    /**
     * 根据值的类型创建等于表达式
     * 
     * @throws IllegalArgumentException 创建表达式失败
     */
    private static Expression createEqualsExpression(String columnName, Object value) {
        if (value == null) {
            return null;
        }

        try {
            if (value instanceof Number number) {
                if (number instanceof Long || number instanceof Integer) {
                    return new EqualsTo(
                            new net.sf.jsqlparser.schema.Column(columnName),
                            new LongValue(number.longValue()));
                } else if (number instanceof Double || number instanceof Float) {
                    return new EqualsTo(
                            new net.sf.jsqlparser.schema.Column(columnName),
                            new DoubleValue(String.valueOf(number)));
                }
            } else if (value instanceof String str) {
                return new EqualsTo(
                        new net.sf.jsqlparser.schema.Column(columnName),
                        new StringValue(str));
            } else if (value instanceof Boolean bool) {
                return new EqualsTo(
                        new net.sf.jsqlparser.schema.Column(columnName),
                        new LongValue(bool ? 1L : 0L));
            }
            log.warn("Unsupported param type: column={}, value={}, type={}", columnName, value, value.getClass().getName());
            return null;
        } catch (Exception e) {
            log.error("Build condition failed: column={}, value={}", columnName, value, e);
            var errorMessage = ComposeExceptionUtils.getRootErrorMessage(e);
            throw ComposeException.build(BizExceptionCodeEnum.composeBuildConditionFailed, errorMessage);
        }
    }

    /**
     * 验证SQL是否只包含一个表
     */
    public static boolean isSingleTableQuery(String sql) throws JSQLParserException {
        Statement statement = CCJSqlParserUtil.parse(sql);
        if (statement instanceof Select) {
            Select select = (Select) statement;
            PlainSelect plainSelect = select.getPlainSelect();
            if (plainSelect != null) {
                FromItem fromItem = plainSelect.getFromItem();
                return fromItem instanceof Table;
            }
        }
        return true; // 非SELECT语句默认为单表操作
    }

    /**
     * 获取SQL操作类型
     */
    public static SqlType getSqlOperationType(String sql) throws JSQLParserException {
        return getSqlType(sql);
    }

    /**
     * 替换表名
     */
    private static void replaceTableName(FromItem fromItem, String newTableName) {
        if (fromItem instanceof Table table) {
            table.setName(newTableName);
        } else if (fromItem instanceof LateralSubSelect lateral) {
            var select = lateral.getSelect();
            if (select instanceof PlainSelect subPlainSelect) {
                replaceTableName(subPlainSelect.getFromItem(), newTableName);
                if (subPlainSelect.getJoins() != null) {
                    for (Join join : subPlainSelect.getJoins()) {
                        replaceTableName(join.getRightItem(), newTableName);
                    }
                }
            }
        } else if (fromItem instanceof ParenthesedFromItem parenthesed) {
            replaceTableName(parenthesed.getFromItem(), newTableName);
        } else {
            // 其他类型如 TableFunction、ValuesList 等，通常无需替换表名
            log.warn("Unsupported fromItem type: {}", fromItem.getClass().getName());
        }
    }
}