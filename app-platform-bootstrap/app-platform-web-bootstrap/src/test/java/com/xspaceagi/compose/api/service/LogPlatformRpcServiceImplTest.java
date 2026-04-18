package com.xspaceagi.compose.api.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xspaceagi.log.api.service.LogPlatformRpcServiceImpl;
import com.xspaceagi.log.sdk.reponse.AgentLogModelResponse;
import com.xspaceagi.log.sdk.request.AgentLogEntryRequest;
import com.xspaceagi.log.sdk.request.AgentLogSearchParamsRequest;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@SpringBootTest
public class LogPlatformRpcServiceImplTest {

    @Autowired
    private LogPlatformRpcServiceImpl logPlatformRpcService;

    @Test
    public void testAddLog_Success() {
        // Arrange
        AgentLogEntryRequest request = new AgentLogEntryRequest();
        // 填充request的测试数据（根据实际定义）

        request.setRequestId("req_001333");
        request.setMessageId("msg_001");
        request.setConversationId("session_001");
        request.setUserUid("user_123");
        request.setTenantId("1");
        request.setSpaceId("space_001");
        request.setUserInput("请帮我分析一下这个数据");
        request.setOutput("根据您提供的数据，我分析出以下结论...");
        request.setRequestStartTime(LocalDateTime.parse("2024-01-15T10:30:00Z", DateTimeFormatter.ISO_DATE_TIME));
        request.setRequestEndTime(LocalDateTime.parse("2024-01-15T10:30:05Z", DateTimeFormatter.ISO_DATE_TIME));
        request.setStatus("success");

        request.setUserId(1L);
        request.setUserName("张三");

        // Act
        boolean result = logPlatformRpcService.addLog(request);

        // Assert
        assert (result);
    }

    @Test
    public void testSearchAgentLogs_Success() {
        // Arrange - 准备搜索参数
        AgentLogSearchParamsRequest searchParams = new AgentLogSearchParamsRequest();

        // 设置时间范围 - 使用RFC 3339格式（ISO_DATE_TIME）
        LocalDateTime startTime = LocalDateTime.parse("2024-01-01T00:00:00Z", DateTimeFormatter.ISO_DATE_TIME);
        LocalDateTime endTime = LocalDateTime.parse("2024-12-31T23:59:59Z", DateTimeFormatter.ISO_DATE_TIME);
        searchParams.setStartTime(startTime);
        searchParams.setEndTime(endTime);
        searchParams.setRequestId("req_001333");

        // 设置其他搜索条件
        searchParams.setTenantId("1"); // 当前租户
        searchParams.setUserInput(Arrays.asList("分析", "数据")); // 搜索包含这些关键词的用户输入

        // 设置当前页和页大小
        Long current = 1L;
        Long pageSize = 10L;

        log.info("searchParams result", JSON.toJSONString(searchParams));

        // Act - 执行搜索
        IPage<AgentLogModelResponse> result = logPlatformRpcService.searchAgentLogs(searchParams, current, pageSize);

        // Assert - 验证结果
        log.info("Total search hits: {}", result.getTotal());
        log.info("Current page row count: {}", result.getRecords().size());

        log.info("result", JSON.toJSONString(result));
        // check 结果记录, RequestId("req_001333") 必须都是这个值的
        for (AgentLogModelResponse log : result.getRecords()) {
            assert (log.getRequestId().equals("req_001333"));
        }

        // 如果有搜索结果，验证第一条记录的基本信息
        if (!result.getRecords().isEmpty()) {
            AgentLogModelResponse firstLog = result.getRecords().get(0);
            log.info("First row requestId: {}", firstLog.getRequestId());
            log.info("First row user input: {}", firstLog.getUserInput());
            log.info("First row createdAt: {}", firstLog.getCreatedAt());

            // 验证返回的记录包含必要的字段
            assert (firstLog.getRequestId() != null);
            assert (firstLog.getUserInput() != null);
        }
    }

}
