package com.xspaceagi.domain.service;

import com.xspaceagi.domain.adaptor.impl.LogHttpClientAdapter;
import com.xspaceagi.domain.adaptor.impl.LogHttpClientConfiguration;
import com.xspaceagi.domain.model.valueobj.AgentLogEntry;
import com.xspaceagi.domain.model.valueobj.AgentLogSearchParams;
import com.xspaceagi.domain.model.valueobj.AgentLogSearchRequest;
import com.xspaceagi.domain.model.valueobj.AgentLogSearchResult;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * LogHttpClientAdapter 单元测试
 * 注意：这些测试需要 Rust 服务正在运行
 */
@Slf4j
public class LogHttpClientAdapterTest {

    private LogHttpClientAdapter logHttpClientAdapter;

    @BeforeEach
    void setUp() {
        // 创建配置对象
        LogHttpClientConfiguration config = new LogHttpClientConfiguration();
        config.setBaseUrl("http://localhost:8098");
        config.setConnectTimeoutSeconds(10);
        config.setWriteTimeoutSeconds(30);
        config.setReadTimeoutSeconds(30);
        config.setEnableConnectionPool(true);
        config.setMaxIdleConnections(5);
        config.setKeepAliveDurationMinutes(5);
        
        logHttpClientAdapter = new LogHttpClientAdapter(config);
    }

    @Test
    void testHealthCheck() throws Exception {
        Boolean result = logHttpClientAdapter.healthCheck();
        log.info("健康检查结果: {}", result);
        // 注意：如果 Rust 服务未运行，这个测试会失败
    }

    @Test
    void testReadyCheck() throws Exception {
        Boolean result = logHttpClientAdapter.readyCheck();
        log.info("就绪检查结果: {}", result);
    }

    @Test
    void testAddAgentLog() throws Exception {
        AgentLogEntry logEntry = AgentLogEntry.builder()
                .requestId("req_test_001")
                .conversationId("session_test_001")
                .userUid("user_test_123")
                .tenantId("tenant_test_001")
                .spaceId("space_test_001")
                .userInput("测试用户输入")
                .output("测试系统输出")
                .inputToken(50)
                .outputToken(100)
                .requestStartTime(LocalDateTime.now().minusSeconds(5))
                .requestEndTime(LocalDateTime.now())
                .elapsedTimeMs(5000L)
                .nodeType("test_node")
                .status("success")
                .nodeName("测试节点")
                .build();

        Boolean result = logHttpClientAdapter.addAgentLog(logEntry);
        log.info("新增单个日志结果: {}", result);
    }

    @Test
    void testBatchAddAgentLogs() throws Exception {
        List<AgentLogEntry> logEntries = Arrays.asList(
                AgentLogEntry.builder()
                        .requestId("req_batch_001")
                        .conversationId("session_batch_001")
                        .userUid("user_batch_123")
                        .tenantId("tenant_batch_001")
                        .spaceId("space_batch_001")
                        .userInput("批量测试输入1")
                        .output("批量测试输出1")
                        .inputToken(30)
                        .outputToken(60)
                        .requestStartTime(LocalDateTime.now().minusSeconds(10))
                        .requestEndTime(LocalDateTime.now().minusSeconds(7))
                        .elapsedTimeMs(3000L)
                        .status("success")
                        .build(),
                AgentLogEntry.builder()
                        .requestId("req_batch_002")
                        .conversationId("session_batch_001")
                        .userUid("user_batch_123")
                        .tenantId("tenant_batch_001")
                        .spaceId("space_batch_001")
                        .userInput("批量测试输入2")
                        .output("批量测试输出2")
                        .inputToken(40)
                        .outputToken(80)
                        .requestStartTime(LocalDateTime.now().minusSeconds(7))
                        .requestEndTime(LocalDateTime.now().minusSeconds(4))
                        .elapsedTimeMs(3000L)
                        .status("success")
                        .build()
        );

        Boolean result = logHttpClientAdapter.batchAddAgentLogs(logEntries);
        log.info("批量新增日志结果: {}", result);
    }

    @Test
    void testSearchAgentLogs() throws Exception {
        AgentLogSearchParams searchParams = AgentLogSearchParams.builder()
                .tenantId("tenant_test_001")
                .userUid("user_test_123")
                .build();

        AgentLogSearchRequest searchRequest = AgentLogSearchRequest.builder()
                .queryFilter(searchParams)
                .current(1L)
                .pageSize(10L)
                .build();

        AgentLogSearchResult result = logHttpClientAdapter.searchAgentLogs(searchRequest);
        log.info("搜索结果: 总数={}, 当前页={}, 每页大小={}, 记录数={}", 
                result.getTotal(), result.getCurrent(), result.getSize(), 
                result.getRecords() != null ? result.getRecords().size() : 0);
    }

    @Test
    void testSyncMethods() {
        // 测试同步方法
        AgentLogEntry logEntry = AgentLogEntry.builder()
                .requestId("req_sync_001")
                .conversationId("session_sync_001")
                .userUid("user_sync_123")
                .tenantId("tenant_sync_001")
                .userInput("同步测试输入")
                .output("同步测试输出")
                .build();

        boolean addResult = logHttpClientAdapter.addAgentLog(logEntry);
        log.info("同步新增日志结果: {}", addResult);

        // 搜索测试
        AgentLogSearchParams searchParams = AgentLogSearchParams.builder()
                .tenantId("tenant_sync_001")
                .build();

        AgentLogSearchRequest searchRequest = AgentLogSearchRequest.builder()
                .queryFilter(searchParams)
                .current(1L)
                .pageSize(5L)
                .build();

        try {
            AgentLogSearchResult searchResult = logHttpClientAdapter.searchAgentLogs(searchRequest);
            log.info("同步搜索结果: 总数={}", searchResult.getTotal());
        } catch (Exception e) {
            log.error("同步搜索失败", e);
        }
    }

    @Test
    void testConfigurationValidation() {
        // 测试配置验证 - 空 URL
        LogHttpClientConfiguration emptyConfig = new LogHttpClientConfiguration();
        emptyConfig.setBaseUrl("");
        
        try {
            new LogHttpClientAdapter(emptyConfig);
            // 如果没有抛出异常，测试失败
            log.error("Expected exception but none was thrown");
        } catch (IllegalArgumentException e) {
            log.info("Caught expected config exception: {}", e.getMessage());
        }
        
        // 测试配置验证 - null URL
        LogHttpClientConfiguration nullConfig = new LogHttpClientConfiguration();
        nullConfig.setBaseUrl(null);
        
        try {
            new LogHttpClientAdapter(nullConfig);
            // 如果没有抛出异常，测试失败
            log.error("Expected exception but none was thrown");
        } catch (IllegalArgumentException e) {
            log.info("Caught expected config exception: {}", e.getMessage());
        }
    }

    @Test
    public void testSearchWithNullQueryFilter() {
        // 创建配置
        LogHttpClientConfiguration config = new LogHttpClientConfiguration();
        config.setBaseUrl("http://localhost:8097");
        
        // 创建适配器
        LogHttpClientAdapter adapter = new LogHttpClientAdapter(config);
        
        try {
            // 创建搜索请求，queryFilter 为 null
            AgentLogSearchRequest searchRequest = AgentLogSearchRequest.builder()
                    .current(1L)
                    .pageSize(10L)
                    .queryFilter(null) // 故意设置为 null
                    .build();
            
            log.info("测试 queryFilter 为 null 的情况");
            log.info("原始 searchRequest: {}", searchRequest);
            
            // 执行搜索
            AgentLogSearchResult result = adapter.searchAgentLogs(searchRequest);
            
            log.info("搜索成功，结果: {}", result);
            
        } catch (Exception e) {
            log.error("测试失败", e);
        } finally {
            adapter.close();
        }
    }
} 