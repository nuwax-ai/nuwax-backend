package com.xspaceagi.domain.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xspaceagi.domain.adaptor.impl.LogHttpClientAdapter;
import com.xspaceagi.domain.model.AgentLogModel;
import com.xspaceagi.domain.model.valueobj.AgentLogEntry;
import com.xspaceagi.domain.model.valueobj.AgentLogSearchParams;
import com.xspaceagi.domain.model.valueobj.AgentLogSearchRequest;
import com.xspaceagi.domain.model.valueobj.AgentLogSearchResult;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class LogPlatformDomainService implements ILogPlatformDomainService {

    @Resource
    private LogHttpClientAdapter logHttpClientAdapter;

    @Override
    public IPage<AgentLogModel> searchAgentLogs(AgentLogSearchParams searchParams, Long current, Long pageSize) {
        log.info("开始搜索智能体日志，参数: {}, 当前页: {}, 每页大小: {}", searchParams, current, pageSize);

        // 构建搜索请求
        AgentLogSearchRequest searchRequest = AgentLogSearchRequest.builder()
                .queryFilter(searchParams)
                .current(current != null ? current : 1L)
                .pageSize(pageSize != null ? pageSize : 10L)
                .build();

        // 调用 Rust 服务搜索
        AgentLogSearchResult searchResult = logHttpClientAdapter.searchAgentLogs(searchRequest);

        // 转换结果
        List<AgentLogModel> records = convertToAgentLogModels(searchResult.getRecords());

        // 构建分页结果
        Page<AgentLogModel> page = new Page<>(
                searchResult.getCurrent(),
                searchResult.getSize());
        page.setRecords(records);
        page.setTotal(searchResult.getTotal());

        log.info("Search done, total: {}, page: {}, elapsed: {}ms",
                searchResult.getTotal(), searchResult.getCurrent(), searchResult.getElapsedTimeMs());

        return page;
    }

    @Override
    public boolean addAgentLog(AgentLogEntry logEntry) {
        log.info("Adding agent log, requestId: {}", logEntry.getRequestId());

        boolean result = logHttpClientAdapter.addAgentLog(logEntry);
        if (result) {
            log.info("新增智能体日志成功，请求ID: {}", logEntry.getRequestId());
        } else {
            log.warn("新增智能体日志失败，请求ID: {}", logEntry.getRequestId());
        }
        return result;
    }

    @Override
    public boolean batchAddAgentLogs(List<AgentLogEntry> logEntries) {
        if (CollectionUtils.isEmpty(logEntries)) {
            log.warn("批量新增智能体日志失败：日志列表为空");
            return false;
        }

        log.info("开始批量新增智能体日志，数量: {}", logEntries.size());

        boolean result = logHttpClientAdapter.batchAddAgentLogs(logEntries);
        if (result) {
            log.info("批量新增智能体日志成功，数量: {}", logEntries.size());
        } else {
            log.warn("批量新增智能体日志失败，数量: {}", logEntries.size());
        }
        return result;
    }

    @Override
    public boolean healthCheck() {
        log.debug("开始检查 Rust 日志服务健康状态");

        boolean isHealthy = logHttpClientAdapter.healthCheck();
        log.debug("Rust 日志服务健康状态: {}", isHealthy ? "健康" : "不健康");
        return isHealthy;
    }

    /**
     * 将 AgentLogEntry 转换为 AgentLogModel
     */
    private List<AgentLogModel> convertToAgentLogModels(List<AgentLogEntry> logEntries) {
        if (CollectionUtils.isEmpty(logEntries)) {
            return List.of();
        }

        return logEntries.stream()
                .map(this::convertToAgentLogModel)
                .collect(Collectors.toList());
    }

    /**
     * 将单个 AgentLogEntry 转换为 AgentLogModel
     */
    private AgentLogModel convertToAgentLogModel(AgentLogEntry logEntry) {
        return AgentLogModel.builder()
                .requestId(logEntry.getRequestId())
                .messageId(logEntry.getMessageId())
                .conversationId(logEntry.getConversationId())
                .agentId(logEntry.getAgentId())
                .userUid(logEntry.getUserUid())
                .tenantId(logEntry.getTenantId())
                .spaceId(logEntry.getSpaceId())
                .userInput(logEntry.getUserInput())
                .output(logEntry.getOutput())
                .inputToken(logEntry.getInputToken())
                .outputToken(logEntry.getOutputToken())
                .executeResult(logEntry.getExecuteResult())
                .requestStartTime(logEntry.getRequestStartTime())
                .requestEndTime(logEntry.getRequestEndTime())
                .elapsedTimeMs(logEntry.getElapsedTimeMs())
                .nodeType(logEntry.getNodeType())
                .status(logEntry.getStatus())
                .nodeName(logEntry.getNodeName())
                .createdAt(logEntry.getCreatedAt())
                .updatedAt(logEntry.getUpdatedAt())
                .userId(logEntry.getUserId())
                .userName(logEntry.getUserName())
                .build();
    }

    @Override
    public AgentLogModel queryOneAgentLog(AgentLogSearchParams searchParams) {
        log.info("开始搜索智能体日志，参数: {}", searchParams);

        // 构建搜索请求
        AgentLogSearchRequest searchRequest = AgentLogSearchRequest.builder()
                .queryFilter(searchParams)
                .current(1L)
                .pageSize(1L)
                .build();

        // 调用 Rust 服务搜索
        AgentLogSearchResult searchResult = logHttpClientAdapter.queryOneAgentLog(searchRequest);

        // 转换结果
        List<AgentLogModel> records = convertToAgentLogModels(searchResult.getRecords());

        log.info("Search done, total: {}, page: {}, elapsed: {}ms",
                searchResult.getTotal(), searchResult.getCurrent(), searchResult.getElapsedTimeMs());

        return records.stream().findFirst().orElse(null);
    }

}
