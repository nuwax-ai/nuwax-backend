package com.xspaceagi.log.api.service;

import com.xspaceagi.log.api.convert.AgentLogEntryConvert;
import com.xspaceagi.system.domain.log.LogRecordPrint;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xspaceagi.domain.adaptor.impl.LogHttpClientAdapter;
import com.xspaceagi.domain.model.AgentLogModel;
import com.xspaceagi.domain.model.valueobj.AgentLogSearchParams;
import com.xspaceagi.domain.service.ILogPlatformDomainService;
import com.xspaceagi.log.sdk.reponse.AgentLogModelResponse;
import com.xspaceagi.log.sdk.request.AgentLogEntryRequest;
import com.xspaceagi.log.sdk.request.AgentLogSearchParamsRequest;
import com.xspaceagi.log.sdk.service.LogPlatformRpcService;
import com.xspaceagi.system.spec.utils.ValidateUtil;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class LogPlatformRpcServiceImpl implements LogPlatformRpcService {

    @Resource
    private AgentLogEntryConvert agentLogEntryConvert;

    @Resource
    private LogHttpClientAdapter logHttpClientAdapter;

    @Resource
    private ILogPlatformDomainService logPlatformDomainService;

    @LogRecordPrint(content = "[智能体日志]-新增日志")
    @Override
    public boolean addLog(AgentLogEntryRequest logEntryRequest) {
        log.info("Adding agent log, requestId: {}", logEntryRequest.getRequestId());
        // 校验必填参数
        ValidateUtil.validate(logEntryRequest);

        var agentLogEntry = agentLogEntryConvert.convertFrom(logEntryRequest);
        return logHttpClientAdapter.addAgentLog(agentLogEntry);
    }

    @LogRecordPrint(content = "[智能体日志]-搜索日志")
    @Override
    public IPage<AgentLogModelResponse> searchAgentLogs(AgentLogSearchParamsRequest searchParams, Long current,
            Long pageSize) {

        AgentLogSearchParams agentLogSearchParams = agentLogEntryConvert.convertFrom(searchParams);
        IPage<AgentLogModel> result = logPlatformDomainService.searchAgentLogs(agentLogSearchParams, current, pageSize);
        return result.convert(agentLogEntryConvert::convertTo);
    }
}
