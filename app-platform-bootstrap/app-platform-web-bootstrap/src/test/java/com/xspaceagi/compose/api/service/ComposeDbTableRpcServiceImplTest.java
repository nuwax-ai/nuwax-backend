package com.xspaceagi.compose.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.xspaceagi.PlatformApiApplication;
import com.xspaceagi.compose.sdk.request.DorisTableDataRequest;
import com.xspaceagi.compose.sdk.request.DorisTableDefineRequest;
import com.xspaceagi.compose.sdk.response.DorisTableDataResponse;
import com.xspaceagi.compose.sdk.vo.define.CreateTableDefineVo;
import com.xspaceagi.system.spec.common.RequestContext;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@SpringBootTest(classes = PlatformApiApplication.class)
public class ComposeDbTableRpcServiceImplTest {

    @Autowired
    private ComposeDbTableRpcServiceImpl composeDbTableRpcService;

    @Test
    void testQueryTableData_update() {
        // 设置租户上下文，防止 MyBatis Plus 多租户插件报错
        try {
            RequestContext.setThreadTenantId(1L);

            // 按照示例构造请求参数
            DorisTableDataRequest request = new DorisTableDataRequest();
            request.setTableId(28L);
            request.setSql(
                    "UPDATE custom_table_table_name SET agent_name={{agent_name}} WHERE agent_id={{agent_id}} limit 100");
            java.util.Map<String, Object> args = new java.util.HashMap<>();
            args.put("agent_id", "agent_001");
            args.put("agent_name", "智能体测试");
            request.setArgs(args);
            java.util.Map<String, Object> extArgs = new java.util.HashMap<>();
            extArgs.put("uid", "6");
            request.setExtArgs(extArgs);
            DorisTableDataResponse response = composeDbTableRpcService.queryTableData(request);
            log.info("update response: {}", JSON.toJSONString(response));

            assertNotNull(response);
            // 可根据实际数据断言返回内容
        } finally {
            RequestContext.remove();
        }
    }

    @Test
    void testQueryTableData_WithNullTableId() {
        // 设置租户上下文，防止 MyBatis Plus 多租户插件报错
        try {
            RequestContext.setThreadTenantId(1L);
            DorisTableDataRequest request = new DorisTableDataRequest();
            // 设置空的tableId
            request.setTableId(null);
            // 验证异常抛出
            assertThrows(RuntimeException.class, () -> {
                composeDbTableRpcService.queryTableData(request);
            });
        } finally {
            RequestContext.remove();
        }
    }

    @Test
    void testInsertCustomTable28() {
        // 设置租户上下文，防止 MyBatis Plus 多租户插件报错
        try {
            RequestContext.setThreadTenantId(1L);
            DorisTableDataRequest insertRequest = new DorisTableDataRequest();
            insertRequest.setTableId(28L);
            insertRequest.setSql(
                    "INSERT INTO custom_table_table_name ( agent_id, agent_name, nick_name, uid, user_name, created) VALUES ( 'agent_002', '测试智能体2', '测试昵称2', 'user_12346', 'user2', NOW())");
            // insert 一般不需要 args/extArgs，直接写死
            DorisTableDataResponse response = composeDbTableRpcService.queryTableData(insertRequest);
            log.info("insert response: {}", JSON.toJSONString(response));
            assertNotNull(response);
            // 可根据 insert 返回内容断言
        } finally {
            RequestContext.remove();
        }
    }

    @Test
    void testSelectCustomTable28() {
        // 设置租户上下文，防止 MyBatis Plus 多租户插件报错
        try {
            RequestContext.setThreadTenantId(1L);
            DorisTableDataRequest selectRequest = new DorisTableDataRequest();
            selectRequest.setTableId(28L);
            selectRequest.setSql("SELECT * FROM custom_table_table_name WHERE uid='user_12346'");
            DorisTableDataResponse response = composeDbTableRpcService.queryTableData(selectRequest);
            log.info("insert response: {}", JSON.toJSONString(response));

            assertNotNull(response);
            // 可断言返回数据包含 user_12346
        } finally {
            RequestContext.remove();
        }
    }

    @Test
    void testSelectLikeCustomTable28() {
        // 设置租户上下文，防止 MyBatis Plus 多租户插件报错
        try {
            RequestContext.setThreadTenantId(1L);
            DorisTableDataRequest selectRequest = new DorisTableDataRequest();
            selectRequest.setTableId(28L);
            Map<String, Object> args = new HashMap<>();
            args.put("team_name", "user");
            selectRequest.setArgs(args);
            selectRequest.setSql("SELECT * FROM custom_table WHERE user_name LIKE '%${{team_name}}%'");
            DorisTableDataResponse response = composeDbTableRpcService.queryTableData(selectRequest);
            log.info("insert response: {}", JSON.toJSONString(response));

            assertNotNull(response);
            // 可断言返回数据包含 user_12346
        } finally {
            RequestContext.remove();
        }
    }

    @Test
    void testSelectCustomTable28_withLimit() {
        // 设置租户上下文，防止 MyBatis Plus 多租户插件报错
        try {
            RequestContext.setThreadTenantId(1L);
            DorisTableDataRequest selectRequest = new DorisTableDataRequest();
            selectRequest.setTableId(28L);
            selectRequest.setSql("SELECT * FROM custom_table_table_name WHERE uid='user_12346' limit 100");
            DorisTableDataResponse response = composeDbTableRpcService.queryTableData(selectRequest);
            log.info("insert response: {}", JSON.toJSONString(response));

            assertNotNull(response);
            // 可断言返回数据包含 user_12346
        } finally {
            RequestContext.remove();
        }
    }

    @Test
    void testDeleteCustomTable28() {
        // 设置租户上下文，防止 MyBatis Plus 多租户插件报错
        try {
            RequestContext.setThreadTenantId(1L);
            DorisTableDataRequest deleteRequest = new DorisTableDataRequest();
            deleteRequest.setTableId(28L);
            deleteRequest.setSql("DELETE FROM custom_table_table_name WHERE id=1002");
            DorisTableDataResponse response = composeDbTableRpcService.queryTableData(deleteRequest);
            assertNotNull(response);
            // 可根据 delete 返回内容断言
        } finally {
            RequestContext.remove();
        }
    }

    @Test
    void testSelectCustomTable28WithJoin() {
        // 设置租户上下文，防止 MyBatis Plus 多租户插件报错
        try {
            RequestContext.setThreadTenantId(1L);
            DorisTableDataRequest joinRequest = new DorisTableDataRequest();
            joinRequest.setTableId(28L); // 这里可用主表ID
            joinRequest.setSql(
                    "SELECT a.*, b.nick_name AS b_nick_name FROM custom_table_table_name a LEFT JOIN custom_table_table_name b ON a.uid = b.uid WHERE a.uid='user_12346'");
            DorisTableDataResponse response = composeDbTableRpcService.queryTableData(joinRequest);
            log.info("SELECT response: {}", JSON.toJSONString(response));

            assertNotNull(response);
            // 可断言返回数据包含 user_12346 及 b_nick_name 字段
        } finally {
            RequestContext.remove();
        }
    }

    @Test
    void testSelectCustomTable28WithJoin_ListParams() {
        // 设置租户上下文，防止 MyBatis Plus 多租户插件报错
        try {
            RequestContext.setThreadTenantId(1L);
            DorisTableDataRequest joinRequest = new DorisTableDataRequest();
            joinRequest.setTableId(28L); // 这里可用主表ID

            var uidList = Arrays.asList("user_12346", "user_12347");
            Map<String, Object> args = new HashMap<>();
            args.put("uidList", uidList);
            joinRequest.setArgs(args);
            // 使用{{uidList}} 占位符, 替换sql中的参数,后面使用uidList 列表需要解析成英文逗号分割
            joinRequest.setSql(
                    "SELECT a.*, b.nick_name AS b_nick_name FROM custom_table_table_name a LEFT JOIN custom_table_table_name b ON a.uid = b.uid WHERE a.uid in ({{uidList}})");
            DorisTableDataResponse response = composeDbTableRpcService.queryTableData(joinRequest);
            log.info("SELECT response: {}", JSON.toJSONString(response));
            assertNotNull(response);
            assertNotNull(response.getData());
            // 可断言返回数据包含 user_12346 及 b_nick_name 字段
        } finally {
            RequestContext.remove();
        }
    }

    @Test
    void testBatchInsertCustomTable28() {
        // 设置租户上下文，防止 MyBatis Plus 多租户插件报错
        try {
            RequestContext.setThreadTenantId(1L);
            DorisTableDataRequest batchInsertRequest = new DorisTableDataRequest();
            batchInsertRequest.setTableId(28L);
            batchInsertRequest.setSql(
                    "INSERT INTO custom_table_table_name ( agent_id, agent_name, nick_name, uid, user_name, created) VALUES "
                            +
                            "( 'agent_003', '测试智能体3', '测试昵称3', 'user_12347', 'user3', NOW()), " +
                            "( 'agent_004', '测试智能体4', '测试昵称4', 'user_12348', 'user4', NOW()), " +
                            "( 'agent_005', '测试智能体5', '测试昵称5', 'user_12349', 'user5', NOW())");

            DorisTableDataResponse response = composeDbTableRpcService.queryTableData(batchInsertRequest);
            log.info("batch insert response: {}", JSON.toJSONString(response));
            assertNotNull(response);
            // 验证批量插入的行数应该为3
            assertEquals(3L, response.getRowNum());
        } finally {
            RequestContext.remove();
        }
    }

    @Test
    void testBatchInsertWithSemicolonCustomTable28() {
        // 设置租户上下文，防止 MyBatis Plus 多租户插件报错
        try {
            RequestContext.setThreadTenantId(1L);
            DorisTableDataRequest batchInsertRequest = new DorisTableDataRequest();
            batchInsertRequest.setTableId(28L);
            batchInsertRequest.setSql(
                    "INSERT INTO custom_table_table_name ( agent_id, agent_name, nick_name, uid, user_name, created) VALUES ( 'agent_006', '测试智能体6', '测试昵称6', 'user_12350', 'user6', NOW());"
                            +
                            "INSERT INTO custom_table_table_name ( agent_id, agent_name, nick_name, uid, user_name, created) VALUES ( 'agent_007', '测试智能体7', '测试昵称7', 'user_12351', 'user7', NOW());"
                            +
                            "INSERT INTO custom_table_table_name ( agent_id, agent_name, nick_name, uid, user_name, created) VALUES ( 'agent_008', '测试智能体8', '测试昵称8', 'user_12352', 'user8', NOW());");

            DorisTableDataResponse response = composeDbTableRpcService.queryTableData(batchInsertRequest);
            log.info("batch insert with semicolon response: {}", JSON.toJSONString(response));
            assertNotNull(response);
            // 验证批量插入的行数，使用分号分割时每条语句都会执行成功
            assertNotNull(response.getRowNum());
        } finally {
            RequestContext.remove();
        }
    }

    @Test
    void testBatchUpdateWithSemicolonCustomTable28() {
        // 设置租户上下文，防止 MyBatis Plus 多租户插件报错
        try {
            RequestContext.setThreadTenantId(1L);
            DorisTableDataRequest batchUpdateRequest = new DorisTableDataRequest();
            batchUpdateRequest.setTableId(28L);
            batchUpdateRequest.setSql(
                    "UPDATE custom_table_table_name SET agent_name='更新智能体3', nick_name='更新昵称3' WHERE agent_id='agent_003';"
                            +
                            "UPDATE custom_table_table_name SET agent_name='更新智能体4', nick_name='更新昵称4' WHERE agent_id='agent_004';"
                            +
                            "UPDATE custom_table_table_name SET agent_name='更新智能体5', nick_name='更新昵称5' WHERE agent_id='agent_005';");

            DorisTableDataResponse response = composeDbTableRpcService.queryTableData(batchUpdateRequest);
            log.info("batch update with semicolon response: {}", JSON.toJSONString(response));
            assertNotNull(response);
            // 验证批量更新成功
            assertNotNull(response.getRowNum());

            // 验证数据是否更新成功
            DorisTableDataRequest selectRequest = new DorisTableDataRequest();
            selectRequest.setTableId(28L);
            selectRequest.setSql(
                    "SELECT * FROM custom_table_table_name WHERE agent_id IN ('agent_003', 'agent_004', 'agent_005')");
            DorisTableDataResponse selectResponse = composeDbTableRpcService.queryTableData(selectRequest);
            log.info("select after batch update response: {}", JSON.toJSONString(selectResponse));
            assertNotNull(selectResponse);
            assertNotNull(selectResponse.getData());
        } finally {
            RequestContext.remove();
        }
    }

    @Test
    public void testCreateTable_Success() {
        try {
            RequestContext.setThreadTenantId(1L);

            // 第一步：先获取一个已存在表的定义，作为参考模板
            DorisTableDefineRequest queryRequest = new DorisTableDefineRequest();
            queryRequest.setTableId(113L); // 请根据实际情况修改

            CreateTableDefineVo templateTable = composeDbTableRpcService.queryCreateTableInfo(queryRequest);
            log.info("Loaded template table definition: {}", JSON.toJSONString(templateTable));

            // 第三步：执行创建表操作
            var tableId = composeDbTableRpcService.createTable(templateTable);
            log.info("Table created, tableId={}",tableId);

            // 可以再次查询验证表是否创建成功
            // 这里省略验证步骤

        } catch (Exception e) {
            log.error("Create table failed", e);
            assert false : "表创建应该成功，但抛出了异常: " + e.getMessage();
        } finally {
            RequestContext.remove();
        }
    }

}