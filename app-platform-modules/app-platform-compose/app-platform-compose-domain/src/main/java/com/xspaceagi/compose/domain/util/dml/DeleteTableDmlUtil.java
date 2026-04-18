package com.xspaceagi.compose.domain.util.dml;

import com.xspaceagi.compose.domain.model.CustomTableDefinitionModel;
import com.xspaceagi.compose.domain.util.BuildSqlUtil;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.ComposeException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class DeleteTableDmlUtil {

    private DeleteTableDmlUtil() {}

    /**
     * 构建删除数据的SQL语句,根据id删除
     */
    public static String buildDeleteSql(CustomTableDefinitionModel tableModel, Long id) {
        if (id == null) {
            // 确保 ID 不为空，这是删除操作的必要条件
            log.error("Delete error: rowId required. Table: {}.{}",
                    tableModel.getDorisDatabase(), tableModel.getDorisTable());
            // 使用与更新逻辑一致的错误码
            throw ComposeException.build(BizExceptionCodeEnum.fieldRequiredButEmpty, "行ID");
        }
        return "DELETE FROM `" + BuildSqlUtil.escapeSqlString(tableModel.getDorisDatabase()) + "`.`"
                + BuildSqlUtil.escapeSqlString(tableModel.getDorisTable()) + "` WHERE id = " + id;
    }

}
