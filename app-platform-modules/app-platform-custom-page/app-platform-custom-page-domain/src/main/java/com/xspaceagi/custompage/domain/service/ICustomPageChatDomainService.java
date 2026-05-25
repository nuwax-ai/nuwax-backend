package com.xspaceagi.custompage.domain.service;

import java.util.Map;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;

/**
 * 前端项目聊天领域服务
 */
public interface ICustomPageChatDomainService {

    /**
     * 建立会话 SSE 连接
     */
    SseEmitter startAgentSessionSse(String sessionId, Long projectId, String requestId, UserContext userContext);

    /**
     * 取消 agent 任务
     */
    ReqResult<Map<String, Object>> agentSessionCancel(String projectId, String sessionId, UserContext userContext);

    /**
     * 查询Agent状态
     */
    ReqResult<Map<String, Object>> getAgentStatus(String projectId, UserContext userContext);

    /**
     * 停止Agent服务
     */
    ReqResult<Map<String, Object>> stopAgent(String projectId, UserContext userContext);
}
