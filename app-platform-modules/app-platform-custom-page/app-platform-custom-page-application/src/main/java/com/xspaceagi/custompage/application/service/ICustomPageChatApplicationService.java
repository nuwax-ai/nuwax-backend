package com.xspaceagi.custompage.application.service;

import java.util.List;
import java.util.Map;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.xspaceagi.custompage.domain.model.CustomPageConversationModel;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.page.SuperPage;

/**
 * 前端项目编码应用服务
 */
public interface ICustomPageChatApplicationService {

        /**
         * 发送聊天消息（使用 Flux 响应式流）
         */
        reactor.core.publisher.Flux<Map<String, Object>> sendAgentChatFlux(Map<String, Object> chatBody,
                        UserContext userContext);

        /**
         * 终止SSE会话
         */
        ReqResult<Void> terminateChatSession(String sessionId, UserContext userContext);

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

        /**
         * 保存用户会话记录
         */
        ReqResult<Void> saveConversation(CustomPageConversationModel model, UserContext userContext);

        /**
         * 查询用户会话记录
         */
        ReqResult<List<CustomPageConversationModel>> listConversations(Long projectId, UserContext userContext);

        /**
         * 分页查询用户会话记录
         */
        ReqResult<SuperPage<CustomPageConversationModel>> pageQueryConversations(CustomPageConversationModel queryModel,
                        Long current, Long pageSize, UserContext userContext);
}