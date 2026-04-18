package com.xspaceagi.custompage.domain.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.FluxSink;

/**
 * 聊天会话管理器,管理SSE会话的生命周期
 */
@Slf4j
@Component
public class CustomPageChatSessionManager {

    /**
     * 存储会话ID和FluxSink的映射关系
     */
    private final ConcurrentHashMap<String, FluxSink<Map<String, Object>>> sessionMap = new ConcurrentHashMap<>();

    /**
     * 注册会话
     */
    public void registerSession(String sessionId, FluxSink<Map<String, Object>> sink) {
        sessionMap.put(sessionId, sink);
        log.info("[Session Manager] register session: session Id={}", sessionId);
    }

    /**
     * 终止会话
     */
    public boolean terminateSession(String sessionId) {
        FluxSink<Map<String, Object>> sink = sessionMap.remove(sessionId);
        if (sink != null) {
            log.info("[Session Manager] terminatesession: session Id={}", sessionId);
            try {
                sink.complete();
            } catch (Exception e) {
                log.debug("[Session Manager] sink already completed, ignore duplicate call", e);
            }
        } else {
            log.info("[Session Manager] session not found or already completed: session Id={}", sessionId);
        }
        // 会话不存在时也返回成功，实现幂等性
        return true;
    }

    /**
     * 获取会话
     */
    public FluxSink<Map<String, Object>> getSession(String sessionId) {
        return sessionMap.get(sessionId);
    }

    /**
     * 移除会话
     */
    public void removeSession(String sessionId) {
        sessionMap.remove(sessionId);
        log.info("[Session Manager] remove session: session Id={}", sessionId);
    }

    /**
     * 获取当前活跃会话数量
     */
    public int getActiveSessionCount() {
        return sessionMap.size();
    }
}
